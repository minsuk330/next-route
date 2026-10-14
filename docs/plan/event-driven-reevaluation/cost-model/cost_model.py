"""버스 실시간 호출 비용 모델: 여정별 키 폴링(현행) vs 노선 단위 감시(PR-B).
usage: python3 -I cost_model.py ridership.json <refresh_sec> [in_window=0.5] [bus_keys_per_journey=1.0]
가정(문서에 명시): 동시 여정 수는 시간대 승차량에 비례(피크 시간 = C), 각 여정의 다음 버스 승차 키는
피크 시간 승차 비중으로 뽑음, 실시간 창(20분) 안 비율 in_window, 창 안 등급 체류 비율 긴급 0.25·주의 0.5·여유 0.25."""
import json, random, sys
from collections import defaultdict
data = json.load(open(sys.argv[1]))
refresh = float(sys.argv[2])
in_window = float(sys.argv[3]) if len(sys.argv) > 3 else 0.5
keys_per = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
hour_tot = defaultdict(float)
for r in data["rows"]:
    for h, v in r["on"].items():
        hour_tot[int(h)] += v
peak_h = max(hour_tot, key=hour_tot.get)
# 시간대별 키 가중치
def weights(h):
    ks, ws = [], []
    for r in data["rows"]:
        v = r["on"][str(h)] if str(h) in r["on"] else r["on"].get(h, 0)
        if v > 0:
            ks.append((r["route"], r["stop"])); ws.append(v)
    return ks, ws
TIERS = [("U", 0.25), ("C", 0.5), ("R", 0.25)]
RANK = {"U": 0, "C": 1, "R": 2}
# 현행: 평가기 간격(긴급 10·주의 30·여유 120초), 같은 키는 15초 캐시가 묶음 → 키당 주기 = max(간격, 15)
CUR = {"U": 15, "C": 30, "R": 120}
# 감시자: 등급 주기(긴급 5·주의 15·여유 60초)와 공급자 갱신 주기 중 큰 값
W = {t: max(c, refresh) for t, c in {"U": 5, "C": 15, "R": 60}.items()}
def sim(n, ks, ws, rng):
    picks = rng.choices(range(len(ks)), weights=ws, k=n)
    tiers = rng.choices([t for t, _ in TIERS], weights=[w for _, w in TIERS], k=n)
    key_t, route_t = {}, {}
    for i, t in zip(picks, tiers):
        k = ks[i]; r = k[0]
        if k not in key_t or RANK[t] < RANK[key_t[k]]: key_t[k] = t
        if r not in route_t or RANK[t] < RANK[route_t[r]]: route_t[r] = t
    cur = sum(1 / CUR[t] for t in key_t.values())
    wat = sum(1 / W[t] for t in route_t.values())
    same = sum(1 / W[t] for t in key_t.values())
    return len(key_t), len(route_t), cur, wat, same
rng = random.Random(415)
cache = {}
print(f"peak hour={peak_h}, refresh={refresh}s, in_window={in_window}, bus_keys/journey={keys_per}")
print("C | 피크 시간 키/노선 | 피크 호출/초 | 하루 호출")
for C in (2000, 6000, 20000):
    day_cur = day_wat = day_same = 0.0
    peak_row = None
    for h in range(24):
        n = int(round(C * hour_tot[h] / hour_tot[peak_h] * in_window * keys_per))
        if n <= 0: continue
        if h not in cache: cache[h] = weights(h)
        kk, kr, cur, wat, same = sim(n, *cache[h], rng)
        day_cur += cur * 3600; day_wat += wat * 3600; day_same += same * 3600
        if h == peak_h: peak_row = (n, kk, kr, cur, wat, same)
    n, kk, kr, cur, wat, same = peak_row
    print(f"{C} | 창 안 여정 {n}: 키 {kk} / 노선 {kr} | 현행 {cur:.1f} / 같은주기 키폴링 {same:.1f} / 노선감시 {wat:.1f} | 하루 현행 {day_cur:,.0f} / 같은주기 키폴링 {day_same:,.0f} / 노선감시 {day_wat:,.0f}")
