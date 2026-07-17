#!/usr/bin/env bash
# Exercise the extracted native application without installing it into the runner.
set -euo pipefail

DEB_PATH="${1:-}"
if [ -z "$DEB_PATH" ]; then
  DEB_PATH="$(find backend/target/dist -maxdepth 1 -name '*.deb' -type f | head -n 1 || true)"
fi
if [ -z "$DEB_PATH" ] || [ ! -f "$DEB_PATH" ]; then
  echo "verify-linux-desktop-runtime.sh: no .deb package found" >&2
  exit 1
fi

ROOT="$(mktemp -d)"
DATA="$ROOT/data"
PRIMARY_LOG="$ROOT/primary.log"
SECONDARY_LOG="$ROOT/secondary.log"
INVALID_LOG="$ROOT/invalid.log"
PRIMARY_LAUNCH_PID=""
PRIMARY_PID=""

cleanup() {
  set +e
  if [ -n "$PRIMARY_PID" ] && kill -0 "$PRIMARY_PID" 2>/dev/null; then
    kill "$PRIMARY_PID" 2>/dev/null
    for _ in $(seq 1 50); do
      kill -0 "$PRIMARY_PID" 2>/dev/null || break
      sleep 0.1
    done
    kill -9 "$PRIMARY_PID" 2>/dev/null
  fi
  if [ -n "$PRIMARY_LAUNCH_PID" ] && kill -0 "$PRIMARY_LAUNCH_PID" 2>/dev/null; then
    kill -9 "$PRIMARY_LAUNCH_PID" 2>/dev/null
  fi
  rm -rf "$ROOT"
}
trap cleanup EXIT

mkdir -p "$DATA"
dpkg-deb -x "$DEB_PATH" "$ROOT/package"
APP="$ROOT/package/opt/fresnel/bin/Fresnel"
if [ ! -x "$APP" ]; then
  echo "verify-linux-desktop-runtime.sh: packaged launcher is missing or not executable" >&2
  exit 1
fi

FIRST_JOB="$(pwd)/docs/jobs/zone-plate/on-axis.fresnel"
SECOND_JOB="$ROOT/hex with spaces ü.fresnel"
INVALID_JOB="$ROOT/broken job.fresnel"
cat > "$SECOND_JOB" <<'JSON'
{
  "format": "io.github.carstenartur.fresnel.job",
  "formatVersion": 1,
  "plugin": {
    "id": "hex-macro-cell",
    "parameterSchemaVersion": 1,
    "algorithmVersion": "hex-macro-cell/1"
  },
  "parameters": {
    "macroRadiusMm": 37,
    "subDiameterMm": 6,
    "subPitchMm": 7,
    "focalLengthMm": 900,
    "targetOffsetXmm": 0,
    "targetOffsetYmm": 0,
    "wavelengthNm": 532,
    "dpi": 1200,
    "maskType": "BINARY_AMPLITUDE",
    "polarity": "POSITIVE"
  }
}
JSON
printf '{not-json' > "$INVALID_JOB"

export FRESNEL_DATA_DIR="$DATA"
export SERVER_PORT=0
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfresnel.desktop.browser.enabled=false"

"$APP" "$FIRST_JOB" >"$PRIMARY_LOG" 2>&1 &
PRIMARY_LAUNCH_PID=$!
METADATA="$DATA/desktop-instance.properties"

for _ in $(seq 1 300); do
  [ -s "$METADATA" ] && break
  if ! kill -0 "$PRIMARY_LAUNCH_PID" 2>/dev/null; then
    echo "Primary packaged Fresnel process exited before publishing metadata" >&2
    cat "$PRIMARY_LOG" >&2
    exit 1
  fi
  sleep 0.1
done
if [ ! -s "$METADATA" ]; then
  echo "Timed out waiting for packaged Fresnel metadata" >&2
  cat "$PRIMARY_LOG" >&2
  exit 1
fi

property() {
  local key="$1"
  sed -n "s/^${key}=//p" "$METADATA" | head -n 1
}
PORT="$(property port)"
PRIMARY_PID="$(property processId)"
SECRET="$(property sessionSecret)"
if ! [[ "$PORT" =~ ^[0-9]+$ ]] || [ "$PORT" -lt 1 ]; then
  echo "Invalid desktop port in metadata: $PORT" >&2
  exit 1
fi
if ! [[ "$PRIMARY_PID" =~ ^[0-9]+$ ]] || ! kill -0 "$PRIMARY_PID" 2>/dev/null; then
  echo "Published desktop process is not alive: $PRIMARY_PID" >&2
  exit 1
fi
if [ "${#SECRET}" -lt 32 ]; then
  echo "Published desktop session secret is missing or too short" >&2
  exit 1
fi

PING="$(curl -fsS -H "Authorization: Bearer $SECRET" \
  "http://127.0.0.1:$PORT/api/internal/desktop/ping")"
[ "$PING" = "fresnel-desktop-v1" ]

wait_token() {
  local log="$1"
  local token=""
  for _ in $(seq 1 200); do
    token="$(grep -oE 'fresnelOpen=[A-Za-z0-9_-]{40,128}' "$log" 2>/dev/null \
      | tail -n 1 | cut -d= -f2 || true)"
    [ -n "$token" ] && break
    sleep 0.1
  done
  if [ -z "$token" ]; then
    echo "Could not find a desktop import token in $log" >&2
    cat "$log" >&2
    return 1
  fi
  printf '%s' "$token"
}

consume_and_assert() {
  local token="$1"
  local python_assertion="$2"
  local response
  response="$(curl -fsS "http://127.0.0.1:$PORT/api/desktop/open/$token")"
  printf '%s' "$response" | python3 -c "import json,sys; value=json.load(sys.stdin); $python_assertion"
  local second_status
  second_status="$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:$PORT/api/desktop/open/$token")"
  [ "$second_status" = "404" ]
}

FIRST_TOKEN="$(wait_token "$PRIMARY_LOG")"
consume_and_assert "$FIRST_TOKEN" \
  "assert value['valid'] is True; assert value['job']['plugin']['id'] == 'zone-plate'"

# A second native invocation must authenticate to and reuse the primary process.
"$APP" "$SECOND_JOB" >"$SECONDARY_LOG" 2>&1
[ "$(property processId)" = "$PRIMARY_PID" ]
SECOND_TOKEN="$(wait_token "$SECONDARY_LOG")"
consume_and_assert "$SECOND_TOKEN" \
  "assert value['valid'] is True; assert value['job']['plugin']['id'] == 'hex-macro-cell'; assert value['job']['parameters']['focalLengthMm'] == 900.0"

# Invalid job data must become a browser-visible error without stopping the primary.
"$APP" "$INVALID_JOB" >"$INVALID_LOG" 2>&1
kill -0 "$PRIMARY_PID"
INVALID_TOKEN="$(wait_token "$INVALID_LOG")"
consume_and_assert "$INVALID_TOKEN" \
  "assert value['valid'] is False; assert value['errorCode'] == 'INVALID_JOB'; assert 'Invalid Fresnel job JSON' in value['errorMessage']"

kill "$PRIMARY_PID"
for _ in $(seq 1 150); do
  if ! kill -0 "$PRIMARY_PID" 2>/dev/null && [ ! -e "$METADATA" ]; then
    break
  fi
  sleep 0.1
done
if kill -0 "$PRIMARY_PID" 2>/dev/null; then
  echo "Packaged primary process did not stop cleanly" >&2
  exit 1
fi
if [ -e "$METADATA" ]; then
  echo "Packaged primary process left stale desktop metadata" >&2
  exit 1
fi
PRIMARY_PID=""
PRIMARY_LAUNCH_PID=""

echo "Verified stopped, already-running and invalid-job native desktop flows"
