"""지하철 부하 사다리(#427)의 가상 열차 모델. 같은 시각엔 같은 답을 내는 시간 함수다.

노선망 규칙은 SubwayLadderFixture(src/test)와 같다: 노선 SUBWAY_LINES × 역 SUBWAY_STATIONS, 역 번호
n = 노선 순번 × 역 수 + 노선 안 순서, 역 id LOADST{n}, 이름 부하역{n}. 하행은 노선 안 순서 0 → S-1, 상행은 반대.

열차: 노선·방향마다 SUBWAY_HEADWAY(180)초 간격으로 출발, 역 사이 SUBWAY_RUN(100)초 + 정차 SUBWAY_DWELL(20)초.
열차마다 역을 지날 때 지연이 조금씩 쌓인다(결정적 난수, -8~+14초, 누적 0~120초 — 앞 열차를 추월하지 않음).
도착 예정 초(barvlDt)는 "지금 위치에서 지연 없이 달린다면"으로 내므로 지연이 쌓이면 예정이 뒤로 밀린다
(실제 원천처럼) — 사용자의 환승 여유가 시간에 따라 바뀌어 판정·알림이 생긴다.

원천 나이: 원천은 받는 순간 이미 약 50초 지난 데이터다(#427 운영 실측 1~9호선 p50 45~54초). 키마다
SUBWAY_AGE ± SUBWAY_AGE_JITTER 초(키별 고정) 전의 상태를 내고 recptnDt 도 그 시각이다.

지문(앱 SubwayChangeFeed 와 같은 규칙): (노선, 역, 방향)마다 남은 초 순 앞 두 대의 (열차 번호, 도착 코드, 현재 역).
도착 코드는 99(운행 중)·1(도착)만 쓴다 — 진입·전역 코드까지 쓰면 지문이 20초꼴로 바뀌어 "1~2분마다"보다 잦다.
"""
import json
import math
import os
from datetime import datetime, timedelta, timezone
from functools import lru_cache
import threading

KST = timezone(timedelta(hours=9))
T0 = 1_760_000_000  # 모델 시계의 원점(고정 — 언제 띄워도 같은 시각엔 같은 답)
MAX_DELAY = 120


def _hash(*parts):
    x = 2166136261
    for p in parts:
        x ^= (p * 2654435761) & 0xFFFFFFFF
        x = (x * 16777619) & 0xFFFFFFFF
        x ^= x >> 13
        x = (x * 0x5BD1E995) & 0xFFFFFFFF
        x ^= x >> 15
    return x


def _u(*parts):
    return _hash(*parts) / 2 ** 32


def kst(epoch):
    return datetime.fromtimestamp(epoch, KST).strftime("%Y-%m-%d %H:%M:%S")


class Model:
    def __init__(self, lines, stations, headway=180, run=100, dwell=20, age=50, age_jitter=5, per_key=3):
        self.lines = lines
        self.S = stations
        self.H = headway
        self.R = run
        self.W = dwell
        self.age = age
        self.jitter = age_jitter
        self.per_key = per_key
        self.hop = run + dwell

    @classmethod
    def from_env(cls):
        e = os.environ
        lines = e.get("SUBWAY_LINES", "1001,1003,1004,1005,1006,1007,1008,1009").split(",")
        return cls(lines, int(e.get("SUBWAY_STATIONS", "60")), int(e.get("SUBWAY_HEADWAY", "180")),
                   int(e.get("SUBWAY_RUN", "100")), int(e.get("SUBWAY_DWELL", "20")),
                   int(e.get("SUBWAY_AGE", "50")), int(e.get("SUBWAY_AGE_JITTER", "5")),
                   int(e.get("SUBWAY_TRAINS_PER_KEY", "3")))

    # ── 노선망 ──
    def keys(self):
        for li, line in enumerate(self.lines):
            for p in range(self.S):
                n = li * self.S + p
                for way in (1, 2):
                    yield li, line, n, way

    def position(self, n, way):
        """역 n 이 그 방향 운행에서 몇 번째인가."""
        local = n % self.S
        return local if way == 2 else self.S - 1 - local

    def station(self, li, pos, way):
        local = pos if way == 2 else self.S - 1 - pos
        return li * self.S + local

    def key_age(self, n, way):
        return self.age + (_hash(n, way, 7) % (2 * self.jitter + 1)) - self.jitter

    def offset(self, li, way):
        return int(_u(li, way, 3) * self.H)

    # ── 열차 ──
    @lru_cache(maxsize=400_000)
    def arrivals(self, li, way, k):
        """열차 k 가 각 운행 순번 역에 실제로 닿는 시각(정수 epoch). 출발 = 도착 + 정차."""
        dep0 = T0 + k * self.H + self.offset(li, way)
        out = [dep0 - self.W]
        delay = 0
        for p in range(1, self.S):
            delay = min(MAX_DELAY, max(0, delay + int(_u(li, way, k, p) * 23) - 8))
            out.append(dep0 + p * self.hop - self.W + delay)
        return tuple(out)

    def train_no(self, li, way, k):
        return f"{li + 1}{way}{k % 10000:04d}"

    def rows_for(self, li, line, n, way, s):
        """상태 시각 s 에 역 n·방향 way 로 다가오는(아직 안 떠난) 열차 앞 per_key 대. 남은 초 순."""
        p = self.position(n, way)
        base = T0 + self.offset(li, way) + p * self.hop
        k = (s - base - MAX_DELAY) // self.H - 1
        rows = []
        while len(rows) < self.per_key:
            arr = self.arrivals(li, way, k)
            if arr[p] + self.W > s:  # 이 역을 아직 안 떠났다(기점 출발 전 열차는 기점에 서 있는 것으로 낸다)
                rows.append(self.row(li, line, n, way, p, k, arr, s))
            k += 1
        rows.sort(key=lambda r: int(r["barvlDt"]))
        return rows

    def row(self, li, line, n, way, p, k, arr, s):
        c = max((i for i in range(self.S) if arr[i] <= s), default=0)  # 마지막으로 닿은 운행 순번
        dwelling = s < arr[c] + self.W
        if arr[0] > s:
            # 기점 출발 전(시간표상 대기) — 기점에 있는 것으로, 예정은 시간표 그대로
            code, seconds = "99", max(1, arr[0] + p * self.hop - s)
        elif c == p:
            code, seconds = "1", 0
        else:
            code = "99"
            # 지금 위치에서 지연 없이 달린다면의 예정(실제 원천처럼 쌓일 지연을 모른다)
            seconds = max(1, arr[c] + self.W + (p - c) * self.hop - self.W - s)
        cur = self.station(li, c, way)
        prev_n = self.station(li, max(p - 1, 0), way)
        next_n = self.station(li, min(p + 1, self.S - 1), way)
        term = self.station(li, self.S - 1, way)
        msg2 = f"부하역{n} 도착" if c == p else f"[{p - c}]번째 전역 (부하역{cur})"
        return {
            "subwayId": line, "updnLine": "하행" if way == 2 else "상행",
            "trainLineNm": f"부하역{term}행 - 부하역{next_n}방면",
            "statnFid": f"LOADST{prev_n}", "statnTid": f"LOADST{next_n}",
            "statnId": f"LOADST{n}", "statnNm": f"부하역{n}",
            "trnsitCo": "1", "ordkey": f"{way - 1}{len(str(p))}{p - c:03d}부하역{term}0",
            "subwayList": line, "statnList": f"LOADST{n}",
            "btrainSttus": "일반", "barvlDt": str(seconds), "btrainNo": self.train_no(li, way, k),
            "bstatnId": f"LOADST{term}", "bstatnNm": f"부하역{term}",
            "recptnDt": kst(s), "arvlMsg2": msg2, "arvlMsg3": f"부하역{cur}",
            "arvlCd": code, "lstcarAt": "0", "_dwelling": dwelling,
        }

    def state_time(self, n, way, now):
        return int(now) - self.key_age(n, way)

    def all_rows(self, now):
        out = []
        for li, line, n, way in self.keys():
            out.extend(self.rows_for(li, line, n, way, self.state_time(n, way, now)))
        return out

    def station_rows(self, name, now):
        try:
            n = int(name.replace("부하역", ""))
        except ValueError:
            return []
        li = n // self.S
        if li >= len(self.lines):
            return []
        out = []
        for way in (1, 2):
            out.extend(self.rows_for(li, self.lines[li], n, way, self.state_time(n, way, now)))
        return out

    def fingerprint(self, li, line, n, way, s):
        rows = [r for r in self.rows_for(li, line, n, way, s) if r["arvlCd"] != "2"]
        marks = []
        for r in rows:
            m = (r["btrainNo"], r["arvlCd"], r["arvlMsg3"])
            if m not in marks:
                marks.append(m)
        return tuple(marks[:2])


def envelope(rows):
    clean = [{k: v for k, v in r.items() if not k.startswith("_")} for r in rows]
    return json.dumps({
        "errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다.",
                         "link": "", "developerMessage": "", "total": len(clean)},
        "realtimeArrivalList": clean}, ensure_ascii=False)


class ChangeLog:
    """ALL 을 낼 때마다 지문이 바뀐 키를 적는다.

    한 줄 = 변화 하나: line, stationId, way, source_epoch(원천에서 지문이 지금 값이 된 시각),
    visible_epoch(= 원천 + 나이: 역별 조회에도 보이기 시작한 시각), first_seen_epoch(그 변화를 담은 ALL 을 처음
    다 보낸 시각 = 수집기가 처음 본 시각), skipped(두 ALL 사이에 지나가 버린 중간 변화 수).
    """

    def __init__(self, model, path):
        self.m = model
        self.path = path
        self.last = {}
        self.lock = threading.Lock()
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w") as f:
            f.write("line\tstationId\tway\tsource_epoch\tvisible_epoch\tfirst_seen_epoch\tskipped\n")

    def observe(self, served_at, first_seen):
        lines = []
        with self.lock:
            for li, line, n, way in self.m.keys():
                s = self.m.state_time(n, way, served_at)
                fp = self.m.fingerprint(li, line, n, way, s)
                prev = self.last.get((li, n, way))
                self.last[(li, n, way)] = (s, fp)
                if prev is None or prev[1] == fp:
                    continue
                prev_s = prev[0]
                # 두 ALL 사이를 1초씩 되짚어 지문이 지금 값이 된 시각과 중간 변화 수를 찾는다
                change_at, skipped, cur = s, -1, prev[1]
                for t in range(prev_s + 1, s + 1):
                    f = self.m.fingerprint(li, line, n, way, t)
                    if f != cur:
                        skipped += 1
                        cur = f
                        change_at = t
                age = self.m.key_age(n, way)
                lines.append(f"{line}\tLOADST{n}\t{way}\t{change_at}\t{change_at + age}\t{first_seen:.3f}\t{max(skipped, 0)}\n")
            with open(self.path, "a") as f:
                f.writelines(lines)


# ── 실제 원천(SUBWAY_SOURCE=real) ──────────────────────────────────────────────

WAY = {"상행": 1, "하행": 2, "내선": 0, "외선": 1}  # SubwayDirectionNormalizer.toWayCode


def way_code(line, label):
    """앱이 대기 단계 방향으로 읽을 수 있는 코드. 2호선만 내·외선, 그 밖은 상·하행만 받는다."""
    if line == "1002":
        return {"내선": 0, "외선": 1}.get(label)
    return {"상행": 1, "하행": 2}.get(label)


def parse_kst(text):
    try:
        return datetime.strptime(text, "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST).timestamp()
    except (TypeError, ValueError):
        return None


def real_fingerprints(rows):
    """앱 SubwayChangeFeed.fingerprints 와 같은 규칙: (노선, 역, 방향)별 떠난 열차(2) 빼고 남은 초 순 앞 두 대의
    (열차 번호, 도착 코드, 현재 역). 같이 그 두 행의 가장 늦은 recptnDt(원천 시각)를 돌려준다."""
    grouped = {}
    for r in rows:
        if not r.get("subwayId") or not r.get("statnId") or not r.get("updnLine"):
            continue
        if r.get("arvlCd") == "2":
            continue
        grouped.setdefault((r["subwayId"], r["statnId"], r["updnLine"]), []).append(r)
    out = {}
    for key, lst in grouped.items():
        def sec(r):
            try:
                return (0, int(r.get("barvlDt")))
            except (TypeError, ValueError):
                return (1, 0)
        marks, src = [], []
        for r in sorted(lst, key=sec):  # 파이썬 정렬은 안정 — 앱(자바 stable sort)과 같은 순서
            m = (r.get("btrainNo"), r.get("arvlCd"), r.get("arvlMsg3"))
            if m not in marks:
                marks.append(m)
                src.append(parse_kst(r.get("recptnDt")))
            if len(marks) == 2:
                break
        out[key] = (tuple(marks), max((x for x in src if x is not None), default=None))
    return out


class RealChangeLog:
    """수집기에 넘긴 실제 ALL 응답마다 지문이 바뀐 키를 적는다(가상 모델 ChangeLog 와 같은 칸).

    원천 시각 = 새 지문을 이루는 두 행의 가장 늦은 recptnDt. 키가 사라지면(열차가 다 지나감) 원천 시각을 모르므로
    처음 본 시각을 쓴다. 키가 새로 생기는 것도 앱 감지기처럼 변화로 센다(첫 장은 기준선).
    visible_epoch 칸은 실제 원천에선 원천 시각과 같다(역별 조회도 같은 원천이라 따로 보이는 시각이 없다).
    """

    def __init__(self, path):
        self.path = path
        self.last = None
        self.lock = threading.Lock()
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w") as f:
            f.write("line\tstationId\tway\tsource_epoch\tvisible_epoch\tfirst_seen_epoch\tskipped\n")

    def observe(self, rows, first_seen):
        fps = real_fingerprints(rows)
        lines = []
        with self.lock:
            prev, self.last = self.last, fps
            if prev is None:
                return
            for key in set(prev) | set(fps):
                before, now = prev.get(key), fps.get(key)
                if before is not None and now is not None and before[0] == now[0]:
                    continue
                code = way_code(key[0], key[2])
                if code is None:
                    continue
                source = now[1] if now is not None and now[1] is not None else first_seen
                lines.append(f"{key[0]}\t{key[1]}\t{code}\t{source:.0f}\t{source:.0f}\t{first_seen:.3f}\t0\n")
            with open(self.path, "a") as f:
                f.writelines(lines)
