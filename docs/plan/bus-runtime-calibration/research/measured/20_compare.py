"""Measured (position stop_flag labels) vs GTFS (KTDB 2025-03) bus segment times.

Usage: python -I 20_compare.py <work_dir>
Inputs (in <work_dir>/data): labels.pkl (10_load_labels.py), gtfs_st_sel.pkl (GTFS stop_times for target routes),
                             bus_route.csv, bus_route_stop.csv (VPS catalog export, 04/05 sql)
Outputs: <work_dir>/out/segments.parquet (one row per measured segment) + printed summaries.

Definitions
- measured time  = label_arrival_at(j) - label_arrival_at(i), same vehicle trip (our trip_id), arrival->arrival.
  label_arrival_at = data_tm of the first 60s position poll with stop_flag=1 at that stop (resolution ~ dwell/60s).
- GTFS time      = arrival(j) - arrival(i) of the GTFS trip of the same route whose arrival at stop i is
                   closest in time-of-day to the measured arrival at i (GTFS run times vary by hour).
- stop mapping   = Seoul stop_id == GTFS stop_id minus 'BS_TAGO_SEB'; if a stop appears twice in the pattern,
                   the GTFS position closest to our seq wins (|diff|<=15).
- LONG segment   = greedy, non-overlapping, origin seq>=2, destination 10..20 stops later (closest to 15).
- SHORT segment  = same greedy rule, 4..8 stops (closest to 6).
- FULL segment   = first labelled stop with seq 2..5 -> last labelled stop within 3 of the route end
                   (>= ~90% of the run; terminal dwell at origin excluded by starting at seq>=2).
"""
import sys, os
import numpy as np, pandas as pd

W = sys.argv[1]
OUT = os.path.join(W, 'out'); os.makedirs(OUT, exist_ok=True)
lab = pd.read_pickle(os.path.join(W, 'data/labels.pkl'))
st = pd.read_pickle(os.path.join(W, 'data/gtfs_st_sel.pkl'))
br = pd.read_csv(os.path.join(W, 'data/bus_route.csv'), dtype={'route_id': str})
rs = pd.read_csv(os.path.join(W, 'data/bus_route_stop.csv'), dtype=str)
rs['seq'] = rs.seq.astype(int)

HOLIDAYS = {'2026-09-24', '2026-09-25', '2026-09-26', '2026-10-03', '2026-10-05', '2026-10-09'}
TYPE = {1: '공항', 2: '마을', 3: '간선', 4: '지선', 5: '순환', 6: '광역', 15: '심야'}
br['type_name'] = br.route_type.map(TYPE).fillna('기타')
rinfo = br.set_index('route_id')[['route_name', 'type_name']]

st['sid'] = st.stop_id.str.replace('BS_TAGO_SEB', '', regex=False)
st = st.sort_values(['trip_id', 'seq'])

lab['tod'] = (lab.label_arrival_at - lab.service_date).dt.total_seconds()  # seconds since service_date 00:00
lab['dkey'] = lab.service_date.dt.strftime('%Y-%m-%d')

def band(sec):
    h = sec / 3600.0
    if h < 6: return '04-06'
    if h < 9: return '06-09'
    if h < 16: return '09-16'
    if h < 20: return '16-20'
    if h < 24: return '20-24'
    return '24+'

rows = []
for route, g in st.groupby('route'):
    # most common stop pattern
    pats = g.groupby('trip_id').sid.apply(tuple)
    pat = pats.value_counts().index[0]
    trips = pats[pats == pat].index
    gg = g[g.trip_id.isin(trips)]
    A = gg.pivot(index='trip_id', columns='seq', values='a').to_numpy(dtype=float)  # trips x positions
    pos_of = {}
    for p, s in enumerate(pat):
        pos_of.setdefault(s, []).append(p)
    nstops_g = len(pat)
    L = lab[lab.route_id == route]
    if L.empty: continue
    nstops_c = rs[rs.route_id == route].seq.max()
    for tid, t in L.groupby('trip_id', sort=False):
        t = t.sort_values('seq')
        pts = []  # (seq, gpos, tod, date)
        last_p, last_t = -1, -1e18
        for seq, sid, tod in zip(t.seq.to_numpy(), t.stop_id.to_numpy(), t.tod.to_numpy()):
            cand = pos_of.get(sid)
            if not cand: continue
            p = min(cand, key=lambda x: abs(x + 1 - seq))
            if abs(p + 1 - seq) > 15: continue
            if p <= last_p or tod <= last_t: continue
            pts.append((seq, p, tod)); last_p, last_t = p, tod
        if len(pts) < 2: continue
        dkey = t.dkey.iat[0]
        def emit(kind, i, j):
            si, pi, ti = pts[i]; sj, pj, tj = pts[j]
            k = np.nanargmin(np.abs(A[:, pi] - ti))
            gt = A[k, pj] - A[k, pi]
            if not gt > 0: return
            rows.append((route, tid, dkey, kind, si, sj, sj - si, ti, tj - ti, gt, abs(A[k, pi] - ti)))
        # LONG
        i = 0
        while i < len(pts):
            if pts[i][0] < 2: i += 1; continue
            best, bd = None, 99
            for j in range(i + 1, len(pts)):
                d = pts[j][0] - pts[i][0]
                if d > 20: break
                if d >= 10 and abs(d - 15) < bd: best, bd = j, abs(d - 15)
            if best is None: i += 1; continue
            emit('LONG', i, best); i = best
        # SHORT (4..8 stops, closest to 6) - typical app ride length
        i = 0
        while i < len(pts):
            if pts[i][0] < 2: i += 1; continue
            best, bd = None, 99
            for j in range(i + 1, len(pts)):
                d = pts[j][0] - pts[i][0]
                if d > 8: break
                if d >= 4 and abs(d - 6) < bd: best, bd = j, abs(d - 6)
            if best is None: i += 1; continue
            emit('SHORT', i, best); i = best
        # FULL
        o = next((x for x in range(len(pts)) if 2 <= pts[x][0] <= 5), None)
        e = len(pts) - 1
        if o is not None and pts[e][0] >= nstops_c - 3 and e > o:
            emit('FULL', o, e)

seg = pd.DataFrame(rows, columns=['route_id', 'trip_id', 'date', 'kind', 'seq_i', 'seq_j', 'nstop', 'tod_i',
                                  'meas_s', 'gtfs_s', 'gtfs_match_gap_s'])
seg['ratio'] = seg.meas_s / seg.gtfs_s
seg['band'] = seg.tod_i.map(band)
dow = pd.to_datetime(seg.date).dt.dayofweek
seg['daytype'] = np.where((dow >= 5) | seg.date.isin(HOLIDAYS), 'weekend_hol', 'weekday')
seg['period'] = np.where(seg.date < '2026-09-01', 'Jun', 'SepOct')
seg = seg.join(rinfo, on='route_id')
n0 = len(seg)
seg['outlier'] = ~seg.ratio.between(1 / 3, 3)
seg.to_parquet(os.path.join(OUT, 'segments.parquet'))
print('segments', n0, 'outliers(ratio outside 1/3..3)', int(seg.outlier.sum()))
print(seg.groupby('kind').size())
