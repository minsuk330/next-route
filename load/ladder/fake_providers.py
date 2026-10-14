"""부하 사다리용 가짜 공급자(#415-4). 서울 버스 도착·위치 XML, ML 서빙 JSON 을 운영 실측 지연으로 흉내 낸다.

실제 응답(route_all.xml, pos.xml)을 템플릿으로 노선·정류장·시각만 바꿔 돌려준다 — 파서가 운영과 같은
길을 탄다. 지연은 운영 Prometheus 7일 평균(서울 버스 73ms, ML 14ms)을 평균으로 하는 로그정규 분포에서
뽑고, SLOW_RATIO 비율로 SLOW_MS 꼬리를 섞는다(운영 최대 약 4초).

usage: python3 -I fake_providers.py <port>   (env BUS_MS=73 ML_MS=14 SLOW_RATIO=0.001 SLOW_MS=4000)
"""
import json, math, os, random, re, sys, threading, time

os.environ.setdefault("PYTHONHASHSEED", "0")
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
KST = timezone(timedelta(hours=9))
BUS_MS = float(os.environ.get("BUS_MS", "73"))
ML_MS = float(os.environ.get("ML_MS", "14"))
SLOW_RATIO = float(os.environ.get("SLOW_RATIO", "0.001"))
SLOW_MS = float(os.environ.get("SLOW_MS", "4000"))

ROUTE_ALL = open(os.path.join(HERE, "route_all.xml"), encoding="utf-8").read()
POS = open(os.path.join(HERE, "pos.xml"), encoding="utf-8").read()
HEAD = ROUTE_ALL[:ROUTE_ALL.find("<itemList>")]
TAIL = ROUTE_ALL[ROUTE_ALL.rfind("</itemList>") + len("</itemList>"):]
ITEMS = re.findall(r"<itemList>.*?</itemList>", ROUTE_ALL, re.S)
RUNNING = [it for it in ITEMS if "<vehId1>0</vehId1>" not in it] or ITEMS
POS_HEAD = POS[:POS.find("<itemList>")]
POS_TAIL = POS[POS.rfind("</itemList>") + len("</itemList>"):]
POS_ITEMS = re.findall(r"<itemList>.*?</itemList>", POS, re.S)
counts = {"arrival": 0, "route_all": 0, "pos": 0, "ml": 0}
lock = threading.Lock()


def sleep_like(mean_ms):
    if random.random() < SLOW_RATIO:
        time.sleep(SLOW_MS / 1000)
        return
    sigma = 0.6
    mu = math.log(max(mean_ms, 1)) - sigma * sigma / 2
    time.sleep(random.lognormvariate(mu, sigma) / 1000)


def sub(tag, value, xml):
    return re.sub(rf"<{tag}>.*?</{tag}>", f"<{tag}>{value}</{tag}>", xml, count=1, flags=re.S)


def now_mk():
    return datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S.0")


def arrival_item(route_id, stop_id, seq, base):
    item = base
    item = sub("busRouteId", route_id, item)
    item = sub("stId", stop_id, item)
    item = sub("staOrd", seq, item)
    return sub("mkTm", now_mk(), item)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, body, ctype):
        data = body.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path.endswith("/arrive/getArrInfoByRoute"):
            with lock:
                counts["arrival"] += 1
            sleep_like(BUS_MS)
            # 정류장마다 같은 차량·같은 예정 시각을 돌려준다. 매 호출 무작위면 판정이 늘 "바뀜"이 돼 무변화
            # 백오프가 안 걸리고 조회도 늘 200 이라, 운영보다 평가·조회 부하가 부풀려진다(첫 사다리에서 확인).
            stop = q.get("stId", "")
            item = arrival_item(q.get("busRouteId", ""), stop, q.get("ord", "1"),
                                RUNNING[hash(stop) % len(RUNNING)])
            return self.send(HEAD + item + TAIL, "application/xml;charset=UTF-8")
        if url.path.endswith("/arrive/getArrInfoByRouteAll"):
            with lock:
                counts["route_all"] += 1
            sleep_like(BUS_MS)
            route = q.get("busRouteId", "")
            body = "".join(arrival_item(route, f"{route}-S{i}", str(i), it) for i, it in enumerate(ITEMS[:40], 1))
            return self.send(HEAD + body + TAIL, "application/xml;charset=UTF-8")
        if url.path.endswith("/buspos/getBusPosByRtid"):
            with lock:
                counts["pos"] += 1
            sleep_like(BUS_MS)
            tm = datetime.now(KST).strftime("%Y%m%d%H%M%S")
            body = "".join(sub("dataTm", tm, it) for it in POS_ITEMS[:10])
            return self.send(POS_HEAD + body + POS_TAIL, "application/xml;charset=UTF-8")
        if url.path.endswith("/metadata"):
            # 심은 노선(LOAD-ROUTE-n)을 모델 지원 노선으로 알린다 — 먼 환승은 ML 경로를 탄다(운영과 같은 갈래)
            cats = [f"LOAD-ROUTE-{i}" for i in range(int(os.environ.get("LADDER_MAX_ROUTES", "30000")))]
            return self.send(json.dumps({"model_version": "ladder", "route_categories": cats}), "application/json")
        if url.path.endswith("/counts"):
            return self.send(json.dumps(counts), "application/json")
        self.send_response(404)
        self.end_headers()

    def read_body(self):
        # JDK HttpClient 는 평문 HTTP 에서 h2c 업그레이드를 시도하며 본문을 chunked 로 보낼 수 있다
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            body = b""
            while True:
                size = int(self.rfile.readline().strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    return body
                body += self.rfile.read(size)
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def do_POST(self):
        url = urlparse(self.path)
        payload = json.loads(self.read_body() or b"{}")
        if url.path.endswith("/predict"):
            with lock:
                counts["ml"] += 1
            sleep_like(ML_MS)
            results = [{"request_id": it["request_id"], "status": "AVAILABLE",
                        "seconds_to_arrival": 300.0, "model_version": "ladder"}
                       for it in payload.get("items", [])]
            return self.send(json.dumps({"results": results}), "application/json")
        self.send_response(404)
        self.end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18999
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
