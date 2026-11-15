#!/usr/bin/env python3
"""지방 지하철 역 시드(src/main/resources/subway/regional-stations.json) 생성.

입력
  --otp   운영 OTP GraphQL 응답 JSON
          { routes(transportModes:[SUBWAY]) { gtfsId shortName longName patterns { code stops { gtfsId name lat lon } } } }
  --tago  운영 DB subway_station_tago 덤프 CSV (tago_station_id,route_name,station_name)
출력
  --out   시드 JSON. 노선마다 일직선 순서(seq)를 매기고 TAGO 역 id 와 이름으로 맞춘다.

규칙(docs/plan/subway-regional-timetable/PLAN.md 5절 1-1)
  - 노선 순서 = 가장 긴 패턴. 다른 모든 패턴은 그 순서의 연속 부분열(또는 역순)이어야 한다. 아니면 실패.
  - 역 = 노선 안에서 정규화 이름이 같은 정류장 묶음(방향별 정류장 id 가 다를 수 있어 gtfsStopIds 는 목록).
  - 이름 정규화: 괄호 부분, 공백, 끝의 '역' 제거. 안 맞으면 aliases.json, 그래도 남으면 실패.
  - stationKey = {lineCode}-{TAGO id 뒤 3자리}. providerStationCode 도 같은 3자리(부산 scode, 대구 STT_CD, 대전, 광주 역번호).

사용
  python3 -I tools/regional-subway/build_stations.py --otp otp.json --tago tago.csv --out src/main/resources/subway/regional-stations.json
  python3 -I tools/regional-subway/build_stations.py --otp otp.json --tago tago.csv --check
"""
import argparse
import csv
import json
import os
import re
import sys

LINES = {
    # OTP route shortName: (lineCode, TAGO id prefix)
    "부산1호선": ("BS1", "MTRBS1"),
    "부산2호선": ("BS2", "MTRBS2"),
    "부산3호선": ("BS3", "MTRBS3"),
    "부산4호선": ("BS4", "MTRBS4"),
    "대구1호선": ("DG1", "MTRDG1"),
    "대구2호선": ("DG2", "MTRDG2"),
    "대구3호선": ("DG3", "MTRDG3"),
    "대전1호선": ("DJ1", "MTRDJ1"),
    "광주1호선": ("GJ1", "MTRGJ1"),
}
EXPECTED_COUNTS = {"BS1": 40, "BS2": 43, "BS3": 17, "BS4": 14, "DG1": 35, "DG2": 29, "DG3": 30, "DJ1": 22, "GJ1": 20}


def normalize(name: str) -> str:
    name = re.sub(r"\(.*?\)", "", name or "")
    name = re.sub(r"[\s·.]", "", name)   # '경성대·부경대'(OTP) = '경성대.부경대'(TAGO)
    if name.endswith("역") and len(name) > 2:
        name = name[:-1]
    return name


def is_contiguous(base, seq):
    """seq 가 base 의 순서를 지키는 부분열인가(건너뛰는 역 허용: 부산3호선 수영→대저 16역 패턴)."""
    try:
        idx = [base.index(x) for x in seq]
    except ValueError:
        return False
    return all(a < b for a, b in zip(idx, idx[1:]))


def line_order(patterns, line_name):
    """패턴들(이름 목록)로 노선 일직선 순서를 만든다."""
    seqs = [[normalize(s["name"]) for s in p["stops"]] for p in patterns]
    base = max(seqs, key=len)
    for s in seqs:
        if not (is_contiguous(base, s) or is_contiguous(base, list(reversed(s)))):
            missing = [x for x in s if x not in base]
            raise SystemExit(f"[{line_name}] 일직선 아님: 패턴 {s[0]}→{s[-1]}({len(s)}) 가 기준 {base[0]}→{base[-1]}({len(base)}) 의 연속 부분열이 아님. 기준에 없는 역 {missing}")
    return base


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--otp", required=True)
    ap.add_argument("--tago", required=True)
    ap.add_argument("--aliases", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "aliases.json"))
    ap.add_argument("--out")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    aliases = json.load(open(args.aliases, encoding="utf-8")) if os.path.exists(args.aliases) else {}
    otp = json.load(open(args.otp, encoding="utf-8"))
    routes = otp["data"]["routes"]

    tago_by_prefix = {}
    with open(args.tago, encoding="utf-8") as f:
        for row in csv.reader(f):
            if not row or row[0] == "tago_station_id":
                continue
            tid, _route, name = row[0], row[1], row[2]
            for _, (code, prefix) in LINES.items():
                if tid.startswith(prefix):
                    tago_by_prefix.setdefault(code, {})
                    key = normalize(name)
                    if key in tago_by_prefix[code]:
                        raise SystemExit(f"[{code}] TAGO 이름 중복 {key}: {tago_by_prefix[code][key]} / {tid}")
                    tago_by_prefix[code][key] = tid

    out, problems = [], []
    for line_name, (code, _prefix) in LINES.items():
        line_routes = [r for r in routes if r["shortName"] == line_name]
        patterns = [p for r in line_routes for p in r["patterns"]]
        if not patterns:
            problems.append(f"[{code}] OTP 에 {line_name} 패턴 없음")
            continue
        order = line_order(patterns, line_name)
        stops = {}
        for p in patterns:
            for s in p["stops"]:
                st = stops.setdefault(normalize(s["name"]), {"gtfs": [], "lat": s["lat"], "lon": s["lon"], "otpName": s["name"]})
                if s["gtfsId"] not in st["gtfs"]:
                    st["gtfs"].append(s["gtfsId"])
        tago = tago_by_prefix.get(code, {})
        otp_alias = aliases.get(code, {})
        used = set()
        for seq, name in enumerate(order):
            tago_key = normalize(otp_alias.get(name, name))
            tid = tago.get(tago_key)
            if tid is None:
                problems.append(f"[{code}] TAGO 에 없음: OTP '{stops[name]['otpName']}'(정규화 '{tago_key}')")
                continue
            used.add(tago_key)
            st = stops[name]
            code3 = aliases.get("codeOverride", {}).get(tid, tid[-3:])
            out.append({
                "stationKey": f"{code}-{code3}",
                "lineCode": code,
                "lineName": line_name,
                "stationName": tago_key,   # 현재 역명(TAGO). OTP 는 개명 전 이름일 수 있다
                "otpStationName": name,
                "seq": seq,
                "tagoStationId": tid,
                "providerStationCode": code3,
                "gtfsStopIds": sorted(st["gtfs"]),
                "lat": round(st["lat"], 7),
                "lon": round(st["lon"], 7),
            })
        for k, tid in tago.items():
            if k not in used:
                problems.append(f"[{code}] OTP 에 없는 TAGO 역: {k} ({tid})")
        count = sum(1 for o in out if o["lineCode"] == code)
        print(f"{code} {line_name}: {count}역 (기대 {EXPECTED_COUNTS[code]}) {order[0]} → {order[-1]}")
        if count != EXPECTED_COUNTS[code]:
            problems.append(f"[{code}] 역 수 {count} != 기대 {EXPECTED_COUNTS[code]}")

    seen = {}
    for o in out:
        if o["stationKey"] in seen:
            problems.append(f"stationKey 중복 {o['stationKey']}: {seen[o['stationKey']]} / {o['stationName']}")
        seen[o["stationKey"]] = o["stationName"]
    if problems:
        print("\n".join(problems), file=sys.stderr)
        raise SystemExit(1)
    if args.out and not args.check:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
            f.write("\n")
        print(f"wrote {len(out)} stations → {args.out}")


if __name__ == "__main__":
    main()
