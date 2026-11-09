"""Contracts of apply_day_types.py (#557).

Run (cwd tools/transit-routing-benchmark): python3 -m unittest runner/test_apply_day_types.py -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apply_day_types as adt  # noqa: E402
from test_add_missing_trips import ST_HEADER, hms, st_lines  # noqa: E402

R7 = "RR_ACC1_S-1-07-1D"
R8 = "RR_ACC1_S-1-08-1D"
S7 = [f"RS_ACC1_S-1-27{i:02d}" for i in range(11, 16)]
S8 = [f"RS_ACC1_S-1-28{i:02d}" for i in range(11, 14)]
BUILD_DAY = date(2026, 11, 12)


def feed(path: Path, calendar_end: str = "20270111", calendar_dates: str | None = None) -> None:
    trips = [(f"{R7}_Ord001", R7, 6 * 3600, S7, "shp7"),
             (f"{R7}_Ord002", R7, 7 * 3600, S7, "shp7"),
             (f"{R7}_Ord003", R7, 8 * 3600, S7[:3], "shp7s"),
             (f"{R8}_Ord001", R8, 6 * 3600, S8, "shp8"),
             ("BUS_1_Ord001", "BUS_1", 8 * 3600, ["B1", "B2"], "shpb")]
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("routes.txt", "route_id,agency_id,route_short_name,route_type\n"
                                 f"{R7},A1,서울7호선,1\n{R8},A1,서울8호선,1\nBUS_1,A1,1,3\n")
        z.writestr("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,"
                                   f"start_date,end_date\nB1,1,1,1,1,1,1,1,20260729,{calendar_end}\n")
        if calendar_dates is not None:
            z.writestr("calendar_dates.txt", calendar_dates)
        z.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\n" +
                   "".join(f"{s},{s},37.5,127.0\n" for s in S7 + S8 + ["B1", "B2"]))
        z.writestr("trips.txt", "route_id,service_id,trip_id,shape_id\n" +
                   "".join(f"{r},B1,{t},{sh}\n" for t, r, _, _, sh in trips))
        z.writestr("stop_times.txt", ST_HEADER + "\n" +
                   "".join(ln + "\n" for t, _, d, s, _ in trips for ln in st_lines(t, d, s)))


def trip(route: str, svc: str, no: str, dep: int, stops: list[str]) -> dict:
    t, rows = dep, []
    for k, s in enumerate(stops):
        dwell = 30 if 0 < k < len(stops) - 1 else 0
        rows.append([s, hms(t), hms(t + dwell)])
        t += dwell + 120
    return dict(tripId=f"{route}_{svc[2:]}_{no}", trainNo=no, service=svc, stops=rows)


def data_file(path: Path, route: str, trips: list[dict]) -> Path:
    path.write_text(json.dumps(dict(routeId=route, source={}, tripCount=len(trips), trips=trips)), encoding="utf-8")
    return path


def holidays(path: Path, days: list[str], lo: str = "2026-11-01", hi: str = "2027-03-31") -> Path:
    path.write_text(json.dumps(dict(coverage={"from": lo, "to": hi},
                                    days=[dict(date=d, name="h", source="test") for d in days])), encoding="utf-8")
    return path


def read(path: Path, name: str) -> list[list[str]]:
    with zipfile.ZipFile(path) as z:
        return [ln.split(",") for ln in z.read(name).decode().splitlines()[1:]]


class ApplyDayTypesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.src, self.out = self.tmp / "in.gtfs.zip", self.tmp / "out.gtfs.zip"
        feed(self.src)
        self.hol = holidays(self.tmp / "h.json", ["2026-12-25", "2027-01-02", "2027-01-03"])
        self.d7 = data_file(self.tmp / "d7.json", R7, [
            trip(R7, "M_SH", "7001", 6 * 3600, S7),          # same as a weekday trip's time: not a duplicate
            trip(R7, "M_SA", "7002", 9 * 3600, S7),
            trip(R7, "M_HO", "7003", 9 * 3600, S7),          # same time as the Saturday one: other day
            trip(R7, "M_HO", "7004", 10 * 3600, S7[1:4]),    # slice of a full trip
        ])

    def run_(self, paths=None, **kw):
        return adt.run(self.src, paths or [self.d7], self.hol, self.out, build_day=BUILD_DAY, **kw)

    def test_target_trips_move_to_weekday_and_others_stay_byte_for_byte(self):
        self.run_()
        with zipfile.ZipFile(self.src) as a, zipfile.ZipFile(self.out) as b:
            src_trips, out_trips = a.read("trips.txt").decode().splitlines(), b.read("trips.txt").decode().splitlines()
            self.assertEqual(a.read("routes.txt"), b.read("routes.txt"))
            self.assertTrue(b.read("stop_times.txt").startswith(a.read("stop_times.txt")))
        svc = {r[2]: r[1] for r in read(self.out, "trips.txt")}
        self.assertEqual(svc[f"{R7}_Ord001"], "M_WD")
        self.assertEqual(svc[f"{R7}_Ord003"], "M_WD")
        self.assertEqual(svc[f"{R8}_Ord001"], "B1")
        self.assertEqual(svc["BUS_1_Ord001"], "B1")
        for ln in src_trips:
            if not ln.startswith(R7):
                self.assertIn(ln, out_trips)

    def test_weekend_trips_copy_a_weekday_template(self):
        rep = self.run_()
        rows = {r[2]: r for r in read(self.out, "trips.txt")}
        self.assertEqual(rows[f"{R7}_SH_7001"][1], "M_SH")
        self.assertEqual(rows[f"{R7}_SA_7002"][3], "shp7")
        st = [r for r in read(self.out, "stop_times.txt") if r[0] == f"{R7}_HO_7004"]
        self.assertEqual([r[3] for r in st], S7[1:4])
        self.assertEqual([r[4] for r in st], ["1", "2", "3"])
        self.assertEqual(st[0][1], "10:00:00")
        self.assertEqual(rep["routes"][0]["added"], {"M_SH": 1, "M_SA": 1, "M_HO": 2})
        self.assertEqual(rep["routes"][0]["templateKinds"], {"same": 3, "slice": 1})

    def test_calendar_and_holidays(self):
        rep = self.run_()
        cal = {r[0]: r[1:] for r in read(self.out, "calendar.txt")}
        self.assertEqual(cal["B1"], ["1"] * 7 + ["20260729", "20270111"])
        self.assertEqual(cal["M_WD"][:7], list("1111100"))
        self.assertEqual(cal["M_SA"][:7], list("0000010"))
        self.assertEqual(cal["M_HO"][:7], list("0000001"))
        self.assertEqual(cal["M_SH"][:7], list("0000011"))
        self.assertEqual(cal["M_SH"][7:], ["20260729", "20270111"])
        cd = sorted(tuple(r) for r in read(self.out, "calendar_dates.txt"))
        self.assertEqual(cd, sorted([
            ("M_WD", "20261225", "2"), ("M_HO", "20261225", "1"), ("M_SH", "20261225", "1"),  # Friday
            ("M_SA", "20270102", "2"), ("M_HO", "20270102", "1"),                             # Saturday
        ]))                                                                                    # 01-03 Sunday: none
        self.assertEqual(rep["holidaysOutsideRange"], [])

    def test_holidays_after_the_service_range_are_skipped(self):
        hol = holidays(self.tmp / "h2.json", ["2026-12-25", "2027-03-01"])
        rep = adt.run(self.src, [self.d7], hol, self.out, build_day=BUILD_DAY)
        self.assertEqual(rep["holidaysOutsideRange"], ["2027-03-01"])
        self.assertFalse(any(r[1] == "20270301" for r in read(self.out, "calendar_dates.txt")))

    def test_service_range_past_holiday_coverage_fails(self):
        hol = holidays(self.tmp / "h3.json", ["2026-12-25"], hi="2026-12-31")
        with self.assertRaisesRegex(adt.DayTypesError, "holiday coverage"):
            adt.run(self.src, [self.d7], hol, self.out, build_day=BUILD_DAY)

    def test_existing_calendar_dates_are_kept(self):
        feed(self.src, calendar_dates="service_id,date,exception_type\nB1,20261224,2\n")
        self.run_()
        cd = read(self.out, "calendar_dates.txt")
        self.assertEqual(cd[0], ["B1", "20261224", "2"])
        self.assertIn(["M_HO", "20261225", "1"], cd)

    def test_same_day_duplicate_fails(self):
        d = data_file(self.tmp / "dup.json", R7, [trip(R7, "M_SA", "1", 9 * 3600, S7),
                                                  trip(R7, "M_SH", "2", 9 * 3600 + 30, S7)])
        with self.assertRaisesRegex(adt.DayTypesError, "duplicates .* on sat"):
            self.run_([d])

    def test_same_day_overtaking_fails(self):
        slow = trip(R7, "M_HO", "1", 9 * 3600, S7)
        fast = trip(R7, "M_HO", "2", 9 * 3600 + 120, S7)
        fast["stops"][-1] = [S7[-1], hms(9 * 3600 + 200), hms(9 * 3600 + 200)]
        for k in range(1, 4):
            fast["stops"][k] = [S7[k], hms(9 * 3600 + 120 + k * 10), hms(9 * 3600 + 120 + k * 10)]
        d = data_file(self.tmp / "ot.json", R7, [slow, fast])
        with self.assertRaisesRegex(adt.DayTypesError, "overtakes"):
            self.run_([d])

    def test_stop_list_without_template_fails(self):
        d = data_file(self.tmp / "nt.json", R7, [trip(R7, "M_SA", "1", 9 * 3600, [S7[0], S7[2]])])
        with self.assertRaisesRegex(adt.DayTypesError, "no trip of the route runs"):
            self.run_([d])

    def test_bad_service_or_prefix_or_empty_fails(self):
        bad_svc = trip(R7, "M_SA", "1", 9 * 3600, S7)
        bad_svc["service"] = "B1"
        for name, trips, msg in [("s", [bad_svc], "service B1"),
                                 ("p", [trip(R8, "M_SA", "1", 9 * 3600, S8)], "does not start with"),
                                 ("e", [], "no trips")]:
            with self.subTest(name), self.assertRaisesRegex(adt.DayTypesError, msg):
                self.run_([data_file(self.tmp / f"{name}.json", R7, trips)])

    def test_reapply_is_already_applied_with_same_bytes(self):
        self.run_()
        again = self.tmp / "again.gtfs.zip"
        rep = adt.run(self.out, [self.d7], self.hol, again, build_day=BUILD_DAY)
        self.assertEqual(rep["status"], "alreadyApplied")
        self.assertEqual(self.out.read_bytes(), again.read_bytes())

    def test_reapply_with_other_data_fails(self):
        self.run_()
        other = data_file(self.tmp / "o.json", R7, [trip(R7, "M_SA", "9999", 11 * 3600, S7)])
        with self.assertRaisesRegex(adt.DayTypesError, "missing or differs"):
            adt.run(self.out, [other], self.hol, self.tmp / "x.gtfs.zip", build_day=BUILD_DAY)

    def test_target_trip_not_on_base_service_fails(self):
        feed(self.src)
        with zipfile.ZipFile(self.src) as z:
            files = {n: z.read(n) for n in z.namelist()}
        files["trips.txt"] = files["trips.txt"].replace(f"{R7},B1,{R7}_Ord002".encode(), f"{R7},X1,{R7}_Ord002".encode())
        with zipfile.ZipFile(self.src, "w") as z:
            for n, b in files.items():
                z.writestr(n, b)
        with self.assertRaisesRegex(adt.DayTypesError, "on X1, not B1"):
            self.run_()


if __name__ == "__main__":
    unittest.main()
