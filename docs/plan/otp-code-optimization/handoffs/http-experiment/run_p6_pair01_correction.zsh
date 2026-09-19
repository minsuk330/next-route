#!/usr/bin/env zsh
set -u

# P6-01-H was invalidated after a duplicate runner/port collision.  Once the
# planned six-pair sequence has finished, restore the intended A->H pair using
# two fresh JVMs and a clean pair directory.

SCRIPT=${0:A:h}/run_baden_native_http.zsh
BASE=/private/tmp/otp-baden-build-LgbuLS
ROOT=/private/tmp/otp-baden-http-native-20260917-p6
CORR=$ROOT/pair01-correction

while [[ ! -f "$ROOT/pair06-A/status.txt" ]] || ! rg -q '^finishedAt=' "$ROOT/pair06-A/status.txt"; do
  sleep 30
done

if [[ ! -f "$CORR/A/status.txt" ]] || ! rg -q '^finishedAt=' "$CORR/A/status.txt"; then
  "$SCRIPT" P6-01-correction-A "$BASE/otp-baseline.jar" "$CORR/A" 2500 8 32 \
    > "$ROOT/P6-01-correction-A-wrapper.log" 2>&1 || exit $?
fi
if [[ ! -f "$CORR/H/status.txt" ]] || ! rg -q '^finishedAt=' "$CORR/H/status.txt"; then
  "$SCRIPT" P6-01-correction-H "$BASE/otp-h-only.jar" "$CORR/H" 2500 8 32 \
    > "$ROOT/P6-01-correction-H-wrapper.log" 2>&1 || exit $?
fi
print "P6-01 correction complete $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
