#!/usr/bin/env zsh
set -u

# Continue the six-pair P6 order after pair01-A has been started manually.
# Order: AB, BA, BA, AB, AB, BA.
# Each invocation is deliberately sequential so no two OTP JVMs share port 18080.

SCRIPT=${0:A:h}/run_baden_native_http.zsh
HTTP_DIR=${SCRIPT:A:h}
BASE=/private/tmp/otp-baden-build-LgbuLS
ROOT=/private/tmp/otp-baden-http-native-20260917-p6

# The basenames are legacy and misleading.  Role is determined by verified
# content: A (upstream) is the flat-array JAR, H (optimized) is the
# TripTimes/offset JAR.
A_JAR="$BASE/otp-h-only.jar"      # SHA 2e1808...; arrivalTimes/departureTimes
H_JAR="$BASE/otp-baseline.jar"   # SHA 3d583a...; TripTimes/offsetsByTrip

run_role() {
  local tag=$1 jar=$2 out=$3
  mkdir -p "$ROOT"
  # A preflight failure or an interrupted/duplicate attempt can still leave a
  # finishedAt line.  Only skip a run when its identity and completion gate are
  # unambiguous; otherwise start a fresh attempt in the same expected slot.
  local complete=0
  if [[ -f "$out/status.txt" ]] \
    && [[ "$(rg -c '^ready=1$' "$out/status.txt" 2>/dev/null || true)" == "1" ]] \
    && [[ "$(rg -c '^generatorExit=0$' "$out/status.txt" 2>/dev/null || true)" == "1" ]] \
    && [[ "$(rg -c '^serverExit=0$' "$out/status.txt" 2>/dev/null || true)" == "1" ]] \
    && [[ "$(rg -c '^finishedAt=' "$out/status.txt" 2>/dev/null || true)" == "1" ]] \
    && [[ -f "$out/requests.jsonl" ]] \
    && (( $(wc -l < "$out/requests.jsonl") >= 20001 )); then
    complete=1
  fi
  if (( complete )); then
    print "SKIP $tag (already finished)"
    return 0
  fi
  print "START $tag $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  "$SCRIPT" "$tag" "$jar" "$out" 2500 8 32 \
    > "$ROOT/${tag}-wrapper.log" 2>&1
  local rc=$?
  print "DONE $tag rc=$rc $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  return $rc
}

# Pair01-H is running separately when this continuation starts. Wait for its
# status file to report finishedAt before touching the shared OTP port.
while [[ ! -f "$ROOT/pair01-H/status.txt" ]] || ! rg -q '^finishedAt=' "$ROOT/pair01-H/status.txt"; do
  sleep 30
done

run_role P6-02-H-only "$H_JAR" "$ROOT/pair02-H" || exit $?
run_role P6-02-A-baseline "$A_JAR" "$ROOT/pair02-A" || exit $?
run_role P6-03-H-only "$H_JAR" "$ROOT/pair03-H" || exit $?
run_role P6-03-A-baseline "$A_JAR" "$ROOT/pair03-A" || exit $?
run_role P6-04-A-baseline "$A_JAR" "$ROOT/pair04-A" || exit $?
run_role P6-04-H-only "$H_JAR" "$ROOT/pair04-H" || exit $?
run_role P6-05-A-baseline "$A_JAR" "$ROOT/pair05-A" || exit $?
run_role P6-05-H-only "$H_JAR" "$ROOT/pair05-H" || exit $?
run_role P6-06-H-only "$H_JAR" "$ROOT/pair06-H" || exit $?
run_role P6-06-A-baseline "$A_JAR" "$ROOT/pair06-A" || exit $?

python3 "$HTTP_DIR/analyze_baden_http_runs.py" "$ROOT"/pair[0-9][0-9]-[AH] \
  --output "$ROOT/p6-run-summaries.json" || exit $?
python3 "$HTTP_DIR/bootstrap_baden_pairs.py" \
  --root "$ROOT" --output "$ROOT/p6-paired-bootstrap.json" || exit $?
print "P6 remaining sequence complete $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
