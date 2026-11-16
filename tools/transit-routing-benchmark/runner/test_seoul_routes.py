"""Contracts of the Seoul bus refresh (apply_gbis_routes.py --provider seoul, add_seoul_routes.py).
Run: python3 -m unittest runner/test_seoul_routes.py -v"""

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
import add_seoul_routes as ar  # noqa: E402
import apply_gbis_routes as ag  # noqa: E402

LAT = 37.5


def lon(k: float) -> float:
    return 127.0 + k * 0.001   # ≈ 88 m per step


def _csv(rows):
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


def _answer(items, code="0"):
    return json.dumps({"msgHeader": {"headerCd": code}, "msgBody": {"itemList": items}}, ensure_ascii=False)


def write_source(d: Path, rid: str, stations, n: int = 10):
    """stations: (id, k, name, transYn).  Line runs east n steps and back (a round trip)."""
    ks = list(range(n + 1)) + list(range(n - 1, -1, -1))
    (d / f"{rid}.path.json").write_text(_answer(
        [{"no": str(i + 1), "gpsX": str(lon(k)), "gpsY": str(LAT)} for i, k in enumerate(ks)]))
    (d / f"{rid}.stations.json").write_text(_answer(
        [{"seq": str(i + 1), "station": sid, "stationNm": name, "gpsX": str(lon(k)), "gpsY": str(LAT), "transYn": t,
          "arsId": "0" if name.endswith("IC") else "01234"}
         for i, (sid, k, name, t) in enumerate(stations)]))


STOPS_HDR = ["stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "wheelchair_boarding"]
ROUTES_HDR = ["route_id", "agency_id", "route_short_name", "route_long_name", "route_type", "route_color"]
TRIPS_HDR = ["route_id", "service_id", "trip_id", "shape_id", "wheelchair_accessible", "bikes_allowed"]
ST_HDR = ["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence", "pickup_type", "drop_off_type",
          "shape_dist_traveled", "timepoint"]
SH_HDR = ["shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence", "shape_dist_traveled"]


def write_feed(path: Path, end_k: float = 0.2):
    """One Seoul round trip 100100001 calling at A(0) OLD(3) T(10) A2(end_k), and one Gyeonggi trip."""
    stops = [["BS_TAGO_SEB1", "옛이름A", LAT, lon(0), "0", "0"], ["BS_TAGO_SEBOLD", "없어진정류장", LAT, lon(3), "0", "0"],
             ["BS_TAGO_SEB9", "회차", LAT, lon(10), "0", "0"], ["BS_TAGO_SEB2", "종점", LAT, lon(end_k), "0", "0"],
             ["BS_TAGO_GGB7", "경기정류장", LAT, lon(50), "0", "0"], ["BS_TAGO_GGB8", "경기정류장2", LAT, lon(60), "0", "0"]]
    st = [["BR_TAGO_SEB100100001_Ord001", "06:00:00", "06:00:10", "BS_TAGO_SEB1", "1", "0", "0", "0.0", "1"],
          ["BR_TAGO_SEB100100001_Ord001", "06:03:00", "06:03:10", "BS_TAGO_SEBOLD", "2", "0", "0", "264", "1"],
          ["BR_TAGO_SEB100100001_Ord001", "06:10:00", "06:10:10", "BS_TAGO_SEB9", "3", "0", "0", "880", "1"],
          ["BR_TAGO_SEB100100001_Ord001", "06:20:00", "06:20:00", "BS_TAGO_SEB2", "4", "0", "0", "1742", "1"],
          ["GG_T1", "07:00:00", "07:00:00", "BS_TAGO_GGB7", "1", "0", "0", "0", "1"],
          ["GG_T1", "07:10:00", "07:10:00", "BS_TAGO_GGB8", "2", "0", "0", "880", "1"]]
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("agency.txt", "agency_id,agency_name\nA1,KTDB\n")
        z.writestr("calendar.txt", "service_id,monday,start_date,end_date\nB1,1,20260101,20271231\n")
        z.writestr("routes.txt", _csv([ROUTES_HDR, ["BR_TAGO_SEB100100001", "A1", "100", "-", "3", "0068B7"],
                                        ["BR_TAGO_GGB200000001", "A1", "77", "-", "3", "53B332"]]))
        z.writestr("trips.txt", _csv([TRIPS_HDR, ["BR_TAGO_SEB100100001", "B1", "BR_TAGO_SEB100100001_Ord001", "old1", "0", "0"],
                                       ["BR_TAGO_GGB200000001", "B1", "GG_T1", "gg1", "0", "0"]]))
        z.writestr("stops.txt", _csv([STOPS_HDR] + stops))
        z.writestr("stop_times.txt", _csv([ST_HDR] + st))
        z.writestr("shapes.txt", _csv([SH_HDR, ["old1", LAT, lon(0), "1", "0"], ["old1", LAT, lon(10), "2", "880"],
                                        ["gg1", LAT, lon(50), "1", "0"], ["gg1", LAT, lon(60), "2", "880"]]))


def read(zpath: Path, name: str):
    with zipfile.ZipFile(zpath) as z:
        return list(csv.DictReader(io.TextIOWrapper(z.open(name), encoding="utf-8-sig", newline="")))


class SeoulApplyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.src = self.tmp / "src"
        self.src.mkdir()
        self.addCleanup(ag.configure, "gbis")

    def test_loader_drops_pass_through_points_and_finds_the_turn(self):
        write_source(self.src, "100100001", [("1", 0, "A", "N"), ("277", 2, "어디(경유)", "N"), ("5", 4, "B(미정차)", "N"),
                                             ("9", 10, "회차", "Y"), ("2", 0.2, "종점", "N")])
        g = ag.load_seoul(self.src, "100100001")
        self.assertEqual([s["stationId"] for s in g.stations], ["1", "9", "2"])
        self.assertEqual(g.turn, 1)
        self.assertEqual(g.station_meas, sorted(g.station_meas))

    def test_loader_drops_points_without_a_stop_number_even_when_untagged(self):
        write_source(self.src, "100100002", [("1", 0, "A", "N"), ("X", 3, "북인천IC", "N"), ("9", 10, "회차", "Y")])
        self.assertEqual([s["stationId"] for s in ag.load_seoul(self.src, "100100002").stations], ["1", "9"])

    def test_first_station_nearer_the_loop_end_does_not_collapse_the_measures(self):
        # closed loop: east 10 steps and back; the first station sits just past the line's end point
        ks = list(range(11)) + list(range(9, -1, -1))
        (self.src / "100100003.path.json").write_text(_answer(
            [{"no": str(i + 1), "gpsX": str(lon(k)), "gpsY": str(LAT)} for i, k in enumerate(ks)]))
        st = [("1", -1.5, "터미널B1", "N"), ("5", 5, "가", "N"), ("9", 10, "회차", "Y"), ("2", 0, "터미널3층", "N")]
        (self.src / "100100003.stations.json").write_text(_answer(
            [{"seq": str(i + 1), "station": sid, "stationNm": n, "gpsX": str(lon(k)), "gpsY": str(LAT if sid != "1" else LAT + 0.0003),
              "transYn": t, "arsId": "01234"} for i, (sid, k, n, t) in enumerate(st)]))
        g = ag.load_seoul(self.src, "100100003")
        self.assertLess(g.station_meas[0], 1.0)
        self.assertGreater(g.station_meas[-1] - g.station_meas[0], 0.8 * g.line.length)
        self.assertEqual(g.station_meas, sorted(g.station_meas))

    def test_despike_removes_short_back_steps_and_keeps_a_real_turnaround(self):
        # east along a road with two link-join hooks (step back ~9 m, then on), a 90 degree corner,
        # and a real turnaround at the end (880 m out, 880 m back)
        line = [(LAT, lon(0)), (LAT, lon(3)), (LAT, lon(2.9)), (LAT, lon(6)), (LAT, lon(5.95)), (LAT, lon(5.9)),
                (LAT, lon(10)), (LAT + 0.004, lon(10)), (LAT, lon(10)), (LAT, lon(0))]
        out = ag.despike(line)
        self.assertEqual(len(out), len(line) - 3)            # one vertex per back step is dropped
        self.assertIn((LAT + 0.004, lon(10)), out)          # 440 m out and back: a turnaround, not a spike
        self.assertEqual((out[0], out[-1]), (line[0], line[-1]))
        xs = [p[1] for p in out[:out.index((LAT + 0.004, lon(10))) - 1]]
        self.assertEqual(xs, sorted(xs))                    # no back step is left on the way out
        self.assertEqual(ag.despike(out), out)              # idempotent

    def test_loader_returns_none_without_a_usable_answer(self):
        (self.src / "1.path.json").write_text(_answer([], code="4"))
        (self.src / "1.stations.json").write_text(_answer([], code="4"))
        self.assertIsNone(ag.load_seoul(self.src, "1"))
        self.assertIsNone(ag.load_seoul(self.src, "missing"))

    def test_seoul_trip_takes_official_stops_names_and_shape_and_other_trips_are_untouched(self):
        feed, out = self.tmp / "in.gtfs.zip", self.tmp / "out.gtfs.zip"
        write_feed(feed)
        # official: OLD is gone, NEW(5) and NEW2(7 on the way back) exist, A was renamed
        write_source(self.src, "100100001", [("1", 0, "새이름A", "N"), ("NEW", 5, "새정류장", "N"), ("9", 10, "회차", "Y"),
                                             ("NEW2", 7, "새정류장2", "N"), ("2", 0.2, "종점", "N")])
        ag.configure("seoul")
        s = ag.run(feed, out, self.src, self.tmp / "report.json")
        self.assertEqual((s["rewritten"], s["trips"]), (1, 1))
        st = [r for r in read(out, "stop_times.txt") if r["trip_id"] == "BR_TAGO_SEB100100001_Ord001"]
        self.assertEqual([r["stop_id"] for r in st],
                         ["BS_TAGO_SEB1", "BS_TAGO_SEBNEW", "BS_TAGO_SEB9", "BS_TAGO_SEBNEW2", "BS_TAGO_SEB2"])
        # first departure, the turn's time and the last arrival survive; new stops sit in between
        self.assertEqual(st[0]["departure_time"], "06:00:10")
        self.assertEqual(st[2]["arrival_time"], "06:10:00")
        self.assertEqual(st[-1]["arrival_time"], "06:20:00")
        times = [ag.parse_time(r["arrival_time"]) for r in st]
        self.assertEqual(times, sorted(times))
        stops = {r["stop_id"]: r for r in read(out, "stops.txt")}
        self.assertEqual(stops["BS_TAGO_SEB1"]["stop_name"], "새이름A")
        self.assertEqual(stops["BS_TAGO_SEBNEW"]["stop_name"], "새정류장")
        self.assertIn("BS_TAGO_SEBOLD", stops)          # other feeds may still use it; the trip no longer does
        self.assertEqual(s["stops"]["renamed"], 1)
        trips = {r["trip_id"]: r for r in read(out, "trips.txt")}
        self.assertTrue(trips["BR_TAGO_SEB100100001_Ord001"]["shape_id"].startswith("seoul_100100001_"))
        # the Gyeonggi trip is not this provider's: byte-for-byte the same rows
        self.assertEqual(trips["GG_T1"]["shape_id"], "gg1")
        self.assertEqual([r for r in read(out, "stop_times.txt") if r["trip_id"] == "GG_T1"],
                         [r for r in read(feed, "stop_times.txt") if r["trip_id"] == "GG_T1"])

    def test_trip_whose_stops_are_off_the_official_line_takes_the_whole_official_list(self):
        feed, out = self.tmp / "in.gtfs.zip", self.tmp / "out.gtfs.zip"
        write_feed(feed, end_k=20)      # a one-way trip: the round-trip fallback does not apply
        # the route was replaced: none of the old stop ids exist and the line runs 2 km to the north
        ks = list(range(11)) + list(range(9, -1, -1))
        (self.src / "100100001.path.json").write_text(_answer(
            [{"no": str(i + 1), "gpsX": str(lon(k)), "gpsY": str(LAT + 0.02)} for i, k in enumerate(ks)]))
        (self.src / "100100001.stations.json").write_text(_answer(
            [{"seq": str(i + 1), "station": sid, "stationNm": sid, "gpsX": str(lon(k)), "gpsY": str(LAT + 0.02),
              "transYn": "N", "arsId": "01234"} for i, (sid, k) in enumerate([("P", 0), ("Q", 5), ("R", 10), ("S", 0.3)])]))
        ag.configure("seoul")
        s = ag.run(feed, out, self.src, self.tmp / "report.json")
        self.assertEqual((s["rewritten"], s["shapeOnly"], s["spanHow"]), (1, 0, {"offLine": 1}))
        st = [r for r in read(out, "stop_times.txt") if r["trip_id"] == "BR_TAGO_SEB100100001_Ord001"]
        self.assertEqual([r["stop_id"] for r in st], ["BS_TAGO_SEBP", "BS_TAGO_SEBQ", "BS_TAGO_SEBR", "BS_TAGO_SEBS"])
        self.assertEqual((st[0]["departure_time"], st[-1]["arrival_time"]), ("06:00:10", "06:20:00"))

    def test_gbis_provider_still_ignores_seoul_routes(self):
        feed, out = self.tmp / "in.gtfs.zip", self.tmp / "out.gtfs.zip"
        write_feed(feed)
        ag.configure("gbis")
        s = ag.run(feed, out, self.src, self.tmp / "report.json")
        self.assertEqual(s["trips"], 1)                 # only the Gyeonggi trip is in scope
        self.assertEqual(read(out, "stop_times.txt"), read(feed, "stop_times.txt"))


class AddSeoulRoutesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.src = self.tmp / "src"
        self.src.mkdir()
        self.feed, self.out = self.tmp / "in.gtfs.zip", self.tmp / "out.gtfs.zip"
        write_feed(self.feed)
        self.addCleanup(ag.configure, "gbis")

    def run_tool(self, tsv: str):
        routes = self.tmp / "routes.tsv"
        routes.write_text(tsv)
        return ar.run(self.feed, self.out, self.src, routes, self.tmp / "add.json")

    def test_departures(self):
        self.assertEqual(ar.departures(6 * 3600, 7 * 3600, 30), [21600, 23400, 25200])
        self.assertEqual(ar.departures(6 * 3600, 6 * 3600, 0), [21600])
        self.assertEqual(ar.departures(23 * 3600, 1 * 3600, 60), [82800, 86400, 90000])   # wraps past midnight
        self.assertEqual(ar.hhmm("20260825054000"[8:12]), 5 * 3600 + 40 * 60)
        self.assertIsNone(ar.hhmm(":"))

    def test_missing_route_is_added_with_round_trips_and_new_stops(self):
        write_source(self.src, "100900009", [("1", 0, "A", "N"), ("N5", 5, "새정류장", "N"), ("9", 10, "회차", "Y"),
                                             ("2", 0.2, "종점", "N")])
        s = self.run_tool("100900009\t새노선\t3\t30\t0600\t0700\n")
        self.assertEqual((s["routesAdded"], s["tripsAdded"], s["stopsAdded"]), (1, 3, 1))
        routes = {r["route_id"]: r for r in read(self.out, "routes.txt")}
        self.assertEqual(routes["BR_TAGO_SEB100900009"]["route_short_name"], "새노선")
        self.assertEqual(routes["BR_TAGO_SEB100900009"]["agency_id"], "A1")
        trips = [r for r in read(self.out, "trips.txt") if r["route_id"] == "BR_TAGO_SEB100900009"]
        self.assertEqual([t["service_id"] for t in trips], ["B1"] * 3)
        st = [r for r in read(self.out, "stop_times.txt") if r["trip_id"] == "BR_TAGO_SEB100900009_Ord002"]
        self.assertEqual([r["stop_id"] for r in st], ["BS_TAGO_SEB1", "BS_TAGO_SEBN5", "BS_TAGO_SEB9", "BS_TAGO_SEB2"])
        self.assertEqual(st[0]["departure_time"], "06:30:00")
        t = [ag.parse_time(r["arrival_time"]) for r in st]
        self.assertEqual(t, sorted(t))
        self.assertGreater(t[-1] - t[0], 300)           # ~1.7 km at 16 km/h plus dwell
        self.assertEqual([r["timepoint"] for r in st], ["1", "0", "0", "0"])   # estimated times
        shape = [r for r in read(self.out, "shapes.txt") if r["shape_id"] == trips[0]["shape_id"]]
        self.assertGreater(len(shape), 2)
        self.assertAlmostEqual(float(shape[-1]["shape_dist_traveled"]), float(st[-1]["shape_dist_traveled"]), delta=1.0)
        # nothing that was there changed
        self.assertEqual(read(self.out, "stop_times.txt")[:6], read(self.feed, "stop_times.txt"))
        self.assertEqual(read(self.out, "stops.txt")[:6], read(self.feed, "stops.txt"))

    def test_route_already_in_feed_or_without_data_is_not_added(self):
        write_source(self.src, "100100001", [("1", 0, "A", "N"), ("9", 10, "회차", "Y")])
        s = self.run_tool("100100001\t100\t3\t10\t0600\t0700\n100900404\t자료없음\t3\t10\t0600\t0700\n")
        self.assertEqual((s["routesAdded"], s["alreadyInFeed"]), (0, 1))
        self.assertEqual(s["skipped"], {"자료없음": "no station list or line"})
        self.assertEqual(read(self.out, "trips.txt"), read(self.feed, "trips.txt"))

    def test_route_with_direction_suffix_in_feed_counts_as_present(self):
        rows = read(self.feed, "routes.txt")
        with zipfile.ZipFile(self.feed, "a") as z:
            pass
        feed2 = self.tmp / "in2.gtfs.zip"
        with zipfile.ZipFile(self.feed) as zin, zipfile.ZipFile(feed2, "w") as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                if info.filename == "routes.txt":
                    data += _csv([["BR_TAGO_SEB100100419_U", "A1", "6012", "공항출발", "3", "E60012"]]).encode()
                zout.writestr(info.filename, data)
        self.feed = feed2
        write_source(self.src, "100100419", [("1", 0, "A", "N"), ("9", 10, "회차", "Y"), ("2", 0.2, "종점", "N")])
        s = self.run_tool("100100419\t6012\t1\t20\t0500\t2300\n")
        self.assertEqual((s["routesAdded"], s["alreadyInFeed"]), (0, 1))
        self.assertEqual(len(rows), 2)


if __name__ == "__main__":
    unittest.main()
