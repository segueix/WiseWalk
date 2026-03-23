#!/usr/bin/env sh
set -eu

DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"

if [ -f "$WRAPPER_JAR" ]; then
  exec java -jar "$WRAPPER_JAR" "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  echo "gradle-wrapper.jar not found; using installed 'gradle' from PATH." >&2
  exec gradle "$@"
fi

echo "Error: gradle-wrapper.jar is missing and 'gradle' is not installed." >&2
echo "Install Gradle locally or open the project in Android Studio to sync/build." >&2
exit 1
