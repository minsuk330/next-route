"""Contracts of add_station_pathways.py.  Run: python3 -m unittest runner/test_add_station_pathways.py -v"""

from __future__ import annotations

import csv
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import add_station_pathways as ap  # noqa: E402

HDR = ["stop_id", "stop_code", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"]
LAT = 37.5


def lon(m: float) -> float:
    return 127.0 + m / 88300.0      # metres east at this latitude


def _csv(rows):
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


STOPS = [
    # station A: platform P1 with two exits (50 m and 300 m away), platform P2 (transfer partner) with one exit
    ["VST_P1", "", "가", LAT, lon(0), "1", ""],
    ["P1", "", "가(1호선)", LAT, lon(0), "0", "VST_P1"],
    ["VEX_n1_P1", "1", "가역 1번 출구", LAT, lon(50), "0", "VST_P1"],
    ["VEX_n2_P1", "2", "가역 2번 출구", LAT, lon(300), "0", "VST_P1"],
    ["VST_P2", "", "가", LAT, lon(200), "1", ""],
    ["P2", "", "가(2호선)", LAT, lon(200), "0", "VST_P2"],
    ["VEX_n1_P2", "1", "가역 1번 출구", LAT, lon(50), "0", "VST_P2"],
    # all-lines station of 가 with a copy of each platform and of the exits
    ["VSA_P1", "", "가", LAT, lon(0), "1", ""],
    ["VSA_P_P1", "", "가(1호선)", LAT, lon(0), "0", "VSA_P1"],
    ["VSA_P_P2", "", "가(2호선)", LAT, lon(200), "0", "VSA_P1"],
    ["VSA_X_n1_P1", "1", "가역 1번 출구", LAT, lon(50), "0", "VSA_P1"],
    # a platform with a station but no exits, a national rail platform without a station, a bus stop
    ["VST_P3", "", "나", LAT, lon(5000), "1", ""],
    ["P3", "", "나", LAT, lon(5000), "0", "VST_P3"],
    ["RS_TAGO_NAT1", "", "가", LAT, lon(100), "0", ""],
    ["BS_1", "", "가역앞", LAT, lon(60), "0", ""],
]
TRANSFERS = [["from_stop_id", "to_stop_id", "transfer_type", "min_transfer_time"],
             ["P1", "P2", "2", "150"], ["P2", "P1", "2", "150"],
             ["P1", "RS_TAGO_NAT1", "2", "240"], ["P2", "P3", "2", "30"]]


def write_feed(path: Path, extra: dict | None = None):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("stops.txt", _csv([HDR] + STOPS))
        z.writestr("transfers.txt", _csv(TRANSFERS))
        z.writestr("trips.txt", "route_id,service_id,trip_id\nR,B1,T1\n")
        for name, body in (extra or {}).items():
            z.writestr(name, body)


def read(zpath: Path, name: str):
    with zipfile.ZipFile(zpath) as z:
        return list(csv.DictReader(io.TextIOWrapper(z.open(name), encoding="utf-8-sig", newline="")))


class AddStationPathwaysTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.feed, self.out = self.tmp / "in.gtfs.zip", self.tmp / "out.gtfs.zip"
        write_feed(self.feed)
        self.summary = ap.run(self.feed, self.out, self.tmp / "report.json")
        self.stops = {r["stop_id"]: r for r in read(self.out, "stops.txt")}
        self.pw = {r["pathway_id"]: r for r in read(self.out, "pathways.txt")}

    def test_exit_time_follows_distance_with_a_floor(self):
        self.assertEqual(ap.exit_seconds(0), 90)
        self.assertEqual(ap.exit_seconds(20), 90)
        self.assertEqual(ap.exit_seconds(116), 176)      # Kakao: 116 m to the exit, 3 minutes
        self.assertEqual(ap.exit_seconds(320), 380)      # Kakao: 320 m, 6 minutes

    def test_every_exit_gets_an_entrance_with_a_pathway_to_its_platform(self):
        for ent, exit_id, platform, seconds in (("VEN_n1_P1", "VEX_n1_P1", "P1", 110), ("VEN_n2_P1", "VEX_n2_P1", "P1", 360),
                                                ("VEN_n1_P2", "VEX_n1_P2", "P2", 210)):
            e = self.stops[ent]
            self.assertEqual((e["location_type"], e["parent_station"]), ("2", "VST_" + platform))
            self.assertEqual((e["stop_lat"], e["stop_lon"], e["stop_name"]),
                             (self.stops[exit_id]["stop_lat"], self.stops[exit_id]["stop_lon"], self.stops[exit_id]["stop_name"]))
            p = self.pw["PW_" + ent]
            self.assertEqual((p["from_stop_id"], p["to_stop_id"], p["pathway_mode"], p["is_bidirectional"]), (ent, platform, "1", "1"))
            self.assertAlmostEqual(int(p["traversal_time"]), seconds, delta=2)
        self.assertEqual(self.summary["entrances"], 3)

    def test_exit_stops_and_all_other_stops_are_kept_as_they_were(self):
        before = read(self.feed, "stops.txt")
        self.assertEqual(read(self.out, "stops.txt")[:len(before)], before)

    def test_transfer_pair_with_entrances_on_both_sides_gets_one_pathway_with_the_feed_time(self):
        p = self.pw["PW_X_P1_P2"]
        self.assertEqual((p["from_stop_id"], p["to_stop_id"], p["traversal_time"], p["is_bidirectional"]), ("P1", "P2", "150", "1"))
        self.assertEqual(self.summary["transferPathways"], 1)

    def test_transfer_to_a_platform_without_exits_is_left_to_the_street(self):
        # national rail and the exit-less platform keep their street link; the walk goes through an exit
        self.assertFalse(any("RS_TAGO_NAT1" in k or "P3" in k for k in self.pw))
        self.assertEqual(self.summary["transferPairsWithOneSideOnStreet"], 2)
        self.assertFalse(any(k.startswith("VEN_") and k.endswith("P3") for k in self.stops))

    def test_all_lines_platform_copy_reaches_its_platform_in_one_second(self):
        for copy, platform in (("VSA_P_P1", "P1"), ("VSA_P_P2", "P2")):
            p = self.pw["PW_C_" + copy]
            self.assertEqual((p["from_stop_id"], p["to_stop_id"], p["traversal_time"]), (copy, platform, "1"))
        self.assertEqual(self.summary["allLinesCopyPathways"], 2)

    def test_other_files_are_copied_byte_for_byte(self):
        with zipfile.ZipFile(self.feed) as a, zipfile.ZipFile(self.out) as b:
            for name in ("transfers.txt", "trips.txt"):
                self.assertEqual(a.read(name), b.read(name))

    def test_feed_that_already_has_pathways_is_refused(self):
        with self.assertRaises(SystemExit):
            ap.run(self.out, self.tmp / "again.gtfs.zip")


if __name__ == "__main__":
    unittest.main()
