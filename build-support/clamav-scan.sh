#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Scans APKs with ClamAV from the official image, refreshing its signatures
# first. A detection or a scanner error fails the job; the log lists every file.
set -euo pipefail
target="${1:?File or directory to scan}"
report="${2:-}"
image="${CLAMAV_IMAGE:-clamav/clamav:stable}"
[[ -e "$target" ]] || { printf 'Nothing to scan: %s\n' "$target" >&2; exit 2; }
dir="$(cd "$(dirname "$target")" && pwd)"
name="$(basename "$target")"

docker pull --quiet "$image" >/dev/null
set +e
# freshclam exits 1 when the database is already current. If the update server
# refuses, the image's own signatures (rebuilt daily upstream) are still used.
output="$(docker run --rm -v "$dir:/scan:ro" --entrypoint /bin/sh "$image" -c '
  freshclam --quiet --stdout >/dev/null 2>&1; rc=$?
  [ "$rc" -le 1 ] || echo "warning: signature update failed ($rc), scanning with the image database"
  clamscan --version
  clamscan --recursive --stdout "/scan/$1"' sh "$name" 2>&1)"
status=$?
set -e
printf '%s\n' "$output"
[[ -z "$report" ]] || printf '%s\n' "$output" > "$report"
case $status in
  0) echo 'ClamAV: no threats found' ;;
  1) echo '::error::ClamAV found a threat' >&2; exit 1 ;;
  *) printf '::error::ClamAV could not scan (exit %s)\n' "$status" >&2; exit 1 ;;
esac
