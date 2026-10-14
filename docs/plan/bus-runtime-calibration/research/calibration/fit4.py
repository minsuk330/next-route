from fit2 import *
import fit2
fit2.FEATS['k+dist+add'] = lambda p: [1.0, p['gdist'], p['hops']]
fit2.FEATS['k+dist'] = lambda p: [1.0, p['gdist']]
fit2.FEATS['dist'] = lambda p: [p['gdist']]
fit2.FEATS['odsdist+add'] = lambda p: [p['dist'], p['hops']]
for args in [dict(mink=2.0), dict(minst=3), dict()]:
    P = prepare(**args); print('####', args, len(P))
    run(P, ['dist', 'odsdist+add', 'k+dist', 'k+dist+add'])
