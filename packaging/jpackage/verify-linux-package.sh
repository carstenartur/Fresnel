#!/usr/bin/env bash
# Inspect the generated Debian package rather than trusting only jpackage exit 0.
set -euo pipefail

DEB_PATH="${1:-}"
if [ -z "$DEB_PATH" ]; then
  DEB_PATH="$(find backend/target/dist -maxdepth 1 -name '*.deb' -type f | head -n 1 || true)"
fi
if [ -z "$DEB_PATH" ] || [ ! -f "$DEB_PATH" ]; then
  echo "verify-linux-package.sh: no .deb package found" >&2
  exit 1
fi

ROOT="$(mktemp -d)"
CONTROL="$(mktemp -d)"
trap 'rm -rf "$ROOT" "$CONTROL"' EXIT

dpkg-deb -x "$DEB_PATH" "$ROOT"
dpkg-deb -e "$DEB_PATH" "$CONTROL"

LIB_DIR="$ROOT/opt/fresnel/lib"
DESKTOP="$(find "$LIB_DIR" -maxdepth 1 -type f -name '*-Fresnel.desktop' | head -n 1 || true)"
MIME_XML="$(find "$LIB_DIR" -maxdepth 1 -type f -name '*-Fresnel-MimeInfo.xml' | head -n 1 || true)"
APP_CONFIG="$LIB_DIR/app/Fresnel.cfg"

for required in "$DESKTOP" "$MIME_XML" "$APP_CONFIG" "$CONTROL/postinst" "$CONTROL/prerm"; do
  if [ -z "$required" ] || [ ! -f "$required" ]; then
    echo "verify-linux-package.sh: expected package file missing: ${required:-<not found>}" >&2
    exit 1
  fi
done

require_fixed() {
  local file="$1"
  local needle="$2"
  local description="$3"
  if ! grep -Fq -- "$needle" "$file"; then
    echo "verify-linux-package.sh: missing $description in $file" >&2
    echo "Expected: $needle" >&2
    echo "Actual file:" >&2
    sed -n '1,180p' "$file" >&2
    exit 1
  fi
}

if ! grep -Eq '^MimeType=application/vnd\.carstenartur\.fresnel\.job\+json;?$' "$DESKTOP"; then
  echo "verify-linux-package.sh: desktop file has no canonical Fresnel MimeType line" >&2
  cat "$DESKTOP" >&2
  exit 1
fi
require_fixed "$DESKTOP" 'Exec=/opt/fresnel/bin/Fresnel %f' 'single-file %f forwarding command'
require_fixed "$MIME_XML" '<mime-type type="application/vnd.carstenartur.fresnel.job+json">' 'canonical MIME declaration'
require_fixed "$MIME_XML" 'glob pattern="*.fresnel"' '.fresnel glob'
require_fixed "$APP_CONFIG" 'java-options=-Dfresnel.desktop.enabled=true' 'desktop launcher Java option'
require_fixed "$CONTROL/postinst" 'xdg-desktop-menu install' 'desktop-menu installation hook'
require_fixed "$CONTROL/postinst" 'xdg-mime install' 'MIME installation hook'
require_fixed "$CONTROL/prerm" 'xdg-desktop-menu uninstall' 'desktop-menu removal hook'
require_fixed "$CONTROL/prerm" 'xdg-mime uninstall' 'MIME removal hook'

# The package may advertise itself as a capable handler, but it must not replace
# a user-selected default application during installation.
if grep -Fq -- 'xdg-mime default' "$CONTROL/postinst"; then
  echo "verify-linux-package.sh: installer must not override the user's default handler" >&2
  sed -n '1,180p' "$CONTROL/postinst" >&2
  exit 1
fi

echo "Verified Linux .fresnel association, %f forwarding and desktop launcher metadata in $DEB_PATH"
