from common import *
P = [p for p in load_pairs() if p['ok']]
for p in P:
    p['RUN'] = st.median(t[1] for t in p['trips']); p['DW'] = st.median(t[2] for t in p['trips']); p['T'] = p['t']*60
S = [p for p in P if p['src'] == 'SEB']
b = 54.5
for p in S: p['pred'] = (p['RUN'] + b*p['hops'] + p['DW'])/60; p['ratio'] = p['pred']/p['t']
S.sort(key=lambda p: p['ratio'])
fmt = lambda p: f"{p['od']} {p['busNo']:>6s} {p['start'][:10]:10s}->{p['end'][:10]:10s} T{p['t']:3d} pred{p['pred']:5.1f} gtfs{(p['RUN']+p['DW'])/60:5.1f} nst{p['nst']:3d} hops{p['hops']:3d} km{p['dist']/1000:5.1f} gkm{p['gdist']/1000:5.1f} odsKmh{p['dist']/1000/(p['t']/60):5.1f} gKmh{p['gdist']/1000/((p['RUN']+p['DW'])/3600):5.1f} r{p['ratio']:.2f} lane{p['laneIdx']}/{p['nLanes']}"
print('--- lowest'); [print(fmt(p)) for p in S[:15]]
print('--- highest'); [print(fmt(p)) for p in S[-12:]]
# ODsay speed vs GTFS speed correlation per route
import math
xs = [(p['dist']/p['t'], p['gdist']*60/(p['RUN']+p['DW'])) for p in S]
mx = st.mean(x for x, _ in xs); my = st.mean(y for _, y in xs)
cov = sum((x-mx)*(y-my) for x, y in xs); vx = sum((x-mx)**2 for x, _ in xs); vy = sum((y-my)**2 for _, y in xs)
print('corr odsay speed vs gtfs speed', round(cov/math.sqrt(vx*vy), 3))
# odsay min/km by lane index
for li in (0, 1, 2):
    r = [p['ratio'] for p in S if min(p['laneIdx'], 2) == li]
    if r: print('laneIdx', li, len(r), round(st.median(r), 3))
# ODsay minutes per km vs hops/km
print('ODsay sec per km med', st.median(p['T']/(p['dist']/1000) for p in S), 'gtfs', st.median((p['RUN']+p['DW'])/(p['gdist']/1000) for p in S))
