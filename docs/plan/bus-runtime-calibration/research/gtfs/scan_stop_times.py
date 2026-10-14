#!/usr/bin/env python3
"""One streaming pass over a GTFS zip's stop_times.txt -> per-trip summary + sample rows.

Read only. Never loads stop_times into memory: rows are streamed from `unzip -p`.

Outputs (in OUT dir):
  trips_summary.csv   one row per trip: group, route, first_dep, last_arr, n_stops,
                      shape_dist_m, straight_m, dwell_n, dwell_sum_s, zero_seg_n,
                      sec0_frac (share of times with :00 seconds), seg_speed_cv,
                      seg_time_sig (hash of segment-time vector)
  sample_stop_times.csv  full rows for sampled routes (segment-level analysis)
  dwell_values.csv    counter of dwell (dep-arr) seconds per group
Usage: scan_stop_times.py GTFS_ZIP OUT_DIR
"""
import csv
import hashlib
import io
import math
import random
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

csv.field_size_limit(2**31 - 1)

ZIP = sys.argv[1]
OUT = Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)

FORCED_SAMPLES = {
    "BR_TAGO_SEB100100409",  # 421
    "BR_TAGO_SEB100100537",  # 740
    "BR_TAGO_SEB100100025",  # 146
    "BR_TAGO_SEB100100075",  # 472
}
SAMPLES_PER_GROUP = 15


def group_of(route_id: str) -> str:
    p = route_id.split("_")
    if len(p) > 2 and p[1] == "TAGO":
        city = re.sub(r"\d.*$", "", p[2])
        if city == "SEB":
            return "SEOUL(TAGO_SEB)"
        if city == "GGB":
            return "GYEONGGI(TAGO_GGB)"
        if city == "ICB":
            return "INCHEON(TAGO_ICB)"
        if city == "TSB":
            return "TAGO_TSB(시군)"
        return "TAGO_LOCAL(기타 도시)"
    if p[0] == "BR":
        if p[1] == "3100":
            return "BR_3100(경기 마을)"
        if p[1] == "PORT":
            return "BR_PORT(공항)"
        if p[1] == "ACC3":
            return "BR_ACC3(시외)"
        if p[1] == "KPTI":
            return "BR_KPTI"
        if p[1] == "GGTR":
            return "BR_GGTR(똑버스)"
        return "BR_OTHER(지자체코드)"
    return "RAIL/" + p[0] + "_" + (p[1] if len(p) > 1 else "")


def unzip_stream(name: str):
    proc = subprocess.Popen(["unzip", "-p", ZIP, name], stdout=subprocess.PIPE)
    return proc, io.TextIOWrapper(proc.stdout, encoding="utf-8-sig", newline="")


def tsec(s: str) -> int:
    h, m, x = s.split(":")
    return int(h) * 3600 + int(m) * 60 + int(x)


def hav(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


# --- routes / trips / stops -------------------------------------------------
route_group = {}
route_name = {}
route_type = {}
proc, f = unzip_stream("routes.txt")
for row in csv.DictReader(f):
    rid = row["route_id"]
    route_group[rid] = group_of(rid)
    route_name[rid] = row["route_short_name"]
    route_type[rid] = row["route_type"]
proc.wait()

trip_route = {}
proc, f = unzip_stream("trips.txt")
for row in csv.DictReader(f):
    trip_route[row["trip_id"]] = row["route_id"]
proc.wait()

stop_ll = {}
proc, f = unzip_stream("stops.txt")
for row in csv.DictReader(f):
    try:
        stop_ll[row["stop_id"]] = (float(row["stop_lat"]), float(row["stop_lon"]))
    except ValueError:
        pass
proc.wait()

# sample routes: per group, routes with >=10 trips, seeded
rng = random.Random(20261013)
trips_per_route = Counter(trip_route.values())
by_group = defaultdict(list)
for rid, g in route_group.items():
    if route_type[rid] == "3" and trips_per_route[rid] >= 10:
        by_group[g].append(rid)
sample_routes = set(FORCED_SAMPLES)
for g, rids in by_group.items():
    rids.sort()
    sample_routes.update(rng.sample(rids, min(SAMPLES_PER_GROUP, len(rids))))
print(f"routes={len(route_group)} trips={len(trip_route)} stops={len(stop_ll)} "
      f"sample_routes={len(sample_routes)}", file=sys.stderr)

# --- stream stop_times ------------------------------------------------------
summary_f = (OUT / "trips_summary.csv").open("w", newline="")
summary = csv.writer(summary_f)
summary.writerow(["trip_id", "route_id", "route_name", "group", "route_type",
                  "first_dep", "last_arr", "n_stops", "shape_dist_m", "straight_m",
                  "dwell_n", "dwell_sum_s", "zero_seg_n", "sec0_frac",
                  "seg_speed_cv", "seg_time_sig"])
sample_f = (OUT / "sample_stop_times.csv").open("w", newline="")
sample_w = csv.writer(sample_f)
dwell_counter = defaultdict(Counter)
seen_trips = set()
noncontig = 0

proc, f = unzip_stream("stop_times.txt")
reader = csv.reader(f)
header = next(reader)
ix = {h: i for i, h in enumerate(header)}
sample_w.writerow(["route_id", "route_name", "group"] + header)
TI, AI, DI, SI, QI, DSI = (ix["trip_id"], ix["arrival_time"], ix["departure_time"],
                           ix["stop_id"], ix["stop_sequence"], ix["shape_dist_traveled"])


def flush(trip, rows):
    global noncontig
    if not rows:
        return
    if trip in seen_trips:
        noncontig += 1
    seen_trips.add(trip)
    rows.sort(key=lambda r: int(r[QI]))
    rid = trip_route.get(trip, "")
    g = route_group.get(rid, "UNKNOWN")
    arr = [tsec(r[AI]) for r in rows]
    dep = [tsec(r[DI]) for r in rows]
    try:
        sd = [float(r[DSI]) for r in rows]
        shape_dist = sd[-1] - sd[0]
    except ValueError:
        sd = None
        shape_dist = float("nan")
    straight = 0.0
    seg_d = []
    for a, b in zip(rows, rows[1:]):
        la, lb = stop_ll.get(a[SI]), stop_ll.get(b[SI])
        d = hav(*la, *lb) if la and lb else 0.0
        straight += d
        seg_d.append(d)
    dwell = [d - a for a, d in zip(arr, dep)]
    dwell_n = sum(1 for x in dwell[1:-1] if x > 0)
    dwell_sum = sum(dwell[1:-1])
    for x in dwell[1:-1]:
        dwell_counter[g][x] += 1
    seg_t = [arr[i + 1] - dep[i] for i in range(len(rows) - 1)]
    zero = sum(1 for t in seg_t if t <= 0)
    secs = [x % 60 for x in arr + dep]
    sec0 = sum(1 for x in secs if x == 0) / len(secs)
    # segment speed CV using shape distance when available
    seg_dist = ([sd[i + 1] - sd[i] for i in range(len(rows) - 1)] if sd else seg_d)
    sp = [dd / t for dd, t in zip(seg_dist, seg_t) if t > 0 and dd > 0]
    if len(sp) >= 3:
        m = sum(sp) / len(sp)
        cv = math.sqrt(sum((x - m) ** 2 for x in sp) / len(sp)) / m
    else:
        cv = float("nan")
    sig = hashlib.md5(",".join(map(str, seg_t + dwell)).encode()).hexdigest()[:10]
    summary.writerow([trip, rid, route_name.get(rid, ""), g, route_type.get(rid, ""),
                      dep[0], arr[-1], len(rows), round(shape_dist, 1), round(straight, 1),
                      dwell_n, dwell_sum, zero, round(sec0, 3),
                      round(cv, 3) if cv == cv else "", sig])
    if rid in sample_routes:
        for r in rows:
            sample_w.writerow([rid, route_name.get(rid, ""), g] + r)


cur, buf, n = None, [], 0
for parts in reader:
    n += 1
    t = parts[TI]
    if t != cur:
        flush(cur, buf)
        cur, buf = t, []
    buf.append(parts)
    if n % 2_000_000 == 0:
        print(f"rows={n}", file=sys.stderr, flush=True)
flush(cur, buf)
proc.wait()
summary_f.close()
sample_f.close()

with (OUT / "dwell_values.csv").open("w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["group", "dwell_s", "count"])
    for g, c in dwell_counter.items():
        for k, v in c.most_common(15):
            w.writerow([g, k, v])
print(f"done rows={n} trips={len(seen_trips)} noncontig={noncontig}", file=sys.stderr)
