"""Match ODsay segments to GTFS trips. -> pairs_trips.json
Per segment: best pattern (board stop within TOL of ODsay start, alight within TOL of end, hop count ~ stationCount).
Per matched trip: board dep (s), RUN (sum of hop running secs), DW (intermediate dwell secs), hops, first-stop dep (s)."""
import csv, json, math, re, collections, sys
TOL = 200
def hav(a, b, c, d):
    R = 6371000; p1, p2 = math.radians(a), math.radians(c); dp = p2 - p1; dl = math.radians(d - b)
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*R*math.asin(math.sqrt(h))
def norm(s): return re.sub(r'\s', '', re.sub(r'\(.*?\)', '', s or '')).upper()
def sec(t): h, m, s = t.split(':'); return int(h)*3600 + int(m)*60 + int(s)
segs = json.load(open('segments.json'))
routes = json.load(open('cand_routes.json'))
stops = json.load(open('cand_stops.json'))
# grid index of stops
grid = collections.defaultdict(list)
for sid, (la, lo, _) in stops.items(): grid[(int(la*200), int(lo*200))].append(sid)
def near(la, lo):
    out = {}
    gx, gy = int(la*200), int(lo*200)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for sid in grid.get((gx+dx, gy+dy), ()):
                d = hav(la, lo, stops[sid][0], stops[sid][1])
                if d <= TOL: out[sid] = d
    return out
by_name = collections.defaultdict(list); by_lid = collections.defaultdict(list)
for k, s in enumerate(segs):
    s['nearS'] = near(s['sy'], s['sx']); s['nearE'] = near(s['ey'], s['ex'])
    by_name[norm(s['busNo'])].append(k)
    if s['localId']: by_lid[s['localId']].append(k)
def cand_segs(rid):
    tail = re.sub(r'^BR_[A-Z0-9]+_[A-Z]*', '', rid)
    ks = set(by_name.get(norm(routes[rid]['short']), ())) | set(by_lid.get(tail, ()))
    return ks
pat_cache = {}
best = {}   # seg -> (score, route, patkey, i, j)
trips_by_pat = collections.defaultdict(list)
def eval_trip(tid, rid, rows):
    rows.sort(key=lambda r: r[0])
    sids = tuple(r[3] for r in rows)
    pk = (rid, sids)
    trips_by_pat[pk].append((tid, [(r[1], r[2], r[4]) for r in rows]))
    if pk in pat_cache: return
    pat_cache[pk] = True
    for k in cand_segs(rid):
        s = segs[k]
        I = [(i, s['nearS'][x]) for i, x in enumerate(sids) if x in s['nearS']]
        J = [(j, s['nearE'][x]) for j, x in enumerate(sids) if x in s['nearE']]
        for i, d1 in I:
            for j, d2 in J:
                if j <= i: continue
                hd = abs((j - i) - s['nst'])
                if hd > 2: continue
                sc = d1 + d2 + 60*hd
                if k not in best or sc < best[k][0]: best[k] = (sc, rid, pk, i, j, d1, d2)
cur = None; rows = []
with open('cand_stop_times.csv') as f:
    rd = csv.reader(f); next(rd)
    for tid, rid, seq, arr, dep, sid, dist in rd:
        if tid != cur:
            if rows: eval_trip(cur, crid, rows)
            cur, crid, rows = tid, rid, []
        rows.append((int(seq), sec(arr), sec(dep), sid, float(dist or 0)))
    if rows: eval_trip(cur, crid, rows)
out = []
for k, (sc, rid, pk, i, j, d1, d2) in best.items():
    s = segs[k]
    tl = []
    for tid, rr in trips_by_pat[pk]:
        run = sum(rr[h+1][0] - rr[h][1] for h in range(i, j))
        dw = sum(rr[h][1] - rr[h][0] for h in range(i+1, j))
        tl.append((rr[i][1], run, dw, rr[0][1]))
    rr0 = trips_by_pat[pk][0][1]
    gdist = rr0[j][2] - rr0[i][2]
    hopd = [max(rr0[h+1][2] - rr0[h][2], 0.0) for h in range(i, j)]
    out.append(dict({x: s[x] for x in ('od', 'region', 'busNo', 'btype', 'localId', 'laneIdx', 'nLanes', 'start', 'end', 't', 'dist', 'nst')},
        route=rid, short=routes[rid]['short'], hops=j-i, d1=round(d1), d2=round(d2), gdist=round(gdist), hopd=[round(x) for x in hopd], trips=tl))
json.dump(out, open('pairs_trips.json', 'w'), ensure_ascii=False)
print('segments', len(segs), 'matched', len(out), 'patterns', len(pat_cache), file=sys.stderr)
