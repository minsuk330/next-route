"""Generic linear models: T = sum(coef_k * feat_k) + DW, fit per group by relative LS, LOO by OD."""
import sys, math
from common import *
def lstsq(X, y):
    n = len(X[0]); A = [[0.0]*n for _ in range(n)]; c = [0.0]*n
    for xi, yi in zip(X, y):
        for i in range(n):
            c[i] += xi[i]*yi
            for j in range(n): A[i][j] += xi[i]*xi[j]
    # gaussian elimination w/ tiny ridge
    for i in range(n): A[i][i] += 1e-9
    M = [row[:] + [c[i]] for i, row in enumerate(A)]
    for i in range(n):
        piv = max(range(i, n), key=lambda r: abs(M[r][i])); M[i], M[piv] = M[piv], M[i]
        if abs(M[i][i]) < 1e-15: return None
        for r in range(n):
            if r != i:
                f = M[r][i]/M[i][i]
                for k in range(i, n+1): M[r][k] -= f*M[i][k]
    return [M[i][n]/M[i][i] for i in range(n)]
FEATS = {
    'mul':  lambda p: [p['RUN']],
    'add':  lambda p: None,  # handled: RUN fixed coef 1
    'mul+add': lambda p: [p['RUN'], p['hops']],
    'dist+add': lambda p: [p['gdist'], p['hops']],
    'dist+add+mul': lambda p: [p['gdist'], p['hops'], p['RUN']],
}
GROUP = lambda p: {'3100': 'GGB'}.get(p['src'], p['src'])
def prepare(lane0=True, hopd=1, mink=0.0, minst=1):
    P = [p for p in load_pairs() if p['ok']]
    out = []
    for p in P:
        if lane0 and p['laneIdx'] != 0: continue
        if abs(p['hops'] - p['nst']) > hopd: continue
        if p['dist'] < mink*1000 or p['nst'] < minst: continue
        p['RUN'] = st.median(t[1] for t in p['trips']); p['DW'] = st.median(t[2] for t in p['trips']); p['T'] = p['t']*60
        if (p['RUN'] + p['DW']) > 3*p['T']: continue
        out.append(p)
    return out
def fitm(rows, model):
    if model == 'add':
        num = sum((x['hops']/x['T'])*((x['T']-x['DW']-x['RUN'])/x['T']) for x in rows); den = sum((x['hops']/x['T'])**2 for x in rows)
        return [num/den]
    X = [[f/x['T'] for f in FEATS[model](x)] for x in rows]; y = [(x['T']-x['DW'])/x['T'] for x in rows]
    return lstsq(X, y)
def predm(p, model, c):
    if model == 'add': return (p['RUN'] + c[0]*p['hops'] + p['DW'])/60
    return (sum(a*f for a, f in zip(c, FEATS[model](p))) + p['DW'])/60
def run(P, models, show_coef=True, pool_small=True):
    for model in models:
        res = []
        for p in P:
            g = GROUP(p)
            tr = [x for x in P if GROUP(x) == g and x['od'] != p['od']]
            if len({x['od'] for x in tr}) < 2 and pool_small: tr = [x for x in P if x['od'] != p['od'] and GROUP(x) not in ('BSB',)]
            c = fitm(tr, model)
            res.append(dict(p, ratio=predm(p, model, c)*60/p['T']))
        print(f'== {model}')
        print('  ALL    ', summ(res))
        for k in ('서울', '경기', '인천', '부산', '대구', '대전'): print(f'  {k:6s}', summ([x for x in res if x['region'] == k]))
        if show_coef:
            print('  coef', {g: [round(v, 4) for v in fitm([x for x in P if GROUP(x) == g], model)] for g in sorted({GROUP(x) for x in P}) if sum(GROUP(x) == g for x in P) >= 5})
if __name__ == '__main__':
    for args in [dict(), dict(minst=3), dict(mink=2.0)]:
        P = prepare(**args); print('########', args, 'n', len(P))
        print('baseline', summ([dict(p, ratio=(p['RUN']+p['DW'])/p['T']) for p in P]))
        run(P, ['mul', 'add', 'mul+add', 'dist+add', 'dist+add+mul'])
