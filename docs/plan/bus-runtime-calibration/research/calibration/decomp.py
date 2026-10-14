import json, os, re, sys, statistics as st
def norm(s): return re.sub(r'\s', '', re.sub(r'\(.*?\)', '', s or '')).upper()
def med(v): return round(st.median(v), 1) if v else None
for outdir in sys.argv[1:]:
    rows = []
    for f in sorted(os.listdir('../odsay/raw_odsay')):
        od = f[:-5]; r = json.load(open('../odsay/raw_odsay/' + f))
        if 'result' not in r or not os.path.exists(f'{outdir}/{od}.json'): continue
        o = json.load(open(f'{outdir}/{od}.json'))
        its = [e['node'] for e in ((o.get('data') or {}).get('planConnection') or {}).get('edges', [])]
        for p in r['result']['path']:
            if p['pathType'] != 2: continue
            seq = tuple(norm(s['lane'][0]['busNo']) for s in p['subPath'] if s['trafficType'] == 2)
            cand = [n for n in its if tuple(norm(l['route']['shortName']) for l in n['legs'] if l['mode'] == 'BUS') == seq]
            if not cand: continue
            n = min(cand, key=lambda n: n['duration'])
            ride = sum(l['duration'] for l in n['legs'] if l['mode'] == 'BUS') / 60
            walk = sum(l['duration'] for l in n['legs'] if l['mode'] == 'WALK') / 60
            wait = n['duration'] / 60 - ride - walk
            oride = sum(s['sectionTime'] for s in p['subPath'] if s['trafficType'] == 2)
            owalk = sum(s['sectionTime'] for s in p['subPath'] if s['trafficType'] == 3)
            rows.append(dict(od=od, nb=len(seq), ride=ride, walk=walk, wait=wait, oride=oride, owalk=owalk, ot=p['info']['totalTime'], t=n['duration']/60))
    print('####', outdir, 'n', len(rows))
    for nb in (1, 2):
        R = [x for x in rows if x['nb'] == nb]
        if not R: continue
        print(f' buses={nb} n={len(R)} OTP ride {med([x["ride"] for x in R])} walk {med([x["walk"] for x in R])} wait {med([x["wait"] for x in R])} total {med([x["t"] for x in R])} | ODsay ride {med([x["oride"] for x in R])} walk {med([x["owalk"] for x in R])} total {med([x["ot"] for x in R])} | ride ratio {med([x["ride"]/x["oride"] for x in R])} walk ratio {med([x["walk"]/max(x["owalk"],1) for x in R])}')
