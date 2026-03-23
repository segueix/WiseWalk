#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo "Error: define ANDROID_HOME or ANDROID_SDK_ROOT before running this script." >&2
  exit 1
fi

./gradlew --continuous assembleDebug
