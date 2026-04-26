#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_VERSION="9.3.1"
GRADLE_DIR="$ROOT_DIR/.gradle-local/gradle-$GRADLE_VERSION"
GRADLE_ZIP="$ROOT_DIR/.gradle-local/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

mkdir -p "$ROOT_DIR/.gradle-local"

if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
  echo "Downloading Gradle $GRADLE_VERSION..."
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail "$GRADLE_URL" -o "$GRADLE_ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget "$GRADLE_URL" -O "$GRADLE_ZIP"
  else
    echo "curl or wget is required to download Gradle."
    exit 1
  fi

  echo "Extracting Gradle..."
  unzip -q "$GRADLE_ZIP" -d "$ROOT_DIR/.gradle-local"
fi

exec "$GRADLE_DIR/bin/gradle" "$@"
