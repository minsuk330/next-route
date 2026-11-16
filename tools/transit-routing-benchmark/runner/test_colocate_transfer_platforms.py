"""Contracts of colocate_transfer_platforms.py.  Run: python3 -m unittest runner/test_colocate_transfer_platforms.py -v"""

from __future__ import annotations

import csv
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import colocate_transfer_platforms as ctp  # noqa: E402

FIELDS = ["stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"]


def stop(stop_id: str, name: str, lat: float, lon: float, location_type: str = "0") -> dict[str, str]:
    return {"stop_id": stop_id, "stop_name": name, "stop_lat": f"{lat:.6f}", "stop_lon": f"{lon:.6f}",
            "location_type": location_type, "parent_station": ""}


def pair(a: str, b: str, seconds: int = 68, transfer_type: str = "2") -> list[dict[str, str]]:
    return [{"from_stop_id": x, "to_stop_id": y, "transfer_type": transfer_type, "min_transfer_time": str(seconds)}
            for x, y in ((a, b), (b, a))]


class ColocateTest(unittest.TestCase):
    def test_declared_pair_moves_onto_one_member(self):
        # 신도림 2호선 ↔ 1호선, 77 m apart (production coordinates)
        stops = [stop("P2", "신도림(2호선)", 37.508327, 126.891464), stop("P1", "신도림(1호선)", 37.509014, 126.891594),
                 stop("X", "구로(1호선)", 37.503, 126.882)]
        report = ctp.colocate(stops, pair("P2", "P1"), 500)
        self.assertEqual(report["moved_groups"], 1)
        self.assertEqual((stops[0]["stop_lat"], stops[0]["stop_lon"]), (stops[1]["stop_lat"], stops[1]["stop_lon"]))
        # a pair has no single medoid: the first stop id (P1 after sorting) keeps its place
        self.assertEqual((stops[0]["stop_lat"], stops[0]["stop_lon"]), ("37.509014", "126.891594"))
        self.assertEqual(stops[2]["stop_lat"], "37.503000")  # not in a pair: untouched
        self.assertEqual(report["moved"][0]["span_m"], 77)

    def test_three_platform_station_is_one_group(self):
        # 대곡: 경의중앙선 ↔ 3호선 ↔ GTX-A declared as two pairs
        stops = [stop("A", "대곡(경의중앙선)", 37.6316, 126.8110), stop("B", "대곡(3호선)", 37.6316, 126.8110),
                 stop("C", "대곡(GTX-A)", 37.6327, 126.8105)]
        ctp.colocate(stops, pair("A", "B") + pair("B", "C"), 500)
        self.assertEqual(len({(s["stop_lat"], s["stop_lon"]) for s in stops}), 1)

    def test_wide_group_moves_onto_the_middle_platform_not_the_centroid(self):
        # 디지털미디어시티: three platforms in a row, the middle one is the medoid
        stops = [stop("A", "디지털미디어시티(경의중앙선)", 37.5800, 126.9000), stop("B", "디지털미디어시티(6호선)", 37.5790, 126.9000),
                 stop("C", "디지털미디어시티(공항철도)", 37.5776, 126.9000)]
        ctp.colocate(stops, pair("A", "B") + pair("B", "C"), 500)
        self.assertEqual({(s["stop_lat"], s["stop_lon"]) for s in stops}, {("37.579000", "126.900000")})

    def test_already_colocated_pair_is_left_alone(self):
        stops = [stop("A", "왕십리(경의중앙선)", 37.5613, 127.0371), stop("B", "왕십리(수인분당선)", 37.5613, 127.0371)]
        report = ctp.colocate(stops, pair("A", "B"), 500)
        self.assertEqual((report["moved_groups"], report["already_colocated_groups"]), (0, 1))

    def test_generated_copies_follow_their_platform_but_exits_stay(self):
        stops = [stop("P2", "신도림(2호선)", 37.508327, 126.891464), stop("P1", "신도림(1호선)", 37.509014, 126.891594),
                 stop("VST_P2", "신도림", 37.508327, 126.891464, "1"), stop("VSA_P_P1", "신도림(1호선)", 37.509014, 126.891594),
                 stop("VEX_n1_P2", "신도림역 1번 출구", 37.509338, 126.890713)]
        ctp.colocate(stops, pair("P2", "P1"), 500)
        self.assertEqual(stops[2]["stop_lat"], stops[0]["stop_lat"])
        self.assertEqual(stops[3]["stop_lon"], stops[1]["stop_lon"])
        self.assertEqual(stops[4]["stop_lat"], "37.509338")

    def test_group_wider_than_the_limit_is_reported_not_moved(self):
        stops = [stop("A", "가", 37.50, 127.00), stop("B", "나", 37.51, 127.00)]  # ~1.1 km
        report = ctp.colocate(stops, pair("A", "B"), 500)
        self.assertEqual(report["moved_groups"], 0)
        self.assertEqual(report["skipped_groups"][0]["stops"], ["A", "B"])
        self.assertEqual(stops[0]["stop_lat"], "37.500000")

    def test_forbidden_transfer_and_non_platform_rows_do_not_group(self):
        stops = [stop("A", "가", 37.5000, 127.0), stop("B", "나", 37.5005, 127.0), stop("S", "역", 37.5002, 127.0, "1")]
        report = ctp.colocate(stops, pair("A", "B", transfer_type="3") + pair("A", "S"), 500)
        self.assertEqual(report["moved_groups"], 0)

    def test_only_listed_groups_move(self):
        stops = [stop("P2", "신도림(2호선)", 37.508327, 126.891464), stop("P1", "신도림(1호선)", 37.509014, 126.891594),
                 stop("J2", "잠실(2호선)", 37.5133, 127.1001), stop("J8", "잠실(8호선)", 37.5150, 127.1040)]
        report = ctp.colocate(stops, pair("P2", "P1") + pair("J2", "J8"), 500, only={"P1"})
        self.assertEqual(report["moved_groups"], 1)
        self.assertEqual(stops[0]["stop_lat"], stops[1]["stop_lat"])
        self.assertNotEqual(stops[2]["stop_lat"], stops[3]["stop_lat"])

    def test_zip_keeps_other_files_byte_for_byte(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp) / "in.zip", Path(tmp) / "out.zip"
            buffer = io.StringIO()
            writer = csv.DictWriter(buffer, fieldnames=FIELDS, lineterminator="\n")
            writer.writeheader()
            writer.writerows([stop("P2", "신도림(2호선)", 37.508327, 126.891464), stop("P1", "신도림(1호선)", 37.509014, 126.891594)])
            with zipfile.ZipFile(src, "w") as zf:
                zf.writestr("stops.txt", buffer.getvalue())
                zf.writestr("transfers.txt", "from_stop_id,to_stop_id,transfer_type,min_transfer_time\nP2,P1,2,68\nP1,P2,2,68\n")
                zf.writestr("trips.txt", "route_id,service_id,trip_id\nR,S,T\n")
            report = ctp.run(src, out, Path(tmp) / "report.json", 500)
            self.assertEqual(report["moved_groups"], 1)
            with zipfile.ZipFile(out) as zf:
                self.assertEqual(zf.read("trips.txt"), b"route_id,service_id,trip_id\nR,S,T\n")
                self.assertIn("P2,P1,2,68", zf.read("transfers.txt").decode())
                rows = list(csv.DictReader(io.StringIO(zf.read("stops.txt").decode())))
                self.assertEqual(rows[0]["stop_lat"], rows[1]["stop_lat"])


if __name__ == "__main__":
    unittest.main()
