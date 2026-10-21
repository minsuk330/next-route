"""Contracts of extend_calendar.py.  Run (cwd tools/transit-routing-benchmark):
python3 -m unittest runner/test_extend_calendar.py -v
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
import extend_calendar as ec  # noqa: E402

CAL = "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" \
      "B1,1,1,1,1,1,1,1,20260729,20261130\nW1,1,1,1,1,1,0,0,20260729,20261031\n"
BUILD_DAY = date(2026, 10, 21)


class Feed:
    def __init__(self, calendar: str = CAL, calendar_dates: str | None = None, build: dict | None = None):
        self.tmp = tempfile.TemporaryDirectory()
        d = Path(self.tmp.name)
        self.inp, self.out, self.rep, self.cfg = d / "in.zip", d / "out.zip", d / "r.json", d / "build-config.json"
        with zipfile.ZipFile(self.inp, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("agency.txt", "agency_id,agency_name\nA1,a\n")
            z.writestr("calendar.txt", calendar)
            if calendar_dates is not None:
                z.writestr("calendar_dates.txt", calendar_dates)
            z.writestr("trips.txt", "route_id,service_id,trip_id\nR,B1,T1\n")
        self.cfg.write_text(json.dumps(build if build is not None else {"transitServiceStart": "-P1D", "transitServiceEnd": "P2M"}))

    def run(self, end: str, **kw) -> dict:
        kw.setdefault("build_config", self.cfg)
        kw.setdefault("build_day", BUILD_DAY)
        return ec.run(kw.pop("inp", self.inp), kw.pop("out", self.out), ec.parse_ymd(end), report_path=self.rep, **kw)

    def calendar(self, path: Path | None = None) -> str:
        with zipfile.ZipFile(path or self.out) as z:
            return z.read("calendar.txt").decode()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.tmp.cleanup()


class Extends(unittest.TestCase):
    def test_end_date_moves_and_report_has_old_and_new_ranges(self):
        with Feed() as f:
            rep = f.run("20261221")
            self.assertEqual(f.calendar(), CAL.replace("20261130", "20261221").replace("20261031", "20261221"))
            self.assertEqual(rep["services"][0], dict(serviceId="B1", before="20260729..20261130",
                                                      after="20260729..20261221", status="applied"))
            self.assertEqual(rep["graph"]["serviceDays"], "2026-10-20..2026-12-21")
            self.assertEqual(rep["graph"]["days"], 63)
            self.assertEqual(json.loads(f.rep.read_text())["end"], "20261221")

    def test_only_selected_services(self):
        with Feed() as f:
            f.run("20261221", services={"B1"})
            self.assertIn("W1,1,1,1,1,1,0,0,20260729,20261031", f.calendar())

    def test_other_members_unchanged_and_rerun_byte_identical(self):
        with Feed(calendar_dates="service_id,date,exception_type\nB1,20261009,2\n") as f:
            rep = f.run("20261221")
            with zipfile.ZipFile(f.inp) as a, zipfile.ZipFile(f.out) as b:
                for n in ("agency.txt", "trips.txt", "calendar_dates.txt"):
                    self.assertEqual(a.read(n), b.read(n), n)
            self.assertEqual(rep["calendarDates"], dict(rows=1, services=["B1"]))
            again = Path(f.tmp.name) / "again.zip"
            rep2 = f.run("20261221", inp=f.out, out=again)
            self.assertEqual(f.out.read_bytes(), again.read_bytes())
            self.assertEqual({s["status"] for s in rep2["services"]}, {"alreadyApplied"})


class Refuses(unittest.TestCase):
    def test_shortening(self):
        with Feed() as f, self.assertRaisesRegex(ec.CalendarError, "before the current end"):
            f.run("20261101")

    def test_end_past_otp_window(self):
        with Feed() as f, self.assertRaisesRegex(ec.CalendarError, "OTP keeps service days only through 2026-12-21"):
            f.run("20261222")
        self.assertFalse(f.out.exists())

    def test_service_only_in_calendar_dates(self):
        with Feed(calendar_dates="service_id,date,exception_type\nX9,20261009,1\n") as f, \
                self.assertRaisesRegex(ec.CalendarError, "X9"):
            f.run("20261221", services={"B1", "X9"})

    def test_without_build_config_no_otp_check(self):
        with Feed() as f:
            rep = f.run("20270331", build_config=None)
            self.assertIsNone(rep["graph"])
            self.assertIn("20270331", f.calendar())


class OtpPeriods(unittest.TestCase):
    def test_relative_and_absolute_values(self):
        self.assertEqual(ec.otp_date("-P1D", BUILD_DAY), date(2026, 10, 20))
        self.assertEqual(ec.otp_date("P2M", BUILD_DAY), date(2026, 12, 21))
        self.assertEqual(ec.otp_date("P2M", date(2026, 12, 31)), date(2027, 2, 28))  # month end clamps
        self.assertEqual(ec.otp_date("P1Y2M3D", date(2026, 1, 31)), date(2027, 4, 3))
        self.assertEqual(ec.otp_date("-P1Y", BUILD_DAY), date(2025, 10, 21))
        self.assertEqual(ec.otp_date("2027-03-31", BUILD_DAY), date(2027, 3, 31))
        with self.assertRaises(ec.CalendarError):
            ec.otp_date("PT1H", BUILD_DAY)

    def test_defaults_when_build_config_has_no_window(self):
        with Feed(build={}) as f:
            rep = f.run("20270331")
            self.assertEqual(rep["graph"]["otpWindow"], "2025-10-21..2029-10-21")

    def test_national_build_config_is_the_heap_guard(self):
        """The committed national build-config still caps the graph at two months from the build day."""
        cfg = Path(__file__).resolve().parents[1] / "config/otp-national/build-config.json"
        s, e, _, _ = ec.otp_window(cfg, BUILD_DAY)
        self.assertEqual((s, e), (date(2026, 10, 20), date(2026, 12, 21)))


if __name__ == "__main__":
    unittest.main()
