"""check_catalog.py 판정. 실행: python3 -I -m unittest discover -s tools/subway-express -p 'test_*.py'"""
import unittest

from check_catalog import check, render
from fetch_trainnos import table_rows

CATALOG = {
    "services": [
        {"id": "1001-gyeongin", "lineId": "1001",
         "match": {"trainNoRanges": [["1000", "1099"]], "trainNoAuthoritative": True}, "stops": []},
        {"id": "1001-gyeongbu-a", "lineId": "1001",
         "match": {"trainNoRanges": [["1900", "1999"]], "excludeTrainNos": ["1902"]},
         "stops": [{"name": "금천구청", "onlyTrains": ["1903", "1905"]}]},
    ],
    "allStopTrains": [{"lineId": "1001", "trainNoRanges": [["1401", "1416"]]}],
}


def report(**overrides):
    base = {
        "weeks": ["1"],
        "missingStations": [],
        "services": {
            "1001-gyeongin": {"line": "1001", "trains": {"1001": [], "1003": []}, "optionalStops": {}},
            "1001-gyeongbu-a": {"line": "1001", "trains": {"1903": [], "1905": [], "1948": []},
                                "optionalStops": {"금천구청": ["1903", "1905"], "노량진?": ["1902"]}},
        },
        "unmatchedExpressFlagged": {"1001": {"1401": ["상행", "용산", "구로", "D"]}},
        "seenTrainNos": {"1001": ["500", "1001", "1003", "1401", "1902", "1903", "1905", "1948"]},
    }
    base.update(overrides)
    return base


class CheckCatalogTest(unittest.TestCase):

    def test_matching_timetable_is_clean(self):
        problems, broken = check(report(), CATALOG)
        self.assertIsNone(broken)
        self.assertEqual([], problems)

    def test_normal_train_in_express_range_is_reported(self):
        r = report()
        r["seenTrainNos"]["1001"].append("1050")
        problems, _ = check(r, CATALOG)
        self.assertEqual("1001-gyeongin (line 1001)", problems[0][0])
        self.assertIn("1050", problems[0][1][0])
        self.assertIn("정차가 아니다", problems[0][1][0])

    def test_express_outside_catalog_numbers_is_reported(self):
        r = report()
        r["services"]["1001-gyeongin"]["trains"]["2101"] = []
        problems, _ = check(r, CATALOG)
        self.assertIn("카탈로그 번호에 없다: 2101", problems[0][1][0])

    def test_only_trains_difference_is_reported(self):
        r = report()
        r["services"]["1001-gyeongbu-a"]["optionalStops"]["금천구청"] = ["1903", "1948"]
        problems, _ = check(r, CATALOG)
        self.assertIn("카탈로그만: 1905 / 시간표만: 1948", problems[0][1][0])

    def test_unknown_express_flag_is_reported_but_all_stop_trains_are_not(self):
        r = report()
        r["unmatchedExpressFlagged"]["1001"]["1777"] = ["상행", "a", "b", "D"]
        problems, _ = check(r, CATALOG)
        self.assertEqual(1, len(problems))
        self.assertIn("1777", problems[0][1][0])
        self.assertNotIn("1401", problems[0][1][0])

    def test_missing_station_or_empty_timetable_cannot_judge(self):
        _, broken = check(report(missingStations=["1001:서울"]), CATALOG)
        self.assertIn("1001:서울", broken)
        _, broken = check(report(seenTrainNos={"1001": []}), CATALOG)
        self.assertIsNotNone(broken)

    def test_unread_or_empty_table_cannot_judge(self):
        for field in ("unreadTables", "emptyTables"):
            problems, reason = check(report(**{field: ["1001:금천구청:1:하행"]}), CATALOG)
            self.assertEqual(problems, [])
            self.assertIn("금천구청", reason)

    def test_table_rows_tells_failure_from_no_data(self):
        rows = [{"TRAIN_NO": "K1903"}]
        self.assertEqual(table_rows({"SearchSTNTimeTableByIDService": {"row": rows}}), rows)
        self.assertEqual(table_rows({"RESULT": {"CODE": "INFO-200", "MESSAGE": "해당하는 데이터가 없습니다."}}), [])
        self.assertIsNone(table_rows({"error": "URLError"}))
        self.assertIsNone(table_rows({"RESULT": {"CODE": "ERROR-500"}}))

    def test_render_lists_each_problem(self):
        r = report()
        r["seenTrainNos"]["1001"].append("1050")
        problems, _ = check(r, CATALOG)
        body = render(problems, r)
        self.assertIn("### 1001-gyeongin (line 1001)", body)
        self.assertIn("- 카탈로그 번호인데", body)


if __name__ == "__main__":
    unittest.main()
