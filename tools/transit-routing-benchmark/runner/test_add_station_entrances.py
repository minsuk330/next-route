"""Contracts of add_station_entrances.py.  Run: python3 -m unittest runner/test_add_station_entrances.py -v"""

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
import add_station_entrances as ase  # noqa: E402

LAT = 37.5
DLON = 0.001  # ≈ 88 m at this latitude


def lon(k: float) -> float:
    return 127.0 + k * DLON


def platform(stop_id: str, name: str, k: float) -> ase.Platform:
    return ase.Platform(stop_id, name, LAT, lon(k))


class BaseNameTest(unittest.TestCase):
    def test_strips_line_and_station_suffix(self):
        self.assertEqual(ase.base_name("잠실(2호선)"), "잠실")
        self.assertEqual(ase.base_name("서울(지하)"), "서울")
        self.assertEqual(ase.base_name("서울역"), "서울")
        self.assertEqual(ase.base_name("역삼"), "역삼")

    def test_exit_label_variants(self):
        self.assertTrue(ase.label_names_station("삼성(무역센터)역 3번출구", "삼성"))
        self.assertTrue(ase.label_names_station("동대문역사문화공원 1번출구", "동대문역사문화공원"))
        self.assertFalse(ase.label_names_station("", "삼성"))
        self.assertFalse(ase.label_names_station("선릉역 1번출구", "삼성"))


class GroupingTest(unittest.TestCase):
    def test_same_name_within_radius_is_one_station(self):
        groups = ase.group_platforms([platform("RS_A", "잠실(2호선)", 0), platform("RS_B", "잠실(8호선)", 4)],
                                     [], 500, 1000)
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0].name, "잠실")

    def test_same_name_far_apart_stays_separate(self):
        # 양평(경의중앙선) and 양평(5호선) are tens of km apart
        groups = ase.group_platforms([platform("RS_A", "양평", 0), platform("RS_B", "양평", 100)], [], 500, 1000)
        self.assertEqual(len(groups), 2)

    def test_transfer_pair_joins_different_names_within_radius(self):
        groups = ase.group_platforms([platform("RS_A", "총신대입구", 0), platform("RS_B", "이수", 2)],
                                     [("RS_A", "RS_B")], 500, 1000)
        self.assertEqual(len(groups), 1)

    def test_transfer_pair_too_far_is_ignored(self):
        groups = ase.group_platforms([platform("RS_A", "가", 0), platform("RS_B", "나", 20)],
                                     [("RS_A", "RS_B")], 500, 1000)
        self.assertEqual(len(groups), 2)


class ExitAssignmentTest(unittest.TestCase):
    def setUp(self):
        self.groups = ase.group_platforms([platform("RS_A", "가", 0), platform("RS_B", "나", 6)], [], 500, 1000)

    def test_nearest_platform_wins(self):
        unmatched = ase.assign_exits(self.groups, [ase.Exit("n1", LAT, lon(1), "1", "")], 300)
        self.assertEqual(unmatched, [])
        self.assertEqual([e.osm_id for e in self.groups[0].exits], ["n1"])

    def test_label_naming_another_station_in_range_overrides_distance(self):
        # 2.8 steps (≈246 m) from 가, 3.2 (≈282 m) from 나: both within 300 m, but the label says 나
        ase.assign_exits(self.groups, [ase.Exit("n1", LAT, lon(2.8), "1", "나역 1번출구")], 300)
        self.assertEqual(self.groups[0].exits, [])
        self.assertEqual([e.osm_id for e in self.groups[1].exits], ["n1"])

    def test_exit_out_of_range_is_unmatched(self):
        unmatched = ase.assign_exits(self.groups, [ase.Exit("n9", LAT, lon(50), "1", "")], 300)
        self.assertEqual([e.osm_id for e in unmatched], ["n9"])


class StationRowsTest(unittest.TestCase):
    def test_one_station_per_metro_platform_with_all_exits(self):
        # a shared parent would put the traveller on every line's platform at once (2호선 with 0 walking)
        g = ase.group_platforms([platform("RS_ACC1_A", "잠실(2호선)", 0), platform("RS_ACC1_B", "잠실(8호선)", 4)],
                                [], 500, 1000)
        ase.assign_exits(g, [ase.Exit("n1", LAT, lon(1), "5", ""), ase.Exit("n2", LAT, lon(3), "9", "잠실역 9번출구")], 300)
        parent_of, rows = ase.station_rows(g, "RS_ACC1_")
        self.assertEqual(parent_of, {"RS_ACC1_A": "VST_RS_ACC1_A", "RS_ACC1_B": "VST_RS_ACC1_B"})
        by_id = {r["stop_id"]: r for r in rows}
        self.assertEqual(by_id["VST_RS_ACC1_A"]["location_type"], "1")
        exits_a = sorted(r["stop_id"] for r in rows if r["parent_station"] == "VST_RS_ACC1_A")
        self.assertEqual(exits_a, ["VEX_n1_RS_ACC1_A", "VEX_n2_RS_ACC1_A"])
        self.assertEqual(by_id["VEX_n1_RS_ACC1_A"]["location_type"], "0")  # plain stop, linked to the street
        self.assertEqual(by_id["VEX_n1_RS_ACC1_A"]["stop_code"], "5")
        self.assertEqual(by_id["VEX_n1_RS_ACC1_A"]["stop_name"], "잠실역 5번 출구")
        self.assertEqual(by_id["VEX_n2_RS_ACC1_B"]["stop_name"], "잠실역 9번출구")

    def test_station_without_exits_is_untouched(self):
        g = ase.group_platforms([platform("RS_ACC1_A", "가", 0)], [], 500, 1000)
        self.assertEqual(ase.station_rows(g, "RS_ACC1_"), ({}, []))

    def test_national_rail_platform_is_untouched(self):
        g = ase.group_platforms([platform("RS_ACC1_A", "서울(지하)", 0), platform("RS_TAGO_B", "서울", 1)], [], 500, 1000)
        ase.assign_exits(g, [ase.Exit("n1", LAT, lon(0.5), "1", "서울역 1번출구")], 300)
        parent_of, _ = ase.station_rows(g, "RS_ACC1_")
        self.assertEqual(set(parent_of), {"RS_ACC1_A"})


class AllLinesRowsTest(unittest.TestCase):
    def test_multi_platform_station_gets_all_lines_parent_with_copies(self):
        # "서울역" = any line, KTX included; the real platforms stay children of their VST_
        g = ase.group_platforms([platform("RS_ACC1_A", "서울(지하)", 0), platform("RS_ACC1_B", "서울(4호선)", 2),
                                 platform("RS_TAGO_K", "서울", 1)], [], 500, 1000)
        ase.assign_exits(g, [ase.Exit("n1", LAT, lon(0.5), "1", "서울역 1번출구")], 300)
        rows = {r["stop_id"]: r for r in ase.all_lines_rows(g, "RS_ACC1_")}
        self.assertEqual(rows["VSA_RS_ACC1_A"]["location_type"], "1")
        self.assertEqual(rows["VSA_RS_ACC1_A"]["stop_name"], "서울")
        children = sorted(k for k, r in rows.items() if r["parent_station"] == "VSA_RS_ACC1_A")
        self.assertEqual(children, ["VSA_P_RS_ACC1_A", "VSA_P_RS_ACC1_B", "VSA_P_RS_TAGO_K", "VSA_X_n1_RS_ACC1_A"])
        copy = rows["VSA_P_RS_ACC1_B"]
        self.assertEqual((copy["location_type"], copy["stop_name"]), ("0", "서울(4호선)"))
        self.assertEqual((copy["stop_lat"], copy["stop_lon"]), (f"{LAT:.7f}", f"{lon(2):.7f}"))  # the platform's point
        self.assertEqual(rows["VSA_X_n1_RS_ACC1_A"]["stop_code"], "1")

    def test_single_platform_station_has_no_all_lines_parent(self):
        # the app falls back to the platform's VST_, which means the same
        g = ase.group_platforms([platform("RS_ACC1_A", "역삼", 0)], [], 500, 1000)
        ase.assign_exits(g, [ase.Exit("n1", LAT, lon(0.5), "1", "")], 300)
        self.assertEqual(ase.all_lines_rows(g, "RS_ACC1_"), [])

    def test_station_without_exits_or_metro_has_no_all_lines_parent(self):
        no_exits = ase.group_platforms([platform("RS_ACC1_A", "가", 0), platform("RS_ACC1_B", "가", 1)], [], 500, 1000)
        self.assertEqual(ase.all_lines_rows(no_exits, "RS_ACC1_"), [])
        rail_only = ase.group_platforms([platform("RS_TAGO_A", "나", 0), platform("RS_TAGO_B", "나", 1)], [], 500, 1000)
        ase.assign_exits(rail_only, [ase.Exit("n1", LAT, lon(0.5), "1", "")], 300)
        self.assertEqual(ase.all_lines_rows(rail_only, "RS_ACC1_"), [])


class EndToEndTest(unittest.TestCase):
    def _feed(self, d: Path, stops_rows, transfers="") -> Path:
        src = d / "in.zip"
        buf = io.StringIO()
        w = csv.writer(buf, lineterminator="\n")
        w.writerow(["stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"])
        w.writerows(stops_rows)
        with zipfile.ZipFile(src, "w") as z:
            z.writestr("stops.txt", buf.getvalue())
            if transfers:
                z.writestr("transfers.txt", transfers)
            z.writestr("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nS1,37.5,127.0,1\n")
        return src

    def test_zip_rewrites_stops_and_copies_the_rest(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = self._feed(d, [["RS_ACC1_A", "잠실(2호선)", LAT, lon(0), "0", ""],
                                 ["RS_ACC1_B", "잠실(8호선)", LAT, lon(4), "0", ""],
                                 ["BS_1", "잠실역.롯데월드", LAT, lon(2), "0", ""]])
            exits = d / "entrances.csv"
            exits.write_text(f"# source=test\nosm_id,lat,lon,ref,label\nn1,{LAT},{lon(1)},5,잠실역 5번출구\n",
                             encoding="utf-8")
            out, rep = d / "out.zip", d / "report.json"
            report = ase.run(src, exits, out, rep)
            with zipfile.ZipFile(out) as z:
                self.assertEqual(z.read("shapes.txt").decode(),
                                 "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nS1,37.5,127.0,1\n")
                self.assertNotIn("pathways.txt", z.namelist())
                rows = {r["stop_id"]: r for r in csv.DictReader(io.StringIO(z.read("stops.txt").decode()))}
            self.assertEqual(rows["RS_ACC1_A"]["parent_station"], "VST_RS_ACC1_A")
            self.assertEqual(rows["BS_1"]["parent_station"], "")
            self.assertEqual(rows["VEX_n1_RS_ACC1_B"]["parent_station"], "VST_RS_ACC1_B")
            self.assertEqual(rows["VSA_P_RS_ACC1_A"]["parent_station"], "VSA_RS_ACC1_A")
            self.assertEqual(report["virtualStations"], 2)
            self.assertEqual(report["exitStops"], 2)
            self.assertEqual((report["allLineStations"], report["allLinePlatformCopies"], report["allLineExitCopies"]),
                             (1, 2, 1))
            self.assertEqual(report["metroPlatformsWithoutStation"], [])
            self.assertEqual(json.loads(rep.read_text(encoding="utf-8"))["groupsWithExits"], 1)

    def test_refuses_feed_that_already_has_parents(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = self._feed(d, [["RS_ACC1_A", "가", LAT, lon(0), "0", "P"]])
            exits = d / "e.csv"
            exits.write_text("osm_id,lat,lon,ref,label\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                ase.run(src, exits, d / "o.zip", None)

    def test_refuses_too_wide_station_group(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            # 8 steps (≈700 m) apart, joined by a transfer pair → one group wider than 600 m
            src = self._feed(d, [["RS_ACC1_A", "가", LAT, lon(0), "0", ""], ["RS_ACC1_B", "나", LAT, lon(8), "0", ""]],
                             "from_stop_id,to_stop_id,transfer_type,min_transfer_time\nRS_ACC1_A,RS_ACC1_B,2,300\n")
            exits = d / "e.csv"
            exits.write_text("osm_id,lat,lon,ref,label\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                ase.run(src, exits, d / "o.zip", None)


if __name__ == "__main__":
    unittest.main()
