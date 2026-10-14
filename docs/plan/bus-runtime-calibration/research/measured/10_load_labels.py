"""Load high-confidence bus arrival labels (local June archive + VPS archive Sep13-Oct12) into one pickle.
Usage: python -I 10_load_labels.py <out_dir>
Keeps label_source=POSITION_STOP_FLAG_CORRECTED, excluded_from_training=false, deleted_at null.
Dedupes (trip_id, seq) keeping earliest label.
"""
import sys, glob, os
import pyarrow.parquet as pq, pandas as pd
OUT = sys.argv[1]
SRC = sorted(glob.glob('/Users/wa/golbob/개인/nextroute/ml/data/bus_label/*/data.parquet')) + \
      sorted(glob.glob(os.path.join(OUT, 'data/vps_label/*/data.parquet')))
cols = ['service_date', 'route_id', 'vehicle_identity', 'trip_id', 'stop_id', 'seq', 'label_arrival_at',
        'departed_at', 'dwell_seconds', 'label_source', 'excluded_from_training', 'deleted_at']
frames = []
stats = []
for f in SRC:
    t = pq.ParquetFile(f).read(columns=cols).to_pandas()
    n0 = len(t)
    t = t[(t.label_source == 'POSITION_STOP_FLAG_CORRECTED') & (~t.excluded_from_training) & t.deleted_at.isna()]
    t['service_date'] = pd.to_datetime(t['service_date'].astype(str))
    t = t.drop(columns=['label_source', 'excluded_from_training', 'deleted_at'])
    stats.append((f.split('service_date=')[1][:10], n0, len(t)))
    frames.append(t)
df = pd.concat(frames, ignore_index=True)
n1 = len(df)
df = df.sort_values(['trip_id', 'seq', 'label_arrival_at']).drop_duplicates(['trip_id', 'seq'], keep='first')
print('files', len(SRC), 'rows kept', n1, 'after dedupe', len(df))
for s in stats: print(*s)
df.to_pickle(os.path.join(OUT, 'data/labels.pkl'))
