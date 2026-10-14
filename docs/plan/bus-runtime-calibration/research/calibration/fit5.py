"""L1 (median relative error) fit of hop = c*dist + b via IRLS, LOO by OD."""
from fit2 import *
def fit_l1(rows, iters=30):
    w = [1.0]*len(rows); c = None
    for _ in range(iters):
        X = [[x['gdist']/x['T']*wi, x['hops']/x['T']*wi] for x, wi in zip(rows, w)]
        y = [(x['T']-x['DW'])/x['T']*wi for x, wi in zip(rows, w)]
        c = lstsq(X, y)
        r = [abs((c[0]*x['gdist'] + c[1]*x['hops'] + x['DW'] - x['T'])/x['T']) for x in rows]
        w = [1/max(ri, 0.02)**0.5 for ri in r]
    return c
for args in [dict(mink=2.0), dict(minst=3), dict()]:
    P = prepare(**args)
    res = []
    for p in P:
        g = GROUP(p)
        tr = [x for x in P if GROUP(x) == g and x['od'] != p['od']]
        if len({x['od'] for x in tr}) < 2: tr = [x for x in P if x['od'] != p['od'] and GROUP(x) != 'BSB']
        c = fit_l1(tr)
        res.append(dict(p, ratio=(c[0]*p['gdist'] + c[1]*p['hops'] + p['DW'])/p['T']))
    print('####', args, 'L1 dist+add'); print('  ALL    ', summ(res))
    for k in ('서울', '경기', '인천', '부산', '대구', '대전'): print(f'  {k:6s}', summ([x for x in res if x['region'] == k]))
    print('  coef', {g: [round(v, 4) for v in fit_l1([x for x in P if GROUP(x) == g])] for g in sorted({GROUP(x) for x in P}) if sum(GROUP(x) == g for x in P) >= 5})
