#!/usr/bin/env python3
"""Segment-level analysis of sampled routes (sample_stop_times.csv from scan_stop_times.py).

Checks:
  A. Is the segment time a lookup by (segment, clock hour)? -> share of (segment, hour)
     cells (>=2 trips) whose segment running time is identical across trips.
  B. Time-of-day profile: segment running time / that segment's all-day median,
     aggregated by clock hour of traversal (time-weighted) per source group.
  C. Running speed by hour (sum shape dist / sum running time) per group.
Outputs: seg_hour_profile.csv, seg_lookup_check.csv (+ markdown on stdout)
"""
import csv
import statistics as st
import sys
from collections import defaultdict
from pathlib import Path

OUT = Path(sys.argv[1])


def tsec(s):
    h, m, x = s.split(":")
    return int(h) * 3600 + int(m) * 60 + int(x)


trips = defaultdict(list)
meta = {}
for r in csv.DictReader((OUT / "sample_stop_times.csv").open()):
    trips[r["trip_id"]].append(r)
    meta[r["trip_id"]] = (r["route_id"], r["route_name"], r["group"])

seg = defaultdict(list)  # (route, seq, from, to) -> [(hour, run_s, dist_m)]
for tid, rows in trips.items():
    rows.sort(key=lambda r: int(r["stop_sequence"]))
    rid, _, g = meta[tid]
    for a, b in zip(rows, rows[1:]):
        dep = tsec(a["departure_time"])
        run = tsec(b["arrival_time"]) - dep
        try:
            d = float(b["shape_dist_traveled"]) - float(a["shape_dist_traveled"])
        except ValueError:
            d = float("nan")
        seg[(g, rid, a["stop_sequence"], a["stop_id"], b["stop_id"])].append(((dep // 3600) % 24, run, d))

# A. lookup check
cells = defaultdict(lambda: [0, 0])  # group -> [cells, identical]
for (g, *_), obs in seg.items():
    byh = defaultdict(list)
    for h, run, _ in obs:
        byh[h].append(run)
    for h, runs in byh.items():
        if len(runs) >= 2:
            cells[g][0] += 1
            cells[g][1] += len(set(runs)) == 1

# B/C. profile
prof = defaultdict(lambda: defaultdict(lambda: [0.0, 0.0, 0.0, 0.0]))  # g->h->[sum run, sum median, dist, run_with_dist]
for (g, *_), obs in seg.items():
    if len(obs) < 5:
        continue
    med = st.median(run for _, run, _ in obs)
    if med <= 0:
        continue
    for h, run, d in obs:
        p = prof[g][h]
        p[0] += run
        p[1] += med
        if d == d and d > 0 and run > 0:
            p[2] += d
            p[3] += run

rows_out = []
for g in sorted(prof):
    for h in sorted(prof[g]):
        sr, sm, sd, srd = prof[g][h]
        rows_out.append({"group": g, "hour": h,
                         "time_ratio_vs_allday_median": round(sr / sm, 3) if sm else "",
                         "run_speed_kmh": round(sd / srd * 3.6, 1) if srd else "",
                         "run_hours_sampled": round(sr / 3600, 1)})
with (OUT / "seg_hour_profile.csv").open("w", newline="") as fh:
    w = csv.DictWriter(fh, fieldnames=list(rows_out[0].keys()))
    w.writeheader()
    w.writerows(rows_out)

with (OUT / "seg_lookup_check.csv").open("w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["group", "segment_hour_cells", "identical_pct"])
    for g, (n, k) in sorted(cells.items()):
        w.writerow([g, n, round(100 * k / n, 1)])

print("A. (segment, hour) cells with identical running time across trips")
for g, (n, k) in sorted(cells.items()):
    print(f"  {g}: {k}/{n} = {100*k/n:.1f}%")
print()
hours = [5, 7, 8, 9, 11, 13, 15, 17, 18, 19, 21, 23]
print("B. running time / all-day median by clock hour (C. running speed km/h in brackets)")
print("| group | " + " | ".join(f"{h:02d}" for h in hours) + " |")
print("|---|" + "---|" * len(hours))
for g in sorted(prof):
    cells_s = []
    for h in hours:
        sr, sm, sd, srd = prof[g].get(h, [0, 0, 0, 0])
        cells_s.append(f"{sr/sm:.2f} ({sd/srd*3.6:.0f})" if sm and srd else "-")
    print(f"| {g} | " + " | ".join(cells_s) + " |")
