"""Contracts of apply_gbis_routes.py.  Run: python3 -m unittest runner/test_apply_gbis_routes.py -v"""

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
import apply_gbis_routes as ag  # noqa: E402

LAT = 37.5
DLON = 0.001  # ≈ 88 m at this latitude


def lon(k: float) -> float:
    return 127.0 + k * DLON


def out_and_back_line(n: int = 10) -> list[tuple[float, float]]:
    """East along one road for n steps, then back west on the same road (round trip)."""
    east = [(LAT, lon(k)) for k in range(n + 1)]
    return east + east[-2::-1]


class MonotoneProjectionTest(unittest.TestCase):
    def test_return_leg_stations_project_on_return_leg(self):
        line = ag.Line(out_and_back_line())
        half = line.length / 2
        # a stop passed twice at the same point is a tie either way, so the return stops differ
        pts = [ag._to_m(LAT, lon(k)) for k in (1, 5, 9)] + [ag._to_m(LAT, lon(k)) for k in (7, 4, 1)]
        meas, resid = ag.monotone_measures(line, pts)
        self.assertEqual(meas, sorted(meas))
        self.assertTrue(all(m < half for m in meas[:3]))
        self.assertTrue(all(m > half for m in meas[3:]))
        self.assertLess(max(resid), 1.0)


class TimeSynthesisTest(unittest.TestCase):
    def test_anchors_kept_and_new_stops_interpolated_by_distance(self):
        old_ids = ["A", "C", "E"]
        old_times = [(0, 0), (600, 630), (1200, 1200)]
        new_ids = ["A", "B", "C", "D", "E"]
        meas = [0.0, 100.0, 200.0, 300.0, 400.0]
        out = ag.synthesize_times(old_ids, old_times, new_ids, meas)
        self.assertEqual(out[0], (0, 0, True))
        self.assertEqual(out[2], (600, 630, True))
        self.assertEqual(out[4], (1200, 1200, True))
        self.assertEqual(out[1], (300, 300, False))
        self.assertEqual(out[3], (915, 915, False))

    def test_fully_reorganised_route_keeps_first_and_last_time(self):
        out = ag.synthesize_times(["X", "Y"], [(100, 100), (700, 700)], ["P", "Q", "R"], [0.0, 50.0, 300.0])
        self.assertEqual([t[0] for t in out], [100, 200, 700])
        self.assertEqual([t[2] for t in out], [True, False, True])

    def test_span_running_past_old_end_spreads_time_instead_of_bunching(self):
        # GBIS runs two stops further than the old trip's last stop "C"
        out = ag.synthesize_times(["A", "B", "C"], [(0, 0), (300, 300), (600, 600)],
                                  ["A", "B", "C", "D", "E"], [0.0, 100.0, 200.0, 300.0, 400.0])
        arr = [t[0] for t in out]
        self.assertEqual(arr, [0, 300, 400, 500, 600])
        self.assertEqual(len(set(arr)), 5)

    def test_anchor_matched_on_wrong_pass_is_dropped_by_speed(self):
        # "C" (old time 60 s) is matched 10 km into the span: 167 m/s → not an anchor
        out = ag.synthesize_times(["A", "C", "E"], [(0, 0), (60, 60), (1200, 1200)],
                                  ["A", "C", "E"], [0.0, 10000.0, 12000.0])
        self.assertEqual([t[2] for t in out], [True, False, True])
        self.assertEqual(out[1][0], 1000)

    def test_span_too_long_for_old_timetable_is_rejected(self):
        # 30 km in 10 minutes = 50 m/s
        self.assertIsNone(ag.synthesize_times(["A", "E"], [(0, 0), (600, 600)], ["A", "E"], [0.0, 30000.0]))

    def test_times_never_go_backwards(self):
        # "C" matches but its old time is after the trip's end → not an anchor, interpolated instead
        out = ag.synthesize_times(["A", "C", "E"], [(0, 0), (5000, 5000), (1000, 1000)],
                                  ["A", "C", "E"], [0.0, 1.0, 2.0])
        self.assertEqual(out, [(0, 0, True), (500, 500, False), (1000, 1000, True)])
        # last arrival before first departure → rejected outright
        self.assertIsNone(ag.synthesize_times(["A", "E"], [(900, 900), (100, 100)], ["A", "E"], [0.0, 1.0]))


def gbis_with(stations: list[tuple[str, float, str]], turn: int | None = None) -> ag.Gbis:
    line = ag.Line(out_and_back_line())
    sts = [{"stationId": sid, "x": lon(k), "y": LAT, "stationName": name, "turnYn": "Y" if i == turn else "N",
            "stationSeq": i + 1} for i, (sid, k, name) in enumerate(stations)]
    g = ag.Gbis(line=line, stations=sts, turn=turn)
    g.station_meas, g.station_resid = ag.monotone_measures(line, [ag._to_m(LAT, s["x"]) for s in sts])
    return g


class SpanTest(unittest.TestCase):
    ROUND = [("1", 0, "a"), ("2", 5, "b"), ("3", 10, "turn"), ("4", 5, "b'"), ("5", 0, "a'")]

    def test_ids_pick_span(self):
        g = gbis_with(self.ROUND, turn=2)
        self.assertEqual(ag.choose_span(g, None, ["2", "3", "4"], [None] * 3, False)[:2], (1, 3))

    def test_suffix_fallback_when_ids_and_coords_unknown(self):
        g = gbis_with(self.ROUND, turn=2)
        far = [ag._to_m(36.0, 126.0)] * 2
        self.assertEqual(ag.choose_span(g, "_U", ["x", "y"], far, False)[:2], (0, 2))
        self.assertEqual(ag.choose_span(g, "_D", ["x", "y"], far, False)[:2], (2, 4))
        self.assertEqual(ag.choose_span(g, None, ["x", "y"], far, True)[:2], (0, 4))
        self.assertIsNone(ag.choose_span(g, None, ["x", "y"], far, False))

    def test_recurring_station_does_not_stretch_span_past_trip_end(self):
        # "2" is served outbound and again inbound; the trip ends at its first visit
        g = gbis_with([("1", 0, "a"), ("2", 3, "b"), ("3", 10, "turn"), ("2", 3, "b"), ("5", 0, "a'")], turn=2)
        self.assertEqual(ag.choose_span(g, None, ["1", "2"], [None] * 2, False)[:2], (0, 1))

    def test_coordinate_fallback(self):
        g = gbis_with(self.ROUND, turn=2)
        near = [ag._to_m(LAT, lon(0)), ag._to_m(LAT, lon(10))]
        self.assertEqual(ag.choose_span(g, None, ["x", "y"], near, False)[:2], (0, 2))

    def test_round_trip_started_elsewhere_takes_the_whole_gbis_loop(self):
        # GTFS runs the loop from "4" round to "5"; GBIS lists it from "1" → ends match 3..4, a sliver
        g = gbis_with([("1", 0, "a"), ("2", 3, "b"), ("3", 10, "turn"), ("4", 5, "b'"), ("5", 0, "a'")], turn=2)
        old = ["4", "1", "2", "3", "5"]
        self.assertEqual(ag.choose_span(g, None, old, [None] * 5, True), (0, 4, "loopRotated"))
        # not a round trip → keep the matched span
        self.assertEqual(ag.choose_span(g, None, ["4", "5"], [None] * 2, False)[:2], (3, 4))

    def test_coordinate_fallback_prefers_nearest_station_over_any_within_radius(self):
        # a station 264 m away is inside the 300 m radius but must not cut the trip short
        g = gbis_with([("1", 0, "a"), ("2", 3, "b"), ("3", 10, "turn"), ("4", 5, "b'"), ("5", 0, "a'")], turn=2)
        start = ag._to_m(LAT, lon(0))
        self.assertEqual(ag.choose_span(g, None, ["x", "x2", "y"], [start, None, start], True)[:2], (0, 4))


def _csv(rows: list[list[str]]) -> str:
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


class EndToEndTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        gbis = self.tmp / "gbis"
        gbis.mkdir()
        line = [{"lineSeq": i + 1, "x": lo, "y": la} for i, (la, lo) in enumerate(out_and_back_line())]
        stations = [("200000111", 0, "기점"), ("200000112", 3, "새정류장"), ("200000998", 6, "IC(경유)"),
                    ("200000113", 10, "회차"), ("200000114", 5, "귀로"), ("200000111", 0, "기점")]
        sts = [{"stationId": int(s), "stationSeq": i + 1, "stationName": n, "x": lon(k), "y": LAT,
                "turnYn": "Y" if n == "회차" else "N"} for i, (s, k, n) in enumerate(stations)]
        ok = lambda key, items: {"response": {"msgHeader": {"resultCode": 0}, "msgBody": {key: items}}}  # noqa: E731
        (gbis / "200000001.line.json").write_text(json.dumps(ok("busRouteLineList", line)))
        (gbis / "200000001.stations.json").write_text(json.dumps(ok("busRouteStationList", sts), ensure_ascii=False))
        self.gbis = gbis
        self.inp = self.tmp / "in.zip"
        stops_hdr = ["stop_id", "stop_code", "stop_name", "stop_desc", "stop_lat", "stop_lon", "zone_id",
                     "stop_url", "location_type", "parent_station", "stop_timezone", "wheelchair_boarding",
                     "platform_code", "level_id"]
        stop = lambda sid, k: [sid, "", sid, "", f"{LAT + 0.0002:.6f}", f"{lon(k):.6f}", "", "", "0", "", "", "0", "", ""]  # noqa: E731
        st_hdr = ["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence", "stop_headsign",
                  "pickup_type", "drop_off_type", "shape_dist_traveled", "timepoint", "continuous_pickup",
                  "continuous_drop_off"]
        self.other_rows = [["BR_SEB_1_Ord001", "06:00:00", "06:00:00", "BS_SEB_9", "1", "", "0", "0", "0.0", "1", "", ""],
                           ["BR_SEB_1_Ord001", "06:10:00", "06:10:00", "BS_TAGO_GGB200000111", "2", "", "0", "0", "900.0", "1", "", ""]]
        with zipfile.ZipFile(self.inp, "w") as z:
            z.writestr("routes.txt", "route_id,route_short_name\nBR_TAGO_GGB200000001,1\nBR_SEB_1,1\n")
            z.writestr("trips.txt", _csv([["route_id", "service_id", "trip_id", "shape_id"],
                                          ["BR_TAGO_GGB200000001", "B1", "GG_T1", "old_gg"],
                                          ["BR_SEB_1", "B1", "BR_SEB_1_Ord001", "old_seb"]]))
            z.writestr("stops.txt", _csv([stops_hdr, stop("BS_TAGO_GGB200000111", 0), stop("BS_TAGO_GGB200000113", 10),
                                          stop("BS_SEB_9", 2)]))
            z.writestr("stop_times.txt", _csv([st_hdr,
                ["GG_T1", "07:00:00", "07:00:00", "BS_TAGO_GGB200000111", "1", "", "0", "0", "0.0", "1", "", ""],
                ["GG_T1", "07:20:00", "07:21:00", "BS_TAGO_GGB200000113", "2", "", "0", "0", "880.0", "1", "", ""],
                ["GG_T1", "07:40:00", "07:40:00", "BS_TAGO_GGB200000111", "3", "", "0", "0", "1760.0", "1", "", ""]]
                + self.other_rows))
            z.writestr("shapes.txt", _csv([["shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence", "shape_dist_traveled"],
                                           ["old_gg", str(LAT), str(lon(0)), "1", "0"], ["old_gg", str(LAT), str(lon(0.1)), "2", "9"],
                                           ["old_seb", str(LAT), str(lon(1)), "1", "0"], ["old_seb", str(LAT), str(lon(2)), "2", "88"]]))
            z.writestr("agency.txt", "agency_id\nA1\n")

    def test_rewrite(self):
        out, rep = self.tmp / "out.zip", self.tmp / "rep.json"
        summary = ag.run(self.inp, out, self.gbis, rep)
        self.assertEqual(summary["rewritten"], 1)
        z = zipfile.ZipFile(out)
        read = lambda n: list(csv.DictReader(io.TextIOWrapper(z.open(n), encoding="utf-8")))  # noqa: E731
        st = [r for r in read("stop_times.txt") if r["trip_id"] == "GG_T1"]
        # 경유 waypoint dropped, GBIS order, BS_TAGO_GGB<stationId> ids
        self.assertEqual([r["stop_id"][-3:] for r in st], ["111", "112", "113", "114", "111"])
        self.assertTrue(all(r["stop_id"].startswith("BS_TAGO_GGB") for r in st))
        # anchors keep times, first/last fixed
        self.assertEqual((st[0]["departure_time"], st[2]["arrival_time"], st[2]["departure_time"], st[-1]["arrival_time"]),
                         ("07:00:00", "07:20:00", "07:21:00", "07:40:00"))
        dists = [float(r["shape_dist_traveled"]) for r in st]
        self.assertEqual(dists, sorted(dists))
        self.assertEqual(dists[0], 0.0)
        # trip points at the new shape; old one removed, other shapes untouched
        trips = {r["trip_id"]: r["shape_id"] for r in read("trips.txt")}
        self.assertTrue(trips["GG_T1"].startswith("gbis_200000001_"))
        shapes = read("shapes.txt")
        ids = {r["shape_id"] for r in shapes}
        self.assertNotIn("old_gg", ids)
        self.assertIn("old_seb", ids)
        new_pts = [r for r in shapes if r["shape_id"] == trips["GG_T1"]]
        self.assertAlmostEqual(float(new_pts[-1]["shape_dist_traveled"]), dists[-1], delta=1.0)
        # non-Gyeonggi rows byte-identical
        raw = io.TextIOWrapper(z.open("stop_times.txt"), encoding="utf-8").read()
        for r in self.other_rows:
            self.assertIn(",".join(r) + "\n", raw)
        # new stations added; shared stop moved only within the limit; other stops untouched
        stops = {r["stop_id"]: r for r in read("stops.txt")}
        self.assertIn("BS_TAGO_GGB200000112", stops)
        self.assertEqual(stops["BS_TAGO_GGB200000112"]["stop_name"], "새정류장")
        self.assertNotIn("BS_TAGO_GGB200000998", stops)
        self.assertEqual(stops["BS_TAGO_GGB200000111"]["stop_lat"], f"{LAT:.6f}")  # moved ≈22 m (< limit)
        self.assertEqual(stops["BS_SEB_9"]["stop_lat"], f"{LAT + 0.0002:.6f}")

    def test_span_far_longer_than_old_trip_keeps_stops_and_swaps_shape_only(self):
        # old trip says it runs 10 m; the matched GBIS span is ~880 m → mis-chosen span
        old = ag.SPAN_LENGTH_SLACK_M
        ag.SPAN_LENGTH_SLACK_M = 0.0
        try:
            with zipfile.ZipFile(self.inp) as z:
                files = {n: z.read(n) for n in z.namelist()}
            st = files["stop_times.txt"].decode().replace(",880.0,", ",5.0,").replace(",1760.0,", ",10.0,")
            files["stop_times.txt"] = st.encode()
            with zipfile.ZipFile(self.inp, "w") as z:
                for n, b in files.items():
                    z.writestr(n, b)
            summary = ag.run(self.inp, self.tmp / "out.zip", self.gbis, self.tmp / "rep.json")
        finally:
            ag.SPAN_LENGTH_SLACK_M = old
        self.assertEqual((summary["rewritten"], summary["shapeOnly"], summary["spanLengthRejected"]), (0, 1, 1))
        z = zipfile.ZipFile(self.tmp / "out.zip")
        st = [r for r in csv.DictReader(io.TextIOWrapper(z.open("stop_times.txt"), encoding="utf-8"))
              if r["trip_id"] == "GG_T1"]
        self.assertEqual([r["stop_id"][-3:] for r in st], ["111", "113", "111"])  # stops kept

    def test_route_without_gbis_is_untouched(self):
        for f in self.gbis.iterdir():
            f.unlink()
        out, rep = self.tmp / "out.zip", self.tmp / "rep.json"
        summary = ag.run(self.inp, out, self.gbis, rep)
        self.assertEqual(summary["rewritten"], 0)
        self.assertEqual(summary["gbisMissingRoutes"], ["200000001"])
        z = zipfile.ZipFile(out)
        self.assertEqual(z.read("stop_times.txt"), zipfile.ZipFile(self.inp).read("stop_times.txt"))


if __name__ == "__main__":
    unittest.main()
