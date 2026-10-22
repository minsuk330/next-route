"""급행 카탈로그의 열차번호가 지금 시간표와 맞는지 본다(#497 후속). 주간 GitHub Actions 가 부른다.

열차번호 → 급행 규칙은 공식 문서로 정해진 것이 아니다(2026-10-22 조사). 카탈로그 번호는 시간표에서 열차마다 정차·통과를
보고 모은 결과라 시간표 개정 때 바뀔 수 있다. 일반 열차가 급행 번호대로 들어오면 그 열차를 급행으로 보고 정차역을 잘못
센다 — 그래서 매주 같은 판정(fetch_trainnos.py --json)을 다시 돌려 카탈로그와 견준다.

사용:
  python3 -I tools/subway-express/check_catalog.py <fetch_trainnos --json 출력> <express-catalog.json>

종료 코드: 0 일치, 1 차이 있음(표준 출력 = 이슈 본문 markdown), 2 시간표를 다 못 받음(판정 불가).
"""
import json
import sys


def matcher(match):
    """카탈로그 match → 번호(문자열, 앞 0·K 없음)가 그 서비스인가."""
    numbers = set(match.get("trainNos") or [])
    ranges = [(int(lo), int(hi)) for lo, hi in match.get("trainNoRanges") or []]
    exclude = set(match.get("excludeTrainNos") or [])

    def matches(no):
        if no in exclude:
            return False
        if no in numbers:
            return True
        return no.isdigit() and any(lo <= int(no) <= hi for lo, hi in ranges)
    return matches


def all_stop_numbers(catalog, line):
    """allStopTrains(구로 급행 등) — 급행 표시지만 모든 역에 서서 일반으로 보는 번호."""
    out = []
    for rule in catalog.get("allStopTrains") or []:
        if rule.get("lineId") == line:
            out.append(matcher(rule.get("match") or rule))
    return lambda no: any(m(no) for m in out)


def check(report, catalog):
    """@return (문제 목록[(제목, 줄들)], 판정 불가 사유 또는 None)."""
    if report.get("missingStations"):
        return [], "역 코드를 못 찾은 역: %s" % ", ".join(report["missingStations"])
    seen = report.get("seenTrainNos") or {}
    services = report.get("services") or {}
    if not services or not any(seen.values()):
        return [], "시간표 행이 하나도 없다(API 장애·키 문제)"

    defined = {s["id"]: s for s in catalog.get("services", [])}
    problems = []
    for service_id, r in sorted(services.items()):
        service = defined.get(service_id)
        if service is None:
            problems.append((service_id, ["카탈로그에 이 서비스가 없다 — fetch_trainnos.py SERVICES 와 맞춘다"]))
            continue
        line = r["line"]
        matches = matcher(service.get("match") or {})
        members = set(r.get("trains") or {})
        lines = []
        missing = sorted((n for n in members if not matches(n)), key=int)
        if missing:
            lines.append("시간표상 이 급행인데 카탈로그 번호에 없다: %s" % " ".join(missing))
        intruders = sorted((n for n in seen.get(line, []) if matches(n) and n not in members),
                           key=lambda n: int(n) if n.isdigit() else -1)
        if intruders:
            lines.append("카탈로그 번호인데 시간표상 이 급행 정차가 아니다(일반·다른 계통): %s" % " ".join(intruders))
        optional = {name: set(v) for name, v in (r.get("optionalStops") or {}).items() if not name.endswith("?")}
        for stop in service.get("stops") or []:
            name = stop.get("name")
            if not name or "onlyTrains" not in stop:
                continue
            want = set(stop["onlyTrains"])
            got = optional.pop(name, set())
            if want != got:
                lines.append("%s 예외 정차 열차가 다르다 — 카탈로그만: %s / 시간표만: %s" % (
                    name, " ".join(sorted(want - got, key=int)) or "-", " ".join(sorted(got - want, key=int)) or "-"))
        for name, got in sorted(optional.items()):
            lines.append("%s 에 일부 열차만 선다(카탈로그에 onlyTrains 없음): %s" % (name, " ".join(sorted(got, key=int))))
        if lines:
            problems.append(("%s (line %s)" % (service_id, line), lines))

    for line, flagged in sorted((report.get("unmatchedExpressFlagged") or {}).items()):
        all_stop = all_stop_numbers(catalog, line)
        unknown = sorted((n for n in flagged if not all_stop(n)), key=lambda n: int(n) if n.isdigit() else -1)
        if unknown:
            problems.append(("급행 표시인데 어느 서비스도 아닌 열차 (line %s)" % line,
                             ["%s — 새 계통이거나 통과역 없는 급행. 카탈로그 서비스·allStopTrains 를 본다" % " ".join(unknown)]))
    return problems, None


def render(problems, report):
    out = ["시간표(서울 열린데이터 `SearchSTNTimeTableByIDService`, WEEK_TAG %s)와 `express-catalog.json` 열차번호가 다르다."
           % ",".join(report.get("weeks") or []),
           "", "번호가 바뀌었으면 카탈로그 `match` 를 고친다. 일반 열차가 급행 번호대에 들어오면 그 열차를 급행으로 보고 정차역을 "
           "잘못 센다.", ""]
    for title, lines in problems:
        out.append("### %s" % title)
        out.extend("- %s" % line for line in lines)
        out.append("")
    out.append("다시 보기: `fetch_trainnos.py --weeks 1,2,3` (사용법은 파일 머리말).")
    return "\n".join(out)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as f:
        report = json.load(f)
    with open(sys.argv[2], encoding="utf-8") as f:
        catalog = json.load(f)
    problems, broken = check(report, catalog)
    if broken:
        print(broken)
        sys.exit(2)
    if not problems:
        print("일치: %s" % ", ".join(sorted(report["services"])))
        return
    print(render(problems, report))
    sys.exit(1)


if __name__ == "__main__":
    main()
