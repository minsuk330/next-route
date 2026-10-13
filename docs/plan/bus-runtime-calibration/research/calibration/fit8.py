"""Spatial model: hop coefficients by where the hop is (region of its first stop), fitted jointly. LOO by OD."""
import json, collections
from fit2 import *
SR = json.load(open('stop_spatial_region.json'))
REGS = ['SEB', 'GGB', 'ICB', 'BSB', 'DGB', 'DJB']
def sreg(h, p):
    r = SR.get(h) or p['src']
    r = {'3100': 'GGB'}.get(r, r)
    return r if r in REGS else {'3100': 'GGB'}.get(p['src'], p['src'])
def feats_spatial(p):
    f = collections.defaultdict(float)
    for h, d in zip(p['hopstops'], p['hopd']):
        r = sreg(h, p); f['D_' + r] += d; f['N_' + r] += 1
    return [f['D_' + r] for r in REGS] + [f['N_' + r] for r in REGS]
def feats_route(p):
    r = {'3100': 'GGB'}.get(p['src'], p['src'])
    return [p['gdist'] if r == x else 0 for x in REGS] + [p['hops'] if r == x else 0 for x in REGS]
def fitj(rows, F):
    X = [[v/x['T'] for v in F(x)] for x in rows]; y = [(x['T']-x['DW'])/x['T'] for x in rows]
    # ridge toward a pooled prior to keep sparse regions stable
    n = len(X[0]); lam = 0.05
    X += [[(lam if k == i else 0.0) for k in range(n)] for i in range(n)]
    prior = [0.105]*6 + [55.0]*6
    y += [lam*prior[i] for i in range(n)]
    return lstsq(X, y)
def pred(p, F, c): return (sum(a*v for a, v in zip(c, F(p))) + p['DW'])/60
for args in [dict(), dict(minst=3), dict(mink=2.0)]:
    P = [p for p in prepare(**args) if 'hopstops' in p]
    print('####', args, len(P))
    for name, F in [('route-region', feats_route), ('spatial', feats_spatial)]:
        res = []
        ods = sorted({p['od'] for p in P})
        for od in ods:
            c = fitj([x for x in P if x['od'] != od], F)
            res += [dict(p, ratio=pred(p, F, c)*60/p['T']) for p in P if p['od'] == od]
        print(f'  {name:13s} ALL', summ(res)); print('                H  ', summ([x for x in res if x['od'].startswith('H')]))
        print('                GGB', summ([x for x in res if x['src'] in ('GGB', '3100')]))
        if not args: print('   coef', [round(v, 4) for v in fitj(P, F)])
