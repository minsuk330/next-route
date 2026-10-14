"""사다리 한 칸 결과 요약: 앱 Prometheus 히스토그램에서 단계별 p50/p95, k6 응답 p95, 공급자 호출 수.

워밍업이 끝난 시점(prom-start)과 끝(prom-end) 두 스냅숏의 차이만 센다 — 누적값을 그대로 쓰면 심은 여정이
한꺼번에 만기된 기동 직후가 섞인다.
usage: python3 -I summarize.py prom-start.txt prom-end.txt k6.json fake.json label"""
import json, re, sys
from collections import defaultdict

start_path, end_path, k6path, fakepath, label = sys.argv[1:6]
LINE = re.compile(r'([a-zA-Z_:][\w:]*)(\{(?:[^"}]|"[^"]*")*\})?\s+(\S+)')


def parse(path):
    b, sc = defaultdict(dict), {}
    for line in open(path):
        if line.startswith('#') or not line.strip():
            continue
        m = LINE.match(line)
        if not m:
            continue
        name, lbl, raw = m.group(1), m.group(2) or '', m.group(3)
        try:
            val = float(raw)
        except ValueError:
            continue
        if name.endswith('_bucket'):
            le = re.search(r'le="([^"]+)"', lbl).group(1)
            rest = re.sub(r',?le="[^"]+"', '', lbl)
            b[(name[:-7], rest)][float('inf') if le == '+Inf' else float(le)] = val
        else:
            sc[(name, lbl)] = val
    return b, sc


b0, s0 = parse(start_path)
b1, s1 = parse(end_path)
buckets = defaultdict(list)
for key, rows in b1.items():
    before = b0.get(key, {})
    buckets[key] = [(le, c - before.get(le, 0.0)) for le, c in rows.items()]
# 카운터는 차이, 게이지(cpu·heap·큐)는 끝 값이 아니라 워밍업 끝(부하 중) 값을 쓴다
COUNTER_SUFFIX = ('_total', '_count', '_sum')
scalars = {}
for key, v in s1.items():
    scalars[key] = v - s0.get(key, 0.0) if key[0].endswith(COUNTER_SUFFIX) else s0.get(key, v)


def pct(name, match='', q=0.95):
    agg = defaultdict(float)
    for (n, lbl), rows in buckets.items():
        if n == name and match in lbl:
            for le, c in rows:
                agg[le] += c
    if not agg:
        return None, 0
    items = sorted(agg.items())
    total = items[-1][1]
    if total == 0:
        return None, 0
    target = q * total
    prev_le, prev_c = 0.0, 0.0
    for le, c in items:
        if c >= target:
            if le == float('inf'):
                return prev_le, total
            frac = (target - prev_c) / (c - prev_c) if c > prev_c else 0
            return prev_le + frac * (le - prev_le), total
        prev_le, prev_c = le, c
    return items[-1][0], total


def s(name, match=''):
    return sum(v for (n, l), v in scalars.items() if n == name and match in l)


def fmt(name, match='', unit=1.0, u='s'):
    p50, n = pct(name, match, 0.5)
    p95, _ = pct(name, match, 0.95)
    if p95 is None:
        return 'n/a'
    return f'p50 {p50*unit:.2f}{u} / p95 {p95*unit:.2f}{u} (n={int(n)})'


k6 = json.load(open(k6path))
def k6p95(tag):
    m = k6['metrics'].get(f'http_req_duration{{endpoint:{tag}}}') or {}
    v = m.get('values', m)
    return v.get('p(95)'), v.get('med')
fake = json.load(open(fakepath))

print(f'── 사다리 {label} ──')
print('① 수집/외부 : 공급자 호출', fake, '| http.client', fmt('http_client_requests_seconds', '', 1000, 'ms'))
print('③ 선점      : 쿼리', fmt('journey_evaluation_claim_duration_seconds', '', 1000, 'ms'),
      '| 선점 수', int(s('journey_evaluation_claimed_total')))
print('④ 평가 대기 : 예정→시작', fmt('journey_evaluation_start_lag_seconds'),
      '| 큐 대기', fmt('journey_evaluation_queue_wait_seconds'))
print('④ 평가      : 1건', fmt('journey_evaluation_duration_seconds', 'outcome="evaluated"', 1000, 'ms'),
      '| 판정 바뀜/그대로', int(s('journey_evaluation_judgment_total', 'changed="true"')), '/',
      int(s('journey_evaluation_judgment_total', 'changed="false"')),
      '| 실패', int(s('journey_evaluation_failures_total')))
print('④ DB        : 커넥션 대기', fmt('hikaricp_connections_acquire_seconds', '', 1000, 'ms'))
print('재평가      : 앞당김', int(s('journey_reeval_pulled_total')), '| dirty', int(s('journey_reeval_dirty_marked_total')),
      '| 미룸', int(s('journey_reeval_deferred_total')))
print('⑤ 알림      : 적재→발송', fmt('journey_notification_delivery_lag_seconds'))
print('⑥ 조회(앱)  : /api/journeys/{id}', fmt('http_server_requests_seconds', 'uri="/api/journeys/{journeyId}"', 1000, 'ms'),
      '| nearby', fmt('http_server_requests_seconds', 'uri="/api/nearby/bus-stops"', 1000, 'ms'))
print('⑥ 조회(k6)  : journey p95/med', k6p95('journey'), '| nearby', k6p95('nearby'))
print('자원        : process_cpu_usage', round(s('process_cpu_usage'), 3),
      '| heap MB', round(s('jvm_memory_used_bytes', 'area="heap"') / 1e6),
      '| 평가 실행기 큐', s('executor_queued_tasks', 'name="journeyEvaluatorExecutor"'))
