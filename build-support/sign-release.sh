#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
set -euo pipefail
for name in ANDROID_KEYSTORE_FILE ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then printf 'Missing signing configuration: %s\n' "$name" >&2; exit 1; fi
done
[[ -f "$ANDROID_KEYSTORE_FILE" ]] || { printf 'Signing file not found\n' >&2; exit 1; }
input_dir="${1:?APK directory required}"
output_dir="${2:?Output directory required}"
build_tools="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}/build-tools/36.0.0"
apk="$(node build-support/release-tools.mjs apk-name)"
unsigned="$(node build-support/release-tools.mjs check-apk "$input_dir")"
mkdir -p "$output_dir"
work_dir="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pikatv-sign.XXXXXX")"
cleanup() { rm -f -- "$work_dir/aligned.apk"; rmdir -- "$work_dir"; }
trap cleanup EXIT
"$build_tools/zipalign" -P 16 -f 4 "$input_dir/$unsigned" "$work_dir/aligned.apk"
"$build_tools/apksigner" sign --ks "$ANDROID_KEYSTORE_FILE" --ks-type PKCS12 \
  --ks-key-alias "$ANDROID_KEY_ALIAS" --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
  --key-pass env:ANDROID_KEY_PASSWORD --v4-signing-enabled false \
  --out "$output_dir/$apk" "$work_dir/aligned.apk"
# Updates install over an old version only with the same key, so a changed certificate stops the release.
expected="$(sed -n 's/.*"signerSha256": *"\([0-9a-f]*\)".*/\1/p' build-support/release-config.json)"
actual="$("$build_tools/apksigner" verify --print-certs "$output_dir/$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ -n "$expected" && "$actual" == "$expected" ]] || { printf 'Unexpected signing certificate: %s\n' "$actual" >&2; exit 1; }
version="$(node build-support/release-tools.mjs version)"
docs="pikatv-v$version-docs.zip"
python3 build-support/package-docs.py "$output_dir" "$version"
(cd "$output_dir" && sha256sum "$apk" "$docs" > SHA256SUMS)
node build-support/release-tools.mjs verify-assets "$output_dir"
