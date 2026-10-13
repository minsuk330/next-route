#!/usr/bin/env python3
"""Aggregate trips_summary.csv (from scan_stop_times.py) into per-source tables.

Outputs (markdown to stdout + CSVs in OUT):
  group_overview.csv    per group: routes, trips, median speeds, dwell, :00 share,
                        share of routes whose trips are all time-shifted copies
  group_hour_speed.csv  per group x departure hour: trips, p25/p50/p75 speed km/h
  route_tod_ratio.csv   per route (dominant pattern): min/max hourly median duration
Speed = shape_dist_traveled (pfaedle road distance) / (last_arr - first_dep),
i.e. commercial speed including the feed's dwell. run_speed excludes dwell.
"""
import csv
import statistics as st
import sys
from collections import Counter, defaultdict
from pathlib import Path

OUT = Path(sys.argv[1])
rows = list(csv.DictReader((OUT / "trips_summary.csv").open()))


def q(xs, p):
    xs = sorted(xs)
    if not xs:
        return float("nan")
    k = (len(xs) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


by_group = defaultdict(list)
for r in rows:
    if r["route_type"] != "3":
        continue
    dur = int(r["last_arr"]) - int(r["first_dep"])
    d = float(r["shape_dist_m"]) if r["shape_dist_m"] not in ("", "nan") else float("nan")
    if dur <= 0 or not d == d or d < 1000:
        continue
    run = dur - int(r["dwell_sum_s"])
    r["_dur"] = dur
    r["_spd"] = d / dur * 3.6
    r["_run_spd"] = d / run * 3.6 if run > 0 else float("nan")
    r["_sl_spd"] = float(r["straight_m"]) / dur * 3.6
    r["_hour"] = (int(r["first_dep"]) // 3600) % 24
    r["_dist"] = d
    by_group[r["group"]].append(r)

# --- overview ---------------------------------------------------------------
ov = []
for g, rs in sorted(by_group.items(), key=lambda x: -len(x[1])):
    routes = defaultdict(list)
    for r in rs:
        routes[r["route_id"]].append(r)
    # share of multi-trip routes where every trip has an identical segment-time vector
    multi = [v for v in routes.values() if len(v) >= 5]
    identical = sum(1 for v in multi if len({x["seg_time_sig"] for x in v}) == 1)
    distinct_ratio = st.median([len({x["seg_time_sig"] for x in v}) / len(v) for v in multi]) if multi else float("nan")
    ov.append({
        "group": g, "routes": len(routes), "trips": len(rs),
        "spd_p25": round(q([r["_spd"] for r in rs], .25), 1),
        "spd_p50": round(q([r["_spd"] for r in rs], .5), 1),
        "spd_p75": round(q([r["_spd"] for r in rs], .75), 1),
        "run_spd_p50": round(q([r["_run_spd"] for r in rs if r["_run_spd"] == r["_run_spd"]], .5), 1),
        "straight_spd_p50": round(q([r["_sl_spd"] for r in rs], .5), 1),
        "detour_ratio_p50": round(q([r["_dist"] / float(r["straight_m"]) for r in rs if float(r["straight_m"]) > 0], .5), 2),
        "dwell_share_p50": round(q([int(r["dwell_sum_s"]) / r["_dur"] for r in rs], .5), 3),
        "sec00_share_p50": round(q([float(r["sec0_frac"]) for r in rs], .5), 3),
        "seg_speed_cv_p50": round(q([float(r["seg_speed_cv"]) for r in rs if r["seg_speed_cv"]], .5), 2),
        "routes_all_trips_identical_pct": round(100 * identical / len(multi), 1) if multi else "",
        "distinct_timeprofiles_per_trip_p50": round(distinct_ratio, 2),
    })
with (OUT / "group_overview.csv").open("w", newline="") as fh:
    w = csv.DictWriter(fh, fieldnames=list(ov[0].keys()))
    w.writeheader()
    w.writerows(ov)

# --- hour profile -----------------------------------------------------------
hp = []
for g, rs in by_group.items():
    hours = defaultdict(list)
    for r in rs:
        hours[r["_hour"]].append(r["_spd"])
    for h in sorted(hours):
        xs = hours[h]
        hp.append({"group": g, "hour": h, "trips": len(xs),
                   "p25": round(q(xs, .25), 1), "p50": round(q(xs, .5), 1),
                   "p75": round(q(xs, .75), 1)})
with (OUT / "group_hour_speed.csv").open("w", newline="") as fh:
    w = csv.DictWriter(fh, fieldnames=list(hp[0].keys()))
    w.writeheader()
    w.writerows(hp)

# --- per-route time-of-day ratio (dominant pattern = most common n_stops+dist) ---
tod = []
for g, rs in by_group.items():
    routes = defaultdict(list)
    for r in rs:
        routes[r["route_id"]].append(r)
    for rid, v in routes.items():
        pat = Counter((x["n_stops"], x["shape_dist_m"]) for x in v).most_common(1)[0][0]
        v = [x for x in v if (x["n_stops"], x["shape_dist_m"]) == pat]
        if len(v) < 8:
            continue
        hrs = defaultdict(list)
        for x in v:
            hrs[x["_hour"]].append(x["_dur"])
        med = {h: st.median(d) for h, d in hrs.items()}
        lo_h = min(med, key=med.get)
        hi_h = max(med, key=med.get)
        tod.append({"group": g, "route_id": rid, "route_name": v[0]["route_name"],
                    "trips": len(v), "dist_km": round(float(pat[1]) / 1000, 1),
                    "min_hour": lo_h, "min_dur_min": round(med[lo_h] / 60, 1),
                    "max_hour": hi_h, "max_dur_min": round(med[hi_h] / 60, 1),
                    "max_over_min": round(med[hi_h] / med[lo_h], 3),
                    "distinct_profiles": len({x["seg_time_sig"] for x in v})})
with (OUT / "route_tod_ratio.csv").open("w", newline="") as fh:
    w = csv.DictWriter(fh, fieldnames=list(tod[0].keys()))
    w.writeheader()
    w.writerows(tod)

# --- markdown print ---------------------------------------------------------
print("| group | routes | trips | spd p25/p50/p75 km/h | run p50 | straight p50 | detour | dwell share | :00 share | segCV | all-identical routes % | profiles/trip |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|")
for o in ov:
    print(f"| {o['group']} | {o['routes']} | {o['trips']} | {o['spd_p25']}/{o['spd_p50']}/{o['spd_p75']} | "
          f"{o['run_spd_p50']} | {o['straight_spd_p50']} | {o['detour_ratio_p50']} | {o['dwell_share_p50']} | "
          f"{o['sec00_share_p50']} | {o['seg_speed_cv_p50']} | {o['routes_all_trips_identical_pct']} | "
          f"{o['distinct_timeprofiles_per_trip_p50']} |")
print()
tg = defaultdict(list)
for t in tod:
    tg[t["group"]].append(t)
print("| group | routes(>=8 trips) | max/min hourly duration p25/p50/p75 | routes with ratio<1.02 % | peak hour mode |")
print("|---|---|---|---|---|")
for g, ts in sorted(tg.items(), key=lambda x: -len(x[1])):
    ratios = [t["max_over_min"] for t in ts]
    flat = sum(1 for x in ratios if x < 1.02) / len(ratios) * 100
    peak = Counter(t["max_hour"] for t in ts).most_common(3)
    print(f"| {g} | {len(ts)} | {q(ratios,.25):.3f}/{q(ratios,.5):.3f}/{q(ratios,.75):.3f} | {flat:.1f} | {peak} |")
