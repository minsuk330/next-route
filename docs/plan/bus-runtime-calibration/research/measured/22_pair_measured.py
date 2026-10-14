"""Measured arrival->arrival between two exact catalog seqs on one route (labels.pkl), weekday vs weekend, by hour band,
with GTFS same-hour reference. Usage: python -I 22_pair_measured.py <work_dir> <route_id> <seqA> <seqB>"""
import sys, numpy as np, pandas as pd
W, r, A, B = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
HOL = {'2026-09-24', '2026-09-25', '2026-09-26', '2026-10-03', '2026-10-05', '2026-10-09'}
lab = pd.read_pickle(W + '/data/labels.pkl'); L = lab[(lab.route_id == r) & lab.seq.isin([A, B])]
p = L.pivot_table(index=['trip_id', 'service_date'], columns='seq', values='label_arrival_at', aggfunc='first').dropna().reset_index()
p['min'] = (p[B] - p[A]).dt.total_seconds() / 60
p = p[(p['min'] > 0) & (p['min'] < 240)]
p['tod'] = (p[A] - p.service_date).dt.total_seconds()
p['wk'] = np.where((p.service_date.dt.dayofweek >= 5) | p.service_date.dt.strftime('%Y-%m-%d').isin(HOL), 'wkend', 'wkday')
st = pd.read_pickle(W + '/data/gtfs_st_sel.pkl'); g = st[st.route == r]
ga = g[g.seq == A].set_index('trip_id').a; gb = g[g.seq == B].set_index('trip_id').a
ga, gb = ga.align(gb, join='inner'); gA = ga.to_numpy(); gT = ((gb - ga) / 60).to_numpy()
p['gtfs'] = [gT[np.argmin(np.abs(gA - t))] for t in p.tod]
p['band'] = pd.cut(p.tod / 3600, [0, 6, 9, 16, 20, 24, 30], labels=['04-06', '06-09', '09-16', '16-20', '20-24', '24+'])
f = lambda s: f'{s.median():.1f} [{s.quantile(.25):.1f}-{s.quantile(.75):.1f}]'
out = p.groupby(['wk', 'band'], observed=True).agg(n=('min', 'size'), measured=('min', f), gtfs=('gtfs', 'median'))
out['ratio_med'] = p.assign(rt=p['min'] / p.gtfs).groupby(['wk', 'band'], observed=True).rt.median().round(2)
print(out.to_string())
