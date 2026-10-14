"""KRIC 역사별 승강장 정보(convenientInfo/stPlf) → src/main/resources/subway/platform-type.csv (#335).

사용:
  KRIC_SERVICE_KEY=<레일포털 서비스 키> python3 -I tools/subway-platform-type/fetch_platform_type.py <역코드.xlsx> \
      [--dump responses.json] > src/main/resources/subway/platform-type.csv
  이미 받은 응답으로 재생성(키 불필요):
  python3 -I tools/subway-platform-type/fetch_platform_type.py <역코드.xlsx> --responses responses.json > ...

역코드.xlsx = 레일포털 > 정보공유 > 자료실 "역사코드정보" 첨부
(https://data.kric.go.kr/rips/download.file?type=L&id=17&answerId=17&fileId=1, 로그인 불필요).
응답에 역 이름이 없어서 이 파일로 이름을 붙인다.

노선 단위로 부른다(stinCd 생략) — 전국 47노선 = 47회, 1초 간격. 키 발급 조건이 "1일 1회 또는 배치,
과도한 GET 모니터링"이고 원천 갱신이 분기·반기라 런타임에서 부르지 않고 이 도구로 가끔 다시 만든다.
키는 출력·커밋 금지.

역 판정: 그 역 승강장 행들의 plfTpNm 이 전부 섬식 → ISLAND, 전부 상대식 → SIDE, 그 밖(2폼3선식·쌍섬식·
1섬 1상대식·혼재·null) → OTHER. 역코드 파일에 없는 코드(신분당 D007 ↔ 4305, 부산 0 채움 등)는 버리고 stderr 에 센다.
"""
import argparse
import csv
import datetime
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter, defaultdict

URL = "https://openapi.kric.go.kr/openapi/convenientInfo/stPlf"
NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def read_station_codes(path):
    """첫 시트 (RAIL_OPR_ISTT_CD, RAIL_OPR_ISTT_NM, LN_CD, LN_NM, STIN_CD, STIN_NM) → {(opr, ln, stin): name}."""
    with zipfile.ZipFile(path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", NS):
                shared.append("".join(t.text or "" for t in si.iter(f"{{{NS['m']}}}t")))
        sheet = ET.fromstring(z.read("xl/worksheets/sheet1.xml"))
    rows = []
    for row in sheet.iter(f"{{{NS['m']}}}row"):
        values = {}
        for c in row.findall("m:c", NS):
            col = "".join(ch for ch in c.get("r") if ch.isalpha())
            v = c.find("m:v", NS)
            if v is None:
                inline = c.find("m:is", NS)
                text = "".join(t.text or "" for t in inline.iter(f"{{{NS['m']}}}t")) if inline is not None else None
            elif c.get("t") == "s":
                text = shared[int(v.text)]
            else:
                text = v.text
            values[col] = text.strip() if text else text
        rows.append([values.get(col) for col in "ABCDEF"])
    header, body = rows[0], rows[1:]
    if header[0] != "RAIL_OPR_ISTT_CD" or header[4] != "STIN_CD":
        raise SystemExit(f"역코드 파일 헤더가 다르다: {header}")
    return {(r[0], r[2], r[4]): r[5] for r in body if r[0] and r[2] and r[4]}


def fetch(lines, key, dump):
    responses = {}
    for opr, ln in lines:
        q = urllib.parse.urlencode({"serviceKey": key, "format": "json", "railOprIsttCd": opr, "lnCd": ln})
        with urllib.request.urlopen(f"{URL}?{q}", timeout=30) as resp:
            body = json.load(resp)
        responses[f"{opr}|{ln}"] = body
        header = body.get("header", {})
        print(f"{opr}|{ln} code={header.get('resultCode')} rows={header.get('resultCnt')}", file=sys.stderr)
        time.sleep(1.0)
    if dump:
        with open(dump, "w", encoding="utf-8") as f:
            json.dump(responses, f, ensure_ascii=False)
    return responses


def platform_type(types):
    if types == {"섬식"}:
        return "ISLAND"
    if types == {"상대식"}:
        return "SIDE"
    return "OTHER"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("codes")
    parser.add_argument("--responses", help="이미 받은 응답 JSON({\"opr|ln\": 응답})")
    parser.add_argument("--dump", help="받은 응답을 저장할 경로")
    args = parser.parse_args()

    names = read_station_codes(args.codes)
    lines = sorted({(opr, ln) for opr, ln, _ in names})
    if args.responses:
        with open(args.responses, encoding="utf-8") as f:
            responses = json.load(f)
    else:
        responses = fetch(lines, os.environ["KRIC_SERVICE_KEY"], args.dump)

    types = defaultdict(set)
    for body in responses.values():
        for p in body.get("body") or []:
            types[(p["railOprIsttCd"], p["lnCd"], p["stinCd"])].add(p.get("plfTpNm"))
    unknown = Counter(f"{k[0]}|{k[1]}" for k in types if k not in names)
    for line, n in sorted(unknown.items()):
        print(f"역코드 파일에 없는 코드 버림 {line} {n}역", file=sys.stderr)

    out = csv.writer(sys.stdout, lineterminator="\n")
    print(f"# source=KRIC convenientInfo/stPlf fetched={datetime.date.today().isoformat()} "
          f"codes={os.path.basename(args.codes)}")
    out.writerow(["opr", "ln_cd", "stin_cd", "stin_nm", "platform_type"])
    for key in sorted(k for k in types if k in names):
        out.writerow([key[0], key[1], key[2], names[key], platform_type(types[key])])


if __name__ == "__main__":
    main()
