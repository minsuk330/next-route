import json, sys, numpy as np, pandas as pd
B = sys.argv[1]
T = json.load(open(f'{B}/topis/topis_mid.json'))
COEF = {'SEB': (0.1191, 52.6), 'GGB': (0.0879, 59.9), '3100': (0.0879, 59.9)}
def sid(g): return g.replace('BS_TAGO_SEB', '').replace('BS_TAGO_GGB', '')
def tget(a, b): return T.get(f'{sid(a)}|{sid(b)}')
# ODsay legs
P = json.load(open(f'{B}/loop/pairs_trips.json'))
def ok(p):
    dr = p['gdist'] / p['dist'] if p['dist'] else 0
    return p['laneIdx'] == 0 and not p['busNo'].upper().startswith('N') and 0.8 <= dr <= 1.25 and abs(p['hops'] - p['nst']) <= 1 and p['t'] >= 2 and p.get('alight')
P = [p for p in P if ok(p) and any(k in p['route'] for k in ('SEB', 'GGB', 'BR_3100'))]
def src(p): return 'SEB' if 'SEB' in p['route'] else 'GGB'
def leg_odsay(p, w, s):
    c, b = COEF[src(p)]; hs = p['hopstops'] + [p['alight']]; tot = 0
    for k, d in enumerate(p['hopd']):
        f = c*d + b; t = tget(hs[k], hs[k+1])
        tot += f if t is None else (1-w)*f + w*s*t
    return (tot + 10*(len(p['hopd'])-1)) / 60
# measured Oct segments (Seoul)
seg = pd.read_parquet(f'{B}/measured_cal/out/segments.parquet')
seg = seg[(~seg.outlier) & (seg.daytype == 'weekday') & (pd.to_datetime(seg.date) >= '2026-10-01') & (seg.band == '09-16') & seg.kind.isin(['LONG', 'SHORT'])]
g = seg.groupby(['kind', 'route_id', 'seq_i', 'seq_j']).agg(meas=('meas_s', 'median'), ours=('gtfs_s', 'median'), n=('meas_s', 'size')).reset_index()
g = g[g.n >= 3]
rs = pd.read_csv(f'{B}/measured/data/bus_route_stop.csv', dtype=str); rs['seq'] = rs.seq.astype(int)
stop = {(r.route_id, r.seq): r.stop_id for r in rs.itertuples()}
segs = []
for r in g.itertuples():
    tl = [T.get(f'{stop.get((r.route_id, q))}|{stop.get((r.route_id, q+1))}') for q in range(r.seq_i, r.seq_j)]
    segs.append((r.kind, r.meas, r.ours, tl))
def meas_ratio(w, s, kind):
    out = []
    for k, meas, ours, tl in segs:
        if k != kind: continue
        n = len(tl); cov = [x for x in tl if x is not None]
        # ours (calibrated formula) split evenly per hop; replace covered share by blend
        per = ours / n
        pred = sum(per if x is None else (1-w)*per + w*(s*x + 10) for x in tl)
        out.append(pred / meas)
    return np.array(out)
N = json.load(open(f'{B}/topis/naver_legs.json'))
def naver_ratio(w, s):
    out = []
    for x in N:
        st = x['stops']; n = len(st) - 1; per = x['ride']*60 / n; tot = 0
        for k in range(n):
            t = tget(st[k], st[k+1]); tot += per if t is None else (1-w)*per + w*(s*t + 10)
        out.append((x['nonride'] + tot/60) / x['naver'])
    return np.array(out)
def m(r): return f'ratio {np.median(r):.3f} |err| {np.median(np.abs(r-1))*100:4.1f}%'
print('w    s   | ODsay SEB+GG legs (n=%d)            | Oct meas LONG             | Oct meas SHORT            | Naver 5 (ratios)' % len(P))
for w in (0, 0.25, 0.5, 0.75, 1.0):
    for s in ((1.0,) if w == 0 else (0.9, 1.0, 1.1, 1.2)):
        o = np.array([leg_odsay(p, w, s) / p['t'] for p in P])
        nv = naver_ratio(w, s)
        print(f'{w:4.2f} {s:3.1f} | {m(o)} | {m(meas_ratio(w, s, "LONG"))} | {m(meas_ratio(w, s, "SHORT"))} | {m(nv)} {np.round(nv, 2).tolist()}')
