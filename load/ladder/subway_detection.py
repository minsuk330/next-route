"""지하철 사다리(#427) 감지 지연 집계: 공급자 변화 기록 × 표본기 평가 기록 × 심은 여정 표.

변화 하나(키 = 노선·역·방향)마다 그 키를 지켜보는 여정들이 **처음 본 시각 이후 처음 평가를 마친 시각**을 찾는다.
  A. 처음 본 시각(그 변화를 담은 ALL 을 다 보낸 시각) → 판정 저장   — 서버 SLO(긴급 15s / 주의 30s / 여유 120s)
  B. 원천 시각(recptnDt 기준으로 지문이 바뀐 시각) → 판정 저장        — 체감(참고), 원천 나이 약 50초 + 수집 주기 포함
등급은 **평가 시점의 등급**: 처음 본 시각 직전 평가가 남긴 준비 시각·상태로 InterestIndex.tierOf 와 같은 규칙
(빠듯·놓침이면 긴급, 준비까지 5분 이내 긴급, 15분 이내 주의, 그 밖·모름 여유). 직전 평가가 없으면 '모름'으로 따로 센다.
측정 창 [from, to] 의 변화만 센다. to 는 끝보다 130초 앞(여유 SLO 2분이 끝나기 전에 끝나는 변화를 놓침으로 세지 않게).
창 안인데 끝까지 평가가 없으면 '미판정'으로 무한대 지연 처리(백분위에 포함).

정확도: 판정 저장 시각 = last_evaluated_at(앱 시계, ms). 처음 본 시각 = 공급자가 응답을 다 쓴 시각(같은 노트북 시계).
수집기가 응답을 파싱·저장·비교하는 시간(수백 ms)은 A 에 포함된다. 한 여정이 1초 안에 평가를 두 번 마치면 앞의 것이
표본에 안 잡혀 A 가 최대 1초 커질 수 있다. 처음 본 시각 직후 아직 지워지지 않은 역별 캐시(10초)를 읽은 평가가
있으면 실제로는 새 값을 못 본 평가를 감지로 세 A 가 작게 나올 수 있다(트리거 전 캐시 삭제 사이 수백 ms 창).

usage: python3 -I subway_detection.py changes.tsv samples.tsv seed.tsv from_epoch to_epoch
"""
import bisect
import math
import sys
from collections import defaultdict

changes_path, samples_path, seed_path = sys.argv[1:4]
t_from, t_to = float(sys.argv[4]), float(sys.argv[5])
SLO = {"urgent": 15, "caution": 30, "relaxed": 120}

journeys_by_key = defaultdict(list)
cls_by_journey = {}
for line in open(seed_path):
    p = line.rstrip("\n").split("\t")
    if len(p) < 6:
        continue
    jid = int(p[0])
    journeys_by_key[(p[2], p[3], p[4])].append(jid)
    cls_by_journey[jid] = p[5]

evals = defaultdict(list)  # jid -> [(evaluated_at, ready_at|None, status)]
for i, line in enumerate(open(samples_path)):
    if i == 0:
        continue
    p = line.rstrip("\n").split("\t")
    if len(p) != 6:
        continue
    evals[int(p[1])].append((float(p[2]), float(p[4]) if p[4] else None, p[5]))
for v in evals.values():
    v.sort()
times = {j: [e[0] for e in v] for j, v in evals.items()}


def tier_of(prev, at):
    if prev is None:
        return "unknown"
    _, ready, status = prev
    if status in ("AT_RISK", "MISSED"):
        return "urgent"
    if ready is None:
        return "relaxed"
    until = ready - at
    return "urgent" if until <= 300 else "caution" if until <= 900 else "relaxed"


lat_a, lat_b, lat_v = defaultdict(list), defaultdict(list), defaultdict(list)
censored = defaultdict(int)
n_changes = n_pairs = skipped = 0
keys_seen = set()
for i, line in enumerate(open(changes_path)):
    if i == 0:
        continue
    line_id, station, way, source, visible, first_seen, skip = line.rstrip("\n").split("\t")
    first_seen, source, visible = float(first_seen), float(source), float(visible)
    if not (t_from <= first_seen <= t_to):
        continue
    n_changes += 1
    skipped += int(skip)
    keys_seen.add((line_id, station, way))
    for jid in journeys_by_key.get((line_id, station, way), []):
        n_pairs += 1
        ts = times.get(jid, [])
        k = bisect.bisect_left(ts, first_seen)
        prev = evals[jid][k - 1] if k > 0 else None
        tier = tier_of(prev, first_seen)
        if k >= len(ts):
            censored[tier] += 1
            lat_a[tier].append(math.inf)
            lat_b[tier].append(math.inf)
            lat_v[tier].append(math.inf)
            continue
        e = ts[k]
        lat_a[tier].append(e - first_seen)
        lat_b[tier].append(e - source)
        lat_v[tier].append(e - visible)


def q(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    return s[min(len(s) - 1, max(0, math.ceil(p * len(s)) - 1))]


def f(v):
    return "∞" if v is not None and math.isinf(v) else ("-" if v is None else f"{v:.1f}")


span_min = max(1e-9, (t_to - t_from) / 60)
print(f"감지 창 {t_to - t_from:.0f}s · 변화 {n_changes}건(키 {len(keys_seen)}개, 키당 분당 "
      f"{n_changes / max(1, len(keys_seen)) / span_min:.2f}) · ALL 사이 놓친 중간 변화 {skipped} · 변화×구독 여정 {n_pairs}")
print("등급     n      A 처음 본→저장 p50/p95/p99/max   SLO 충족   | B 원천→저장 p50/p95/p99   | 보임→저장 p50/p95  미판정")
for tier in ("urgent", "caution", "relaxed", "unknown"):
    a = lat_a[tier]
    if not a:
        continue
    slo = SLO.get(tier)
    ok = f"{sum(1 for x in a if x <= slo) / len(a) * 100:.1f}%≤{slo}s" if slo else "-"
    print(f"{tier:8s} {len(a):6d}  {f(q(a, .5))}/{f(q(a, .95))}/{f(q(a, .99))}/{f(q(a, 1))}  {ok:>12s}"
          f"  | {f(q(lat_b[tier], .5))}/{f(q(lat_b[tier], .95))}/{f(q(lat_b[tier], .99))}"
          f"  | {f(q(lat_v[tier], .5))}/{f(q(lat_v[tier], .95))}  {censored[tier]}")
allv = [x for t in lat_a for x in lat_a[t]]
print(f"전체     {len(allv):6d}  {f(q(allv, .5))}/{f(q(allv, .95))}/{f(q(allv, .99))}/{f(q(allv, 1))}")

# 변화 시점 여정 등급 분포(변화×구독 여정 한 쌍 = 여정 하나) — 심은 비율(시간 기준 긴급 20 / 주의 30 / 여유 50)과 비교할 값.
# 아래 "평가 시점" 분포는 평가 건수 가중이라 자주 평가되는 긴급이 부풀려진다(긴급 최소 10초·여유 120초 간격).
pairs = {t: len(lat_a[t]) for t in ("urgent", "caution", "relaxed", "unknown")}
ptot = sum(pairs.values()) or 1
print("변화 시점 여정 등급 분포:", " / ".join(f"{t} {pairs[t] / ptot * 100:.1f}%" for t in pairs))

# 평가 시점 등급 분포(모든 평가) — 심은 비율(시간 기준 20/30/50)이 위험 판정으로 얼마나 긴급 쪽으로 쏠렸나
mix = defaultdict(int)
for jid, v in evals.items():
    for idx in range(1, len(v)):
        at = v[idx][0]
        if t_from <= at <= t_to:
            mix[tier_of(v[idx - 1], at)] += 1
tot = sum(mix.values()) or 1
print("평가 시점 등급 분포:", " / ".join(f"{t} {mix[t] / tot * 100:.1f}%" for t in ("urgent", "caution", "relaxed")),
      f"(평가 {tot}건)")
