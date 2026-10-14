"""route-all 응답을 주기적으로 받아 정류장별 mkTm(공급자 생성 시각)이 언제 바뀌는지 기록한다.
usage: python3 -I mktm_sampler.py <out.jsonl> <interval_sec> <duration_sec> routeId...
키는 env SEOUL_BUS_API(이미 인코딩된 data.go.kr 키, 다시 인코딩하지 않음)."""
import json, os, sys, time, urllib.request, xml.etree.ElementTree as ET
out, interval, duration = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
routes = sys.argv[4:]
key = os.environ["SEOUL_BUS_API"]
end = time.time() + duration
with open(out, "a") as f:
    while time.time() < end:
        for r in routes:
            t0 = time.time()
            url = f"http://ws.bus.go.kr/api/rest/arrive/getArrInfoByRouteAll?serviceKey={key}&busRouteId={r}"
            try:
                body = urllib.request.urlopen(url, timeout=10).read()
                root = ET.fromstring(body)
                items = []
                for it in root.iter("itemList"):
                    g = lambda n: (it.findtext(n) or "").strip()
                    items.append({"stId": g("stId"), "seq": g("staOrd"), "mkTm": g("mkTm"),
                                  "v1": g("vehId1"), "v2": g("vehId2"),
                                  "s1": g("exps1") or g("traTime1"), "s2": g("exps2") or g("traTime2"),
                                  "msg1": g("arrmsg1")})
                hdr = root.findtext(".//headerCd")
                f.write(json.dumps({"t": t0, "route": r, "hdr": hdr, "lat": time.time() - t0, "items": items}, ensure_ascii=False) + "\n")
            except Exception as e:
                f.write(json.dumps({"t": t0, "route": r, "err": str(e)}) + "\n")
            f.flush()
        time.sleep(max(0, interval - (time.time() % interval)))
