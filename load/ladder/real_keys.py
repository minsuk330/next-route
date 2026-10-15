"""실제 서울 실시간 도착 ALL 한 장에서 지하철 사다리(#427) 관심 키를 뽑는다.

지금 열차가 있는(떠난 열차 2 를 뺀 행이 하나라도 있는) (노선, 역 id, 역 이름, 방향 코드). 방향 코드는 앱 규칙 —
상행 1·하행 2, 2호선만 내선 0·외선 1(subway_model.way_code). 앱이 방향으로 못 읽는 행은 버린다.
usage: python3 -I real_keys.py <arrival_key> out.tsv
"""
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import subway_model  # noqa: E402

key, out = sys.argv[1], sys.argv[2]
base = os.environ.get("SUBWAY_REAL_BASE", "http://swopenAPI.seoul.go.kr/api/subway")
with urllib.request.urlopen(f"{base}/{key}/json/realtimeStationArrival/ALL", timeout=30) as res:
    rows = json.loads(res.read().decode("utf-8")).get("realtimeArrivalList") or []
keys = {}
for r in rows:
    if r.get("arvlCd") == "2":
        continue
    code = subway_model.way_code(r.get("subwayId"), r.get("updnLine"))
    if code is None or not r.get("statnId") or not r.get("statnNm"):
        continue
    keys[(r["subwayId"], r["statnId"], code)] = r["statnNm"]
with open(out, "w") as f:
    for (line, station, code), name in sorted(keys.items()):
        f.write(f"{line}\t{station}\t{name}\t{code}\n")
print(f"rows={len(rows)} keys={len(keys)}", file=sys.stderr)
