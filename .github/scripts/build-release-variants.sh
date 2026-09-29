#!/usr/bin/env bash
# Builds the protected and unprotected release APKs and collects each APK and its R8 mapping
# into dist/. Both variants share app/build/outputs, so each is copied out before the next build.
#
#   dist/apk/app-release.apk                  protected (LSpeciallyParanoid), published everywhere
#   dist/apk/app-release-unprotected.apk      --protect false
#   dist/mapping/mapping-release[-unprotected].txt
set -euo pipefail

mkdir -p dist/apk dist/mapping

build_variant() {
    local suffix=$1
    shift
    ./x build --release "$@"
    cp -v app/build/outputs/apk/release/app-release.apk "dist/apk/app-release${suffix}.apk"
    cp -v app/build/outputs/mapping/release/mapping.txt "dist/mapping/mapping-release${suffix}.txt"
}

build_variant "" --protect true
build_variant "-unprotected" --protect false
