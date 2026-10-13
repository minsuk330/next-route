"""경기 버스 노선 유형(GBIS routeTypeCd) → src/main/resources/bus/gyeonggi-route-types.csv (#391).

사용:
  DATA_GO_KR_KEY=<공공데이터포털 인증키> python3 -I tools/gyeonggi-bus-route-type/fetch_route_types.py <routes.txt> \
      > src/main/resources/bus/gyeonggi-route-types.csv

<routes.txt> = OTP 그래프에 넣은 GTFS routes.txt(예: tools/transit-routing-benchmark/data/otp-national/shape-build/
gtfs-out/routes.txt). 경기 노선 ID 는 BR_TAGO_GGB<9자리> 와 마을버스 BR_3100_<9자리> 의 9자리(= GBIS routeId)다.

키: 공공데이터포털 "경기도_버스노선 조회"(busrouteservice/v2). env/api.env 의 키는 이미 URL 인코딩된 값이라
'%' 가 있으면 그대로 붙인다 — 한 번 더 인코딩하면 SERVICE_KEY_IS_NOT_REGISTERED_ERROR 가 난다. 키는 출력·커밋 금지.

수집: 노선번호 검색(getBusRouteListv2)을 키워드 0~9·M·G 로 12회 → 응답에 routeTypeCd 가 같이 온다. 검색은 전부를
돌려주지 않으므로(키워드 4 는 100건뿐) GTFS 에 있는데 못 찾은 노선만 노선정보(getBusRouteInfoItemv2)로 한 건씩
채운다. 그래도 없는 노선(resultCode 4)은 GTFS 이후 폐선·번호 변경이라 버린다.
"""
import csv
import datetime
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://apis.data.go.kr/6410000/busrouteservice/v2"
GG_ROUTE = re.compile(r"^BR_(?:TAGO_GGB|3100_)(\d{9})(?:_[UD])?$")
KEYWORDS = list("0123456789") + ["M", "G"]


def call(key, path, params, attempts=3):
    """연결이 가끔 끊긴다(실측 수백 회 중 1번) — 같은 요청을 몇 초 쉬고 다시 보낸다."""
    q = urllib.parse.urlencode({"format": "json", **params})
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(f"{BASE}/{path}?{q}&serviceKey={key}", timeout=30) as resp:
                return json.load(resp)["response"]
        except OSError as e:
            if attempt == attempts:
                raise
            print(f"재시도 {path} {params} ({e})", file=sys.stderr)
            time.sleep(5 * attempt)


def items(body, field):
    value = (body.get("msgBody") or {}).get(field) or []
    return value if isinstance(value, list) else [value]


def main():
    raw_key = os.environ["DATA_GO_KR_KEY"]
    key = raw_key if "%" in raw_key else urllib.parse.quote(raw_key, safe="")
    with open(sys.argv[1], encoding="utf-8") as f:
        wanted = {m.group(1) for r in csv.DictReader(f) for m in [GG_ROUTE.match(r["route_id"])] if m}

    found = {}
    for keyword in KEYWORDS:
        body = call(key, "getBusRouteListv2", {"keyword": keyword})
        for it in items(body, "busRouteList"):
            found[str(it["routeId"])] = (str(it["routeName"]), int(it["routeTypeCd"]))
        time.sleep(1.0)
    by_search = len(wanted & found.keys())

    gone = 0
    for route_id in sorted(wanted - found.keys()):
        body = call(key, "getBusRouteInfoItemv2", {"routeId": route_id})
        info = items(body, "busRouteInfoItem")
        if info and info[0].get("routeTypeCd") is not None:
            found[route_id] = (str(info[0]["routeName"]), int(info[0]["routeTypeCd"]))
        else:
            gone += 1
        time.sleep(0.3)

    print(f"GTFS 경기 노선 {len(wanted)}: 검색 {by_search}, 단건 {len(wanted & found.keys()) - by_search}, "
          f"GBIS 에 없음 {gone}. 전체 행 {len(found)}", file=sys.stderr)
    out = csv.writer(sys.stdout, lineterminator="\n")
    print(f"# source=GBIS busrouteservice/v2 fetched={datetime.date.today().isoformat()} "
          f"gtfs={os.path.basename(os.path.dirname(os.path.abspath(sys.argv[1])))}/routes.txt")
    out.writerow(["route_id", "route_name", "route_type_cd"])
    for route_id in sorted(found):
        name, type_cd = found[route_id]
        out.writerow([route_id, name.replace(",", " "), type_cd])


if __name__ == "__main__":
    main()
