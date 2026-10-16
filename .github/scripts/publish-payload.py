#!/usr/bin/env python3
"""Stream-encrypt one signed APK, sign its manifest, and publish it.

Only ciphertext, signed public metadata and nonsecret resume state are written.
Content keys and publication session tokens remain in this process's memory.
The local command mints the same short-lived HS256 CI session accepted by the
publication API using the private release-machine secret; GitHub Actions may
still use its OIDC exchange when no local secret file is supplied.
"""
import argparse
import base64
import hashlib
import hmac
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import secrets
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CHUNK_SIZE = 1048576
PART_SIZE = 8388608


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def b64_decode(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("invalid base64url secret")
    try:
        raw = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except ValueError as error:
        raise ValueError("invalid base64url secret") from error
    if b64(raw) != value:
        raise ValueError("invalid base64url secret")
    return raw


def digest_file(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        while data := source.read(CHUNK_SIZE):
            h.update(data)
    return h.hexdigest()


def save_state(path, state):
    temporary = path.with_suffix(".part")
    temporary.write_text(json.dumps(state, separators=(",", ":")), encoding="utf-8")
    temporary.replace(path)


def prepare(args, output):
    apk_size = args.apk.stat().st_size
    if not apk_size:
        raise ValueError("business APK is empty")
    apk_hash = digest_file(args.apk)
    payload_id = args.payload_id or uuid.uuid4().hex
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", payload_id):
        raise ValueError("invalid payload ID")
    key = secrets.token_bytes(32)
    prefix = secrets.token_bytes(8)
    count = (apk_size + CHUNK_SIZE - 1) // CHUNK_SIZE
    if count > 0xffffffff:
        raise ValueError("too many encryption chunks")
    aad_prefix = f"WEKIT-APK-V1\n{payload_id}\n{apk_hash}\n".encode() + struct.pack(">QI", apk_size, count)
    cipher_path = output / "cipher.bin"
    temporary = output / "cipher.bin.part"
    aes = AESGCM(key)
    with args.apk.open("rb") as source, temporary.open("wb") as encrypted:
        for index in range(count):
            chunk = source.read(CHUNK_SIZE)
            encrypted.write(aes.encrypt(prefix + struct.pack(">I", index), chunk,
                                        aad_prefix + struct.pack(">I", index)))
        if source.read(1):
            raise ValueError("APK changed during encryption")
    if digest_file(args.apk) != apk_hash:
        raise ValueError("APK changed during encryption")
    temporary.replace(cipher_path)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    version = subprocess.check_output(["git", "rev-parse", "--short=8", "HEAD"], text=True).strip()
    manifest = {
        "payloadId": payload_id, "businessVersion": f"git+{version}", "sourceCommit": commit,
        "entryClass": "dev.ujhhgtg.wekit.loader.startup.PayloadEntryImpl",
        "apkSha256": apk_hash, "apkSize": apk_size,
        "cipherSha256": digest_file(cipher_path), "cipherSize": cipher_path.stat().st_size,
        "encryption": {"algorithm": "AES-256-GCM", "chunkSize": CHUNK_SIZE,
                       "noncePrefix": b64(prefix), "chunkCount": count},
        "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    raw = json.dumps(manifest, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    signing = serialization.load_pem_private_key(args.signing_key_file.read_bytes(), password=None)
    if not isinstance(signing, ec.EllipticCurvePrivateKey) or not isinstance(signing.curve, ec.SECP256R1):
        raise ValueError("release signing key must be EC P-256")
    r, s = utils.decode_dss_signature(signing.sign(b"WEKIT-RELEASE-V1\0" + raw, ec.ECDSA(hashes.SHA256())))
    envelope = {"keyId": args.key_id, "manifest": b64(raw),
                "signature": b64(r.to_bytes(32, "big") + s.to_bytes(32, "big"))}
    state = {"jobId": uuid.uuid4().hex, "payloadId": payload_id, "manifest": envelope,
             "sourceCommit": commit,
             "created": False, "parts": [], "cipherSha256": manifest["cipherSha256"],
             "cipherSize": manifest["cipherSize"], "apiUrl": args.api_url.rstrip("/")}
    save_state(output / "manifest.json", envelope)
    save_state(output / "publication.json", state)
    return state, key


class Publisher:
    def __init__(self, origin, audience, local_secret=None, source_commit=None,
                 repository_id="1349763946", repository_owner_id="52984443"):
        self.origin, self.audience = origin, audience
        self.local_secret, self.source_commit = local_secret, source_commit
        self.repository_id, self.repository_owner_id = repository_id, repository_owner_id
        self.token, self.expires_at = None, 0

    @staticmethod
    def request(url, body=None, token=None, method="POST"):
        data = body if isinstance(body, bytes) else json.dumps(body or {}, separators=(",", ":")).encode()
        headers = {"Content-Type": "application/octet-stream" if isinstance(body, bytes) else "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        for attempt in range(4):
            try:
                request = urllib.request.Request(url, data=data if method != "GET" else None,
                                                 headers=headers, method=method)
                with urllib.request.urlopen(request, timeout=120) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if error.code < 500 or attempt == 3:
                    raise RuntimeError(f"publication HTTP {error.code} at {urllib.parse.urlsplit(url).path}") from None
            except urllib.error.URLError:
                if attempt == 3:
                    raise RuntimeError("publication connection failed") from None
            time.sleep(2 ** attempt)

    def session(self):
        if self.token and self.expires_at > time.time() * 1000 + 30000:
            return self.token
        if self.local_secret is not None:
            if not self.source_commit or not re.fullmatch(r"[0-9a-f]{40}", self.source_commit):
                raise ValueError("publication state has no valid source commit")
            now = int(time.time())
            header = b64(json.dumps({"alg": "HS256", "typ": "JWT"},
                                    separators=(",", ":")).encode())
            claims = {
                "sha": self.source_commit,
                "runId": "local-" + secrets.token_hex(16),
                "iss": "wekit-ci",
                "aud": "wekit-publication",
                "sub": f"{self.repository_owner_id}:{self.repository_id}",
                "iat": now,
                "exp": now + 600,
            }
            payload = b64(json.dumps(claims, separators=(",", ":")).encode())
            signing_input = f"{header}.{payload}".encode("ascii")
            signature = hmac.new(self.local_secret, signing_input, hashlib.sha256).digest()
            self.token = f"{header}.{payload}.{b64(signature)}"
            self.expires_at = (now + 600) * 1000
            return self.token
        oidc_url = os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"]
        separator = "&" if "?" in oidc_url else "?"
        oidc = self.request(oidc_url + separator + urllib.parse.urlencode({"audience": self.audience}),
                            token=os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"], method="GET")["value"]
        response = self.request(self.origin + "/ci/sessions", token=oidc)
        self.token, self.expires_at = response["token"], response["expiresAt"]
        return self.token

    def call(self, path, body=None, method="POST"):
        return self.request(self.origin + path, body, self.session(), method)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--api-url", required=True)
    parser.add_argument("--audience", required=True)
    parser.add_argument("--signing-key-file", type=Path, required=True)
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--channel", default="stable")
    parser.add_argument("--payload-id")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--local-secrets-file", type=Path,
                        help="JSON containing CI_SESSION_SECRET for local publication")
    parser.add_argument("--repository-id", default="1349763946")
    parser.add_argument("--repository-owner-id", default="52984443")
    args = parser.parse_args()
    if urllib.parse.urlsplit(args.api_url).scheme != "https":
        parser.error("publication API must use HTTPS")
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    state_path = output / "publication.json"
    key = None
    if state_path.is_file():
        state = json.loads(state_path.read_text())
        if state["apiUrl"] != args.api_url.rstrip("/") or (args.payload_id and state["payloadId"] != args.payload_id):
            raise ValueError("existing publication belongs to a different API/payload")
        if digest_file(output / "cipher.bin") != state["cipherSha256"]:
            raise ValueError("existing ciphertext no longer matches publication")
    else:
        state, key = prepare(args, output)
    # Older resumable state files predate the explicit sourceCommit field.
    # Recover it from the signed manifest without trusting a mutable sidecar.
    if not state.get("sourceCommit"):
        try:
            encoded = state["manifest"]["manifest"]
            raw_manifest = b64_decode(encoded)
            state["sourceCommit"] = json.loads(raw_manifest)["sourceCommit"]
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            raise ValueError("publication state has no valid source commit") from error
    if args.prepare_only:
        # This is a local encryption/signature inspection only, not a resumable publication:
        # no key is stored. Use a fresh output directory for the real publication.
        print(f"Prepared inspection ciphertext for {state['payloadId']}; content key discarded")
        return
    local_secret = None
    if args.local_secrets_file:
        mode = args.local_secrets_file.stat().st_mode & 0o777
        if mode & 0o077:
            raise ValueError("local secrets file must be private (mode 0600 or stricter)")
        try:
            local_values = json.loads(args.local_secrets_file.read_text(encoding="utf-8"))
            local_secret = b64_decode(local_values["CI_SESSION_SECRET"])
        except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
            raise ValueError("local secrets file does not contain CI_SESSION_SECRET") from error
        if len(local_secret) != 32:
            raise ValueError("CI_SESSION_SECRET must be exactly 32 bytes")
    publisher = Publisher(
        args.api_url.rstrip("/"), args.audience, local_secret,
        state.get("sourceCommit"), args.repository_id, args.repository_owner_id,
    )
    if not state["created"]:
        body = {k: state[k] for k in ("jobId", "payloadId", "manifest")}
        if key is not None:
            body["contentKey"] = b64(key)
        publisher.call("/ci/publications", body)
        state["created"] = True
        save_state(state_path, state)
    key = None
    remote = publisher.call(f"/ci/publications/{state['jobId']}", method="GET")
    if remote["state"] == "UPLOADING":
        with (output / "cipher.bin").open("rb") as source:
            number = 1
            while part := source.read(PART_SIZE):
                response = publisher.call(f"/ci/publications/{state['jobId']}/parts/{number}", part, "PUT")
                result = {"partNumber": number, "etag": response["etag"]}
                state["parts"] = [p for p in state["parts"] if p["partNumber"] != number] + [result]
                save_state(state_path, state)
                number += 1
        publisher.call(f"/ci/publications/{state['jobId']}/complete", {"parts": state["parts"]})
    elif remote["state"] not in ("READY", "ALLOWED"):
        raise ValueError("publication is not uploadable or ready")
    publisher.call(f"/ci/publications/{state['jobId']}/activate", {"channel": args.channel})
    state["state"] = "ALLOWED"
    save_state(state_path, state)
    print(f"Published payload {state['payloadId']} to {args.channel}")


if __name__ == "__main__":
    main()
