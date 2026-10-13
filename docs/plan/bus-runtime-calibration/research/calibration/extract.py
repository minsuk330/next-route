"""Pick GTFS bus routes that could match an ODsay segment, dump their trips' stop_times.
usage: extract.py GTFS_DIR  -> cand_routes.json, cand_stop_times.csv, cand_stops.json"""
import csv, json, re, sys
G = sys.argv[1]
def norm(s): return re.sub(r'\s', '', re.sub(r'\(.*?\)', '', s or '')).upper()
segs = json.load(open('segments.json'))
names = {norm(s['busNo']) for s in segs}
lids = {s['localId'] for s in segs if s['localId']}
routes = {}
for r in csv.DictReader(open(f'{G}/routes.txt', encoding='utf-8-sig')):
    if r['route_type'] != '3': continue
    rid = r['route_id']; sn = norm(r['route_short_name'])
    tail = re.sub(r'^BR_[A-Z0-9]+_[A-Z]*', '', rid)
    if sn in names or tail in lids:
        routes[rid] = dict(short=r['route_short_name'], long=r['route_long_name'], color=r.get('route_color', ''))
trips = {}
for t in csv.DictReader(open(f'{G}/trips.txt', encoding='utf-8-sig')):
    if t['route_id'] in routes: trips[t['trip_id']] = t['route_id']
print('routes', len(routes), 'trips', len(trips), file=sys.stderr)
stops = set()
with open(f'{G}/stop_times.txt', encoding='utf-8-sig') as f, open('cand_stop_times.csv', 'w') as o:
    rd = csv.reader(f); h = next(rd)
    ti, ai, di, si, qi, sdi = (h.index(x) for x in ('trip_id', 'arrival_time', 'departure_time', 'stop_id', 'stop_sequence', 'shape_dist_traveled'))
    o.write('trip_id,route_id,seq,arr,dep,stop_id,dist\n')
    for row in rd:
        tid = row[ti]
        if tid in trips:
            o.write(f'{tid},{trips[tid]},{row[qi]},{row[ai]},{row[di]},{row[si]},{row[sdi]}\n'); stops.add(row[si])
coords = {}
for s in csv.DictReader(open(f'{G}/stops.txt', encoding='utf-8-sig')):
    if s['stop_id'] in stops: coords[s['stop_id']] = (float(s['stop_lat']), float(s['stop_lon']), s['stop_name'])
json.dump(routes, open('cand_routes.json', 'w'), ensure_ascii=False)
json.dump(coords, open('cand_stops.json', 'w'), ensure_ascii=False)
print('stops', len(coords), file=sys.stderr)
