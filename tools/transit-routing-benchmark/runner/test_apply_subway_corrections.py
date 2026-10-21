"""Contracts of apply_subway_corrections.py.

Run (cwd tools/transit-routing-benchmark): python3 -m unittest runner/test_apply_subway_corrections.py -v
"""

from __future__ import annotations

import copy
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apply_subway_corrections as asc  # noqa: E402

ST_HEADER = "trip_id,arrival_time,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type,drop_off_type," \
            "shape_dist_traveled,timepoint"
HOP, DWELL = 120, 30


def hms(t: int) -> str:
    return asc.format_time(t)


def t0(s: str) -> int:
    return asc.parse_time(s)


def line(prefix: str, n: int, lon0: float) -> list[tuple[str, float, float, float]]:
    """n metro stops 0.01° of longitude apart; shape points sit on the stops, shape_dist 1000 m apart."""
    return [(f"RS_ACC1_{prefix}{i}", 37.5, round(lon0 + 0.01 * i, 4), 1000.0 * i) for i in range(n)]


M = line("M", 5, 127.00)   # 경인 특급 → 급행 (retemplate)
N = line("N", 4, 127.10)   # 덕계 (insert_stop)
K = line("K", 5, 127.20)   # K5851 (truncate_after)
A = line("A", 4, 127.30)   # 공항철도 (assert_absent)
POS = {s: (lat, lon, d) for s, lat, lon, d in M + N + K + A}


def trip(tid: str, dep: str, stops: list[str], hops: list[int], seqs: list[int] | None = None) -> list[str]:
    """stop_times lines; first stop no drop-off, last stop no pickup, DWELL at intermediate stops."""
    t = t0(dep)
    seqs = seqs or list(range(1, len(stops) + 1))
    out = []
    for k, s in enumerate(stops):
        if k:
            t += hops[k - 1]
        arr = t
        dep_t = t + (DWELL if 0 < k < len(stops) - 1 else 0)
        pick, drop = ("0", "1") if k == 0 else ("1", "0") if k == len(stops) - 1 else ("0", "0")
        out.append(f"{tid},{hms(arr)},{hms(dep_t)},{s},{seqs[k]},,{pick},{drop},{POS[s][2]!r},1")
        t = dep_t
    return out


def ids(stops, *idx) -> list[str]:
    return [stops[i][0] for i in idx]


TRIPS = {  # trip_id -> (route_id, shape_id, stop_times lines)
    "T1": ("R4D", "shpM", trip("T1", "09:00:00", ids(M, 0, 1, 2, 3, 4), [HOP] * 4)),
    "T2": ("R4D", "shpM", trip("T2", "09:30:00", ids(M, 0, 1, 2, 3, 4), [HOP] * 4)),
    "X1": ("R4D", "shpMx", trip("X1", "09:15:00", ids(M, 0, 2, 4), [200, 200], [1, 3, 5])),
    "L1": ("R1D", "shpN", trip("L1", "08:00:00", ids(N, 0, 1, 2, 3), [HOP] * 3)),
    "E1": ("R1D", "shpN", trip("E1", "08:10:00", ids(N, 0, 2, 3), [200, HOP], [1, 3, 4])),
    "KL": ("RKJ", "shpK", trip("KL", "18:00:00", ids(K, 0, 1, 2), [HOP] * 2)),
    "KX": ("RKJ", "shpK", trip("KX", "18:10:00", ids(K, 0, 1, 3, 4), [HOP, 200, HOP], [1, 2, 4, 5])),
    "AP1": ("RAP", "shpA", trip("AP1", "05:00:00", ids(A, 0, 1, 2, 3), [HOP] * 3)),
    # bus: unnecessary quotes must survive byte for byte
    "BUS1": ("BR_1", "shpB", ['BUS1,07:00:00,07:00:00,"BS_1",1,,0,1,,1', 'BUS1,07:05:00,07:05:00,BS_2,2,,1,0,,1']),
}
ROUTES = "route_id,agency_id,route_short_name,route_long_name,route_type\n" \
         "R4D,A1,서울1호선,급행,1\nR1D,A1,서울1호선,경원,1\nRKJ,A1,경의중앙선,,1\nRAP,A1,공항철도,,1\n" \
         '"BR_1",A1,100,"버스, 노선",3\n'
STOPS_EXTRA = ["RS_ACC1_R1,당고개,37.6,127.0,0,VST_RS_ACC1_R1", "VST_RS_ACC1_R1,당고개,37.6,127.0,1,",
               "VEX_n1_RS_ACC1_R1,당고개역 1번 출구,37.6,127.0,0,VST_RS_ACC1_R1",
               "VEX_n2_RS_ACC1_R1,1번 출구,37.6,127.0,0,VST_RS_ACC1_R1",
               '"BS_1","당고개역",37.6,127.001,0,', "BS_2,버스2,37.6,127.002,0,"]


def corrections(**over) -> dict:
    ops = [
        dict(id="retemplate", op="retemplate", routeId="R4D", trips=["X1"], expectTrips=1, exhaustive=True,
             before=ids(M, 0, 2, 4), after=ids(M, 0, 1, 2, 3, 4)),
        dict(id="insert", op="insert_stop", routeId="R1D", trips=["E1"], expectTrips=1, exhaustive=True,
             stopId=N[1][0], between=ids(N, 0, 2), expectHopSec=200,
             expect=dict(arriveAfterPrevSec=HOP, dwellSec=DWELL, shiftFollowingSec=HOP + DWELL + HOP - 200)),
        dict(id="truncate", op="truncate_after", routeId="RKJ", trips=["KX"], expectTrips=1,
             keepThrough=K[1][0], beforeTail=ids(K, 1, 3, 4), terminal=dict(stopId=K[2][0]),
             expect=dict(arriveAfterPrevSec=HOP)),
        dict(id="rename", op="rename", stopId="RS_ACC1_R1", **{"from": "당고개", "to": "불암산"}),
        dict(id="absent", op="assert_absent", routeIds=["RAP"], routeShortName="공항철도",
             lineStops=ids(A, 0, 1, 2, 3)),
    ]
    doc = {"version": "test", "operations": ops}
    for op_id, changes in over.items():
        op = next(o for o in ops if o["id"] == op_id)
        op.update(changes)
    return doc


def write_feed(path: Path, trips: dict | None = None) -> None:
    trips = trips if trips is not None else TRIPS
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\nA1,a,http://a,Asia/Seoul\n")
        z.writestr("routes.txt", ROUTES)
        z.writestr("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,"
                                   "start_date,end_date\nB1,1,1,1,1,1,1,1,20260729,20261130\n")
        z.writestr("trips.txt", "route_id,service_id,trip_id,trip_headsign,shape_id\n" + "".join(
            (f'"{r}",B1,{t},,{s}\n' if r == "BR_1" else f"{r},B1,{t},,{s}\n") for t, (r, s, _) in trips.items()))
        z.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station\n"
                   + "".join(f"{s},{s[-2:]},{lat},{lon},0,\n" for s, lat, lon, _ in M + N + K + A)
                   + "\n".join(STOPS_EXTRA) + "\n")
        z.writestr("stop_times.txt", ST_HEADER + "\n" + "".join(l + "\n" for _, _, ls in trips.values() for l in ls))
        shapes = "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence,shape_dist_traveled\n"
        for sid, pts in (("shpM", M), ("shpMx", M), ("shpN", N), ("shpK", K), ("shpA", A)):
            shapes += "".join(f"{sid},{lat},{lon},{i + 1},{d!r}\n" for i, (_, lat, lon, d) in enumerate(pts))
        z.writestr("shapes.txt", shapes)


class Fixture:
    def __init__(self, doc: dict | None = None, trips: dict | None = None):
        self.tmp = tempfile.TemporaryDirectory()
        d = Path(self.tmp.name)
        self.inp, self.out, self.cfg, self.rep = d / "in.zip", d / "out.zip", d / "c.json", d / "r.json"
        write_feed(self.inp, trips)
        self.cfg.write_text(json.dumps(doc or corrections(), ensure_ascii=False), encoding="utf-8")

    def run(self, inp: Path | None = None, out: Path | None = None) -> dict:
        return asc.run(inp or self.inp, self.cfg, out or self.out, self.rep)

    def lines(self, name: str, zip_path: Path | None = None) -> list[str]:
        with zipfile.ZipFile(zip_path or self.out) as z:
            return z.read(name).decode("utf-8").splitlines()

    def trip_rows(self, tid: str, zip_path: Path | None = None) -> list[list[str]]:
        return [l.split(",") for l in self.lines("stop_times.txt", zip_path) if l.startswith(tid + ",")]


class AppliesOperations(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()
        self.rep = self.f.run()
        self.status = {o["id"]: o["status"] for o in self.rep["operations"]}

    def tearDown(self):
        self.f.tmp.cleanup()

    def test_every_op_reports_its_status(self):
        self.assertEqual(self.status, dict(retemplate="applied", insert="applied", truncate="applied",
                                           rename="applied", absent="checked"))

    def test_retemplate_copies_pattern_shape_and_offsets_keeps_first_departure(self):
        rows = self.f.trip_rows("X1")
        self.assertEqual([r[3] for r in rows], ids(M, 0, 1, 2, 3, 4))
        self.assertEqual(rows[0][2], "09:15:00")
        t1 = self.f.trip_rows("T1")  # nearest template (09:00 vs 09:30: tie broken to the earlier one)
        shift = t0("09:15:00") - t0("09:00:00")
        self.assertEqual([t0(r[1]) - shift for r in rows], [t0(r[1]) for r in t1])
        self.assertEqual([r[4] for r in rows], [r[4] for r in t1])  # stop_sequence
        self.assertEqual([r[8] for r in rows], [r[8] for r in t1])  # shape_dist_traveled
        trips = {l.split(",")[2]: l.split(",")[4] for l in self.f.lines("trips.txt")[1:]}
        self.assertEqual(trips["X1"], "shpM")
        applied = self.rep["operations"][0]["applied"][0]
        self.assertEqual((applied["template"], applied["shapeBefore"]), ("T1", "shpMx"))

    def test_insert_stop_uses_local_train_times_and_shape_position(self):
        rows = self.f.trip_rows("E1")
        self.assertEqual([r[3] for r in rows], ids(N, 0, 1, 2, 3))
        n1 = rows[1]
        self.assertEqual((n1[1], n1[2], n1[4], n1[6], n1[7]), ("08:12:00", "08:12:30", "2", "0", "0"))
        self.assertAlmostEqual(float(n1[8]), 1000.0, places=3)  # projected on shpN
        shift = HOP + DWELL + HOP - 200
        self.assertEqual(rows[2][1], hms(t0("08:10:00") + 200 + shift))
        self.assertEqual(rows[3][2], hms(t0("08:10:00") + 200 + DWELL + HOP + shift))

    def test_truncate_ends_at_new_terminal_with_final_stop_flags(self):
        rows = self.f.trip_rows("KX")
        self.assertEqual([r[3] for r in rows], ids(K, 0, 1, 2))
        last = rows[-1]
        self.assertEqual((last[1], last[2], last[4], last[6], last[7]),
                         (hms(t0(rows[1][2]) + HOP),) * 2 + ("3", "1", "0"))
        self.assertAlmostEqual(float(last[8]), 2000.0, places=3)

    def test_rename_covers_virtual_copies_and_keeps_ids(self):
        names = {l.split(",")[0]: l.split(",")[1] for l in self.f.lines("stops.txt")[1:]}
        self.assertEqual(names["RS_ACC1_R1"], "불암산")
        self.assertEqual(names["VST_RS_ACC1_R1"], "불암산")
        self.assertEqual(names["VEX_n1_RS_ACC1_R1"], "불암산역 1번 출구")
        self.assertEqual(names["VEX_n2_RS_ACC1_R1"], "1번 출구")
        self.assertEqual(names['"BS_1"'], '"당고개역"')  # not in the family, raw bytes

    def test_untouched_rows_and_files_are_byte_identical(self):
        def by_file(z: Path) -> dict[str, bytes]:
            with zipfile.ZipFile(z) as zf:
                return {n: zf.read(n) for n in zf.namelist()}
        a, b = by_file(self.f.inp), by_file(self.f.out)
        for name in ("agency.txt", "routes.txt", "calendar.txt", "shapes.txt"):
            self.assertEqual(a[name], b[name], name)
        changed = {"X1", "E1", "KX"}
        keep = lambda data: [l for l in data.decode().splitlines() if l.split(",")[0] not in changed]
        self.assertEqual(keep(a["stop_times.txt"]), keep(b["stop_times.txt"]))
        self.assertIn('BUS1,07:00:00,07:00:00,"BS_1",1,,0,1,,1', b["stop_times.txt"].decode())
        diff = [(x, y) for x, y in zip(a["trips.txt"].decode().splitlines(), b["trips.txt"].decode().splitlines()) if x != y]
        self.assertEqual(diff, [("R4D,B1,X1,,shpMx", "R4D,B1,X1,,shpM")])
        self.assertEqual(self.rep["changes"], dict(stopsRows=3, tripsRows=1, stopTimesTrips=3,
                                                   stopTimesRowsIn=3 + 3 + 4, stopTimesRowsOut=5 + 4 + 3))

    def test_rerun_on_output_is_already_applied_and_byte_identical(self):
        again = Path(self.f.tmp.name) / "again.zip"
        rep2 = self.f.run(inp=self.f.out, out=again)
        self.assertEqual(self.f.out.read_bytes(), again.read_bytes())
        self.assertEqual({o["id"]: o["status"] for o in rep2["operations"]},
                         dict(retemplate="alreadyApplied", insert="alreadyApplied", truncate="alreadyApplied",
                              rename="alreadyApplied", absent="checked"))
        self.assertEqual(rep2["changes"]["stopTimesTrips"], 0)

    def test_same_input_gives_same_bytes(self):
        again = Path(self.f.tmp.name) / "again.zip"
        self.f.run(out=again)
        self.assertEqual(self.f.out.read_bytes(), again.read_bytes())

    def test_trip_count_and_guards_reported(self):
        g = self.rep["guards"]
        self.assertEqual((g["trips"], g["stopTimesTrips"], g["overtaking"]), (len(TRIPS), len(TRIPS), 0))
        self.assertGreaterEqual(g["overtakingPairsChecked"], 3)


class Fails(unittest.TestCase):
    def run_doc(self, doc: dict, trips: dict | None = None) -> str:
        f = Fixture(doc, trips)
        try:
            with self.assertRaises(asc.CorrectionError) as cm:
                f.run()
            self.assertFalse(f.out.exists(), "no output on failure")
            return str(cm.exception)
        finally:
            f.tmp.cleanup()

    def test_expect_trips_mismatch(self):
        self.assertIn("expectTrips", self.run_doc(corrections(retemplate=dict(expectTrips=2))))

    def test_trip_in_neither_state(self):
        msg = self.run_doc(corrections(retemplate=dict(before=ids(M, 0, 1, 4))))
        self.assertIn("neither", msg)

    def test_exhaustive_catches_unlisted_trip(self):
        trips = dict(TRIPS, X2=("R4D", "shpMx", trip("X2", "09:45:00", ids(M, 0, 2, 4), [200, 200], [1, 3, 5])))
        self.assertIn("unlisted", self.run_doc(corrections(), trips))

    def test_overtaking_within_pattern(self):
        trips = dict(TRIPS)
        trips["T1"] = ("R4D", "shpM", trip("T1", "09:00:00", ids(M, 0, 1, 2, 3, 4), [900] * 4))  # slow
        trips["T2"] = ("R4D", "shpM", trip("T2", "09:20:00", ids(M, 0, 1, 2, 3, 4), [60] * 4))  # fast
        trips["X1"] = ("R4D", "shpMx", trip("X1", "09:01:00", ids(M, 0, 2, 4), [200, 200], [1, 3, 5]))
        self.assertIn("overtakes", self.run_doc(corrections(), trips))

    def test_derived_times_differ_from_expect(self):
        msg = self.run_doc(corrections(insert=dict(expect=dict(arriveAfterPrevSec=180, dwellSec=30,
                                                               shiftFollowingSec=60))))
        self.assertIn("derived", msg)

    def test_unexpected_hop(self):
        self.assertIn("takes 200 s", self.run_doc(corrections(insert=dict(expectHopSec=420))))

    def test_otp_feed_scoped_id_rejected(self):
        self.assertIn("OTP id", self.run_doc(corrections(retemplate=dict(trips=["1:X1"]))))

    def test_trip_listed_by_two_ops(self):
        self.assertIn("already changed", self.run_doc(corrections(insert=dict(trips=["X1"]))))

    def test_assert_absent_finds_skip_stop_trip(self):
        trips = dict(TRIPS, AP2=("RAP", "shpA", trip("AP2", "06:00:00", ids(A, 0, 3), [600])))
        self.assertIn("skip stops", self.run_doc(corrections(), trips))

    def test_stop_off_the_shape(self):
        trips = dict(TRIPS)
        trips["E1"] = ("R1D", "shpK", TRIPS["E1"][2])  # wrong shape: N1 is 10 km away
        self.assertIn("m from shpK", self.run_doc(corrections(), trips))

    def test_non_metro_route(self):
        doc = corrections(retemplate=dict(routeId="BR_1", trips=["BUS1"]))
        self.assertIn("not a metro route", self.run_doc(doc))


class DisabledOp(unittest.TestCase):
    def test_disabled_op_is_validated_but_not_applied(self):
        f = Fixture(corrections(truncate=dict(enabled=False, disabledReason="not identified")))
        try:
            rep = f.run()
            op = next(o for o in rep["operations"] if o["id"] == "truncate")
            self.assertEqual((op["status"], op["reason"]), ("disabled", "not identified"))
            self.assertEqual([r[3] for r in f.trip_rows("KX")], ids(K, 0, 1, 3, 4))
        finally:
            f.tmp.cleanup()


class RealConfig(unittest.TestCase):
    """config/otp-national/subway-corrections.json parses and matches the PLAN's trip counts (8/7/8/7/1)."""

    def test_config_is_valid(self):
        path = Path(__file__).resolve().parents[1] / "config/otp-national/subway-corrections.json"
        doc = asc.load_corrections(path)
        counts = [len(o["trips"]) for o in doc["operations"] if o["op"] in asc.TRIP_OPS]
        self.assertEqual(counts, [8, 7, 8, 7, 1])
        ops = {o["id"]: o for o in doc["operations"]}
        self.assertEqual(ops["gyeongwon-express-dukgye-down"]["between"],
                         list(reversed(ops["gyeongwon-express-dukgye-up"]["between"])))


if __name__ == "__main__":
    unittest.main()
