"""End-to-end: query an OTP (local port or 'vps') for every OD, match BUS legs to ODsay segments, compare ride time.
usage: e2e.py fetch TARGET OUTDIR [ODS...]   (TARGET = port number | vps)
       e2e.py eval OUTDIR"""
import json, sys, os, math, re, subprocess, urllib.request, statistics as st
sys.path.insert(0, os.path.dirname(__file__))
from common import summ, q
WHEN = "2026-10-14T14:00:00+09:00"
Q = '''query($o:PlanLabeledLocationInput!,$d:PlanLabeledLocationInput!,$w:OffsetDateTime!,$m:PlanModesInput,$sw:Duration){planConnection(origin:$o,destination:$d,dateTime:{earliestDeparture:$w},first:20,searchWindow:$sw,modes:$m){
 routingErrors{code} edges{node{duration legs{mode duration distance route{shortName gtfsId} trip{gtfsId}
 from{name lat lon stop{gtfsId}} to{name lat lon stop{gtfsId}} start{scheduledTime} intermediatePlaces{name}}}}}}'''
def ods():
    d = {}
    for f in ('../odsay/ods.txt', '../odsay/ods_holdout.txt'):
        for l in open(f):
            p = l.split()
            if p: d[p[0]] = p
    return d
def body(o):
    _, reg, name, sx, sy, ex, ey = o
    v = {"o": {"location": {"coordinate": {"latitude": float(sy), "longitude": float(sx)}}},
         "d": {"location": {"coordinate": {"latitude": float(ey), "longitude": float(ex)}}}, "w": WHEN,
         "m": {"transit": {"transit": [{"mode": "BUS", "cost": {"reluctance": 1.3}}]}}, "sw": "PT60M"}
    return json.dumps({"query": Q, "variables": v})
def post(target, b):
    if target == 'vps':
        r = subprocess.run(["ssh", "vps", "curl -s -m 90 -X POST -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:18080/otp/routers/default/index/graphql"],
                           input=b.encode(), capture_output=True, timeout=120)
        return json.loads(r.stdout)
    req = urllib.request.Request(f"http://localhost:{target}/otp/routers/default/index/graphql", b.encode(), {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=120))
def hav(a, b, c, d):
    R = 6371000; p1, p2 = math.radians(a), math.radians(c); dp = p2 - p1; dl = math.radians(d - b)
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*R*math.asin(math.sqrt(h))
def norm(s): return re.sub(r'\s', '', re.sub(r'\(.*?\)', '', s or '')).upper()
def evaluate(outdir, tol=300):
    segs = [s for s in json.load(open('segments.json')) if s['laneIdx'] == 0]
    res = []
    for od in sorted({s['od'] for s in segs}):
        f = f'{outdir}/{od}.json'
        if not os.path.exists(f): continue
        r = json.load(open(f))
        legs = [l for e in (r.get('data') or {}).get('planConnection', {}).get('edges', []) for l in e['node']['legs']
                if l['mode'] == 'BUS' and l.get('route')]
        for s in (x for x in segs if x['od'] == od):
            best = None
            for l in legs:
                if norm(l['route']['shortName']) != norm(s['busNo']): continue
                d1 = hav(s['sy'], s['sx'], l['from']['lat'], l['from']['lon']); d2 = hav(s['ey'], s['ex'], l['to']['lat'], l['to']['lon'])
                if d1 <= tol and d2 <= tol and (best is None or d1 + d2 < best[0]): best = (d1 + d2, l)
            if not best: continue
            l = best[1]
            same = [x['duration'] for x in legs if x['route']['shortName'] == l['route']['shortName']
                    and x['from']['name'] == l['from']['name'] and x['to']['name'] == l['to']['name']]
            hops = len(l['intermediatePlaces'] or []) + 1
            dr = l['distance'] / s['dist'] if s['dist'] else 0
            night = s['busNo'].upper().startswith('N') or '심야' in s['busNo']
            if night or not 0.8 <= dr <= 1.25 or abs(hops - s['nst']) > 1 or s['t'] < 2: continue
            otp = st.median(same) / 60
            res.append(dict(od=od, region=s['region'], busNo=s['busNo'], start=s['start'], end=s['end'], t=s['t'],
                            nst=s['nst'], km=s['dist']/1000, otp=round(otp, 1), ratio=otp/s['t']))
    return res
def report(res, label=''):
    print(f'#### {label}')
    for name, flt in [('all', lambda x: True), ('>=3stops', lambda x: x['nst'] >= 3), ('>=2km', lambda x: x['km'] >= 2)]:
        R = [x for x in res if flt(x)]
        print(f' [{name}] ALL     ', summ(R))
        print(f' [{name}] holdoutH', summ([x for x in R if x['od'].startswith('H')]))
        for k in ('서울', '경기', '인천', '부산', '대구', '대전'):
            print(f' [{name}] {k:6s}  ', summ([x for x in R if x['region'] == k]))
if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'fetch':
        target, outdir = sys.argv[2], sys.argv[3]; os.makedirs(outdir, exist_ok=True)
        O = ods(); ids = sys.argv[4:] or sorted({s['od'] for s in json.load(open('segments.json'))})
        for i in ids:
            if os.path.exists(f'{outdir}/{i}.json'): continue
            try: r = post(target, body(O[i]))
            except Exception as e: r = {'error': str(e)}
            json.dump(r, open(f'{outdir}/{i}.json', 'w'), ensure_ascii=False)
            n = len(((r.get('data') or {}).get('planConnection') or {}).get('edges', []))
            print(i, 'itins', n, flush=True)
    else:
        res = evaluate(sys.argv[2]); report(res, sys.argv[2])
        json.dump(res, open(f'{sys.argv[2]}/eval.json', 'w'), ensure_ascii=False)
