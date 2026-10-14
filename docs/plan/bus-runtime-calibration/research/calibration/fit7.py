import fit2
from fit2 import *
EXP = (2, 4, 14, 5)
base = lambda p: {'3100': 'GGB'}.get(p['src'], p['src'])
for args in [dict(), dict(minst=3), dict(mink=2.0)]:
    P = prepare(**args); print('####', args, len(P))
    for name, g in [('region', base),
                    ('region+EXPcap', lambda p: 'EXP' if p['btype'] in EXP and base(p) in ('SEB', 'GGB', 'ICB') else base(p)),
                    ('region+EXPregion', lambda p: base(p) + (':EXP' if p['btype'] in EXP and base(p) in ('SEB', 'GGB', 'ICB') else ''))]:
        fit2.GROUP = g
        res = []
        for p in P:
            gg = g(p)
            tr = [x for x in P if g(x) == gg and x['od'] != p['od']]
            if len({x['od'] for x in tr}) < 2: tr = [x for x in P if x['od'] != p['od'] and base(x) == base(p)]
            if len({x['od'] for x in tr}) < 2: tr = [x for x in P if x['od'] != p['od'] and base(x) != 'BSB']
            c = fitm(tr, 'dist+add'); res.append(dict(p, ratio=predm(p, 'dist+add', c)*60/p['T']))
        print(f'  {name:16s} ALL', summ(res)); print('                   H  ', summ([x for x in res if x['od'].startswith('H')]))
        print('                   EXP', summ([x for x in res if x['btype'] in EXP]))
        if not args and name == 'region+EXPcap': print('   coef EXP', [round(v, 4) for v in fitm([x for x in P if g(x) == 'EXP'], 'dist+add')], sum(g(x) == 'EXP' for x in P))
