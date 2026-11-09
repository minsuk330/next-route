"""Contracts of kric_timetable_trips.py (#557, lines outside Seoul 1-9).

Run (cwd tools/transit-routing-benchmark): python3 -m unittest runner/test_kric_timetable_trips.py -v
"""

from __future__ import annotations

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kric_timetable_trips as k  # noqa: E402
import apply_day_types as adt  # noqa: E402
import seoul_timetable_trips as stt  # noqa: E402

H = 3600


def row(**kw) -> dict:
    base = {c: "" for c in k.COLUMNS}
    base.update(노선명="대구도시철도 3호선", 노선번호="S2703", 운행유형="일반", 요일구분="평일", 데이터기준일자="45747")
    base.update(kw)
    return base


class ParseTimeTest(unittest.TestCase):
    def test_formats_and_placeholders(self):
        self.assertEqual(k.parse_time("5:26:10"), 5 * H + 26 * 60 + 10)
        self.assertEqual(k.parse_time("05:30"), 5 * H + 30 * 60)
        self.assertEqual(k.parse_time("0.22916666666666666"), 5 * H + 30 * 60)
        self.assertEqual(k.parse_time("1.5625E-2"), 22 * 60 + 30)          # 00:22:30
        self.assertEqual(k.parse_time("00:00"), 0)                          # a real time, not a placeholder
        for p in ("", "  :  ", ":", "운행시격 3분~10분"):
            self.assertIsNone(k.parse_time(p))
        self.assertEqual(k.excel_day("46173"), "2026-05-31")


class MultiRowTest(unittest.TestCase):
    def test_separators_and_placeholders(self):
        r = row(운행구간정거장="001-발곡+002-회룡+003-범골", 정가장출발시각="001-05:00/002-05:01/003-:")
        self.assertEqual(k.multi_stops(r), [("발곡", 5 * H, 5 * H), ("회룡", 5 * H + 60, 5 * H + 60), ("범골", None, None)])
        r = row(운행구간정거장="001-서울+002-인천공항1터미널", 정가장출발시각="001+06:00+002+06:45")
        self.assertEqual([s[1] for s in k.multi_stops(r)], [6 * H, 6 * H + 45 * 60])

    def test_reversed_departure_cell_uses_arrivals(self):
        r = row(운행구간정거장="001-a+002-b+003-c", 정거장도착시각="001-:+002-6:21+003-6:24",
                정가장출발시각="001-6:40+002-6:35+003-:")
        self.assertEqual([s[1] for s in k.multi_stops(r)][1:], [6 * H + 21 * 60, 6 * H + 24 * 60])

    def test_missing_station_name_stays_none(self):
        r = row(운행구간정거장="001-a+003-c", 정가장출발시각="001-05:00+002-05:02+003-05:04")
        self.assertEqual([s[0] for s in k.multi_stops(r)], ["a", None, "c"])


class UnrollTest(unittest.TestCase):
    def test_midnight_and_early_origin(self):
        out, _ = k.unroll([("a", 23 * H + 58 * 60, 23 * H + 58 * 60), ("b", 60, 60)])
        self.assertEqual([s[1] for s in out], [23 * H + 58 * 60, 24 * H + 60])
        out, _ = k.unroll([("a", 30 * 60, 30 * 60), ("b", 35 * 60, 35 * 60)])
        self.assertEqual(out[0][2], 24 * H + 30 * 60)  # origin before 03:00 → previous service day

    def test_one_typo_middle_stop_is_blanked(self):
        out, blanked = k.unroll([("a", 8 * H, 8 * H), ("b", 5 * H, 5 * H), ("c", 8 * H + 300, 8 * H + 300)])
        self.assertEqual((blanked, out[1][1]), (1, None))

    def test_other_backward_times_are_unusable(self):
        out, _ = k.unroll([("a", 8 * H, 8 * H), ("b", 7 * H, 7 * H), ("c", 6 * H, 6 * H), ("d", 5 * H, 5 * H)])
        self.assertIsNone(out)


class CollectTest(unittest.TestCase):
    def test_row_layout_groups_scattered_rows_and_splits_round_trips(self):
        def r(st, t, o="녹동", d="평동"):
            return row(노선명="광주1호선", 열차번호="1123", 운행구간정거장=st, 정거장도착시각=t, 정가장출발시각=t,
                       운행구간기점명=o, 운행구간종점명=d)
        rows = [r("녹동", "16:00:00"), row(노선명="광주1호선", 열차번호="9", 운행구간정거장="x", 정가장출발시각="07:00:00"),
                r("소태", "16:05:00"), r("녹동", "17:00:00"), r("소태", "17:05:00")]
        trains, _ = k.collect_trains(rows)
        got = sorted([s[0] for s in t["stops"]] for t in trains if t["trainNo"] == "1123")
        self.assertEqual(got, [["녹동", "소태"], ["녹동", "소태"]])

    def test_prose_rows_are_counted(self):
        _, st = k.collect_trains([row(노선명="인천지하철 2호선", 운행구간정거장="221-a,222-b", 정가장출발시각="운행시격 3분~10분")])
        self.assertEqual(st["proseRows"], {"인천지하철 2호선|평일": 1})


class XlsxTest(unittest.TestCase):
    def test_read_shared_and_inline_strings(self):
        tmp = Path(tempfile.mkdtemp()) / "t.xlsx"
        ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
        cols = "ABCDEFGHIJKLM"
        shared = list(k.COLUMNS) + ["광주1호선"]
        head = "".join(f'<c r="{cols[i]}1" t="s"><v>{i}</v></c>' for i in range(len(k.COLUMNS)))
        body = f'<c r="C2" t="s"><v>{len(k.COLUMNS)}</v></c><c r="I2"><v>0.25</v></c><c r="A2" t="inlineStr"><is><t>1123</t></is></c>'
        with zipfile.ZipFile(tmp, "w") as z:
            z.writestr("xl/sharedStrings.xml", f"<sst {ns}>" + "".join(f"<si><t>{x}</t></si>" for x in shared) + "</sst>")
            z.writestr("xl/worksheets/sheet1.xml", f'<worksheet {ns}><sheetData><row r="1">{head}</row>'
                                                   f'<row r="2">{body}</row></sheetData></worksheet>')
        rows = list(k.read_xlsx(tmp))
        self.assertEqual((rows[0]["노선명"], rows[0]["정거장도착시각"], rows[0]["열차번호"]), ("광주1호선", "0.25", "1123"))


# --------------------------------------------------------------------------- #
# build
# --------------------------------------------------------------------------- #

R_D, R_U = "RR_ACC3_S-3-03-1D", "RR_ACC3_S-3-03-1U"
IDS = [f"RS_ACC3_S-3-03-{i:03d}" for i in range(5)]
NAMES = {IDS[0]: "용지", IDS[1]: "범물", IDS[2]: "어린이회관", IDS[3]: "명덕(대구3호선)", IDS[4]: "칠곡경대병원"}
CFG = {"routes": [R_D, R_U], "days": {"평일": "M_WD", "토요일": "M_SA", "휴일": "M_HO"}}


def gtfs_trips(n=20):
    def trip(ids, dep):
        return dict(stops=[(s, dep + 120 * j, dep + 120 * j + (30 if 0 < j < len(ids) - 1 else 0))
                           for j, s in enumerate(ids)])
    return {R_D: {f"d{i}": trip(IDS, 6 * H + 600 * i) for i in range(n)},
            R_U: {f"u{i}": trip(list(reversed(IDS)), 6 * H + 600 * i) for i in range(n)}}


def kric_train(day, no, names, dep, terminal_missing=False):
    stops = []
    for j, n in enumerate(names):
        a = dep + 120 * j
        d = a + (30 if 0 < j < len(names) - 1 else 0)
        stops.append((n, None, None) if terminal_missing and j == len(names) - 1 else (n, a, d))
    return dict(line="대구도시철도 3호선", lineNo="S2703", day=day, trainNo=no, origin=names[0], dest=names[-1],
                kind="일반", baseDate="2025-03-31", blanked=0, stops=stops)


class BuildLineTest(unittest.TestCase):
    kric_names = ["용지", "범물", "어린이세상", "명덕3", "칠곡경대병원"]
    aliases = {"어린이세상": "어린이회관", "명덕3": "명덕(대구3호선)"}

    def trains(self, wd=20):
        out = [kric_train("평일", f"D{i}", self.kric_names, 6 * H + 600 * i) for i in range(wd)]
        out += [kric_train("평일", f"U{i}", list(reversed(self.kric_names)), 6 * H + 600 * i) for i in range(20)]
        out += [kric_train("토요일", "S1", self.kric_names, 7 * H), kric_train("휴일", "S1", self.kric_names, 7 * H),
                kric_train("토요일", "S2", self.kric_names, 8 * H), kric_train("휴일", "S2", self.kric_names, 8 * H + 300,
                                                                          terminal_missing=True),
                kric_train("휴일", "T1", list(reversed(self.kric_names)), 7 * H)]
        return out

    def test_aliases_gate_services_and_filled_terminal(self):
        rep, docs = k.build_line(self.trains(), CFG, gtfs_trips(), NAMES, self.aliases)
        self.assertTrue(rep["directions"]["D"]["passed"])
        d = docs[R_D]
        self.assertEqual([(t["tripId"], t["service"]) for t in d["trips"]],
                         [(f"{R_D}_SH_S1", "M_SH"), (f"{R_D}_SA_S2", "M_SA"), (f"{R_D}_HO_S2", "M_HO")])
        ho = d["trips"][2]
        self.assertTrue(ho["filled"])
        self.assertEqual(ho["stops"][-1], [IDS[4], "08:13:00", "08:13:00"])  # previous dep 08:11:30 + template 90 s
        self.assertEqual([t["tripId"] for t in docs[R_U]["trips"]], [f"{R_U}_HO_T1"])
        out = Path(tempfile.mkdtemp()) / "d.json"
        stt.write_day_data(out, d)
        self.assertEqual(adt.load_data(out)["tripCount"], 3)

    def test_unmapped_name_counts_against_the_gate(self):
        rep, docs = k.build_line(self.trains(), CFG, gtfs_trips(), NAMES, {"명덕3": "명덕(대구3호선)"})
        self.assertEqual(rep["unmappedNames"]["어린이세상"], 20 + 20 + 5)  # every train holding the name
        self.assertEqual(docs, {})

    def test_weekday_mismatch_fails_the_direction(self):
        rep, docs = k.build_line(self.trains(wd=15), CFG, gtfs_trips(), NAMES, self.aliases)
        self.assertFalse(rep["directions"]["D"]["passed"])
        self.assertNotIn(R_D, docs)

    def test_unknown_day_label_fails(self):
        t = self.trains() + [kric_train("명절", "X", self.kric_names, 9 * H)]
        with self.assertRaisesRegex(k.KricError, "명절"):
            k.build_line(t, CFG, gtfs_trips(), NAMES, self.aliases)

    def test_two_names_on_one_stop_fail_unless_typo(self):
        t = self.trains() + [kric_train("평일", "Z", ["용지", "범물", "어린이회관", "명덕3", "칠곡경대병원"], 20 * H)]
        with self.assertRaisesRegex(k.KricError, "two KRIC names"):
            k.build_line(t, CFG, gtfs_trips(), NAMES, self.aliases)
        k.build_line(t, CFG, gtfs_trips(), NAMES, self.aliases, typo_aliases={"어린이세상"})


class MapTrainTest(unittest.TestCase):
    smap = {k.norm_name(n): s for s, n in NAMES.items()} | {"기지사업소": None}
    seqs = [IDS]

    def test_non_revenue_stop_is_cut_and_a_depot_move_is_non_revenue(self):
        t = kric_train("평일", "1", ["기지사업소"] + [NAMES[i] for i in IDS[:3]], 6 * H)
        stops, bad, nonrev = k.map_train(t, self.smap, self.seqs)
        self.assertEqual(([s[0] for s in stops], bad, nonrev), (IDS[:3], [], False))
        _, _, nonrev = k.map_train(kric_train("평일", "2", ["기지사업소", "용지"], 6 * H), self.smap, self.seqs)
        self.assertTrue(nonrev)

    def test_unnamed_stops_fill_from_the_ktdb_sequence(self):
        t = kric_train("평일", "1", [NAMES[IDS[0]], None, None, NAMES[IDS[3]]], 6 * H)
        stops, _, _ = k.map_train(t, self.smap, self.seqs)
        self.assertEqual([s[0] for s in stops], IDS[:4])
        t = kric_train("평일", "1", [NAMES[IDS[0]], None, NAMES[IDS[3]]], 6 * H)  # one name for two stops
        self.assertIsNone(k.map_train(t, self.smap, self.seqs)[0])


class StrictlyIncreasingTest(unittest.TestCase):
    def test_minute_ties_move_30_s(self):
        got = k.strictly_increasing([("a", 0, 0), ("b", 0, 60), ("c", 60, 60), ("d", 60, 60)])
        self.assertEqual(got, [("a", 0, 0), ("b", 30, 60), ("c", 60, 60), ("d", 90, 90)])


if __name__ == "__main__":
    unittest.main()
