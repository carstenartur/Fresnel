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

DESKTOP="$ROOT/opt/fresnel/lib/Fresnel-Fresnel.desktop"
MIME_XML="$ROOT/opt/fresnel/lib/Fresnel-Fresnel-MimeInfo.xml"
APP_CONFIG="$ROOT/opt/fresnel/lib/app/Fresnel.cfg"

for required in "$DESKTOP" "$MIME_XML" "$APP_CONFIG" "$CONTROL/postinst" "$CONTROL/prerm"; do
  if [ ! -f "$required" ]; then
    echo "verify-linux-package.sh: expected package file missing: $required" >&2
    exit 1
  fi
done

grep -Fqx 'MimeType=application/vnd.carstenartur.fresnel.job+json' "$DESKTOP"
grep -Fq '<mime-type type="application/vnd.carstenartur.fresnel.job+json">' "$MIME_XML"
grep -Fq '<glob pattern="*.fresnel"/>' "$MIME_XML"
grep -Fq 'java-options=-Dfresnel.desktop.enabled=true' "$APP_CONFIG"
grep -Fq 'xdg-mime install' "$CONTROL/postinst"
grep -Fq 'xdg-mime default Fresnel-Fresnel.desktop application/vnd.carstenartur.fresnel.job+json' \
  "$CONTROL/postinst"
grep -Fq 'xdg-mime uninstall' "$CONTROL/prerm"

if grep -R -Fq 'sourcePath' "$ROOT/opt/fresnel/lib"; then
  echo "verify-linux-package.sh: package unexpectedly contains a sourcePath desktop contract" >&2
  exit 1
fi

echo "Verified Linux .fresnel association and desktop launcher metadata in $DEB_PATH"
