import json, re, statistics as st, collections
def src_of(rid):
    m = re.match(r'^BR_TAGO_([A-Z]+)', rid)
    if m: return m.group(1)
    m = re.match(r'^BR_([A-Z0-9]+)_', rid)
    return m.group(1) if m else rid
def load_pairs(lo=13*3600, hi=15*3600, path='pairs_trips.json'):
    P = json.load(open(path))
    out = []
    for p in P:
        tl = [t for t in p['trips'] if lo <= t[0] < hi]
        if not tl: continue
        night = p['busNo'].upper().startswith('N') or '심야' in p['busNo'] or '새벽' in p['busNo']
        dr = p['gdist'] / p['dist'] if p['dist'] else 0
        p = dict(p, trips=tl, src=src_of(p['route']), night=night, dr=dr,
                 ok=(not night) and 0.8 <= dr <= 1.25 and p['t'] >= 2)
        out.append(p)
    return out
def ride(p, coef_fn):
    """median calibrated ride minutes over the pair's trips. coef_fn(p, first_dep) -> (a, b_sec_per_hop)"""
    v = []
    for dep, run, dw, first in p['trips']:
        a, b = coef_fn(p, first)
        v.append((a*run + b*p['hops'] + dw) / 60)
    return st.median(v)
def q(v, k): v = sorted(v); return v[min(len(v)-1, int(k*(len(v)-1)+0.5))]
def summ(rows):
    r = [x['ratio'] for x in rows]; e = [abs(x - 1) for x in r]
    if not r: return 'n=0'
    return f"n={len(r):4d} ratio med {st.median(r):.3f} [p25 {q(r,.25):.2f} p75 {q(r,.75):.2f}]  |err| med {st.median(e)*100:5.1f}%  within10% {sum(x<=0.10 for x in e)/len(e)*100:4.0f}%  within20% {sum(x<=0.2 for x in e)/len(e)*100:4.0f}%"
