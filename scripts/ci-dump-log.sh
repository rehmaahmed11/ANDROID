#!/usr/bin/env bash
# Surfaces the Gradle failure into channels that are readable without the Actions log endpoint:
# the job summary (GET /repos/{owner}/{repo}/actions/runs/{id}/summary) and check-run
# annotations. Runner logs are hosted on a blob store that many CI-adjacent environments cannot
# reach, so without this a red build is undiagnosable from the API alone.
set -uo pipefail

SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
{
  echo "### Gradle failure extract"
  for f in "${RUNNER_TEMP:-/tmp}"/test.log "${RUNNER_TEMP:-/tmp}"/assemble.log; do
    [ -f "$f" ] || continue
    echo
    echo "#### $(basename "$f")"
    echo '```'
    grep -E "^e: |^> Task .*FAILED|What went wrong|Execution failed|Caused by:|error:|FAILED|Unresolved reference" "$f" \
      | head -n 60
    echo "----- last 40 lines -----"
    tail -n 40 "$f"
    echo '```'
  done
} >>"$SUMMARY"

for f in "${RUNNER_TEMP:-/tmp}"/test.log "${RUNNER_TEMP:-/tmp}"/assemble.log; do
  [ -f "$f" ] || continue
  grep -E "^e: |Unresolved reference|^> Task .*FAILED|What went wrong|Execution failed" "$f" |
    head -n 25 |
    while IFS= read -r line; do
      safe=$(printf '%s' "$line" | sed 's/%/%25/g; s/:/%3A/g' | cut -c1-500)
      printf '::error::%s\n' "$safe"
    done
done
