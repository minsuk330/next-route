"""CardBusTimeNew 한 달 치를 받아 노선·정류장별 시간대 승차 수를 저장한다.
usage: python3 -I ridership_fetch.py <yyyymm> <out.json>  (env SEOUL_BUS_USAGE)"""
import json, os, sys, urllib.request
month, out = sys.argv[1], sys.argv[2]
key = os.environ["SEOUL_BUS_USAGE"]
rows, start, total = [], 1, None
while True:
    end = start + 999
    url = f"http://openapi.seoul.go.kr:8088/{key}/json/CardBusTimeNew/{start}/{end}/{month}/"
    d = json.loads(urllib.request.urlopen(url, timeout=60).read())
    body = d.get("CardBusTimeNew")
    if not body:
        print("no data", d); break
    total = body["list_total_count"]
    for r in body["row"]:
        on = {}
        for h in range(24):
            v = next((r[k] for k in r if k.startswith(f"HR_{h}_GET_ON")), 0)
            on[h] = float(v or 0)
        rows.append({"route": r.get("RTE_NO"), "stop": r.get("STOPS_ID"), "on": on})
    if len(rows) >= total: break
    start = end + 1
json.dump({"month": month, "total": total, "rows": rows}, open(out, "w"), ensure_ascii=False)
print(month, total, len(rows))
