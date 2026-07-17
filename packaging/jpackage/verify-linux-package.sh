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

grep -Eq '^MimeType=application/vnd\.carstenartur\.fresnel\.job\+json;?$' "$DESKTOP"
grep -Fqx 'Exec=/opt/fresnel/bin/Fresnel %f' "$DESKTOP"
grep -Fq '<mime-type type="application/vnd.carstenartur.fresnel.job+json">' "$MIME_XML"
grep -Fq 'glob pattern="*.fresnel"' "$MIME_XML"
grep -Fq 'java-options=-Dfresnel.desktop.enabled=true' "$APP_CONFIG"
grep -Fq 'xdg-desktop-menu install' "$CONTROL/postinst"
grep -Fq 'xdg-mime install' "$CONTROL/postinst"
grep -Fq 'xdg-desktop-menu uninstall' "$CONTROL/prerm"
grep -Fq 'xdg-mime uninstall' "$CONTROL/prerm"

# jpackage may remove itself from a system default list during uninstall, but the
# installer must not set itself as the user's default handler during installation.
if grep -Fq 'xdg-mime default' "$CONTROL/postinst"; then
  echo "verify-linux-package.sh: installer must not override the user's default handler" >&2
  exit 1
fi

if grep -R -Fq 'sourcePath' "$LIB_DIR"; then
  echo "verify-linux-package.sh: package unexpectedly contains a sourcePath desktop contract" >&2
  exit 1
fi

echo "Verified Linux .fresnel association, %f forwarding and desktop launcher metadata in $DEB_PATH"
