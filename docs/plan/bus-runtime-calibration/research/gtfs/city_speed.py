#!/usr/bin/env python3
"""KTDB bus speed per metro city vs official 시내버스 평균 통행속도 (e-나라지표 5079).
speed_all = sum(shape dist)/sum(trip duration incl. all dwell); speed_10s = duration with
anchor/turnaround waits removed (only the 10 s/stop modeled dwell kept); run = no dwell.
Trips departing 06..21h, route_type 3, city bus prefixes only."""
import csv, sys
from collections import defaultdict
OFFICIAL = {"SEB": ("서울", 17.9, 17.1), "BSB": ("부산", 24.9, 24.4), "DGB": ("대구", 17.6, 17.5),
            "KJB": ("광주", 20.1, 21.0), "DJB": ("대전", 22.1, 21.6), "USB": ("울산", 25.6, 24.8),
            "ICB": ("인천", None, None), "GGB": ("경기", None, None)}
acc = defaultdict(lambda: [0.0, 0.0, 0.0, 0.0, 0])
for r in csv.DictReader(open(sys.argv[1] + "/trips_summary.csv")):
    p = r["route_id"].split("_")
    if len(p) < 3 or p[1] != "TAGO" or r["route_type"] != "3":
        continue
    city = p[2][:3]
    if city not in OFFICIAL:
        continue
    h = int(r["first_dep"]) // 3600
    if not 6 <= h <= 21:
        continue
    try:
        d = float(r["shape_dist_m"])
    except ValueError:
        continue
    dur = int(r["last_arr"]) - int(r["first_dep"])
    n_int = int(r["n_stops"]) - 2
    dwell_all = int(r["dwell_sum_s"])
    run = dur - dwell_all
    if dur <= 0 or run <= 0 or d < 1000:
        continue
    a = acc[city]
    a[0] += d; a[1] += dur; a[2] += run + 10 * max(n_int, 0); a[3] += run; a[4] += 1
print("| city | trips | KTDB all-dwell km/h | KTDB 10s-dwell km/h | KTDB run km/h | official 2024 | official 2025 | ratio(10s/2025) |")
print("|---|---|---|---|---|---|---|---|")
for c, (name, o24, o25) in OFFICIAL.items():
    d, t, t10, tr, n = acc[c]
    s_all, s10, sr = d / t * 3.6, d / t10 * 3.6, d / tr * 3.6
    ratio = f"{s10 / o25:.2f}" if o25 else "-"
    print(f"| {name}({c}) | {n} | {s_all:.1f} | {s10:.1f} | {sr:.1f} | {o24 or '-'} | {o25 or '-'} | {ratio} |")
