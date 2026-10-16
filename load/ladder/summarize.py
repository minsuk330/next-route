"""사다리 한 칸 결과 요약: 앱 Prometheus 히스토그램에서 단계별 p50/p95, k6 응답 p95, 공급자 호출 수.

워밍업이 끝난 시점(prom-start)과 끝(prom-end) 두 스냅숏의 차이만 센다 — 누적값을 그대로 쓰면 심은 여정이
한꺼번에 만기된 기동 직후가 섞인다.
usage: python3 -I summarize.py prom-start.txt prom-end.txt k6.json fake.json label [out_dir]
SPLIT=1 이면 같은 경로의 *-worker*.txt(worker 여러 대면 -worker·-worker2·…)를 더 읽어 JVM 들을 합치고 JVM 별 절(풀 대기·CPU·힙)을 붙인다.
MODE=subway 이면 지하철 사다리(#427) 절도 붙인다: 감지 지연(detection-*.txt), 재평가·알림 불변식, pg_stat_statements 비중."""
import json, os, re, sys
from collections import defaultdict

start_path, end_path, k6path, fakepath, label = sys.argv[1:6]
out_dir = sys.argv[6] if len(sys.argv) > 6 else os.path.dirname(os.path.abspath(end_path))
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


def diff(start, end):
    b0, s0 = parse(start)
    b1, s1 = parse(end)
    bk = defaultdict(list)
    for key, rows in b1.items():
        before = b0.get(key, {})
        bk[key] = [(le, c - before.get(le, 0.0)) for le, c in rows.items()]
    # 카운터는 차이, 게이지(cpu·heap·큐)는 끝 값이 아니라 워밍업 끝(부하 중) 값을 쓴다
    sc = {}
    for key, v in s1.items():
        if key[0].endswith(COUNTER_SUFFIX):
            # 재기동(CHAOS)으로 누적이 0 부터 다시 쌓였으면 끝 값이 곧 재기동 뒤 몫이다(음수 차분 막기, #458)
            before = s0.get(key, 0.0)
            sc[key] = v - before if v >= before else v
        else:
            sc[key] = s0.get(key, v)
    return {'buckets': bk, 'scalars': sc, 'end': s1}


COUNTER_SUFFIX = ('_total', '_count', '_sum')


def end_gauge_d(d, name):
    return sum(v for (n, l), v in d['end'].items() if n == name)


def start_gauge_d(d, name):
    # scalars 의 게이지 = 워밍업 끝 값
    return sum(v for (n, l), v in d['scalars'].items() if n == name)


# SPLIT=1(api·worker 분리 JVM): api 스냅숏은 예전 이름, worker 는 같은 이름에 -worker 꼬리.
# 이름+태그가 같은 줄은 더한다(카운터·히스토그램 = 두 JVM 합). JVM 별로 봐야 하는 값(풀 대기·CPU)은 아래 절에서 따로.
JVMS = {'app': diff(start_path, end_path)}
# worker 여러 대(#458, WORKERS=N): -worker(1번)·-worker2·… 를 모두 읽는다.
if os.environ.get('SPLIT') == '1':
    import glob
    JVMS = {'api': JVMS['app']}
    for ws in sorted(glob.glob(glob.escape(start_path[:-4]) + '-worker*.txt')):
        suffix = ws[len(start_path) - 4 + 1:-4]
        we = end_path[:-4] + '-' + suffix + '.txt'
        if os.path.exists(we):
            JVMS[suffix] = diff(ws, we)
buckets, scalars, s1 = defaultdict(list), defaultdict(float), defaultdict(float)
for d in JVMS.values():
    for key, rows in d['buckets'].items():
        acc = dict(buckets.get(key, []))
        for le, c in rows:
            acc[le] = acc.get(le, 0.0) + c
        buckets[key] = list(acc.items())
    for key, v in d['scalars'].items():
        scalars[key] += v
    for key, v in d['end'].items():
        s1[key] += v


def pct(name, match='', q=0.95, data=None):
    agg = defaultdict(float)
    for (n, lbl), rows in (data['buckets'] if data else buckets).items():
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


def s(name, match='', data=None):
    return sum(v for (n, l), v in (data['scalars'] if data else scalars).items() if n == name and match in l)


def fmt(name, match='', unit=1.0, u='s', data=None):
    p50, n = pct(name, match, 0.5, data)
    p95, _ = pct(name, match, 0.95, data)
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
# 처방 4차: 조건부 조회 결과(204 비율)·버전 캐시 적중·커밋→앱 수신 지연(마지막 변경 기준, 하한) — SSE/long-poll 판단 재료
_cq = {r: s('journey_query_conditional_total', f'result="{r}"') for r in ('not_modified', 'modified', 'first')}
print('⑥ 조건부    : 204', int(_cq['not_modified']), '/ 200', int(_cq['modified']), '/ 버전없음', int(_cq['first']),
      f"| 204 비율 {_cq['not_modified'] / max(1, _cq['not_modified'] + _cq['modified']):.3f}",
      '| 버전 캐시', {r: int(s('journey_version_cache_total', f'result="{r}"'))
                   for r in ('hit', 'miss', 'error', 'refreshed', 'refresh-error')})
print('⑥ 전달 지연 : 커밋→앱 수신 전체', fmt('journey_query_delivery_lag_seconds'),
      '| WAIT', fmt('journey_query_delivery_lag_seconds', 'leg="WAIT"'),
      '| RIDE', fmt('journey_query_delivery_lag_seconds', 'leg="RIDE"'),
      '| WALK', fmt('journey_query_delivery_lag_seconds', 'leg="WALK"'))
print('자원        : process_cpu_usage', round(s('process_cpu_usage'), 3),
      '| heap MB', round(s('jvm_memory_used_bytes', 'area="heap"') / 1e6),
      '| 평가 실행기 큐', s('executor_queued_tasks', 'name="journeyEvaluatorExecutor"'))
if len(JVMS) > 1:
    # 분리 JVM: 위 숫자는 두 JVM 합(process_cpu_usage 도 합 — 각자 '전체 코어 대비' 몫이라 더하면 앱 전체 몫).
    print('── JVM 별 (SPLIT=1) ──')
    for name, d in JVMS.items():
        print(f'  [{name}] 커넥션 대기', fmt('hikaricp_connections_acquire_seconds', '', 1000, 'ms', d),
              '| 커넥션 쥔 시간 평균', f"{s('hikaricp_connections_usage_seconds_sum', '', d) / max(1, s('hikaricp_connections_usage_seconds_count', '', d)) * 1000:.1f}ms"
              f" (n={int(s('hikaricp_connections_usage_seconds_count', '', d))})",
              '| 커넥션 쥔 시간 합/창', f"{s('hikaricp_connections_usage_seconds_sum', '', d) / max(1, end_gauge_d(d, 'process_uptime_seconds') - start_gauge_d(d, 'process_uptime_seconds')):.1f}개",
              '| 풀 active/pending(워밍업 끝)', int(s('hikaricp_connections_active', '', d)), '/', int(s('hikaricp_connections_pending', '', d)))
        print(f'  [{name}] process_cpu_usage', round(s('process_cpu_usage', '', d), 3),
              '| system_cpu_usage', round(s('system_cpu_usage', '', d), 3),
              '| heap MB', round(s('jvm_memory_used_bytes', 'area="heap"', d) / 1e6),
              '| GC pause 합 s', round(s('jvm_gc_pause_seconds_sum', '', d), 1),
              '| http.server 요청', int(s('http_server_requests_seconds_count', '', d)),
              '| 평가', int(s('journey_evaluation_duration_seconds_count', 'outcome="evaluated"', d)),
              '| 수집 주기', int(s('subway_realtime_collect_duration_seconds_count', 'collector="arrival"', d)))


if os.environ.get("MODE") != "subway":
    sys.exit(0)


def end_gauge(name, match=''):
    return sum(v for (n, l), v in s1.items() if n == name and match in l)


def by_label(name, label_name):
    out = {}
    for (n, l), v in scalars.items():
        if n == name:
            m = re.search(label_name + r'="([^"]*)"', l)
            out[m.group(1) if m else ''] = out.get(m.group(1) if m else '', 0) + v
    return {k: int(v) for k, v in sorted(out.items()) if v}


def read(path):
    return open(path).read().rstrip() if os.path.exists(path) else '(없음)'


window = None
cyc = s('subway_realtime_collect_duration_seconds_count', 'collector="arrival"')
evaluated = s('journey_evaluation_duration_seconds_count', 'outcome="evaluated"')
p50, n = pct('journey_evaluation_duration_seconds', 'outcome="evaluated"', 0.5)
print()
print('── 지하철 이벤트 경로(#427) ──')
print('[감지 지연]')
print(read(os.path.join(out_dir, f'detection-{label}.txt')))
print('[② 변화 감지·분배]')
events = s('journey_reeval_events_total', 'source="subway"')
compared = s('journey_reeval_compared_total', 'source="subway"')
pulled = by_label('journey_reeval_pulled_total', 'tier')
dirty = by_label('journey_reeval_dirty_marked_total', 'tier')
woken = sum(pulled.values()) + sum(dirty.values())
print(f'  수집 주기 {int(cyc)}회 | 비교 키 {int(compared)} · 바뀐 키 {int(events)} (이벤트÷조회 {events / max(1, compared):.3f})'
      f' | 깨운 여정 {woken} (주기당 {woken / max(1, cyc):.0f}) 앞당김 {pulled} dirty {dirty}'
      f' | 미룸 {by_label("journey_reeval_deferred_total", "reason")}')
cap_n = s('journey_reeval_wake_cap_chosen_count')
if cap_n:
    # #427 처방 2차: 처리량으로 계산한 깨우기 상한(요청마다 분포) + 끝 시점 게이지(상한·처리량)
    print(f'  깨우기 상한 평균 {s("journey_reeval_wake_cap_chosen_sum") / cap_n:.0f} (요청 {int(cap_n)}회)'
          f' | 끝 시점 상한 {end_gauge("journey_reeval_wake_cap"):.0f} · 처리량 {end_gauge("journey_reeval_wake_rate"):.1f}/s')
print('  수집 1회', fmt('subway_realtime_collect_duration_seconds', 'collector="arrival"', 1000, 'ms'),
      '| 관심 색인 재구성', fmt('journey_reeval_interest_rebuild_seconds', '', 1000, 'ms'))
print('[④ 평가기]')
print(f'  처리 평가 {int(evaluated)}건 (초당 {evaluated / 420:.1f}, 7분 기준) | 판정 바뀜/그대로',
      int(s('journey_evaluation_judgment_total', 'changed="true"')), '/', int(s('journey_evaluation_judgment_total', 'changed="false"')),
      '| 소유권 잃음', int(s('journey_evaluation_lost_claim_total')), '| 보충', by_label('journey_evaluation_refill_total', 'trigger'))
print('  예정→시작', fmt('journey_evaluation_start_lag_seconds'), '| 평가 1건', fmt('journey_evaluation_duration_seconds', 'outcome="evaluated"', 1000, 'ms'))
print('  선점 쿼리', fmt('journey_evaluation_claim_duration_seconds', '', 1000, 'ms'), '| DB 커넥션 대기', fmt('hikaricp_connections_acquire_seconds', '', 1000, 'ms'),
      '| 커넥션 사용', fmt('hikaricp_connections_usage_seconds', '', 1000, 'ms'))
print('  지하철 역별 캐시', by_label('subway_station_arrival_cache_requests_total', 'result'))
print('[CPU — 측정 창]')
for name, d in JVMS.items():
    win = end_gauge_d(d, 'process_uptime_seconds') - start_gauge_d(d, 'process_uptime_seconds')
    used = s('process_cpu_time_ns_total', '', d) / 1e9
    print(f'  [{name}] 평균 사용 코어 {used / max(1, win):.2f} (CPU 시간 {used:.0f}s ÷ 창 {win:.0f}s)')
cpu_path = os.path.join(out_dir, f'cpu-{label}.tsv')
if os.path.exists(cpu_path):
    rows = defaultdict(list)
    for line in open(cpu_path):
        f = line.rstrip('\n').split('\t')
        # JVM 이 막 뜨거나 재기동 직후엔 게이지가 NaN 이다 — 평균을 nan 으로 만들지 않게 뺀다(#458)
        if len(f) >= 3 and f[2] and f[2].lower() != 'nan':
            rows[(f[1], 'p')].append(float(f[2]))
        if len(f) >= 4 and f[3] and f[3].lower() != 'nan':
            rows[(f[1], 's')].append(float(f[3]))
    def avgmax(v):
        return f'평균 {sum(v) / len(v):.2f} / 최대 {max(v):.2f} (n={len(v)})' if v else 'n/a'
    for (n_, kind), v in sorted(rows.items()):
        what = {'p': 'Postgres 사용 코어' if n_ == 'pg' else 'process_cpu_usage(전체 코어 대비)', 's': 'system_cpu_usage'}[kind]
        print(f'  표본 [{n_}] {what} {avgmax(v)}')
print('[⑤ 알림]')
print('  적재(종류·울림)', {k: int(v) for (n_, k), v in [((n, l), v) for (n, l), v in scalars.items() if n == 'journey_notification_enqueued_delivery_total' and v]})
print('  울릴 권리', by_label('journey_notification_alert_claim_total', 'outcome'), '| 취소', by_label('journey_notification_canceled_total', 'reason'),
      '| 실패', by_label('journey_notification_failed_total', 'kind'))
print('  적재→발송(앱, 보류 포함)', fmt('journey_notification_delivery_lag_seconds'), '| 가짜 FCM 왕복 평균', f"{s('loadtest_push_send_seconds_sum') / max(1, s('loadtest_push_send_seconds_count')) * 1000:.0f}ms (n={int(s('loadtest_push_send_seconds_count'))})")
print('  가짜 발송처 수신', {k: int(v) for (n, k), v in scalars.items() if n == 'loadtest_push_received_total' and v})
print('  불변식: 번호 중복', int(s('loadtest_push_duplicate_id_total')), '| 업무 키 중복', int(s('loadtest_push_duplicate_business_total')),
      '| 경로 버전당 울림>1 (가짜 발송처 누적)', int(end_gauge('loadtest_push_alerting_per_revision_over1')),
      '| (DB SENT 기준)', read(os.path.join(out_dir, f'alerting-over1-{label}.txt')))
print('  DB 행(종류·울림·상태·건수·발송지연 p50/p95·보류 p95·적재→발송 p95·사유):')
print('    ' + read(os.path.join(out_dir, f'notif-{label}.tsv')).replace('\n', '\n    '))
print('[DB pg_stat_statements 상위(워밍업 뒤) — 비중%·호출·총ms·평균ms·쿼리]')
print('    ' + read(os.path.join(out_dir, f'pgss-{label}.tsv')).replace('\n', '\n    '))
print('[정리]', read(os.path.join(out_dir, f'leftover-{label}.txt')))
