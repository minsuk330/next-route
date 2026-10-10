"""서울교통공사_빠른하차정보(getFstExit) 전체 → src/main/resources/subway/fast-alight.csv (#266 PR-4).

사용: DATA_GO_KR_KEY=<공공데이터포털 인증키(Decoding)> python3 -I tools/subway-fast-alight/fetch_fast_alight.py > src/main/resources/subway/fast-alight.csv
  또는 이미 받은 응답 파일들: python3 -I tools/subway-fast-alight/fetch_fast_alight.py page1.json page2.json ...
키는 출력·커밋 금지. 역 이름 필터(stinNm)는 원천이 무시하므로 전체를 받는다(약 2,400행).
"""
import csv
import json
import os
import re
import sys
import urllib.parse
import urllib.request

URL = "https://apis.data.go.kr/B553766/inout/getFstExit"


def pages_from_api(key):
    page = 1
    while True:
        q = urllib.parse.urlencode({"serviceKey": key, "dataType": "JSON", "pageNo": page, "numOfRows": 100})
        with urllib.request.urlopen(f"{URL}?{q}", timeout=30) as resp:
            body = json.load(resp)["response"]["body"]
        items = body["items"]["item"]
        items = items if isinstance(items, list) else [items]
        yield from items
        if page * 100 >= int(body["totalCount"]):
            return
        page += 1


def pages_from_files(paths):
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                items = json.loads(line)["response"]["body"]["items"]["item"]
                yield from (items if isinstance(items, list) else [items])


def car_door(raw):
    """"2-3" 그대로, "3-2,3-3 사이" → "3-2~3-3", 모름("NA-NA")·이상값 → None."""
    raw = (raw or "").strip()
    m = re.fullmatch(r"(\d+-\d+)", raw)
    if m:
        return m.group(1)
    m = re.fullmatch(r"(\d+-\d+)\s*,\s*(\d+-\d+)\s*사이", raw)
    if m:
        return f"{m.group(1)}~{m.group(2)}"
    return None


def main():
    rows = pages_from_files(sys.argv[1:]) if len(sys.argv) > 1 else pages_from_api(os.environ["DATA_GO_KR_KEY"])
    out = csv.writer(sys.stdout, lineterminator="\n")
    data = sorted({(r["lineNm"], r["stnNm"], r["upbdnbSe"], r["drtnInfo"], car_door(r["qckgffVhclDoorNo"]),
                    r["plfmCmgFac"], r["crtrYmd"]) for r in rows if car_door(r["qckgffVhclDoorNo"])})
    base = max((d[6] for d in data), default="")
    sys.stdout.write(f"# source=서울교통공사_빠른하차정보(data.go.kr 15143840) crtrYmd={base}\n")
    out.writerow(["line_nm", "stn_nm", "updn", "next_stn", "car_door", "facility"])
    out.writerows(d[:6] for d in data)


if __name__ == "__main__":
    main()
