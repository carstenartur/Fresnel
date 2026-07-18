#!/usr/bin/env bash
# Execute public .fresnel documentation jobs without naming a JUnit test.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ "$#" -lt 1 ]; then
  cat >&2 <<'USAGE'
Usage:
  bash packaging/docs-jobs.sh render <job.fresnel> <output-directory>
  bash packaging/docs-jobs.sh verify <job.fresnel> <expected-directory>
  bash packaging/docs-jobs.sh render-all <job-root> <asset-root>
  bash packaging/docs-jobs.sh verify-all <job-root> <asset-root>
  bash packaging/docs-jobs.sh list <job-root>
USAGE
  exit 2
fi

ARGS=""
for value in "$@"; do
  escaped="${value//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  if [ -n "$ARGS" ]; then ARGS+=" "; fi
  ARGS+="\"$escaped\""
done

mvn -B -ntp -Pno-frontend -DskipTests -pl backend -am compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=org.fresnel.backend.docs.FresnelDocsCli \
  -Dexec.args="$ARGS"
