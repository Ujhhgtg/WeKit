#!/usr/bin/env python3
"""Export every sticker in the FunBox online repository.

The exporter intentionally reuses the independently verified protocol implementation in
``funbox_api_probe.py``.  It calls FunBox operation 10 (catalog) and operation 2 (pack
contents), then downloads and decrypts each pack's ``image`` object.  Operation 10 now
returns a random subset of the catalog, so refreshes collect multiple samples and merge
them into the cached full catalog by pack ID.  Sampled packs have their contents rechecked
for additions, removals, and metadata changes.  Completed files and decoded metadata are
checkpointed atomically, so running the same command again skips unchanged stickers and
retries only missing/failed work.

This is a PC-side data-export utility.  It does not load FunBox as an Xposed module and does
not execute any downloaded code.

Authoritative reverse-engineering references:
  /home/ujhhgtg/coding/funbox_deobf
  /home/ujhhgtg/coding/funbox_deobf/output/decrypted_strings.json
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_shell_apk
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload
  /home/ujhhgtg/coding/wechat_8069
  /home/ujhhgtg/coding/wechat_8074
  scripts/funbox_api_probe.py (GHd, TEA, SM2/SM3, resolver, operation 10/2 and object AES)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import threading
import time
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

import requests
from funbox_api_probe import (
    BinaryReader,
    ProtocolError,
    call,
    catalog_payload,
    decode_catalog,
    decode_object_response,
    decode_pack,
    image_type,
    pack_payload,
    probe_payload,
    resolve_candidates,
)

OP_PROBE = 100
OP_CATALOG = 10
OP_PACK_CONTENTS = 2
USER_AGENT = "WeKit-FunBox-Sticker-Exporter/1.0"
STATE_VERSION = 1
DEFAULT_WORKERS = 4
DEFAULT_RETRIES = 3
DEFAULT_CATALOG_SAMPLES = 20


class ExportError(RuntimeError):
    """An export operation failed after its retry budget."""


def atomic_write_json(path: Path, value: Any) -> None:
    """Write JSON so a process interruption cannot leave a half-written checkpoint."""

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def safe_component(value: str, fallback: str) -> str:
    """Make a catalog title/ID safe as one output-directory component."""

    normalized = re.sub(r"[\\/:*?\"<>|\x00-\x1f]+", "_", value).strip(" .")
    return normalized[:120] or fallback


def unique(values: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(value.rstrip("/") for value in values if value))


def new_session() -> requests.Session:
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Accept": "*/*"})
    return session


def call_with_retries(
    session: requests.Session,
    host: str,
    operation: int,
    payload: bytes,
    *,
    retries: int,
) -> bytes:
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            result = call(session, host, operation, payload, send_content_type=False)
            return result.payload
        except (
            Exception
        ) as error:  # network and server status errors are retryable here
            last_error = error
            if attempt < retries:
                delay = min(30.0, 1.5 * (2 ** (attempt - 1)))
                print(
                    f"  operation {operation} failed ({type(error).__name__}), "
                    f"retrying in {delay:g}s ({attempt}/{retries})",
                    flush=True,
                )
                time.sleep(delay)
    raise ExportError(
        f"operation {operation} failed after {retries} attempts: "
        f"{type(last_error).__name__}: {last_error}"
    ) from last_error


def choose_api_host(
    session: requests.Session,
    candidates: list[str],
    *,
    retries: int,
) -> str:
    """Select the first candidate that passes FunBox's operation-100 status response."""

    errors: list[str] = []
    for host in candidates:
        try:
            payload = call_with_retries(
                session, host, OP_PROBE, probe_payload(), retries=retries
            )
            reader = BinaryReader(payload)
            status = reader.int()
            message = reader.string()
            if status != 0:
                raise ProtocolError(f"probe status={status}, message={message!r}")
            print(f"Using API host: {host}")
            return host
        except Exception as error:
            errors.append(f"{host}: {type(error).__name__}: {error}")
    raise ExportError("No API host passed operation 100:\n" + "\n".join(errors))


def choose_object_host(
    session: requests.Session,
    candidates: list[str],
    *,
    retries: int,
) -> str:
    """Select a usable object host using FunBox's lightweight legacy probe path."""

    errors: list[str] = []
    for host in candidates:
        url = host.rstrip("/") + "/vfile/vfun/vtest"
        for attempt in range(1, retries + 1):
            try:
                response = session.get(url, timeout=(30, 60))
                response.raise_for_status()
                # Older servers return the path itself; current servers return ``success``.
                body = response.text.strip().lower()
                if body not in {"success", "/vfile/vfun/vtest", "vfile/vfun/vtest"}:
                    raise ProtocolError(
                        f"unexpected object probe body={response.text[:80]!r}"
                    )
                print(f"Using object host: {host}")
                return host
            except Exception as error:
                errors.append(f"{host}: {type(error).__name__}: {error}")
                if attempt < retries:
                    time.sleep(min(30.0, 1.5 * (2 ** (attempt - 1))))
    raise ExportError("No object host passed the FunBox probe:\n" + "\n".join(errors))


def fetch_catalog(
    session: requests.Session,
    api_host: str,
    account: str,
    *,
    retries: int,
) -> list[dict[str, Any]]:
    payload = call_with_retries(
        session,
        api_host,
        OP_CATALOG,
        catalog_payload(account),
        retries=retries,
    )
    packs = decode_catalog(payload)
    if not packs:
        raise ExportError("operation 10 returned an empty sticker catalog")
    return packs


def fetch_pack(
    session: requests.Session,
    api_host: str,
    pack_id: str,
    account: str,
    *,
    retries: int,
) -> list[dict[str, str]]:
    payload = call_with_retries(
        session,
        api_host,
        OP_PACK_CONTENTS,
        pack_payload(pack_id, account),
        retries=retries,
    )
    return list(decode_pack(payload)["items"])


def existing_sticker_file(directory: Path, sticker: dict[str, str]) -> Path | None:
    """Return a previously exported file only when its signature is still valid."""

    key = sticker.get("md5", "").strip().upper()
    image_id = sticker.get("image_id", "").strip()
    candidates = []
    if key:
        candidates.extend(directory.glob(f"{safe_component(key, 'sticker')}.*"))
    if not candidates and image_id:
        candidates.extend(directory.glob(f"{safe_component(image_id, 'sticker')}.*"))
    for path in candidates:
        try:
            with path.open("rb") as stream:
                data = stream.read(16)
        except OSError:
            continue
        if data and image_type(data):
            return path
    return None


def sticker_file_name(sticker: dict[str, str], data: bytes) -> str:
    key = sticker.get("md5", "").strip().upper()
    if not key:
        key = hashlib.sha256(sticker.get("image_id", "").encode("utf-8")).hexdigest()
    media_type = image_type(data)
    extension = {"jpeg": "jpg"}.get(media_type or "", media_type or "bin")
    return f"{safe_component(key, 'sticker')}.{extension}"


def download_sticker(
    session: requests.Session,
    object_host: str,
    pack_directory: Path,
    sticker: dict[str, str],
    *,
    retries: int,
) -> tuple[bool, str, int]:
    """Download one full image object, decrypting Sec-protected FunBox responses."""

    object_id = sticker.get("image_id", "").strip()
    if not object_id:
        raise ExportError("sticker has no image object ID")
    existing = existing_sticker_file(pack_directory, sticker)
    if existing is not None:
        return False, existing.name, existing.stat().st_size

    url = object_host.rstrip("/") + "/vfile/image/" + object_id.lstrip("/")
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        temporary: Path | None = None
        try:
            response = session.get(url, timeout=(30, 180))
            response.raise_for_status()
            data, _secured = decode_object_response(response)
            if not data or image_type(data) is None:
                raise ProtocolError(
                    f"object {object_id[:16]!r} returned {len(data)} bytes with unknown image type"
                )
            pack_directory.mkdir(parents=True, exist_ok=True)
            final_path = pack_directory / sticker_file_name(sticker, data)
            temporary = final_path.with_name(f".{final_path.name}.{os.getpid()}.part")
            temporary.write_bytes(data)
            os.replace(temporary, final_path)
            return True, final_path.name, len(data)
        except Exception as error:
            last_error = error
            if temporary is not None:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass
            if attempt < retries:
                time.sleep(min(30.0, 1.5 * (2 ** (attempt - 1))))
    raise ExportError(
        f"image object {object_id[:16]!r} failed after {retries} attempts: "
        f"{type(last_error).__name__}: {last_error}"
    ) from last_error


def load_json(path: Path) -> Any | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None


def catalog_pack_id(pack: Any, context: str) -> str:
    if not isinstance(pack, dict):
        raise ExportError(f"{context} is not an object")
    pack_id = str(pack.get("id", "")).strip()
    if not pack_id:
        raise ExportError(f"{context} has no pack ID")
    return pack_id


def merge_catalogs(
    cached_catalog: list[Any],
    sampled_catalog: list[Any],
) -> tuple[list[dict[str, Any]], set[str], set[str], set[str]]:
    """Merge sampled metadata without treating unobserved cached packs as deleted."""

    merged_by_id: dict[str, dict[str, Any]] = {}
    for index, pack in enumerate(cached_catalog):
        pack_id = catalog_pack_id(pack, f"cached catalog entry {index}")
        merged_by_id[pack_id] = pack

    sampled_ids: set[str] = set()
    added_ids: set[str] = set()
    changed_ids: set[str] = set()
    for index, pack in enumerate(sampled_catalog):
        pack_id = catalog_pack_id(pack, f"sampled catalog entry {index}")
        sampled_ids.add(pack_id)
        previous = merged_by_id.get(pack_id)
        if previous is None:
            added_ids.add(pack_id)
        elif previous != pack:
            changed_ids.add(pack_id)
        merged_by_id[pack_id] = pack

    return list(merged_by_id.values()), sampled_ids, added_ids, changed_ids


def fetch_catalog_samples(
    session: requests.Session,
    api_host: str,
    account: str,
    *,
    retries: int,
    sample_count: int,
) -> list[dict[str, Any]]:
    """Collect random operation-10 responses, retaining the newest metadata per ID."""

    sampled_by_id: dict[str, dict[str, Any]] = {}
    successful_samples = 0
    errors: list[str] = []
    for sample_index in range(sample_count):
        try:
            sample = fetch_catalog(
                session,
                api_host,
                account,
                retries=retries,
            )
            validated_sample: list[tuple[str, dict[str, Any]]] = []
            for pack_index, pack in enumerate(sample):
                pack_id = catalog_pack_id(
                    pack,
                    f"catalog sample {sample_index + 1} entry {pack_index}",
                )
                validated_sample.append((pack_id, pack))
            successful_samples += 1
            for pack_id, pack in validated_sample:
                sampled_by_id[pack_id] = pack
            print(
                f"Catalog sample {sample_index + 1}/{sample_count}: "
                f"received {len(sample)} packs, "
                f"unique this refresh={len(sampled_by_id)}",
                flush=True,
            )
        except KeyboardInterrupt:
            raise
        except Exception as error:
            errors.append(f"{type(error).__name__}: {error}")
            print(
                f"Catalog sample {sample_index + 1}/{sample_count} failed: "
                f"{type(error).__name__}: {error}",
                file=sys.stderr,
                flush=True,
            )

    if successful_samples == 0:
        details = "\n".join(errors)
        raise ExportError(f"all {sample_count} catalog samples failed:\n{details}")
    return list(sampled_by_id.values())


def sticker_identity(sticker: Any) -> str:
    if not isinstance(sticker, dict):
        return "value:" + json.dumps(
            sticker,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    md5 = str(sticker.get("md5", "")).strip().upper()
    if md5:
        return f"md5:{md5}"
    image_id = str(sticker.get("image_id", "")).strip()
    if image_id:
        return f"image:{image_id}"
    return "value:" + json.dumps(
        sticker,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def comparable_sticker(sticker: Any) -> Any:
    if not isinstance(sticker, dict) or "md5" not in sticker:
        return sticker
    normalized = dict(sticker)
    normalized["md5"] = str(normalized["md5"]).strip().upper()
    return normalized


def summarize_item_changes(
    previous_items: list[Any] | None,
    current_items: list[Any],
) -> tuple[int, int, int, bool]:
    """Return added, removed, changed, and reordered item counts/state."""

    if previous_items is None:
        return len(current_items), 0, 0, False

    def indexed(items: list[Any]) -> tuple[dict[tuple[str, int], Any], list[str]]:
        occurrences: dict[str, int] = {}
        values: dict[tuple[str, int], Any] = {}
        identities: list[str] = []
        for item in items:
            identity = sticker_identity(item)
            ordinal = occurrences.get(identity, 0)
            occurrences[identity] = ordinal + 1
            values[(identity, ordinal)] = comparable_sticker(item)
            identities.append(identity)
        return values, identities

    previous_by_key, previous_order = indexed(previous_items)
    current_by_key, current_order = indexed(current_items)
    previous_keys = set(previous_by_key)
    current_keys = set(current_by_key)
    added = len(current_keys - previous_keys)
    removed = len(previous_keys - current_keys)
    changed = sum(
        previous_by_key[key] != current_by_key[key]
        for key in previous_keys & current_keys
    )
    return added, removed, changed, previous_order != current_order


def existing_pack_directory(
    output: Path,
    state: dict[str, Any],
    pack_id: str,
) -> Path | None:
    pack_state = state.get("packs", {}).get(pack_id)
    if not isinstance(pack_state, dict):
        return None
    relative_value = pack_state.get("directory")
    if not isinstance(relative_value, str) or not relative_value.strip():
        return None
    relative = Path(relative_value)
    if relative.is_absolute() or ".." in relative.parts:
        return None
    candidate = (output / relative).resolve()
    if candidate == output or output not in candidate.parents:
        return None
    return candidate


def export(args: argparse.Namespace) -> int:
    output = args.output.resolve()
    metadata_dir = output / "_metadata"
    catalog_path = metadata_dir / "catalog.json"
    state_path = metadata_dir / "checkpoint.json"
    output.mkdir(parents=True, exist_ok=True)

    state = load_json(state_path)
    if not isinstance(state, dict) or state.get("version") != STATE_VERSION:
        state = {"version": STATE_VERSION, "account": args.account, "packs": {}}
    state["account"] = args.account
    if not isinstance(state.get("packs"), dict):
        state["packs"] = {}
    state_lock = threading.Lock()

    bootstrap = new_session()
    if args.api_host and args.object_host:
        resolved_api, resolved_objects = [], []
    else:
        resolved_api, resolved_objects = resolve_candidates(bootstrap)
    api_host = choose_api_host(
        bootstrap,
        unique(args.api_host or resolved_api),
        retries=args.retries,
    )
    object_host = choose_object_host(
        bootstrap,
        unique(args.object_host or resolved_objects),
        retries=args.retries,
    )
    state["api_host"] = api_host
    state["object_host"] = object_host
    with state_lock:
        atomic_write_json(state_path, state)

    cached_catalog = load_json(catalog_path)
    catalog: list[dict[str, Any]]
    sampled_pack_ids: set[str] = set()
    if args.refresh_catalog or not isinstance(cached_catalog, list):
        try:
            sampled_catalog = fetch_catalog_samples(
                bootstrap,
                api_host,
                args.account,
                retries=args.retries,
                sample_count=args.catalog_samples,
            )
            catalog, sampled_pack_ids, added_ids, changed_ids = merge_catalogs(
                cached_catalog if isinstance(cached_catalog, list) else [],
                sampled_catalog,
            )
            atomic_write_json(catalog_path, catalog)
            state["last_catalog_refresh"] = {
                "refreshed_at": int(time.time()),
                "requested_samples": args.catalog_samples,
                "observed_packs": len(sampled_pack_ids),
                "added_packs": len(added_ids),
                "updated_packs": len(changed_ids),
                "cached_packs": len(catalog) - len(added_ids),
                "merged_packs": len(catalog),
            }
            with state_lock:
                atomic_write_json(state_path, state)
            print(
                f"Catalog merged: {len(sampled_pack_ids)} observed, "
                f"{len(added_ids)} new, {len(changed_ids)} updated, "
                f"{len(catalog)} cached in total. "
                "Unobserved cached packs were preserved.",
                flush=True,
            )
        except Exception as error:
            if not isinstance(cached_catalog, list):
                raise
            sampled_pack_ids.clear()
            print(
                f"Catalog refresh failed ({type(error).__name__}: {error}); "
                "resuming from cached catalog.",
                file=sys.stderr,
            )
            catalog, _, _, _ = merge_catalogs(cached_catalog, [])
    else:
        catalog, _, _, _ = merge_catalogs(cached_catalog, [])
        print(f"Using cached catalog: {len(catalog)} packs")

    if args.max_packs is not None:
        catalog = catalog[: args.max_packs]
    print(f"Exporting {len(catalog)} sticker packs to {output}")

    # Each worker owns a requests.Session.  requests.Session is not documented as
    # thread-safe, and the exporter may be interrupted between any two completed objects.
    def process_pack(
        index: int, pack: dict[str, Any]
    ) -> tuple[
        str,
        int,
        int,
        list[str],
        tuple[int, int, int, bool] | None,
        bool,
    ]:
        pack_id = catalog_pack_id(pack, f"catalog entry {index}")
        title = str(pack.get("title", "")).strip() or pack_id
        pack_metadata_path = (
            metadata_dir
            / "packs"
            / (hashlib.sha256(pack_id.encode()).hexdigest() + ".json")
        )
        cached_pack = load_json(pack_metadata_path)
        cached_items = (
            cached_pack.get("items")
            if isinstance(cached_pack, dict)
            and isinstance(cached_pack.get("items"), list)
            else None
        )
        directory = existing_pack_directory(output, state, pack_id)
        if directory is None:
            cached_metadata = (
                cached_pack.get("pack") if isinstance(cached_pack, dict) else None
            )
            directory_title = (
                str(cached_metadata.get("title", "")).strip()
                if isinstance(cached_metadata, dict)
                else ""
            )
            directory = output / (
                f"{safe_component(directory_title or title, 'pack')}_"
                f"{safe_component(pack_id, 'id')[:48]}"
            )

        previous_pack_state = state["packs"].get(pack_id)
        retry_content_refresh = isinstance(previous_pack_state, dict) and bool(
            previous_pack_state.get("content_refresh_error")
        )
        should_refresh_contents = (
            cached_items is None or pack_id in sampled_pack_ids or retry_content_refresh
        )
        content_changes: tuple[int, int, int, bool] | None = None
        content_refresh_error: str | None = None
        if should_refresh_contents:
            try:
                items = fetch_pack(
                    new_session(),
                    api_host,
                    pack_id,
                    args.account,
                    retries=args.retries,
                )
                content_changes = summarize_item_changes(cached_items, items)
                atomic_write_json(
                    pack_metadata_path,
                    {
                        "pack": pack,
                        "items": items,
                        "saved_at": int(time.time()),
                    },
                )
                added, removed, changed, reordered = content_changes
                if added or removed or changed or reordered:
                    details = f"+{added} -{removed} changed={changed}"
                    if reordered:
                        details += " reordered"
                    print(
                        f"[{index + 1}/{len(catalog)}] {title}: "
                        f"contents updated ({details})",
                        flush=True,
                    )
            except KeyboardInterrupt:
                raise
            except Exception as error:
                if cached_items is None:
                    raise
                content_refresh_error = f"{type(error).__name__}: {error}"
                items = cached_items
                print(
                    f"[{index + 1}/{len(catalog)}] {title}: "
                    f"content refresh failed; using cached items: "
                    f"{content_refresh_error}",
                    file=sys.stderr,
                    flush=True,
                )
        else:
            assert cached_items is not None
            items = cached_items

        if args.max_items is not None:
            items = items[: args.max_items]

        completed = 0
        failed: list[str] = []
        with ThreadPoolExecutor(
            max_workers=max(1, min(args.workers, len(items) or 1))
        ) as pool:
            futures = {
                pool.submit(
                    download_sticker,
                    # A Session is deliberately private to each download.  requests does
                    # not promise that one Session can be used concurrently by workers.
                    new_session(),
                    object_host,
                    directory,
                    item,
                    retries=args.retries,
                ): item
                for item in items
            }
            for future in as_completed(futures):
                item = futures[future]
                try:
                    was_downloaded, filename, size = future.result()
                    completed += 1
                    if was_downloaded:
                        print(
                            f"[{index + 1}/{len(catalog)}] {title}: "
                            f"{completed}/{len(items)} "
                            f"saved {filename} ({size} B)",
                            flush=True,
                        )
                except Exception as error:
                    identifier = item.get("md5") or item.get("image_id") or "<unknown>"
                    failed.append(str(identifier))
                    print(
                        f"[{index + 1}/{len(catalog)}] {title}: failed {identifier!s}: {error}",
                        file=sys.stderr,
                        flush=True,
                    )
        result = {
            "pack": pack,
            "items": len(items),
            "completed": completed,
            "failed": failed,
            "directory": str(directory.relative_to(output)),
            "updated_at": int(time.time()),
        }
        if content_changes is not None:
            added, removed, changed, reordered = content_changes
            result["last_content_refresh"] = {
                "refreshed_at": int(time.time()),
                "added": added,
                "removed": removed,
                "changed": changed,
                "reordered": reordered,
            }
        elif isinstance(previous_pack_state, dict) and isinstance(
            previous_pack_state.get("last_content_refresh"), dict
        ):
            result["last_content_refresh"] = previous_pack_state["last_content_refresh"]
        if content_refresh_error is not None:
            result["content_refresh_error"] = content_refresh_error
        with state_lock:
            state["packs"][pack_id] = result
            atomic_write_json(state_path, state)
        return (
            pack_id,
            completed,
            len(items),
            failed,
            content_changes,
            content_refresh_error is not None,
        )

    total_items = 0
    total_completed = 0
    failed_packs = 0
    refreshed_pack_contents = 0
    changed_pack_contents = 0
    # Pack requests are deliberately sequential.  The expensive object downloads are
    # concurrent within each pack, while sequential operation-2 requests avoid flooding
    # this private service and make checkpoint progress easy to inspect.
    for index, pack in enumerate(catalog):
        try:
            (
                _pack_id,
                completed,
                item_count,
                failed,
                content_changes,
                content_refresh_failed,
            ) = process_pack(index, pack)
            total_items += item_count
            total_completed += completed
            failed_packs += bool(failed) or content_refresh_failed
            if content_changes is not None:
                refreshed_pack_contents += 1
                if any(content_changes):
                    changed_pack_contents += 1
        except KeyboardInterrupt:
            with state_lock:
                atomic_write_json(state_path, state)
            print(
                "Interrupted; checkpoint saved. Re-run the same command to continue.",
                file=sys.stderr,
            )
            return 130
        except Exception as error:
            failed_packs += 1
            print(
                f"[{index + 1}/{len(catalog)}] pack failed: {type(error).__name__}: {error}",
                file=sys.stderr,
                flush=True,
            )

    with state_lock:
        state["last_run"] = {
            "finished_at": int(time.time()),
            "packs": len(catalog),
            "items": total_items,
            "completed": total_completed,
            "failed_packs": failed_packs,
            "sampled_packs": len(sampled_pack_ids),
            "refreshed_pack_contents": refreshed_pack_contents,
            "changed_pack_contents": changed_pack_contents,
        }
        atomic_write_json(state_path, state)
    print(
        f"Finished: {total_completed}/{total_items} stickers exported; "
        f"pack contents refreshed={refreshed_pack_contents}, "
        f"changed={changed_pack_contents}, "
        f"packs with failures={failed_packs}. Re-run to retry failures.",
    )
    return 0 if failed_packs == 0 else 2


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("funbox-stickers"),
        help="output directory (default: ./funbox-stickers)",
    )
    parser.add_argument(
        "--account",
        default="",
        help="WeChat login username/wxid used by operation 10/2 (default: empty string)",
    )
    parser.add_argument(
        "--api-host", action="append", help="API host override; may be repeated"
    )
    parser.add_argument(
        "--object-host", action="append", help="object host override; may be repeated"
    )
    parser.add_argument(
        "--refresh-catalog",
        action="store_true",
        help=(
            "sample and merge the server catalog into the cache, then recheck "
            "sampled pack contents"
        ),
    )
    parser.add_argument(
        "--catalog-samples",
        type=int,
        default=DEFAULT_CATALOG_SAMPLES,
        help=(
            "random operation-10 responses to merge during a catalog refresh "
            f"(default: {DEFAULT_CATALOG_SAMPLES}; repeated runs accumulate)"
        ),
    )
    parser.add_argument(
        "--max-packs", type=int, help="export at most N packs (useful for a smoke test)"
    )
    parser.add_argument(
        "--max-items", type=int, help="export at most N stickers per pack"
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=DEFAULT_WORKERS,
        help=f"parallel image downloads per pack (default: {DEFAULT_WORKERS})",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=DEFAULT_RETRIES,
        help=f"attempts per API/object request (default: {DEFAULT_RETRIES})",
    )
    args = parser.parse_args()
    if args.workers < 1 or args.retries < 1 or args.catalog_samples < 1:
        parser.error("--workers, --retries, and --catalog-samples must be at least 1")
    if args.max_packs is not None and args.max_packs < 1:
        parser.error("--max-packs must be at least 1")
    if args.max_items is not None and args.max_items < 1:
        parser.error("--max-items must be at least 1")
    return args


if __name__ == "__main__":
    try:
        raise SystemExit(export(parse_args()))
    except KeyboardInterrupt:
        raise SystemExit(130)
