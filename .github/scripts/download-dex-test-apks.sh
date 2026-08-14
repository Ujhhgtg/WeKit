#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 MANIFEST CACHE_DIR [--failures-out FILE]" >&2
  exit 2
fi

manifest=$1
cache_dir=$2
shift 2
failures_out=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --failures-out)
      if [[ $# -lt 2 ]]; then
        echo "--failures-out requires a file path" >&2
        exit 2
      fi
      failures_out=$2
      shift 2
      ;;
    *)
      echo "unknown option: $1" >&2
      exit 2
      ;;
  esac
done
if [[ -n "$failures_out" ]]; then
  : >"$failures_out"
fi
user_agent='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128 Safari/537.36'
apkeditor_version='V1.4.7'
apkeditor_url="https://github.com/REAndroid/APKEditor/releases/download/${apkeditor_version}/APKEditor-1.4.7.jar"

for command in curl htmlq jq java sha256sum unzip; do
  command -v "$command" >/dev/null || {
    echo "missing required command: $command" >&2
    exit 1
  }
done

test -f "$manifest"
mkdir -p "$cache_dir/tools"

request() {
  local url=$1
  local output=${2:--}
  curl --fail --location --retry 3 --retry-all-errors \
    --user-agent "$user_agent" \
    --header 'Accept-Language: en-US,en;q=0.9' \
    --output "$output" \
    "$url"
}

validate_apk() {
  local apk=$1
  unzip -tqq "$apk" >/dev/null 2>&1 &&
    unzip -Z1 "$apk" | grep -Fx 'AndroidManifest.xml' >/dev/null &&
    unzip -Z1 "$apk" | grep -E '^classes([0-9]+)?\.dex$' >/dev/null
}

cache_is_valid() {
  local apk=$1
  local sidecar="${apk}.sha256"
  [[ -f "$apk" && -f "$sidecar" ]] || return 1
  validate_apk "$apk" || return 1
  local expected actual
  expected=$(cut -d' ' -f1 "$sidecar")
  actual=$(sha256sum "$apk" | cut -d' ' -f1)
  [[ -n "$expected" && "$expected" == "$actual" ]]
}

write_sidecar() {
  local apk=$1
  local digest
  digest=$(sha256sum "$apk" | cut -d' ' -f1)
  printf '%s  %s\n' "$digest" "$(basename "$apk")" >"${apk}.sha256"
}

record_failure() {
  local file_name=$1
  local reason=$2
  echo "failed to download $file_name: $reason" >&2
  if [[ -n "$failures_out" ]]; then
    printf '%s\n' "$file_name" >>"$failures_out"
  else
    exit 1
  fi
}

select_apkmirror_variant() {
  local response=$1
  local wanted_type node text architecture href index
  for wanted_type in APK BUNDLE; do
    for index in $(seq 1 60); do
      node=$(htmlq "div.table-row.headerFont:nth-last-child(${index})" \
        -r 'span:nth-child(n+3)' <<<"$response")
      [[ -n "$node" ]] || break
      text=$(htmlq --text --ignore-whitespace <<<"$node")
      [[ $(sed -n '3p' <<<"$text") == "$wanted_type" ]] || continue
      architecture=$(sed -n '4p' <<<"$text")
      case "$architecture" in
        universal | noarch | arm64-v8a | 'arm64-v8a + armeabi-v7a') ;;
        *) continue ;;
      esac
      href=$(htmlq --base 'https://www.apkmirror.com' --attribute href \
        'div:nth-child(1) > a:nth-child(1)' <<<"$node")
      [[ -n "$href" ]] || continue
      printf '%s\t%s\n' "$wanted_type" "$href"
      return 0
    done
  done
  return 1
}

download_apkmirror() {
  local release_url=$1
  local target=$2
  local response selection asset_type variant_url button_url final_url download
  response=$(request "$release_url" -)
  selection=$(select_apkmirror_variant "$response") || {
    echo "no compatible APKMirror variant at $release_url" >&2
    return 1
  }
  asset_type=${selection%%$'\t'*}
  variant_url=${selection#*$'\t'}
  response=$(request "$variant_url" -)
  button_url=$(htmlq --base 'https://www.apkmirror.com' --attribute href 'a.btn' <<<"$response" | head -n1)
  [[ -n "$button_url" ]]
  response=$(request "$button_url" -)
  final_url=$(htmlq --base 'https://www.apkmirror.com' --attribute href \
    'span > a[rel = nofollow]' <<<"$response" | head -n1)
  [[ -n "$final_url" ]]

  if [[ "$asset_type" == APK ]]; then
    download="${target}.download.$$"
    request "$final_url" "$download"
    mv -f "$download" "$target"
    return
  fi

  local bundle="${target}.bundle.$$.apkm"
  local merged="${target}.merged.$$.apk"
  local apkeditor="$cache_dir/tools/APKEditor-1.4.7.jar"
  request "$final_url" "$bundle"
  if [[ ! -f "$apkeditor" ]]; then
    request "$apkeditor_url" "${apkeditor}.download"
    mv -f "${apkeditor}.download" "$apkeditor"
  fi
  java -jar "$apkeditor" merge -i "$bundle" -o "$merged" -clean-meta -f
  rm -f "$bundle"
  mv -f "$merged" "$target"
}

mapfile -t sources < <(jq -c '.sources[]' "$manifest")
[[ ${#sources[@]} -gt 0 ]] || {
  echo "source manifest is empty" >&2
  exit 1
}

for source in "${sources[@]}"; do
  file_name=$(jq -r '.fileName' <<<"$source")
  channel=$(jq -r '.channel' <<<"$source")
  source_url=$(jq -r '.sourceUrl' <<<"$source")
  target="$cache_dir/$file_name"

  if cache_is_valid "$target"; then
    echo "reusing cached $file_name" >&2
    printf '%s\n' "$target"
    continue
  fi

  rm -f "$target" "${target}.sha256"
  echo "downloading $file_name from $channel source" >&2
  if [[ "$channel" == domestic ]]; then
    if ! request "$source_url" "${target}.download.$$"; then
      record_failure "$file_name" "HTTP request failed"
      continue
    fi
    mv -f "${target}.download.$$" "$target"
  elif [[ "$channel" == google-play ]]; then
    if ! download_apkmirror "$source_url" "$target"; then
      record_failure "$file_name" "APKMirror download failed"
      continue
    fi
  else
    record_failure "$file_name" "unknown APK source channel: $channel"
    continue
  fi

  if ! validate_apk "$target"; then
    rm -f "$target"
    record_failure "$file_name" "downloaded file is not a valid Android APK"
    continue
  fi
  write_sidecar "$target"
  printf '%s\n' "$target"
done

if [[ -n "$failures_out" && -s "$failures_out" ]]; then
  echo "$(wc -l <"$failures_out") of ${#sources[@]} APK sources failed to download" >&2
fi
