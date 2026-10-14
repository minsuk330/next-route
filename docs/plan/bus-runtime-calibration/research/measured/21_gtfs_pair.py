"""GTFS arrival->arrival time between catalog seq A and B for a route, per trip start hour.
Usage: python -I 21_gtfs_pair.py <work_dir> <route_id> <seqA> <seqB>   (seq = GTFS stop_sequence)"""
import sys, pandas as pd
W, r, A, B = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
st = pd.read_pickle(W + '/data/gtfs_st_sel.pkl'); g = st[st.route == r]
a = g[g.seq == A].set_index('trip_id').a; b = g[g.seq == B].set_index('trip_id').a
d = pd.DataFrame({'hh': (a // 3600).astype(int), 'min': (b - a) / 60}).dropna()
print(g[g.seq.isin([A, B])].drop_duplicates('seq')[['seq', 'stop_id']].to_string(index=False))
print(d.groupby('hh')['min'].agg(['size', 'median']).round(1).T.to_string())
