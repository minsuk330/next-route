#!/usr/bin/env zsh

# Preserve P6 raw outside /private/tmp after the formal sequence and correction
# pair have finished.  This is experiment evidence only; it is not OTP code.
# `ALLOW_PARTIAL=1` is an explicit stop/early-finalization path: it archives
# the runs collected so far, including an interrupted run, without pretending
# that the formal six-pair gate completed.

set -u

ROOT=/private/tmp/otp-baden-http-native-20260917-p6
DEST=/Users/wa/golbob/개인/nextroute/.artifacts/otp-baden-p6

if [[ "${ALLOW_PARTIAL:-0}" != "1" ]]; then
  while [[ ! -f "$ROOT/pair01-correction/H/status.txt" ]] || \
        ! rg -q '^finishedAt=' "$ROOT/pair01-correction/H/status.txt"; do
    sleep 30
  done
fi

mkdir -p "$DEST"
for run in "$ROOT"/pair*; do
  [[ -d "$run" ]] || continue
  name=${run:t}
  archive="$DEST/${name}.tar.gz"
  [[ -f "$archive" ]] && continue
  tar -czf "$archive" -C "$ROOT" "$name"
done

shasum -a 256 "$DEST"/*.tar.gz > "$DEST/SHA256SUMS.txt"
if [[ "${ALLOW_PARTIAL:-0}" == "1" ]]; then
  print "archiveMode=partial-stop" > "$DEST/ARCHIVE-METADATA.txt"
  date -u '+archivedAt=%Y-%m-%dT%H:%M:%SZ' >> "$DEST/ARCHIVE-METADATA.txt"
else
  date -u '+archivedAt=%Y-%m-%dT%H:%M:%SZ' > "$DEST/ARCHIVE-METADATA.txt"
fi
print "P6 raw archives complete: $DEST"
