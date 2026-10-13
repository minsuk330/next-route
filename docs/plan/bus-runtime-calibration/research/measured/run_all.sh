#!/bin/bash
# Reproduce measured-vs-GTFS bus run time comparison (read-only on VPS).
# Order: catalog export (SELECT only) -> VPS label archive download -> GTFS extract -> label load -> compare -> summary.
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd)
PY=/Users/wa/golbob/개인/nextroute/ml/.venv/bin/python   # has pandas/pyarrow
Z=$D/../../next-route/tools/transit-routing-benchmark/data/input/national-nojeju-202503-shaped.gtfs.zip
mkdir -p "$D/data/vps_label" "$D/out"
"$D/q.sh" "$D/01_coverage.sql"                                   # label table period / source / confidence
"$D/q.sh" "$D/02_routes.sql"                                     # per-route coverage in DB (last 8 days)
"$D/q.sh" "$D/04_export_catalog.sql"    > "$D/data/bus_route.csv"
"$D/q.sh" "$D/05_export_route_stop.sql" > "$D/data/bus_route_stop.csv"
# archived labels (30-day retention on VPS); June labels come from local ml/data/bus_label
for d in $(ssh vps 'ls /srv/nextroute/ml-data/bus_label/'); do
  mkdir -p "$D/data/vps_label/$d"; scp -q "vps:/srv/nextroute/ml-data/bus_label/$d/data.parquet" "$D/data/vps_label/$d/"
done
(cd /tmp && "$PY" -I "$D/10_load_labels.py" "$D")
(cd /tmp && "$PY" -I -c "
import pandas as pd; s=sorted(pd.read_pickle('$D/data/labels.pkl').route_id.unique()); open('$D/data/target_routes.txt','w').write('\n'.join(s)+'\n')")
sed 's/^/BR_TAGO_SEB/; s/$/_Ord/' "$D/data/target_routes.txt" > "$D/data/gtfs_trip_patterns.txt"
unzip -p "$Z" stop_times.txt | grep -F -f "$D/data/gtfs_trip_patterns.txt" > "$D/data/gtfs_st_sel.csv"
(cd /tmp && "$PY" -I -c "
import pandas as pd
st=pd.read_csv('$D/data/gtfs_st_sel.csv',header=None,usecols=[0,1,2,3,4],names=['trip_id','arr','dep','stop_id','seq'],dtype=str)
sec=lambda s: sum(int(x)*m for x,m in zip(s.split(':'),[3600,60,1]))
st['a']=st.arr.map(sec); st['d']=st.dep.map(sec); st['seq']=st.seq.astype(int)
st['route']=st.trip_id.str.extract(r'SEB(\d+)_Ord')[0]; st.to_pickle('$D/data/gtfs_st_sel.pkl')")
(cd /tmp && "$PY" -I "$D/20_compare.py" "$D")
(cd /tmp && "$PY" -I "$D/30_summary.py" "$D") > "$D/out/summary.txt"
# spot checks
(cd /tmp && "$PY" -I "$D/22_pair_measured.py" "$D" 100100409 12 30)      # 421 강남역(2호선) -> 서울역9번출구
"$D/06_run.sh" 100100437 "2026-10-07 04:00" "2026-10-08 04:00" 10 40      # raw position (no labels) 771 seq10->40
(cd /tmp && "$PY" -I "$D/21_gtfs_pair.py" "$D" 100100437 10 40)
