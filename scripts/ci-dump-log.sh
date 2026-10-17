#!/usr/bin/env bash
# Puts the part of the Gradle output that explains a failure into a check-run annotation.
#
# Why not just read the log: the runner log archive is served from a blob host that is
# unreachable from restricted environments, and the job-summary endpoint is not always readable
# by a CI token. /check-runs/{id}/annotations is plain api.github.com, so that is the channel
# that survives. GitHub truncates a check run at 10 annotations, which is why the whole extract
# travels as ONE annotation with %0A as the line separator.
set -uo pipefail

LOGDIR="${RUNNER_TEMP:-/tmp}"

extract_for() {
  local f="$1"
  # Compiler errors first: the annotation budget is finite and the Gradle stack trace is long.
  grep -E "^e: |^> Task .*FAILED|FAILURE: " "$f" 2>/dev/null | head -n 25
  grep -A 8 -m 1 "What went wrong" "$f" 2>/dev/null
  echo "--- tail ---"
  tail -n 12 "$f"
}

# Human-readable copy in the job summary (visible in the Actions UI).
{
  echo "### Gradle failure extract"
  for f in "$LOGDIR/test.log" "$LOGDIR/assemble.log"; do
    [ -s "$f" ] || continue
    echo
    echo "#### $(basename "$f")"
    echo '```'
    extract_for "$f"
    echo '```'
  done
} >>"${GITHUB_STEP_SUMMARY:-/dev/stdout}"

# Machine-readable copy as annotations.
for f in "$LOGDIR/test.log" "$LOGDIR/assemble.log"; do
  [ -s "$f" ] || continue
  name=$(basename "$f")
  body=$(extract_for "$f" | sed -e 's/[[:space:]]*$//' -e '/^$/d')
  [ -n "$body" ] || continue
  escaped=$(printf '%s' "$body" | sed -e 's/%/%25/g' -e 's/:/%3A/g')
  oneline=$(printf '%s' "$escaped" | awk 'NR==1{printf "%s", $0; next} {printf "%%0A%s", $0}')
  printf '::error::%s\n' "${name}|${oneline:0:16000}"
done
exit 0
