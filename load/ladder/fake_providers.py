"""부하 사다리용 가짜 공급자(#415-4). 서울 버스 도착·위치 XML, ML 서빙 JSON 을 운영 실측 지연으로 흉내 낸다.

실제 응답(route_all.xml, pos.xml)을 템플릿으로 노선·정류장·시각만 바꿔 돌려준다 — 파서가 운영과 같은
길을 탄다. 지연은 운영 Prometheus 7일 평균(서울 버스 73ms, ML 14ms)을 평균으로 하는 로그정규 분포에서
뽑고, SLOW_RATIO 비율로 SLOW_MS 꼬리를 섞는다(운영 최대 약 4초).

usage: python3 -I fake_providers.py <port>   (env BUS_MS=73 ML_MS=14 SLOW_RATIO=0.001 SLOW_MS=4000)

지하철(#427): 서울 실시간 도착 JSON 두 종류(전 노선 ALL·역별)를 **한 가상 열차 모델**(subway_model.py)에서 만든다.
같은 시각엔 같은 답이라 수집기가 본 변화와 평가기가 역별로 읽는 값이 일치한다. ALL 을 낼 때마다 (노선, 역, 방향)
지문이 바뀐 키를 SUBWAY_CHANGE_LOG 에 적는다(원천 시각·처음 낸 시각) — 감지 지연 집계(subway_detection.py)의 재료.
"""
import json, math, os, random, re, sys, threading, time

os.environ.setdefault("PYTHONHASHSEED", "0")
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

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
counts = {"arrival": 0, "route_all": 0, "pos": 0, "ml": 0, "subway_all": 0, "subway_station": 0}
sys.path.insert(0, HERE)
import subway_model  # noqa: E402  (같은 폴더, -I 라 경로를 직접 넣는다)

SUBWAY_SOURCE = os.environ.get("SUBWAY_SOURCE", "real")  # real = 서울 실시간 도착 API 로 넘김, model = 가상 열차
SUBWAY_MS = float(os.environ.get("SUBWAY_MS", "80"))
_change_log_path = os.environ.get("SUBWAY_CHANGE_LOG", os.path.join(HERE, "out", "subway-changes.tsv"))
if SUBWAY_SOURCE == "model":
    SUBWAY = subway_model.Model.from_env()
    SUBWAY_LOG = subway_model.ChangeLog(SUBWAY, _change_log_path)
else:
    SUBWAY, SUBWAY_LOG = None, subway_model.RealChangeLog(_change_log_path)
REAL_BASE = os.environ.get("SUBWAY_REAL_BASE", "http://swopenAPI.seoul.go.kr/api/subway")
# 역별 요청에 내줄 실제 ALL 의 최대 나이. 운영에서 역별 요청(아래 '?' 버그)은 그때그때 ALL 을 새로 받는다 — 여기선
# 서울 API 에 초당 수십 번 2MB 를 받지 않도록 이 간격으로만 새로 받고, 대신 응답 지연은 실측 ALL 지연만큼 쉰다.
STATION_REFRESH_S = float(os.environ.get("SUBWAY_STATION_REFRESH_S", "5"))
real_state = {"body": None, "rows": [], "at": 0.0, "latency": 0.6, "fetches": 0}
real_lock = threading.Lock()


def fetch_real_all(key):
    import urllib.request
    started = time.time()
    with urllib.request.urlopen(f"{REAL_BASE}/{key}/json/realtimeStationArrival/ALL", timeout=20) as res:
        body = res.read().decode("utf-8")
    latency = time.time() - started
    rows = json.loads(body).get("realtimeArrivalList") or []
    with real_lock:
        real_state.update(body=body, rows=rows, at=time.time(), fetches=real_state["fetches"] + 1,
                          latency=0.8 * real_state["latency"] + 0.2 * latency)
        counts["real_all_fetch"] = real_state["fetches"]
        counts["real_all_latency_ms_ema"] = round(real_state["latency"] * 1000)
    return body, rows
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
        if "/json/realtimeStationArrival/" in url.path:
            return self.subway(url.path)
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

    def subway(self, path):
        if SUBWAY_SOURCE != "model":
            return self.subway_real(path)
        sleep_like(SUBWAY_MS)
        now = time.time()
        if path.endswith("/realtimeStationArrival/ALL"):
            with lock:
                counts["subway_all"] += 1
            rows = SUBWAY.all_rows(now)
            body = subway_model.envelope(rows)
            self.send(body, "application/json;charset=UTF-8")
            # 응답을 다 보낸 시각 = 수집기가 이 변화를 "처음 본" 시각(±응답 처리 시간)
            SUBWAY_LOG.observe(now, time.time())
            return
        with lock:
            counts["subway_station"] += 1
        # 🔴 앱의 RestTemplate(Apache HttpClient 5)은 URI.create 로 만든 경로의 한글을 퍼센트 인코딩하지 않고 요청 줄에
        # '?' 로 바꿔 보낸다 → "/0/1000/???61"(경로는 "/0/1000/", 나머지는 쿼리). 이 가짜는 역 이름이 "부하역{n}" 이라
        # 남는 숫자로 역을 되찾는다 — 운영 원천은 그렇게 못 한다(#427 노트 함정·미결).
        raw = self.path.split("/0/1000/", 1)[-1]
        name = unquote(raw)
        digits = re.search(r"(\d+)$", name)
        if digits and not name.startswith("부하역"):
            name = "부하역" + digits.group(1)
        rows = SUBWAY.station_rows(name, now)
        if not rows:
            print("subway station: no rows for", repr(self.path), repr(self.requestline), repr(name), file=sys.stderr, flush=True)
        return self.send(subway_model.envelope(rows), "application/json;charset=UTF-8")

    def subway_real(self, path):
        key = path.split("/subway/", 1)[-1].split("/", 1)[0]
        if path.endswith("/realtimeStationArrival/ALL"):
            with lock:
                counts["subway_all"] += 1
            body, rows = fetch_real_all(key)
            self.send(body, "application/json;charset=UTF-8")
            SUBWAY_LOG.observe(rows, time.time())
            return
        with lock:
            counts["subway_station"] += 1
        tail = self.path.split("/0/1000/", 1)[-1]
        if tail == "" or tail.startswith("?"):
            # 🔴 운영 그대로: 앱(Apache HttpClient 5)이 한글 역 이름을 '?' 로 보내 경로가 "/0/1000/" 로 끝나고, 서울 API 는
            # 그것을 전 노선 ALL(약 3,000행·2MB)로 답한다(2026-10-15 실측 total=2997). 그래서 ALL 을 그대로 준다.
            with lock:
                counts["subway_station_as_all"] = counts.get("subway_station_as_all", 0) + 1
            with real_lock:
                stale = time.time() - real_state["at"] > STATION_REFRESH_S
            if stale:
                body, _ = fetch_real_all(key)
            else:
                with real_lock:
                    body = real_state["body"]
                time.sleep(real_state["latency"])
            return self.send(body, "application/json;charset=UTF-8")
        # 역 이름이 제대로 인코딩돼 온 경우(앱이 고쳐진 뒤): 그 역 행만
        name = unquote(tail)
        sleep_like(SUBWAY_MS)
        with real_lock:
            rows = [r for r in real_state["rows"] if r.get("statnNm") == name]
        return self.send(subway_model.envelope(rows), "application/json;charset=UTF-8")

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
