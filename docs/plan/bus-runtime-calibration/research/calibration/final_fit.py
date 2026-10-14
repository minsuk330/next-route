from fit2 import *
import json
P3 = prepare(minst=3); Pall = prepare(); P2k = prepare(mink=2.0)
grp = lambda P, g: [x for x in P if GROUP(x) == g]
groups = [g for g in sorted({GROUP(x) for x in P3}) if len(grp(P3, g)) >= 5]
# holdout: fit without H ods, test on H
def evalset(fitP, testP, label):
    coefs = {g: fitm([x for x in fitP if GROUP(x) == g], 'dist+add') for g in groups}
    res = [dict(p, ratio=predm(p, 'dist+add', coefs[GROUP(p)])*60/p['T']) for p in testP if GROUP(p) in coefs]
    print(label, summ(res))
    return res
trainP = [x for x in P3 if not x['od'].startswith('H')]
for lab, T in [('H holdout >=3stops', [x for x in P3 if x['od'].startswith('H')]),
               ('H holdout >=2km    ', [x for x in P2k if x['od'].startswith('H')]),
               ('H holdout all      ', [x for x in Pall if x['od'].startswith('H')])]:
    evalset(trainP, T, lab)
print('baseline H >=2km', summ([dict(p, ratio=(p['RUN']+p['DW'])/p['T']) for p in P2k if p['od'].startswith('H')]))
final = {g: fitm(grp(P3, g), 'dist+add') for g in groups}
print({g: (round(c[0], 4), round(c[1], 1), len(grp(P3, g)), len({x['od'] for x in grp(P3, g)})) for g, c in final.items()})
json.dump({g: c for g, c in final.items()}, open('final_coefs.json', 'w'))
