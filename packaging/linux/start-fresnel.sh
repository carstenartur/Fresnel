#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Fresnel — local launcher (Linux / macOS)
#
# Starts the bundled Spring Boot jar with the `standalone` profile, a
# file-based H2 database under $FRESNEL_DATA_DIR, and an external
# config directory the user can edit.
#
# Layout when running from an installer or from the .tar.gz fallback:
#   <install>/
#     bin/start-fresnel.sh        (this file)
#     lib/fresnel.jar             (the Spring Boot fat jar)
#     config/application-standalone.properties
# ----------------------------------------------------------------------
set -euo pipefail

# Resolve the install root regardless of where the script is invoked from.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

# Locate the jar. The Maven assembly drops it next to this script as
# lib/fresnel.jar; fall back to whatever backend-*.jar is present.
APP_JAR="$APP_HOME/lib/fresnel.jar"
if [ ! -f "$APP_JAR" ]; then
  APP_JAR="$(ls "$APP_HOME"/lib/backend-*.jar 2>/dev/null | head -n 1 || true)"
fi
if [ -z "${APP_JAR:-}" ] || [ ! -f "$APP_JAR" ]; then
  echo "Fresnel: could not find application jar under $APP_HOME/lib" >&2
  exit 1
fi

# External config directory. Users can edit the .properties file there
# to override server.port, credentials, etc.
APP_CONFIG_DIR="${FRESNEL_CONFIG_DIR:-$APP_HOME/config}"

# Per-user mutable data (database files). Linux uses XDG-ish defaults;
# macOS uses the standard Application Support directory.
if [ -z "${FRESNEL_DATA_DIR:-}" ]; then
  case "$(uname -s)" in
    Darwin)
      FRESNEL_DATA_DIR="$HOME/Library/Application Support/Fresnel"
      ;;
    *)
      FRESNEL_DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/fresnel"
      ;;
  esac
fi
export FRESNEL_DATA_DIR
mkdir -p "$FRESNEL_DATA_DIR/db"

# Pick a Java runtime. Bundled JRE (jpackage / app-image) is preferred;
# otherwise rely on JAVA_HOME or the first `java` on PATH.
if [ -x "$APP_HOME/runtime/bin/java" ]; then
  JAVA_CMD="$APP_HOME/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="$(command -v java || true)"
fi
if [ -z "$JAVA_CMD" ]; then
  echo "Fresnel: no Java runtime found. Install a JDK 21+ or set JAVA_HOME." >&2
  exit 1
fi

echo "Fresnel: starting on http://localhost:${SERVER_PORT:-8080}"
echo "Fresnel: data directory   = $FRESNEL_DATA_DIR"
echo "Fresnel: config directory = $APP_CONFIG_DIR"

exec "$JAVA_CMD" \
  -Dspring.profiles.active=standalone \
  -Dspring.config.additional-location="optional:file:${APP_CONFIG_DIR}/" \
  ${JAVA_OPTS:-} \
  -jar "$APP_JAR" \
  "$@"
