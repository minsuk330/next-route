"""Contracts of add_missing_trips.py and seoul_timetable_trips.py (#385).

Run (cwd tools/transit-routing-benchmark): python3 -m unittest runner/test_add_missing_trips.py -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import add_missing_trips as amt  # noqa: E402
import seoul_timetable_trips as stt  # noqa: E402

ROUTE = "RR_ACC1_S-1-07-1D"
STOPS = [f"RS_ACC1_S-1-27{i:02d}" for i in range(11, 16)]  # 5 stops
ST_HEADER = "trip_id,arrival_time,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type,drop_off_type," \
            "shape_dist_traveled,timepoint"


def hms(t: int) -> str:
    return stt.fmt_hms(t)


def st_lines(tid: str, dep: int, stops: list[str], dist0: int = 0) -> list[str]:
    out, t = [], dep
    for k, s in enumerate(stops):
        pick, drop = ("0", "1") if k == 0 else ("1", "0") if k == len(stops) - 1 else ("0", "0")
        dwell = 30 if 0 < k < len(stops) - 1 else 0
        out.append(f"{tid},{hms(t)},{hms(t + dwell)},{s},{k + 1},,{pick},{drop},{float(1000 * (k + dist0))!r},1")
        t += dwell + 120
    return out


def feed(path: Path, extra_trips: list[tuple[str, str, int, list[str], str]] = ()) -> None:
    trips = [("RR_ACC1_S-1-07-1D_Ord001", ROUTE, 16 * 3600, STOPS, "shp_full"),
             ("RR_ACC1_S-1-07-1D_Ord002", ROUTE, 17 * 3600, STOPS, "shp_full"),
             ("RR_ACC1_S-1-07-1D_Ord003", ROUTE, 18 * 3600, STOPS[:3], "shp_short"),
             ("BUS_1_Ord001", "BUS_1", 8 * 3600, ["B1", "B2"], "shp_bus")] + list(extra_trips)
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("routes.txt", "route_id,agency_id,route_short_name,route_type\n"
                                 f"{ROUTE},A1,서울7호선,1\nBUS_1,A1,1,3\n")
        z.writestr("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,"
                                   "start_date,end_date\nB1,1,1,1,1,1,1,1,20260729,20261130\n")
        z.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\n" +
                   "".join(f"{s},{s},37.5,127.0\n" for s in STOPS + ["B1", "B2"]))
        z.writestr("trips.txt", "route_id,service_id,trip_id,shape_id\n" +
                   "".join(f"{r},B1,{t},{sh}\n" for t, r, _, _, sh in trips))
        z.writestr("stop_times.txt", ST_HEADER + "\n" +
                   "".join(ln + "\n" for t, _, d, s, _ in trips for ln in st_lines(t, d, s)))
        z.writestr("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence,shape_dist_traveled\n")


def data_file(path: Path, trips: list[tuple[str, int, list[str]]]) -> None:
    rows = []
    for tid, dep, stops in trips:
        t, stop_rows = dep, []
        for k, s in enumerate(stops):
            dwell = 30 if 0 < k < len(stops) - 1 else 0
            stop_rows.append([s, hms(t), hms(t + dwell)])
            t += dwell + 120
        rows.append(dict(tripId=tid, trainNo=tid[-4:], stops=stop_rows))
    path.write_text(json.dumps(dict(routeId=ROUTE, serviceId="B1", source={}, tripCount=len(rows), trips=rows)),
                    encoding="utf-8")


def read(path: Path, name: str) -> list[list[str]]:
    with zipfile.ZipFile(path) as z:
        return [ln.split(",") for ln in z.read(name).decode().splitlines()[1:]]


class AddMissingTripsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.src, self.data, self.out = self.tmp / "in.gtfs.zip", self.tmp / "d.json", self.tmp / "out.gtfs.zip"
        feed(self.src)

    def test_same_pattern_copies_template_and_appends(self):
        data_file(self.data, [(f"{ROUTE}_T7001", 6 * 3600, STOPS)])
        rep = amt.run(self.src, [self.data], self.out)
        self.assertEqual(rep["tripsAdded"], 1)
        trips = read(self.out, "trips.txt")
        self.assertEqual(trips[-1], [ROUTE, "B1", f"{ROUTE}_T7001", "shp_full"])
        st = [r for r in read(self.out, "stop_times.txt") if r[0] == f"{ROUTE}_T7001"]
        self.assertEqual([r[3] for r in st], STOPS)
        self.assertEqual(st[0][1:3], ["06:00:00", "06:00:00"])
        self.assertEqual([r[6:8] for r in (st[0], st[-1])], [["0", "1"], ["1", "0"]])
        self.assertEqual(rep["files"][0]["templateKinds"], {"same": 1})
        # existing rows untouched, byte for byte
        with zipfile.ZipFile(self.src) as a, zipfile.ZipFile(self.out) as b:
            self.assertTrue(b.read("stop_times.txt").startswith(a.read("stop_times.txt")))
            self.assertEqual(a.read("stops.txt"), b.read("stops.txt"))

    def test_slice_of_longer_trip_keeps_shape_dist_and_renumbers(self):
        data_file(self.data, [(f"{ROUTE}_T7003", 6 * 3600, STOPS[2:])])
        rep = amt.run(self.src, [self.data], self.out)
        st = [r for r in read(self.out, "stop_times.txt") if r[0] == f"{ROUTE}_T7003"]
        self.assertEqual([r[4] for r in st], ["1", "2", "3"])
        self.assertEqual([r[8] for r in st], ["2000.0", "3000.0", "4000.0"])
        self.assertEqual([r[6:8] for r in (st[0], st[-1])], [["0", "1"], ["1", "0"]])
        self.assertEqual(rep["files"][0]["trips"][0]["shape"], "shp_full")

    def test_rerun_is_already_applied_and_byte_identical(self):
        data_file(self.data, [(f"{ROUTE}_T7001", 6 * 3600, STOPS)])
        amt.run(self.src, [self.data], self.out)
        again = self.tmp / "again.gtfs.zip"
        rep = amt.run(self.out, [self.data], again)
        self.assertEqual((rep["tripsAdded"], rep["files"][0]["alreadyApplied"]), (0, 1))
        self.assertEqual(amt.sha256_file(self.out), amt.sha256_file(again))

    def test_duplicate_of_existing_trip_fails(self):
        data_file(self.data, [(f"{ROUTE}_T7001", 16 * 3600 + 30, STOPS)])
        with self.assertRaisesRegex(amt.AddTripsError, "duplicates existing trip"):
            amt.run(self.src, [self.data], self.out)

    def test_overtaking_fails(self):
        data_file(self.data, [(f"{ROUTE}_T7001", 6 * 3600, STOPS), (f"{ROUTE}_T7002", 6 * 3600 + 60, STOPS)])
        doc = json.loads(self.data.read_text())
        doc["trips"][1]["stops"][-1][1] = doc["trips"][1]["stops"][-1][2] = "06:09:00"  # T7001 arrives 06:09:30
        self.data.write_text(json.dumps(doc))
        with self.assertRaisesRegex(amt.AddTripsError, "overtakes"):
            amt.run(self.src, [self.data], self.out)

    def test_stop_not_on_route_and_bad_times_fail(self):
        data_file(self.data, [(f"{ROUTE}_T7001", 6 * 3600, STOPS[:2] + ["B1"])])
        with self.assertRaisesRegex(amt.AddTripsError, "stops not on"):
            amt.run(self.src, [self.data], self.out)
        data_file(self.data, [(f"{ROUTE}_T7001", 6 * 3600, STOPS)])
        doc = json.loads(self.data.read_text())
        doc["trips"][0]["stops"][2][1] = "05:00:00"
        self.data.write_text(json.dumps(doc))
        with self.assertRaisesRegex(amt.AddTripsError, "not increasing"):
            amt.load_data(self.data)

    def test_same_id_with_other_content_fails(self):
        data_file(self.data, [(f"{ROUTE}_T7001", 6 * 3600, STOPS)])
        amt.run(self.src, [self.data], self.out)
        data_file(self.data, [(f"{ROUTE}_T7001", 7 * 3600, STOPS)])
        with self.assertRaisesRegex(amt.AddTripsError, "already in trips.txt"):
            amt.run(self.out, [self.data], self.tmp / "x.gtfs.zip")


def row(no, code, arr, dep, origin="2711", dest="2713"):
    return dict(TRAIN_NO=no, STATION_CD=code, ARRIVETIME=arr, LEFTTIME=dep, ORIGINSTATION=origin, DESTSTATION=dest)


class ApiTrainsTest(unittest.TestCase):
    ORDER = ["2711", "2712", "2713"]

    def test_origin_terminal_and_midnight(self):
        tables = {"2711": [row("7999", "2711", "00:00:00", "23:58:00")],
                  "2712": [row("7999", "2712", "23:59:30", "00:00:30")],
                  "2713": [row("7999", "2713", "00:02:00", "00:00:00")]}
        trains, diag = stt.api_trains(tables, self.ORDER)
        self.assertEqual(diag["problems"], [])
        self.assertEqual([(c, stt.fmt_hms(a), stt.fmt_hms(d)) for c, a, d in trains["7999"]["stops"]],
                         [("2711", "23:58:00", "23:58:00"), ("2712", "23:59:30", "24:00:30"),
                          ("2713", "24:02:00", "24:02:00")])

    def test_early_origin_is_previous_service_day(self):
        tables = {"2711": [row("7001", "2711", "00:00:00", "00:10:00")],
                  "2712": [row("7001", "2712", "00:12:00", "00:12:30")],
                  "2713": [row("7001", "2713", "00:14:00", "00:00:00")]}
        trains, _ = stt.api_trains(tables, self.ORDER)
        self.assertEqual(stt.fmt_hms(trains["7001"]["stops"][0][2]), "24:10:00")

    def test_gap_is_a_problem_and_pass_row_is_skipped(self):
        tables = {"2711": [row("7001", "2711", "00:00:00", "06:00:00")],
                  "2712": [row("7001", "2712", "00:00:00", "06:02:00")],
                  "2713": [row("7001", "2713", "06:04:00", "00:00:00")]}
        trains, diag = stt.api_trains(tables, self.ORDER)
        self.assertNotIn("7001", trains)
        self.assertEqual(diag["skipped"]["noStopRows"], 1)
        self.assertIn("misses", diag["problems"][0])

    def test_match_pairs_nearest_same_od(self):
        gtfs = {"g1": dict(stops=[("RS_ACC1_S-1-2711", 100, 100), ("RS_ACC1_S-1-2713", 300, 300)])}
        trains = {"a": dict(origin="2711", dest="2713", stops=[("2711", 160, 160), ("2713", 360, 360)]),
                  "b": dict(origin="2711", dest="2713", stops=[("2711", 100, 100), ("2713", 300, 300)])}
        m = stt.match(gtfs, trains, lambda s: s[len(stt.STOP_PREFIX):], 300)
        self.assertEqual(m["pairs"], {"g1": "b"})
        self.assertEqual(m["stopDeltaSec"], [0, 0])


if __name__ == "__main__":
    unittest.main()
