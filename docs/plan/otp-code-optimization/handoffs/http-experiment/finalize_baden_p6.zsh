#!/usr/bin/env zsh
set -u

ROOT=/private/tmp/otp-baden-http-native-20260917-p6
HERE=${0:A:h}

# Wait for the corrected pair01 A->H, which is scheduled after pair06-A.
while [[ ! -f "$ROOT/pair01-correction/H/status.txt" ]] || ! rg -q '^finishedAt=' "$ROOT/pair01-correction/H/status.txt"; do
  sleep 30
done

RUNS=(
  "$ROOT/pair01-A" "$ROOT/pair02-H" "$ROOT/pair02-A"
  "$ROOT/pair03-H" "$ROOT/pair03-A" "$ROOT/pair04-A" "$ROOT/pair04-H"
  "$ROOT/pair05-A" "$ROOT/pair05-H" "$ROOT/pair06-H" "$ROOT/pair06-A"
  "$ROOT/pair01-correction/A" "$ROOT/pair01-correction/H"
)

python3 "$HERE/analyze_baden_http_runs.py" $RUNS --output "$ROOT/p6-run-summaries.json" || exit $?
python3 "$HERE/bootstrap_baden_pairs.py" --root "$ROOT" --output "$ROOT/p6-paired-bootstrap.json" || exit $?
python3 "$HERE/gc_overlap_baden.py" $RUNS --output "$ROOT/p6-gc-overlap.json" || exit $?
python3 "$HERE/verify_baden_run_integrity.py" $RUNS --output "$ROOT/p6-integrity.json" || exit $?
python3 "$HERE/write_baden_p6_report.py" \
  --root "$ROOT" \
  --output "/Users/wa/golbob/개인/nextroute/docs/plan/otp-code-optimization/handoffs/H-ONLY-BADEN-HTTP-NO-PRESSURE-P6-FORMAL-RESULT.md" || exit $?

python3 - "$ROOT" "$ROOT/p6-manifest.json" <<'PY'
import hashlib, json, sys
from pathlib import Path
root=Path(sys.argv[1]); out=Path(sys.argv[2])
files=[
 root/'p6-run-summaries.json', root/'p6-paired-bootstrap.json', root/'p6-gc-overlap.json', root/'p6-integrity.json',
 Path('/private/tmp/otp-baden-build-LgbuLS/otp-baseline.jar'),
 Path('/private/tmp/otp-baden-build-LgbuLS/otp-h-only.jar'),
 Path('/private/tmp/otp-baden-memory-FRsabG/graph/graph.obj'),
 Path('/private/tmp/otp-baden-memory-FRsabG/official/A-rerun/travelSearch.csv'),
]
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
 return h.hexdigest()
out.write_text(json.dumps({'root':str(root),'files':{str(p):sha(p) for p in files if p.exists()}},ensure_ascii=False,indent=2)+'\n')
PY
python3 "$HERE/write_baden_p6_manifest.py" \
  --root "$ROOT" \
  --output "/Users/wa/golbob/개인/nextroute/docs/plan/otp-code-optimization/handoffs/H-ONLY-BADEN-HTTP-NO-PRESSURE-MANIFEST.json" || exit $?
print "P6/P7/P8 raw analysis complete $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
