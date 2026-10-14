"""ODsay raw responses -> segments.json (one row per subPath x lane)."""
import json, glob, os, re
OD_DIR = '../odsay/raw_odsay'
REG = {}
for f in ('../odsay/ods.txt', '../odsay/ods_holdout.txt'):
    for l in open(f):
        p = l.split()
        if p: REG[p[0]] = p[1]
rows = []
for f in sorted(glob.glob(f'{OD_DIR}/*.json')):
    od = os.path.basename(f)[:-5]
    r = json.load(open(f))
    if 'result' not in r: continue
    seen = set()
    for pi, p in enumerate(r['result']['path']):
        for s in p['subPath']:
            if s['trafficType'] != 2: continue
            st = s.get('passStopList', {}).get('stations', [])
            for li, ln in enumerate(s['lane']):
                k = (ln['busNo'], s['startID'], s['endID'])
                if k in seen: continue
                seen.add(k)
                rows.append(dict(od=od, region=REG.get(od, '?'), busNo=ln['busNo'], btype=ln.get('type'),
                    localId=ln.get('busLocalBlID'), cityCode=ln.get('busCityCode'), laneIdx=li, nLanes=len(s['lane']),
                    start=s['startName'], end=s['endName'], sy=float(s['startY']), sx=float(s['startX']),
                    ey=float(s['endY']), ex=float(s['endX']), t=s['sectionTime'], dist=s['distance'], nst=s['stationCount'],
                    stops=[(float(x['y']), float(x['x'])) for x in st]))
json.dump(rows, open('segments.json', 'w'), ensure_ascii=False)
print('segments', len(rows), 'ods', len({r['od'] for r in rows}))
