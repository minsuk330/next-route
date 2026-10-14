"""Contracts of fetch_seoul_section_times.py.  Run: python3 -m unittest runner/test_fetch_seoul_section_times.py -v"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_seoul_section_times as fst  # noqa: E402


def row(day: str, a: str, b: str, **hours: float) -> dict:
    r = {"CRTR_DD": day, "RTE_ID": "r", "DPTRE_STOPS_ID": a, "ARVL_STOPS_ID": b}
    for h in range(24):
        r[f"OPR_HR_{h:02d}"] = hours.get(f"h{h}", 0.0)
    return r


class CollectTest(unittest.TestCase):
    def run_pages(self, pages, n, excluded=frozenset()):
        orig = fst.fetch_page
        fst.fetch_page = lambda key, page, cache: pages[page - 1] if page <= len(pages) else []
        try:
            return fst.collect("k", n, set(excluded), None)
        finally:
            fst.fetch_page = orig

    def test_keeps_newest_full_weekdays_and_stops_paging(self):
        # 2026-10-07 Wed, 10-06 Tue, 10-04 Sun, 10-03 Sat, 10-02 Fri
        pages = [[row("20261007", "1", "2")], [row("20261006", "1", "2"), row("20261004", "1", "2")],
                 [row("20261003", "1", "2"), row("20261002", "1", "2")], [row("20261001", "1", "2")]]
        rows, days = self.run_pages(pages, 2)
        self.assertEqual(days, ["20261007", "20261006"])
        self.assertEqual({r["CRTR_DD"] for r in rows}, {"20261007", "20261006"})

    def test_excluded_dates_and_weekends_do_not_count(self):
        pages = [[row("20261007", "1", "2")], [row("20261006", "1", "2")], [row("20261004", "1", "2")],
                 [row("20261002", "1", "2")], [row("20261001", "1", "2")]]
        rows, days = self.run_pages(pages, 2, {"20261006"})
        self.assertEqual(days, ["20261007", "20261002"])

    def test_not_enough_days_fails(self):
        with self.assertRaises(SystemExit):
            self.run_pages([[row("20261007", "1", "2")]], 2)


class AggregateTest(unittest.TestCase):
    def test_hourly_median_and_all_row_ignore_zero_hours(self):
        rows = [row("20261007", "1", "2", h8=60, h14=40), row("20261006", "1", "2", h8=80, h14=50),
                row("20261006", "1", "2", h14=60)]
        t = {(a, b, h): (v, n) for a, b, h, v, n in fst.aggregate(rows)}
        self.assertEqual(t[("1", "2", "8")], (70.0, 2))
        self.assertEqual(t[("1", "2", "14")], (50.0, 3))
        self.assertEqual(t[("1", "2", "all")], (60.0, 5))
        self.assertNotIn(("1", "2", "9"), t)


if __name__ == "__main__":
    unittest.main()
