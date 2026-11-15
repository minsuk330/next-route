"""Contracts of apply_route_colors.py and the subway table in config/otp-national/route-colors.json.

Run (cwd tools/transit-routing-benchmark): python3 -m unittest runner/test_apply_route_colors.py -v

The subway colors go into the graph as GTFS route_color, and the server hands them to the app unchanged
(`routeDisplayColor`, journey `legs[].displayColor`). #575: the table had been copied from an old app table
that was not the official palette, so every capital-area line was slightly off. The values below are the
official ones from ko.wikipedia "틀:한국 철도 노선색" (the Seoul standard line colors), checked against
the template source on 2026-11-15.
"""

from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apply_route_colors as arc  # noqa: E402

COLORS = Path(__file__).resolve().parent.parent / "config" / "otp-national" / "route-colors.json"

OFFICIAL = {
    "서울1호선": "0052A4",
    "서울2호선": "00A84D",
    "서울3호선": "EF7C1C",
    "서울4호선": "00A5DE",
    "서울5호선": "996CAC",
    "서울6호선": "CD7C2F",
    "서울7호선": "747F00",
    "서울8호선": "E6186C",
    "서울9호선": "BDB092",
    "인천1호선": "7CA8D5",
    "인천2호선": "ED8B00",
    "공항철도": "0090D2",
    "경춘선": "0C8E72",
    "경의중앙선": "77C4A3",
    "신분당선": "D4003B",
    "수인분당선": "F5A200",
    "서해선": "81A914",
    "GTX-A": "9A6292",
    "김포도시철도": "A17800",
    "신림선": "6789CA",
    "우이신설경전철": "B0CE18",
    "의정부경전철": "FDA600",
    "용인경전철": "509F22",
    "경강선": "003DA5",
    "부산1호선": "F06A00",
    "부산2호선": "81BF48",
    "부산3호선": "BB8C00",
    "부산4호선": "217DCB",
    "부산김해경전철": "8652A1",
    "대구1호선": "D93F5C",
    "대구2호선": "00AA80",
    "대구3호선": "FFB100",
    "광주1호선": "009088",
    "대전1호선": "007448",
}


class SubwayTable(unittest.TestCase):
    def setUp(self) -> None:
        self.colors = json.loads(COLORS.read_text(encoding="utf-8"))

    def test_lines_use_the_official_palette(self) -> None:
        subway = self.colors["subway"]
        mismatched = {name: (subway.get(name), color) for name, color in OFFICIAL.items()
                      if (subway.get(name) or "").upper() != color}
        self.assertEqual({}, mismatched, "route-colors.json subway (now, official)")

    def test_every_subway_color_is_six_hex_digits_without_hash(self) -> None:
        bad = {name: color for name, color in self.colors["subway"].items()
               if not re.fullmatch(r"[0-9A-Fa-f]{6}", color)}
        self.assertEqual({}, bad)

    def test_a_subway_route_takes_its_color_from_the_table(self) -> None:
        route = {"route_type": "1", "route_id": "1:RR_ACC1_S-1-02-1I", "route_short_name": "서울2호선"}
        self.assertEqual(("00A84D", "subway"), arc.pick(route, self.colors, {}))

    def test_a_subway_line_missing_from_the_table_fails_the_build(self) -> None:
        route = {"route_type": "1", "route_id": "1:X", "route_short_name": "없는선"}
        with self.assertRaises(SystemExit):
            arc.pick(route, self.colors, {})


if __name__ == "__main__":
    unittest.main()
