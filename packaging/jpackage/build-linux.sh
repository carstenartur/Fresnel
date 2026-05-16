#!/usr/bin/env bash
# Build a Linux jpackage installer (.deb by default; .rpm or app-image
# also supported via JPACKAGE_TYPE). See packaging/jpackage/README.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET_DIR="$REPO_ROOT/backend/target"

APP_JAR="${APP_JAR:-}"
if [ -z "$APP_JAR" ]; then
  APP_JAR="$(ls "$TARGET_DIR"/backend-*.jar 2>/dev/null | grep -v -E '(sources|javadoc)\.jar$' | head -n 1 || true)"
fi
if [ -z "$APP_JAR" ] || [ ! -f "$APP_JAR" ]; then
  echo "build-linux.sh: cannot find backend jar; run 'mvn -B -ntp package' first" >&2
  exit 1
fi

APP_VERSION="${APP_VERSION:-}"
if [ -z "$APP_VERSION" ]; then
  APP_VERSION="$(basename "$APP_JAR" | sed -E 's/^backend-(.+)\.jar$/\1/')"
fi
# jpackage rejects -SNAPSHOT and non-numeric versions; strip the qualifier.
APP_VERSION_NUM="$(printf '%s' "$APP_VERSION" | sed -E 's/-.*$//')"

JPACKAGE_TYPE="${JPACKAGE_TYPE:-deb}"
OUTPUT_DIR="${OUTPUT_DIR:-$TARGET_DIR/dist}"
mkdir -p "$OUTPUT_DIR"

# Stage the jar into an input directory; jpackage copies everything from
# --input into the app's lib/ directory.
STAGE_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGE_DIR"' EXIT
cp "$APP_JAR" "$STAGE_DIR/fresnel.jar"

JAVA_CMD="${JAVA_HOME:+$JAVA_HOME/bin/}jpackage"
if ! command -v "$JAVA_CMD" >/dev/null 2>&1; then
  echo "build-linux.sh: jpackage not found (need JDK 21+)" >&2
  exit 1
fi

echo "build-linux.sh: building $JPACKAGE_TYPE for Fresnel $APP_VERSION_NUM"

"$JAVA_CMD" \
  --type "$JPACKAGE_TYPE" \
  --name Fresnel \
  --app-version "$APP_VERSION_NUM" \
  --vendor "Fresnel" \
  --description "Fresnel diffractive-optics designer" \
  --input "$STAGE_DIR" \
  --main-jar fresnel.jar \
  --main-class org.springframework.boot.loader.launch.JarLauncher \
  --java-options "-Dspring.profiles.active=standalone" \
  --java-options "-Dspring.config.additional-location=optional:file:\$APPDIR/../config/" \
  --dest "$OUTPUT_DIR" \
  --linux-shortcut \
  --linux-menu-group "Graphics"

echo "build-linux.sh: artifacts in $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"
