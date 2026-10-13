import pandas as pd, numpy as np, json, sys
B = sys.argv[1]
M = pd.read_csv(f'{B}/loop/hop_factor_train.csv')
med = M.f_raw.median()
FR = {(r.a, r.b): r.f_raw / med for r in M.itertuples()}
SEB = (0.1191, 52.6)
def fac(a, b, w, clip=(0.6, 1.8)):
    x = FR.get((a, b))
    return 1.0 if x is None else float(np.clip(x ** w, *clip))
# --- ODsay legs (Seoul routes) ---
P = [p for p in json.load(open(f'{B}/loop/pairs_trips.json')) if p['route'].startswith('BR_TAGO_SEB') and p.get('alight')]
def ok(p):
    night = p['busNo'].upper().startswith('N'); dr = p['gdist'] / p['dist'] if p['dist'] else 0
    return p['laneIdx'] == 0 and not night and 0.8 <= dr <= 1.25 and abs(p['hops'] - p['nst']) <= 1 and p['t'] >= 2
P = [p for p in P if ok(p)]
def odsay_err(w):
    r = []
    for p in P:
        hs = p['hopstops'] + [p['alight']]
        tot = sum((SEB[0]*d + SEB[1]) * fac(hs[k], hs[k+1], w) for k, d in enumerate(p['hopd'])) + 10*(len(p['hopd']) - 1)
        r.append(tot / 60 / p['t'])
    r = np.array(r); return np.median(r), np.median(np.abs(r - 1))
# --- measured October segments (typical per group) ---
seg = pd.read_parquet(f'{B}/measured_cal/out/segments.parquet')
seg = seg[(~seg.outlier) & (seg.daytype == 'weekday') & (pd.to_datetime(seg.date) >= '2026-10-01') & (seg.band == '09-16')]
rs = pd.read_csv(f'{B}/measured/data/bus_route_stop.csv', dtype=str); rs['seq'] = rs.seq.astype(int)
stops = {(r.route_id, r.seq): 'BS_TAGO_SEB' + r.stop_id for r in rs.itertuples()}
F = None
def meas_err(w, kind):
    k = seg[seg.kind == kind]
    g = k.groupby(['route_id', 'seq_i', 'seq_j']).agg(meas=('meas_s', 'median'), ours=('gtfs_s', 'median'), n=('meas_s', 'size')).reset_index()
    g = g[g.n >= 3]
    adj = []
    for r in g.itertuples():
        num = den = 0.0
        for q in range(r.seq_i, r.seq_j):
            a, b = stops.get((r.route_id, q)), stops.get((r.route_id, q + 1))
            f = fac(a, b, w) if a and b else 1.0
            num += f; den += 1
        adj.append(num / den if den else 1.0)
    ratio = g.ours * np.array(adj) / g.meas
    return len(g), np.median(ratio), np.median(np.abs(ratio - 1))
print('w   | ODsay(Seoul legs) ratio |err| | Oct measured LONG ratio |err| | SHORT ratio |err|')
for w in (0.0, 0.25, 0.5, 0.75, 1.0):
    o = odsay_err(w); l = meas_err(w, 'LONG'); s = meas_err(w, 'SHORT')
    print(f'{w:4.2f} | {o[0]:.3f} {o[1]*100:4.1f}% (n={len(P)}) | {l[1]:.3f} {l[2]*100:4.1f}% (n={l[0]}) | {s[1]:.3f} {s[2]*100:4.1f}% (n={s[0]})')
