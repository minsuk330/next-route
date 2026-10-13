import json
from fit2 import *
R = json.load(open('cand_routes.json'))
TN = {1:'일반',2:'좌석',3:'마을',4:'직행좌석',5:'공항',6:'간선급행',10:'외곽',11:'간선',12:'지선',13:'순환',14:'광역',15:'급행',16:'관광',20:'농어촌',22:'경기시외형',26:'급행간선'}
import collections
for args in [dict(mink=2.0), dict(minst=3), dict()]:
    P = prepare(**args)
    for p in P: p['color'] = R[p['route']]['color']
    c = collections.Counter((GROUP(p), p['color'], TN.get(p['btype'], p['btype'])) for p in P)
    if not args.get('minst'): pass
    print('#####', args, len(P))
    import fit2
    for gname, gf in [('src', lambda p: GROUP(p)),
                      ('src+color', lambda p: GROUP(p) + ':' + p['color']),
                      ('src+fast', lambda p: GROUP(p) + (':F' if p['color'] in ('E60012', 'F2B70A', 'FF0000') else ''))]:
        fit2.GROUP = gf
        print('--', gname); run(P, ['dist+add'], show_coef=(gname != 'src+color'))
for k, v in sorted(c.items()): print(k, v)
