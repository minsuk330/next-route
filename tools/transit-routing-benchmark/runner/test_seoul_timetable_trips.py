"""Contracts of seoul_timetable_trips.py build-day-types (#557).

Run (cwd tools/transit-routing-benchmark): python3 -m unittest runner/test_seoul_timetable_trips.py -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import seoul_timetable_trips as stt  # noqa: E402
from test_add_missing_trips import ST_HEADER, st_lines  # noqa: E402

P = stt.STOP_PREFIX
CODES = ["2711", "2712", "2713", "2714", "2715"]
LOCAL_D, EXP_D = "RR_ACC1_S-1-09-1D", "RR_ACC1_S-1-09-2D"
LOCAL_U = "RR_ACC1_S-1-09-1U"


def train(no: str, codes: list[str], dep: int = 6 * 3600, express: bool = False) -> dict:
    return dict(trainNo=no, origin=codes[0], dest=codes[-1], express=express,
                stops=[(c, dep + 120 * k, dep + 120 * k) for k, c in enumerate(codes)])


class AssignRouteTest(unittest.TestCase):
    patterns = {LOCAL_D: {tuple(CODES), tuple(CODES[:3])}, EXP_D: {(CODES[0], CODES[2], CODES[4])}}

    def assign(self, t):
        return stt.assign_route(t, [LOCAL_D, EXP_D], self.patterns, {EXP_D})

    def test_same_stop_list_then_slice(self):
        self.assertEqual(self.assign(train("1", CODES)), (LOCAL_D, "same"))
        self.assertEqual(self.assign(train("2", CODES[1:4])), (LOCAL_D, "slice"))

    def test_express_goes_to_the_express_route(self):
        self.assertEqual(self.assign(train("3", [CODES[0], CODES[2], CODES[4]], express=True)), (EXP_D, "same"))

    def test_no_template(self):
        self.assertEqual(self.assign(train("4", [CODES[0], CODES[3]]))[0], None)

    def test_ambiguous_between_two_local_routes(self):
        pats = {LOCAL_D: {tuple(CODES)}, "R2": {tuple(CODES)}}
        rid, why = stt.assign_route(train("5", CODES[1:3]), [LOCAL_D, "R2"], pats, set())
        self.assertIsNone(rid)
        self.assertIn("ambiguous", why)


class WeekendEntriesTest(unittest.TestCase):
    def test_same_train_in_both_tables_is_one_sh_trip(self):
        sa = {"1": train("1", CODES), "2": train("2", CODES, 7 * 3600), "3": train("3", CODES, 8 * 3600)}
        ho = {"1": train("1", CODES), "2": train("2", CODES, 7 * 3600 + 60), "4": train("4", CODES, 9 * 3600)}
        got = [(s, t["trainNo"]) for s, t in stt.weekend_entries(sa, ho)]
        self.assertEqual(got, [("M_SH", "1"), ("M_SA", "2"), ("M_HO", "2"), ("M_SA", "3"), ("M_HO", "4")])


class ApiTrainsTest(unittest.TestCase):
    def rows(self, no, codes, express="G"):
        out = {}
        for k, c in enumerate(codes):
            t = f"06:{k * 2:02d}:00"
            out[c] = [dict(TRAIN_NO=no, ORIGINSTATION=codes[0], DESTSTATION=codes[-1], EXPRESS_YN=express,
                           ARRIVETIME="00:00:00" if k == 0 else t, LEFTTIME="00:00:00" if k == len(codes) - 1 else t)]
        return out

    def test_skipping_train_is_a_problem_unless_gaps_are_allowed(self):
        rows = self.rows("9001", [CODES[0], CODES[2], CODES[4]], express="D")
        trains, diag = stt.api_trains(rows, CODES)
        self.assertEqual(trains, {})
        self.assertTrue(diag["problems"])
        trains, _ = stt.api_trains(rows, CODES, allow_gaps=True)
        self.assertTrue(trains["9001"]["express"])
        self.assertEqual([s[0] for s in trains["9001"]["stops"]], [CODES[0], CODES[2], CODES[4]])

    def test_api_times_past_midnight_stay_on_the_same_service_day(self):
        rows = {c: [dict(TRAIN_NO="1", ORIGINSTATION=CODES[0], DESTSTATION=CODES[2], EXPRESS_YN="G",
                         ARRIVETIME=a, LEFTTIME=d)]
                for c, a, d in [(CODES[0], "00:00:00", "23:58:00"), (CODES[1], "24:00:30", "24:01:00"),
                                (CODES[2], "24:03:00", "00:00:00")]}
        trains, _ = stt.api_trains(rows, CODES)
        self.assertEqual([s[1] for s in trains["1"]["stops"]], [86280, 86430, 86580])


class LineOrderTest(unittest.TestCase):
    def gtfs(self, *lists):
        return {LOCAL_D: {f"t{k}": dict(stops=[(P + c, 0, 0) for c in s]) for k, s in enumerate(lists)}}

    def test_loop_and_branch_fail(self):
        with self.assertRaisesRegex(stt.TimetableError, "twice"):
            stt.line_order(self.gtfs(CODES + [CODES[0]]), [LOCAL_D])
        with self.assertRaisesRegex(stt.TimetableError, "branch"):
            stt.line_order(self.gtfs(CODES, CODES[:2] + ["9999"]), [LOCAL_D])

    def test_express_route_set(self):
        g = {LOCAL_D: self.gtfs(CODES)[LOCAL_D],
             EXP_D: {"e": dict(stops=[(P + c, 0, 0) for c in (CODES[0], CODES[2], CODES[4])])}}
        self.assertEqual(stt.express_route_set(g, [LOCAL_D, EXP_D], CODES), {EXP_D})


# --------------------------------------------------------------------------- #
# end to end: fake cache + fake GTFS
# --------------------------------------------------------------------------- #

def write_cache(cache: Path, tables: dict[tuple[str, str], list[tuple[str, list[str], int]]]) -> None:
    """tables[(week, inout)] = [(train_no, codes in travel order, first departure)]; 2 min per stop, no dwell."""
    cache.mkdir(parents=True, exist_ok=True)
    (cache / "stations.json").write_text(json.dumps({"SearchSTNBySubwayLineInfo": {"row": [
        dict(LINE_NUM="09호선", STATION_CD=c, FR_CODE=f"9{k:02d}") for k, c in enumerate(CODES)]}}), encoding="utf-8")
    for w in ("1", "2", "3"):
        for i in ("1", "2"):
            by_code: dict[str, list[dict]] = {c: [] for c in CODES}
            for no, codes, dep in tables.get((w, i), []):
                for k, c in enumerate(codes):
                    t = stt.fmt_hms(dep + 120 * k)
                    by_code[c].append(dict(TRAIN_NO=no, ORIGINSTATION=codes[0], DESTSTATION=codes[-1],
                                           EXPRESS_YN="G", ARRIVETIME="00:00:00" if k == 0 else t,
                                           LEFTTIME="00:00:00" if k == len(codes) - 1 else t))
            for c, rows in by_code.items():
                (cache / f"tt_{c}_{w}_{i}.json").write_text(json.dumps(
                    {"SearchSTNTimeTableByIDService": {"list_total_count": len(rows), "row": rows}}), encoding="utf-8")


def write_gtfs(path: Path, trips: list[tuple[str, str, int, list[str]]]) -> None:
    def lines(tid, dep, codes):
        out = []
        for k, c in enumerate(codes):
            t = stt.fmt_hms(dep + 120 * k)
            out.append(f"{tid},{t},{t},{P}{c},{k + 1},,0,0,{1000 * k}.0,1")
        return out
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("trips.txt", "route_id,service_id,trip_id,shape_id\n" +
                   "".join(f"{r},B1,{t},s\n" for t, r, _, _ in trips))
        z.writestr("stop_times.txt", ST_HEADER + "\n" +
                   "".join(ln + "\n" for t, _, d, c in trips for ln in lines(t, d, c)))


class BuildDayTypesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.cache, self.zip = self.tmp / "cache", self.tmp / "f.gtfs.zip"
        up = list(reversed(CODES))
        wd_d = [(f"D{k}", CODES, 6 * 3600 + 600 * k) for k in range(20)]
        wd_u = [(f"U{k}", up, 6 * 3600 + 600 * k) for k in range(20)]
        write_gtfs(self.zip, [(f"{LOCAL_D}_Ord{k:03d}", LOCAL_D, d, c) for k, (_, c, d) in enumerate(wd_d)] +
                   [(f"{LOCAL_U}_Ord{k:03d}", LOCAL_U, d, c) for k, (_, c, d) in enumerate(wd_u)])
        # INOUT 2 = KTDB D here (the tool must find it); weekend: one train differs Sat vs holiday
        self.tables = {("1", "2"): wd_d, ("1", "1"): wd_u,
                       ("2", "2"): [("S1", CODES, 7 * 3600), ("S2", CODES, 8 * 3600)],
                       ("3", "2"): [("S1", CODES, 7 * 3600), ("S2", CODES, 8 * 3600 + 300)],
                       ("2", "1"): [("T1", up, 7 * 3600)], ("3", "1"): [("T1", up, 7 * 3600)]}
        write_cache(self.cache, self.tables)

    def build(self, **kw):
        return stt.build_day_types(self.cache, self.zip, "09호선", [LOCAL_D, LOCAL_U], "2026-11-09", **kw)

    def test_pairing_gate_and_trip_ids(self):
        rep, docs = self.build()
        self.assertEqual(rep["directions"]["D"]["inoutTag"], "2")
        self.assertEqual(rep["directions"]["U"]["inoutTag"], "1")
        self.assertTrue(rep["directions"]["D"]["passed"])
        d = docs[LOCAL_D]
        self.assertEqual([(t["tripId"], t["service"]) for t in d["trips"]],
                         [(f"{LOCAL_D}_SH_S1", "M_SH"), (f"{LOCAL_D}_SA_S2", "M_SA"), (f"{LOCAL_D}_HO_S2", "M_HO")])
        self.assertEqual(d["tripCount"], 3)
        self.assertEqual(d["trips"][0]["stops"][0], [P + CODES[0], "07:00:00", "07:00:00"])
        self.assertEqual([t["tripId"] for t in docs[LOCAL_U]["trips"]], [f"{LOCAL_U}_SH_T1"])

    def test_weekday_mismatch_fails_the_gate_and_writes_no_file(self):
        self.tables[("1", "2")] = self.tables[("1", "2")][:15]  # 15 of 20 KTDB trips in the API
        write_cache(self.cache, self.tables)
        rep, docs = self.build()
        self.assertFalse(rep["directions"]["D"]["passed"])
        self.assertNotIn(LOCAL_D, docs)
        self.assertIn(LOCAL_U, docs)

    def test_unmatched_weekend_trains_fail_the_gate(self):
        self.tables[("2", "2")] = self.tables[("2", "2")] + [("X", [CODES[0], CODES[2]], 9 * 3600)]
        write_cache(self.cache, self.tables)
        rep, docs = self.build()
        self.assertEqual([(u["trainNo"], u["reason"]) for u in rep["directions"]["D"]["unmatched"]], [("X", "noTemplate")])
        self.assertFalse(rep["directions"]["D"]["passed"])
        self.assertNotIn(LOCAL_D, docs)

    def test_feed_after_step_2h_is_refused(self):
        with zipfile.ZipFile(self.zip) as z:
            files = {n: z.read(n) for n in z.namelist()}
        files["trips.txt"] = files["trips.txt"].replace(b"B1,RR_ACC1_S-1-09-1D_Ord001", b"M_WD,RR_ACC1_S-1-09-1D_Ord001")
        with zipfile.ZipFile(self.zip, "w") as z:
            for n, b in files.items():
                z.writestr(n, b)
        with self.assertRaisesRegex(stt.TimetableError, "before step 2h"):
            self.build()

    def test_data_file_loads_in_apply_day_types(self):
        import apply_day_types as adt
        _, docs = self.build()
        out = self.tmp / f"{LOCAL_D}.json"
        stt.write_day_data(out, docs[LOCAL_D])
        self.assertEqual(adt.load_data(out)["tripCount"], 3)


if __name__ == "__main__":
    unittest.main()
