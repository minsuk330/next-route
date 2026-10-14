"""Piecewise hop distance: hop = c1*min(d,D0) + c2*max(d-D0,0) + b. LOO by OD."""
import fit2
from fit2 import *
EXP = (2, 4, 14, 5)
def feats(D0):
    return lambda p: [sum(min(d, D0) for d in p['hopd']), sum(max(d - D0, 0) for d in p['hopd']), p['hops']]
def loo(P, model):
    res = []
    for p in P:
        g = GROUP(p)
        tr = [x for x in P if GROUP(x) == g and x['od'] != p['od']]
        if len({x['od'] for x in tr}) < 2: tr = [x for x in P if x['od'] != p['od'] and GROUP(x) != 'BSB']
        c = fitm(tr, model)
        res.append(dict(p, ratio=predm(p, model, c)*60/p['T']))
    return res
for args in [dict(), dict(minst=3), dict(mink=2.0)]:
    P = prepare(**args); print('####', args, len(P))
    for D0 in (None, 700, 1000, 1500, 2000):
        if D0 is None: model = 'dist+add'
        else: model = f'pw{D0}'; fit2.FEATS[model] = feats(D0)
        R = loo(P, model)
        print(f'  {model:9s} ALL', summ(R)); print('            H  ', summ([x for x in R if x['od'].startswith('H')]))
        print('            EXP', summ([x for x in R if x['btype'] in EXP]))
    if not args:
        for D0 in (1000, 1500):
            print('coef pw', D0, {g: [round(v, 4) for v in fitm([x for x in P if GROUP(x) == g], f'pw{D0}')] for g in sorted({GROUP(x) for x in P}) if sum(GROUP(x) == g for x in P) >= 5})
