#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
set -euo pipefail
if command -v sdkmanager >/dev/null 2>&1; then
  sdkmanager "$@"
elif command -v android >/dev/null 2>&1; then
  for package in "$@"; do android --sdk="${ANDROID_HOME:?ANDROID_HOME required}" --no-metrics sdk install "$package"; done
else
  printf 'Android SDK command-line tools not found\n' >&2
  exit 1
fi
