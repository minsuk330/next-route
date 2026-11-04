"""서울 시간표 API 로 1호선(1001)·4호선(1004) 급행 열차번호를 서비스별로 뽑는다 (subway-express PR-S1).

카탈로그(src/main/resources/subway/express-catalog.json) 의 열차번호 `match` 를 정하는 근거 도구다. 결과는 사람이 검토해
카탈로그에 옮긴다(자동으로 쓰지 않는다). 매주 GitHub Actions(express-catalog-check.yml)가 --json 으로 돌려 check_catalog.py 로
카탈로그와 견준다(키는 repo secret SEOUL_SUBWAY_MASTER).

사용(키는 VPS 에만 있다 — 출력·커밋 금지):
  ssh vps 'cd /root/apps/nextroute && set -a && . env/api.env && set +a && python3 -I - [--weeks 1,2,3] [--json]' \\
      < tools/subway-express/fetch_trainnos.py

원천:
  역 코드   SearchInfoBySubwayNameService/1/50/{역이름}            → STATION_CD (LINE_NUM 으로 노선 거름)
  시간표    SearchSTNTimeTableByIDService/1/1000/{STATION_CD}/{WEEK_TAG}/{INOUT_TAG}/
           WEEK_TAG 1 평일·2 토·3 휴일, INOUT_TAG 1 상행·2 하행. TRAIN_NO 는 'K1056' 처럼 K 가 붙는다.

판정 규칙:
  - 역 시간표 행의 ARRIVETIME 이 00:00:00 이면 통과다(1호선, LEFTTIME 은 통과 시각). 시발역(ORIGINSTATION = 그 역)도
    ARRIVETIME 이 00:00:00 이라 정차로 본다. 종착역은 LEFTTIME 이 00:00:00 이다.
  - 4호선 급행은 지나치는 역 시간표에 아예 없다(EXPRESS_YN 도 'G'). 그래서 열차가 처음·마지막으로 선 역 사이에서
    시간표에 없는 역도 통과로 본다.
  - 서비스마다 구간(완행 역 순서)과 표시(S 정차, X 통과, O 열차별 예외, U 상행만 정차)를 둔다. 열차가 구간 안에서
    2역 이상 서고, 그 사이 S 를 모두 서고 X 를 모두 지나며, 사이에 X 가 하나 이상 있으면 그 서비스다.
  - 번호 제안은 백 단위 블록(계획의 'K16xx'). 블록 안에 서비스 아닌 번호가 있으면 [min, max] 로 좁히고 그 번호를
    제외 목록(적을 때)이나 번호 목록(많을 때)으로 바꾼다. 조사 역에 한 번이라도 나온 그 노선 열차번호 전부와 대조한다.
  - 종착역이 아닌데 LEFTTIME 만 00:00:00 인 행(도착 시각만)은 정차·통과 어느 쪽 검사에도 걸지 않고 '정차 불분명'으로
    따로 적는다(경부 B급행 상행 1902·1904 의 노량진).
  - 끝에 'EXPRESS_YN≠G 인데 어느 서비스에도 안 맞은 열차'를 적는다 — 통과역 없는 급행이나 새 계통을 놓치지 않으려고.
    용산–구로 14xx '구로 급행'이 여기 나온다(모든 역 정차 → 카탈로그 allStopTrains, 분류기는 일반으로 본다).
    --explain 1902,1401 로 열차별 판정 사유를 본다.
"""
import argparse
import concurrent.futures
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

BASE = "http://openapi.seoul.go.kr:8088/"
ATTEMPTS = 3
RETRY_SECONDS = 2
LINE_NUMS = {"1001": "01호선", "1004": "04호선"}
DIRECTIONS = {"1": "상행", "2": "하행"}

# 역 마스터 이름 → 서울 API 역 이름 후보(앞에서부터 시도)
API_NAMES = {
    "서울": ["서울역", "서울"],
    "지제": ["지제", "평택지제"],
    "쌍용": ["쌍용(나사렛대)", "쌍용"],
    "불암산": ["불암산", "당고개"],
    "총신대입구": ["총신대입구(이수)", "총신대입구", "이수"],
    "신창": ["신창(순천향대)", "신창"],
}


def seg(spec):
    """'용산:S 노량진:S 구일:X' → [(이름, 표시)]."""
    out = []
    for token in spec.split():
        name, mark = token.split(":")
        out.append((name, mark))
    return out


_GYEONGIN_ALL = "구로:S 구일:S 개봉:S 오류동:S 온수:S 역곡:S 소사:S 부천:S 중동:S 송내:S 부개:S 부평:S 백운:S 동암:S " \
                "간석:S 주안:S 도화:S 제물포:S 도원:S 동인천:S 인천:S"

# 하행(기점 → 종점) 순서
SERVICES = {
    "1001-gyeongin": ("1001", seg(
        "용산:S 노량진:S 대방:S 신길:S 영등포:S 신도림:S 구로:S 구일:X 개봉:S 오류동:X 온수:X 역곡:S 소사:X 부천:S "
        "중동:X 송내:S 부개:X 부평:S 백운:X 동암:S 간석:X 주안:S 도화:X 제물포:S 도원:X 동인천:S")),
    "1001-gyeongbu-a": ("1001", seg(
        "청량리:S 제기동:S 신설동:S 동묘앞:S 동대문:S 종로5가:S 종로3가:S 종각:S 시청:S 서울:S 남영:S 용산:S 노량진:S "
        "대방:S 신길:S 영등포:S 신도림:S 구로:S 가산디지털단지:S 독산:X 금천구청:O 석수:X 관악:X 안양:S 명학:X 금정:S "
        "군포:X 당정:X 의왕:O 성균관대:S 화서:X 수원:S 세류:X 병점:S 세마:X 오산대:X 오산:S 진위:X 송탄:X 서정리:S "
        "지제:X 평택:S 성환:S 직산:X 두정:S 천안:S 봉명:S 쌍용:S 아산:S 탕정:S 배방:S 온양온천:S 신창:S")),
    "1001-gyeongbu-b": ("1001", seg(
        "서울:S 남영:X 용산:X 노량진:X 대방:X 신길:X 영등포:U 신도림:X 구로:X 가산디지털단지:X 독산:X 금천구청:S "
        "석수:X 관악:X 안양:S 명학:X 금정:X 군포:S 당정:X 의왕:S 성균관대:S 화서:X 수원:S 세류:X 병점:S 세마:X "
        "오산대:X 오산:S 진위:X 송탄:X 서정리:S 지제:X 평택:S 성환:S 직산:X 두정:S 천안:S 봉명:S 쌍용:S 아산:S "
        "탕정:S 배방:S 온양온천:S 신창:S")),
    "1001-gyeongwon": ("1001", seg(
        "동두천:S 보산:X 동두천중앙:S 지행:S 덕정:S 덕계:S 양주:S 녹양:X 가능:X 의정부:S 회룡:S 망월사:X 도봉산:S "
        "도봉:X 방학:X 창동:S 녹천:X 월계:X 광운대:S 석계:S 신이문:S 외대앞:S 회기:S 청량리:S 서울:S 용산:S 노량진:S "
        "대방:S 신길:S 영등포:S 신도림:S " + _GYEONGIN_ALL)),
    "1004-ansan": ("1004", seg(
        "불암산:S 노원:S 창동:S 서울:S 사당:S 남태령:S 선바위:S 경마공원:S 대공원:S 과천:S 정부과천청사:S 인덕원:S "
        "평촌:S 범계:S 금정:S 산본:S 수리산:X 대야미:X 반월:X 상록수:S 한대앞:X 중앙:S 고잔:X 초지:S 안산:S "
        "신길온천:X 정왕:S 오이도:S")),
}


def get(key, path):
    url = BASE + key + "/json/" + path
    for attempt in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(url, timeout=30) as resp:
                return json.load(resp)
        except Exception as e:  # URL 에 키가 있으니 예외 메시지에 URL 을 싣지 않는다
            error = type(e).__name__
            if attempt + 1 < ATTEMPTS:
                time.sleep(RETRY_SECONDS)
    return {"error": error}


def station_code(key, line_id, name):
    want = LINE_NUMS[line_id]
    for api_name in API_NAMES.get(name, [name]):
        body = get(key, "SearchInfoBySubwayNameService/1/50/" + urllib.parse.quote(api_name))
        for row in body.get("SearchInfoBySubwayNameService", {}).get("row", []):
            if row.get("LINE_NUM") == want and row.get("STATION_NM") == api_name:
                return row["STATION_CD"]
    return None


def table_rows(body):
    """시간표 응답 → 행 목록. 못 읽었으면 None — 빈 목록으로 두면 그 역의 열차가 안 서는 것으로 판정된다(#530).

    INFO-200 은 공급자가 '해당 데이터 없음'이라고 답한 것이라 빈 목록이다.
    """
    table = body.get("SearchSTNTimeTableByIDService")
    if table is not None:
        return table.get("row")
    return [] if body.get("RESULT", {}).get("CODE") == "INFO-200" else None


def timetable(key, code, week, inout):
    return table_rows(get(key, "SearchSTNTimeTableByIDService/1/1000/%s/%s/%s/" % (code, week, inout)))


def catalog_no(train_no):
    """'K1056' → '1056', '0112' → '112'. 공항철도 'A' 는 이 도구 대상이 아니다."""
    digits = re.sub(r"^K", "", (train_no or "").strip())
    return digits.lstrip("0") or "0"


def collect(key, weeks):
    """(line, 역 이름) → {(week, inout): rows}. 같은 역은 한 번만 받는다."""
    wanted = sorted({(line, name) for line, segment in SERVICES.values() for name, _ in segment})
    codes = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for (line, name), code in zip(wanted, pool.map(lambda ln: station_code(key, *ln), wanted)):
            codes[(line, name)] = code
    jobs = [(ln, w, io) for ln in wanted if codes[ln] for w in weeks for io in DIRECTIONS]
    tables, unread, empty = {}, [], []
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for (ln, w, io), rows in zip(jobs, pool.map(lambda j: timetable(key, codes[j[0]], j[1], j[2]), jobs)):
            label = "%s:%s:%s:%s" % (ln[0], ln[1], w, DIRECTIONS[io])
            if rows is None:
                unread.append(label)
            elif not rows:
                empty.append(label)
            tables.setdefault(ln, {})[(w, io)] = rows or []
    return codes, tables, unread, empty


def train_visits(segment, line, tables, week, inout):
    """열차번호 → {역 인덱스: 'stop'|'pass'|'unclear'} 와 열차 메타.

    'unclear' = 종착역이 아닌데 LEFTTIME 이 00:00:00(도착 시각만). 경부 B급행 상행(1902·1904)의 노량진이 그렇다 —
    정차인지 통과 시각을 잘못 적은 것인지 시간표만으로는 모른다. 정차·통과 어느 쪽 검사에도 걸지 않고 따로 보고한다.
    """
    visits, meta = {}, {}
    for i, (name, _) in enumerate(segment):
        for row in tables.get((line, name), {}).get((week, inout), []):
            no = row["TRAIN_NO"]
            # 통과 행은 ARRIVETIME 00:00:00 에 LEFTTIME 이 통과 시각이다. 시발역도 ARRIVETIME 이 00:00:00 이라 뺀다.
            origin = row.get("ORIGINSTATION") == row.get("STATION_CD")
            terminal = row.get("DESTSTATION") == row.get("STATION_CD")
            if row.get("ARRIVETIME") == "00:00:00" and not origin:
                kind = "pass"
            elif row.get("LEFTTIME") == "00:00:00" and not terminal:
                kind = "unclear"
            else:
                kind = "stop"
            visits.setdefault(no, {})[i] = kind
            meta[no] = (row.get("SUBWAYSNAME"), row.get("SUBWAYENAME"), row.get("EXPRESS_YN"))
    return visits, meta


def fits(segment, stops_at, direction):
    """구간 안에서 이 열차가 서비스 정차 규칙에 맞는지. (맞음, 예외역·불분명역 목록, 사유)."""
    stopped = sorted(i for i, kind in stops_at.items() if kind == "stop")
    if len(stopped) < 2:
        return False, [], "구간 정차 2역 미만"
    lo, hi = stopped[0], stopped[-1]
    saw_skip = False
    optional = []
    for i in range(lo, hi + 1):
        name, mark = segment[i]
        if stops_at.get(i) == "unclear":
            optional.append(name + "?")
            continue
        did_stop = stops_at.get(i) == "stop"
        must_stop = mark == "S" or (mark == "U" and direction == "상행")
        if mark == "O":
            if did_stop:
                optional.append(name)
            continue
        if must_stop and not did_stop:
            return False, [], "%s 미정차" % name
        if mark == "X" or (mark == "U" and direction == "하행"):
            if did_stop:
                return False, [], "%s 정차" % name
            saw_skip = True
    if not saw_skip:
        return False, [], "통과역 없음(완행과 구분 불가)"
    return True, optional, ""


def propose(members, seen):
    """백 단위(10xx, 12xx …)마다 제안한다.

    - 그 백 단위 전체에 서비스 아닌 번호가 하나도 없으면 블록 전체([X00, X99], 계획의 'K16xx' 표기)
    - 있으면 [min, max] 로 좁히고, 그 안의 서비스 아닌 번호가 적으면 제외 목록, 많으면 번호 목록

    @return (match 제안, 범위 안 서비스 아닌 번호)
    """
    by_block = {}
    for n in members:
        by_block.setdefault(int(n) // 100, []).append(int(n))
    others = sorted(int(n) for n in seen if n.isdigit() and n not in members)
    ranges, trains, exclude, conflicts = [], [], [], []
    for block, numbers in sorted(by_block.items()):
        if not any(block * 100 <= n <= block * 100 + 99 for n in others):
            ranges.append([str(block * 100), str(block * 100 + 99)])
            continue
        lo, hi = min(numbers), max(numbers)
        inside = [n for n in others if lo <= n <= hi]
        conflicts += inside
        if inside and len(inside) * 4 > len(numbers):
            trains += sorted(numbers)
        else:
            ranges.append([str(lo), str(hi)])
            exclude += inside
    match = {}
    if trains:
        match["trainNos"] = [str(n) for n in trains]
    if ranges:
        match["trainNoRanges"] = ranges
    if exclude:
        match["excludeTrainNos"] = [str(n) for n in exclude]
    return match, [str(n) for n in conflicts]


def analyse(tables, weeks, explain=()):
    """서비스별 소속 열차·제안 범위. 충돌은 그 노선 조사 역 어디서든 본 열차 중 서비스가 아닌 번호다."""
    seen_on_line, flagged = {}, {}
    for (line, name), by_key in tables.items():
        for (week, inout), rows in by_key.items():
            for row in rows:
                number = catalog_no(row["TRAIN_NO"])
                seen_on_line.setdefault(line, set()).add(number)
                if row.get("EXPRESS_YN") not in (None, "", "G"):
                    flagged.setdefault(line, {})[number] = (DIRECTIONS[inout], row.get("SUBWAYSNAME"),
                                                            row.get("SUBWAYENAME"), row.get("EXPRESS_YN"))
    report, explained = {}, []
    for service_id, (line, segment) in SERVICES.items():
        members, optional_stops = {}, {}
        for week in weeks:
            for inout, direction in DIRECTIONS.items():
                visits, meta = train_visits(segment, line, tables, week, inout)
                for no, stops_at in visits.items():
                    ok, optional, why = fits(segment, stops_at, direction)
                    number = catalog_no(no)
                    if number in explain:
                        explained.append("%s %s week=%s %s: %s" % (service_id, number, week, direction,
                                                                   "맞음" if ok else why))
                    if ok:
                        members.setdefault(number, set()).add((direction, week) + meta[no])
                        for name in optional:
                            optional_stops.setdefault(name, set()).add(number)
        proposed, conflicts = propose(set(members), seen_on_line.get(line, set()))
        report[service_id] = {
            "line": line,
            "trains": {n: sorted(v) for n, v in sorted(members.items(), key=lambda kv: int(kv[0]))},
            "proposedMatch": proposed,
            "rangeConflicts": conflicts,
            "optionalStops": {k: sorted(v, key=int) for k, v in optional_stops.items()},
        }
    matched = {(r["line"], n) for r in report.values() for n in r["trains"]}
    unmatched = {line: {n: v for n, v in by_no.items() if (line, n) not in matched}
                 for line, by_no in flagged.items()}
    seen = {line: sorted(numbers, key=lambda n: int(n) if n.isdigit() else -1)
            for line, numbers in seen_on_line.items()}
    return report, unmatched, explained, seen


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--weeks", default="1", help="WEEK_TAG 목록(1 평일, 2 토, 3 휴일). 기본 1")
    parser.add_argument("--json", action="store_true", help="열차별 상세까지 JSON 으로")
    parser.add_argument("--explain", default="", help="판정 사유를 볼 열차번호(쉼표, K 없이)")
    args = parser.parse_args()
    key = os.environ.get("SEOUL_SUBWAY_MASTER")
    if not key:
        sys.exit("SEOUL_SUBWAY_MASTER 없음 — VPS 에서 env/api.env 를 읽고 실행한다")
    weeks = [w.strip() for w in args.weeks.split(",") if w.strip()]
    codes, tables, unread, empty = collect(key, weeks)
    missing = sorted("%s:%s" % ln for ln, code in codes.items() if not code)
    explain = {n.strip().lstrip("K") for n in args.explain.split(",") if n.strip()}
    report, unmatched, explained, seen = analyse(tables, weeks, explain)
    if args.json:
        json.dump({"weeks": weeks, "missingStations": missing, "unreadTables": unread, "emptyTables": empty,
                   "services": report,
                   "unmatchedExpressFlagged": unmatched, "seenTrainNos": seen},
                  sys.stdout, ensure_ascii=False, indent=1, default=list)
        print()
        return
    print("# weeks=%s stations=%d missing=%s" % (",".join(weeks), len(codes), missing or "-"))
    for service_id, r in report.items():
        trains = r["trains"]
        print("\n## %s (line %s) trains=%d" % (service_id, r["line"], len(trains)))
        by_dir = {}
        for n, rows in trains.items():
            for direction, week, origin, dest, flag in rows:
                by_dir.setdefault((direction, origin, dest, flag), set()).add(n)
        for (direction, origin, dest, flag), ns in sorted(by_dir.items()):
            ns = sorted(ns, key=int)
            print("  %s %s→%s EXPRESS_YN=%s n=%d: %s" % (direction, origin, dest, flag, len(ns), " ".join(ns)))
        for name, ns in r["optionalStops"].items():
            label = "도착 시각만 있음(정차 불분명)" if name.endswith("?") else "예외 정차"
            print("  %s %s: %s" % (label, name.rstrip("?"), " ".join(ns)))
        print("  proposed match: %s" % json.dumps(r["proposedMatch"], ensure_ascii=False))
        if r["rangeConflicts"]:
            print("  범위 안 서비스 아닌 번호: %s" % " ".join(r["rangeConflicts"]))
    for line, by_no in sorted(unmatched.items()):
        groups = {}
        for n, (direction, origin, dest, flag) in by_no.items():
            groups.setdefault((direction, origin, dest, flag), []).append(n)
        print("\n## 급행 표시(EXPRESS_YN≠G)인데 어느 서비스에도 안 맞은 열차 (line %s)" % line)
        for (direction, origin, dest, flag), ns in sorted(groups.items()):
            print("  %s %s→%s EXPRESS_YN=%s: %s" % (direction, origin, dest, flag, " ".join(sorted(ns, key=int))))
    for line in explained:
        print("explain:", line)


if __name__ == "__main__":
    main()
