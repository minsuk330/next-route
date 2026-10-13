"""Itinerary level: ODsay bus-only path (pathType 2) totalTime vs OTP itinerary duration with the same bus sequence."""
import json, os, re, sys, statistics as st
from common import summ
def norm(s): return re.sub(r'\s', '', re.sub(r'\(.*?\)', '', s or '')).upper()
REG = {}
for f in ('../odsay/ods.txt', '../odsay/ods_holdout.txt'):
    for l in open(f):
        p = l.split()
        if p: REG[p[0]] = p[1]
def run(outdir):
    res = []
    for f in sorted(os.listdir('../odsay/raw_odsay')):
        od = f[:-5]; r = json.load(open('../odsay/raw_odsay/' + f))
        if 'result' not in r or not os.path.exists(f'{outdir}/{od}.json'): continue
        o = json.load(open(f'{outdir}/{od}.json'))
        its = [e['node'] for e in ((o.get('data') or {}).get('planConnection') or {}).get('edges', [])]
        otp = {}
        for n in its:
            seq = tuple(norm(l['route']['shortName']) for l in n['legs'] if l['mode'] == 'BUS')
            otp.setdefault(seq, []).append(n['duration'] / 60)
        for p in r['result']['path']:
            if p['pathType'] != 2: continue
            subs = [s for s in p['subPath'] if s['trafficType'] == 2]
            seqs = [()]
            for s in subs: seqs = [x + (norm(l['busNo']),) for x in seqs for l in s['lane'][:1]]
            seq = seqs[0]
            if seq in otp:
                d = min(otp[seq]); t = p['info']['totalTime']
                res.append(dict(od=od, region=REG[od], seq='>'.join(seq), t=t, otp=round(d, 1), ratio=d / t, nbus=len(seq),
                                km=p['info']['totalDistance'] / 1000))
    return res
for outdir in sys.argv[1:]:
    R = run(outdir); print('####', outdir)
    print(' ALL     ', summ(R)); print(' holdoutH', summ([x for x in R if x['od'].startswith('H')]))
    for k in ('서울', '경기', '인천', '부산', '대구', '대전'): print(f' {k:6s}  ', summ([x for x in R if x['region'] == k]))
    json.dump(R, open(f'{outdir}/itin.json', 'w'), ensure_ascii=False)
