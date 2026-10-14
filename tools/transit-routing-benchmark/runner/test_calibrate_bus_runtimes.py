"""Contracts of calibrate_bus_runtimes.py.  Run: python3 -m unittest runner/test_calibrate_bus_runtimes.py -v"""

from __future__ import annotations

import csv
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import calibrate_bus_runtimes as cbr  # noqa: E402

ST_HEADER = ["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence", "stop_headsign",
             "pickup_type", "drop_off_type", "shape_dist_traveled", "timepoint"]
SPM, SPH = 0.1, 50.0  # 400 m hop -> 90 s
DWELL = 10


def coefficients(**over) -> dict:
    doc = {"todWindow": ["10:00:00", "16:00:00"], "todClamp": [0.7, 1.4], "detourFactor": 1.25,
           "regions": {"seoul": {"routeIdPrefixes": ["BR_TAGO_SEB"], "secPerMeter": SPM, "secPerHop": SPH},
                       "gyeonggi": {"routeIdPrefixes": ["BR_TAGO_GGB", "BR_3100_"], "secPerMeter": 0.2,
                                    "secPerHop": 0.0}}}
    doc.update(over)
    return doc


def trip_rows(trip: str, dep0: str, hop_runs: list[int], dists: list[str] | None = None,
              stops: list[str] | None = None) -> list[list[str]]:
    """Original KTDB-like rows: hop running seconds as given, 10 s dwell at intermediate stops."""
    n = len(hop_runs) + 1
    stops = stops or [f"S{k}" for k in range(n)]
    dists = dists if dists is not None else [str(400.0 * k) for k in range(n)]
    t = cbr.parse_time(dep0)
    rows = []
    for k in range(n):
        if k > 0:
            t += hop_runs[k - 1]
        arr = t
        dep = t + (DWELL if 0 < k < n - 1 else 0)
        rows.append([trip, cbr.format_time(arr), cbr.format_time(dep), stops[k], str(k + 1), "", "0", "0",
                     dists[k], "1"])
        t = dep
    return rows


def write_feed(path: Path, trips: dict[str, tuple[str, list[list[str]]]], routes: dict[str, str]) -> None:
    """trips: trip_id -> (route_id, stop_times rows); routes: route_id -> route_type."""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\nA1,a,http://a,Asia/Seoul\n")
        z.writestr("routes.txt", "route_id,agency_id,route_short_name,route_long_name,route_type\n"
                   + "".join(f"{r},A1,{r},,{t}\n" for r, t in routes.items()))
        z.writestr("trips.txt", "route_id,service_id,trip_id\n"
                   + "".join(f"{r},B1,{t}\n" for t, (r, _) in trips.items()))
        stop_ids = sorted({row[3] for _, rows in trips.values() for row in rows})
        z.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\n"
                   + "".join(f"{s},{s},37.5,{127.0 + 0.001 * int(s[1:])}\n" for s in stop_ids))
        buf = io.StringIO()
        w = csv.writer(buf, lineterminator="\n")
        w.writerow(ST_HEADER)
        for _, rows in trips.values():
            w.writerows(rows)
        z.writestr("stop_times.txt", buf.getvalue())
        z.writestr("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nx,37.5,127.0,1\n")


class Feed:
    def __init__(self, trips, routes, coef=None):
        self.tmp = tempfile.TemporaryDirectory()
        d = Path(self.tmp.name)
        self.inp, self.out, self.coef, self.rep = d / "in.zip", d / "out.zip", d / "coef.json", d / "rep.json"
        write_feed(self.inp, trips, routes)
        self.coef.write_text(json.dumps(coef or coefficients()), encoding="utf-8")

    def run(self) -> cbr.Report:
        return cbr.run(self.inp, self.coef, self.out, self.rep)

    def times(self, zip_path: Path | None = None) -> dict[str, list[tuple[str, str]]]:
        with zipfile.ZipFile(zip_path or self.out) as z:
            rd = csv.DictReader(io.TextIOWrapper(z.open("stop_times.txt"), encoding="utf-8"))
            out: dict[str, list[tuple[str, str]]] = {}
            for r in rd:
                out.setdefault(r["trip_id"], []).append((r["arrival_time"], r["departure_time"]))
            return out

    def close(self):
        self.tmp.cleanup()


SEB, GGV, ACC3, SUBWAY = "BR_TAGO_SEB1", "BR_3100_9", "BR_ACC3_1", "RR_ACC1_1"
ROUTES = {SEB: "3", GGV: "3", ACC3: "3", SUBWAY: "1"}


class RetimeTest(unittest.TestCase):
    def feed(self, trips, coef=None):
        f = Feed(trips, ROUTES, coef)
        self.addCleanup(f.close)
        return f

    def test_hop_is_distance_and_stop_count_and_keeps_first_departure_and_dwell(self):
        f = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 30, 30])),
                       "m2": (SEB, trip_rows("m2", "14:10:00", [30, 30, 30]))})
        f.run()
        # 400 m hops -> 0.1*400+50 = 90 s each; dwell 10 s kept; first departure kept
        self.assertEqual(f.times()["m1"], [("14:00:00", "14:00:00"), ("14:01:30", "14:01:40"),
                                           ("14:03:10", "14:03:20"), ("14:04:50", "14:04:50")])

    def test_rush_hour_trip_keeps_its_time_of_day_factor(self):
        f = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 30, 30])),
                       "r1": (SEB, trip_rows("r1", "18:00:00", [36, 36, 36]))})
        f.run()
        # factor 108/90 = 1.2 -> hop 108 s
        self.assertEqual(f.times()["r1"][-1], ("18:05:44", "18:05:44"))  # 3*108 + 2*10

    def test_factor_is_clamped(self):
        f = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 30, 30])),
                       "r1": (SEB, trip_rows("r1", "08:00:00", [90, 90, 90]))})
        rep = f.run()
        self.assertEqual(rep.clamped_factors, 1)
        self.assertEqual(f.times()["r1"][-1], ("08:06:38", "08:06:38"))  # 1.4*270 + 20

    def test_unlisted_routes_and_other_files_are_byte_identical(self):
        f = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 30])),
                       "x1": (ACC3, trip_rows("x1", "14:00:00", [30, 30])),
                       "s1": (SUBWAY, trip_rows("s1", "14:00:00", [30, 30]))})
        f.run()
        before, after = f.times(f.inp), f.times()
        self.assertEqual(before["x1"], after["x1"])
        self.assertEqual(before["s1"], after["s1"])
        self.assertNotEqual(before["m1"], after["m1"])
        with zipfile.ZipFile(f.inp) as a, zipfile.ZipFile(f.out) as b:
            for name in ("agency.txt", "routes.txt", "trips.txt", "stops.txt", "shapes.txt"):
                self.assertEqual(a.read(name), b.read(name), name)

    def test_second_prefix_of_a_region_is_retimed_with_that_region(self):
        f = self.feed({"g1": (GGV, trip_rows("g1", "14:00:00", [30]))})
        rep = f.run()
        self.assertEqual(f.times()["g1"][-1][0], "14:01:20")  # 0.2*400 + 0
        self.assertEqual(rep.regions["gyeonggi"]["trips"], 1)

    def test_missing_distance_falls_back_to_straight_line_with_detour(self):
        f = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30], dists=["", ""]))})
        rep = f.run()
        self.assertEqual(rep.fallback_hops, 1)
        metres = cbr.haversine_m(37.5, 127.0, 37.5, 127.001) * 1.25
        expected = round(SPM * metres + SPH)
        self.assertEqual(cbr.parse_time(f.times()["m1"][-1][0]) - cbr.parse_time("14:00:00"), expected)

    def test_guard_stops_a_later_trip_from_overtaking(self):
        # ref 90 s/hop at midday; 17:00 is slow (x1.3), 17:01 fast (x0.8) -> would arrive first
        f = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 30, 30])),
                       "a": (SEB, trip_rows("a", "17:00:00", [39, 39, 39])),
                       "b": (SEB, trip_rows("b", "17:01:00", [24, 24, 24]))})
        rep = f.run()
        t = f.times()
        self.assertGreaterEqual(rep.guard_raised, 1)
        self.assertGreaterEqual(cbr.parse_time(t["b"][-1][0]), cbr.parse_time(t["a"][-1][0]))
        self.assertEqual(rep.overtaking_after, 0)

    def test_times_past_midnight_stay_gtfs_times(self):
        f = self.feed({"m1": (SEB, trip_rows("m1", "23:59:00", [30, 30]))})
        f.run()
        self.assertEqual(f.times()["m1"][-1][0], "24:02:10")  # 2*90 + 10

    def test_report_written(self):
        f = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 30]))})
        f.run()
        doc = json.loads(f.rep.read_text(encoding="utf-8"))
        self.assertEqual(doc["regions"]["seoul"]["trips"], 1)
        self.assertEqual(doc["overtakingAfter"], 0)
        self.assertIn("tripRuntimeRatio", doc["regions"]["seoul"])


def section_csv(path: Path, rows: list[tuple[str, str, str, float]]) -> Path:
    path.write_text("from_stop,to_stop,hour,seconds\n" + "".join(f"{a},{b},{h},{v}\n" for a, b, h, v in rows), encoding="utf-8")
    return path


class SectionTimesTest(unittest.TestCase):
    """Stop ids S0, S1 ... ; with stopIdPrefixes ["S"] the section key of S1 is "1"."""

    def feed(self, trips, sections, scale=0.9):
        c = coefficients(sectionTimes={"scale": scale, "stopIdPrefixes": ["S"]})
        f = Feed(trips, ROUTES, c)
        self.addCleanup(f.close)
        sec = section_csv(Path(f.tmp.name) / "sections.csv", sections)
        return f, sec

    def test_section_hop_uses_scaled_value_for_the_departure_hour_and_formula_elsewhere(self):
        f, sec = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 30]))},
                           [("0", "1", "14", 200), ("0", "1", "all", 100)])
        rep = cbr.run(f.inp, f.coef, f.out, f.rep, sec)
        # hop 0: 0.9 * 200 = 180 s (14 h value); hop 1: formula 90 s; dwell 10
        self.assertEqual(f.times()["m1"], [("14:00:00", "14:00:00"), ("14:03:00", "14:03:10"), ("14:04:40", "14:04:40")])
        self.assertEqual((rep.section_hops, rep.formula_hops), (1, 1))

    def test_missing_hour_falls_back_to_any_hour_median(self):
        f, sec = self.feed({"m1": (SEB, trip_rows("m1", "03:00:00", [30]))},
                           [("0", "1", "14", 200), ("0", "1", "all", 100)])
        cbr.run(f.inp, f.coef, f.out, f.rep, sec)
        self.assertEqual(f.times()["m1"][-1][0], "03:01:30")  # 0.9 * 100

    def test_section_far_from_formula_is_ignored(self):
        f, sec = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30]))},
                           [("0", "1", "14", 2000), ("0", "1", "all", 2000)])
        rep = cbr.run(f.inp, f.coef, f.out, f.rep, sec)
        self.assertEqual(rep.section_rejected, 1)
        self.assertEqual(f.times()["m1"][-1][0], "14:01:30")  # formula

    def test_section_hops_do_not_take_the_trip_factor(self):
        f, sec = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 30])),
                            "r1": (SEB, trip_rows("r1", "18:00:00", [36, 36]))},
                           [("0", "1", "all", 100)])
        cbr.run(f.inp, f.coef, f.out, f.rep, sec)
        # r1 factor 1.2: hop 0 section 90 (no factor), hop 1 formula 108
        self.assertEqual(f.times()["r1"][-1][0], "18:03:28")  # 90 + 10 + 108

    def test_hourly_sections_cannot_make_a_later_trip_overtake(self):
        # 13:59 leaves in the slow 13 h slot, 14:00 in the fast 14 h slot -> would arrive first
        f, sec = self.feed({"a": (SEB, trip_rows("a", "13:59:00", [30])), "b": (SEB, trip_rows("b", "14:00:00", [30]))},
                           [("0", "1", "13", 400), ("0", "1", "14", 100), ("0", "1", "all", 200)], scale=1.0)
        rep = cbr.run(f.inp, f.coef, f.out, f.rep, sec)
        t = f.times()
        self.assertEqual(t["a"][-1][0], "14:05:40")
        self.assertEqual(t["b"][-1][0], "14:05:40")  # pushed to a's arrival, not earlier
        self.assertEqual((rep.guard_raised, rep.overtaking_after), (1, 0))

    def test_pair_without_all_row_fails(self):
        f, sec = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30]))}, [("0", "1", "14", 100)])
        with self.assertRaises(ValueError) as cm:
            cbr.run(f.inp, f.coef, f.out, f.rep, sec)
        self.assertIn("'all'", str(cm.exception))

    def test_section_times_need_stop_prefixes(self):
        f = Feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30]))}, ROUTES, coefficients())
        self.addCleanup(f.close)
        sec = section_csv(Path(f.tmp.name) / "s.csv", [("0", "1", "all", 100)])
        with self.assertRaises(ValueError) as cm:
            cbr.run(f.inp, f.coef, f.out, f.rep, sec)
        self.assertIn("stopIdPrefixes", str(cm.exception))


LONG = ["0.0", "400.0", "37400.0", "37800.0"]  # hop 1 = 37 km non-stop (M4403 동탄 -> 강남 형태)


class LongHopTest(unittest.TestCase):
    def feed(self, trips, **over):
        f = Feed(trips, ROUTES, coefficients(longHopMeters=3000, **over))
        self.addCleanup(f.close)
        return f

    def test_long_hop_keeps_its_ktdb_time_and_short_hops_use_the_formula(self):
        f = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 1939, 30], dists=LONG))})
        rep = f.run()
        # 90 s formula, 1939 s KTDB, 90 s formula; dwell 10
        self.assertEqual(f.times()["m1"], [("14:00:00", "14:00:00"), ("14:01:30", "14:01:40"),
                                           ("14:33:59", "14:34:09"), ("14:35:39", "14:35:39")])
        self.assertEqual((rep.long_hops, rep.formula_hops), (1, 2))

    def test_section_time_wins_over_the_long_hop_rule(self):
        f = Feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 1939, 30], dists=LONG))}, ROUTES,
                 coefficients(longHopMeters=3000, sectionTimes={"scale": 0.9, "stopIdPrefixes": ["S"]}))
        self.addCleanup(f.close)
        sec = section_csv(Path(f.tmp.name) / "s.csv", [("1", "2", "all", 2000)])
        rep = cbr.run(f.inp, f.coef, f.out, f.rep, sec)
        self.assertEqual(f.times()["m1"][2][0], "14:31:40")  # 90 + 0.9*2000 + 10
        self.assertEqual((rep.section_hops, rep.long_hops), (1, 0))

    def test_long_hop_does_not_take_the_trip_factor(self):
        f = self.feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 1939, 30], dists=LONG)),
                       "r1": (SEB, trip_rows("r1", "18:00:00", [36, 2327, 36], dists=LONG))})
        f.run()
        # r1 factor 2399/1999 = 1.2: short hops 108 s, long hop its own KTDB 2327 s
        self.assertEqual(f.times()["r1"][-1][0], "18:42:43")  # 108 + 2327 + 108 + 20

    def test_without_the_setting_long_hops_use_the_formula(self):
        f = Feed({"m1": (SEB, trip_rows("m1", "14:00:00", [30, 1939, 30], dists=LONG))}, ROUTES, coefficients())
        self.addCleanup(f.close)
        rep = f.run()
        self.assertEqual(rep.long_hops, 0)
        self.assertEqual(f.times()["m1"][2][0], "15:04:10")  # 90 + (0.1*37000 + 50) + 10


class FailTest(unittest.TestCase):
    def run_with(self, coef, trips=None):
        f = Feed(trips or {"m1": (SEB, trip_rows("m1", "14:00:00", [30]))}, ROUTES, coef)
        self.addCleanup(f.close)
        with self.assertRaises(ValueError) as cm:
            f.run()
        self.assertFalse(f.out.exists(), "no output on failure")
        return str(cm.exception)

    def test_coefficient_out_of_range(self):
        c = coefficients()
        c["regions"]["seoul"]["secPerMeter"] = 0.5
        self.assertIn("secPerMeter", self.run_with(c))

    def test_prefix_matching_no_route(self):
        c = coefficients()
        c["regions"]["busan"] = {"routeIdPrefixes": ["BR_TAGO_BSB"], "secPerMeter": 0.1, "secPerHop": 30}
        self.assertIn("busan", self.run_with(c))

    def test_long_hop_meters_must_be_positive(self):
        self.assertIn("longHopMeters", self.run_with(coefficients(longHopMeters=0)))

    def test_prefix_listed_twice(self):
        c = coefficients()
        c["regions"]["gyeonggi"]["routeIdPrefixes"].append("BR_TAGO_SEB")
        self.assertIn("twice", self.run_with(c))

    def test_stop_times_not_grouped_by_trip(self):
        rows = trip_rows("m1", "14:00:00", [30, 30])
        other = trip_rows("m2", "15:00:00", [30, 30])
        f = Feed({"m1": (SEB, rows[:2]), "m2": (SEB, other), "m1b": (SEB, rows[2:])}, ROUTES, coefficients())
        self.addCleanup(f.close)
        with self.assertRaises(ValueError) as cm:
            f.run()
        self.assertIn("not grouped", str(cm.exception))


if __name__ == "__main__":
    unittest.main()
