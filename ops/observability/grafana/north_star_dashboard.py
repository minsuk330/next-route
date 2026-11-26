# 북극성(정시 알림) 대시보드 생성기.
# 실행: python3 ops/observability/grafana/north_star_dashboard.py ops/observability/grafana/dashboards/north-star.json
#
# 정의(docs/plan/observability-north-star/PLAN.md, 2026-11-26 결정):
# - 여정 알림 = journey_notification, channel='PUSH'(FCM)
# - 예정 시각(due) = created_at(KST 벽시계, naive) + payload.holdMs. scheduled_at 은 재시도 backoff 로 덮여 쓰지 않는다
# - 정시 = SENT 이고 sent_at <= due + 60s
# - 늦음 = SENT 이고 60s 초과 / 유실 = DEAD·FAILED·만료 CANCELED(expired, expired-before-retry — 코드의 recordLost 와 같음)
# - 지연 중 = 아직 PENDING·PROCESSING 인데 due + 60s 지남(worker 정지 등을 실시간으로 잡는다)
# - 제외 = 뜻을 잃어 취소(journey-ended, command-changed, lease-expired 등) — 보낼 필요가 없어진 것
# - 잠금화면 갱신 = subway_ring_push_outbox. due = created_at(KST naive). 분모 = SENT + FAILED(SKIPPED 는 의도된 생략)
# 측정 지점은 FCM 수락까지. 기기 수신은 모른다.
import json, sys

PG = {"type": "grafana-postgresql-datasource", "uid": "nextroute-pg"}

JOURNEY = """
with n as (
  select id, journey_id, checkpoint, status, last_failure_reason, sent_at,
         (created_at at time zone 'Asia/Seoul')
           + coalesce((payload->>'holdMs')::bigint, 0) * interval '1 millisecond' as due
  from journey_notification
  where channel = 'PUSH' and deleted_at is null
), c as (
  select *,
    case
      when status = 'SENT' and sent_at <= due + interval '60 seconds' then 'on_time'
      when status = 'SENT' then 'late'
      when status in ('DEAD', 'FAILED') then 'lost'
      when status = 'CANCELED' and last_failure_reason in ('expired', 'expired-before-retry') then 'lost'
      when status = 'CANCELED' then 'excluded'
      when now() > due + interval '60 seconds' then 'overdue'
      else 'pending'
    end as outcome,
    -- 유실은 지연이 없다(안 나갔다). 아직 대기 중이면 지금까지 밀린 시간.
    case when sent_at is not null then extract(epoch from (sent_at - due))
         when status in ('PENDING', 'PROCESSING') then extract(epoch from (now() - due)) end as delay_s
  from n
)
"""

RING = """
with r as (
  select id, journey_id, reason, status, sent_at,
         (created_at at time zone 'Asia/Seoul') as due
  from subway_ring_push_outbox
  where deleted_at is null and status in ('SENT', 'FAILED')
), c as (
  select *,
    (status = 'SENT' and sent_at <= due + interval '60 seconds') as on_time,
    extract(epoch from (sent_at - due)) as delay_s
  from r
)
"""

panels = []
pid = [0]


def panel(title, typ, x, y, w, h, targets, **extra):
    pid[0] += 1
    p = {"id": pid[0], "title": title, "type": typ, "datasource": PG,
         "gridPos": {"x": x, "y": y, "w": w, "h": h}, "targets": targets}
    p.update(extra)
    panels.append(p)


def sql(q, fmt="table", ref="A"):
    return {"refId": ref, "datasource": PG, "editorMode": "code", "format": fmt, "rawQuery": True, "rawSql": q.strip()}


def desc(t):
    return {"description": t}


def row(title, y):
    pid[0] += 1
    panels.append({"id": pid[0], "type": "row", "title": title, "collapsed": False,
                   "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []})


stat = {"options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "/.*/"}, "colorMode": "none",
                    "graphMode": "none", "textMode": "value_and_name"},
        "fieldConfig": {"defaults": {"unit": "none", "decimals": 0}, "overrides": []}}
bars = {"fieldConfig": {"defaults": {"custom": {"drawStyle": "bars", "fillOpacity": 80, "stacking": {"mode": "normal"},
                                                "lineWidth": 1}, "decimals": 0},
                        "overrides": [
                            {"matcher": {"id": "byName", "options": "정시"}, "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "green"}}]},
                            {"matcher": {"id": "byName", "options": "늦음"}, "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "orange"}}]},
                            {"matcher": {"id": "byName", "options": "유실"}, "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "red"}}]},
                        ]},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}}
line = {"fieldConfig": {"defaults": {"custom": {"drawStyle": "line", "lineWidth": 2, "pointSize": 5,
                                                "showPoints": "always"}, "unit": "s", "decimals": 1}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}}
table = {"options": {"showHeader": True, "cellHeight": "sm"}, "fieldConfig": {"defaults": {}, "overrides": []}}

# ---- 여정 알림 -------------------------------------------------------------
row("여정 알림 정시율 (북극성) — 정시 = 예정 시각 + 60초 이내 FCM 전달", 0)

panel("정시 / 전체", "stat", 0, 1, 8, 5, [sql(JOURNEY + """
select
  count(*) filter (where outcome = 'on_time') || ' / ' || count(*) as "정시 / 전체",
  case when count(*) >= 20
       then round(100.0 * count(*) filter (where outcome = 'on_time') / count(*), 1) || '%'
       else '표본 ' || count(*) || '건 (20건부터 %)' end as "정시율"
from c
where outcome not in ('excluded', 'pending') and $__timeFilter(due)""")],
      **stat, **desc("기간 안 예정 시각 기준. 분모 = 정시+늦음+유실+지연 중. 뜻을 잃은 취소(여정 종료 등)는 뺀다. "
                     "주 20건 미만이면 % 대신 건수만 본다(2026-11-26 결정)."))

panel("늦음 · 유실 · 지연 중", "stat", 8, 1, 8, 5, [sql(JOURNEY + """
select
  count(*) filter (where outcome = 'late') as "늦음",
  count(*) filter (where outcome = 'lost') as "유실",
  count(*) filter (where outcome = 'overdue') as "지연 중"
from c where $__timeFilter(due)""")],
      options={**stat["options"], "colorMode": "background"},
      fieldConfig={"defaults": {"unit": "none", "decimals": 0,
                                "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None},
                                                                             {"color": "red", "value": 1}]}},
                   "overrides": []},
      **desc("하나라도 생기면 Slack 알림(규칙 '여정 알림 늦음·유실'). 지연 중 = 아직 안 나갔는데 예정+60초 지남."))

panel("발송 지연 p50 / p95 (정시·늦음)", "stat", 16, 1, 8, 5, [sql(JOURNEY + """
select
  round(percentile_cont(0.5) within group (order by delay_s)::numeric, 1) as "p50 (초)",
  round(percentile_cont(0.95) within group (order by delay_s)::numeric, 1) as "p95 (초)",
  round(max(delay_s)::numeric, 1) as "최대 (초)"
from c where outcome in ('on_time', 'late') and $__timeFilter(due)""")],
      **{**stat, "fieldConfig": {"defaults": {"unit": "none", "decimals": 1}, "overrides": []}},
      **desc("예정 시각(created_at + holdMs) → FCM 수락. hold(의도된 대기)는 빠진 값."))

panel("checkpoint 별", "table", 0, 6, 12, 7, [sql(JOURNEY + """
select checkpoint as "checkpoint",
  count(*) filter (where outcome = 'on_time') || ' / ' || count(*) as "정시 / 전체",
  count(*) filter (where outcome = 'late') as "늦음",
  count(*) filter (where outcome = 'lost') as "유실",
  count(*) filter (where outcome = 'overdue') as "지연 중",
  round((percentile_cont(0.95) within group (order by delay_s)
         filter (where outcome in ('on_time', 'late')))::numeric, 1) as "p95 지연(초)"
from c
where outcome not in ('excluded', 'pending') and $__timeFilter(due)
group by checkpoint order by count(*) desc""")], **table)

panel("일별 결과", "timeseries", 12, 6, 12, 7, [sql(JOURNEY + """
select date_trunc('day', due at time zone 'Asia/Seoul') at time zone 'Asia/Seoul' as time,
  count(*) filter (where outcome = 'on_time') as "정시",
  count(*) filter (where outcome = 'late') as "늦음",
  count(*) filter (where outcome in ('lost', 'overdue')) as "유실"
from c
where outcome not in ('excluded', 'pending') and $__timeFilter(due)
group by 1 order by 1""", "time_series")], **bars)

panel("늦음 · 유실 · 지연 중 목록", "table", 0, 13, 24, 7, [sql(JOURNEY + """
select id as "id", journey_id as "여정", checkpoint, outcome as "결과", status as "상태",
  due as "예정 시각", sent_at as "발송", round(delay_s::numeric, 1) as "지연(초)",
  last_failure_reason as "사유"
from c
where outcome in ('late', 'lost', 'overdue') and $__timeFilter(due)
order by due desc limit 100""")], **table,
      **desc("analyzer 없이 바로 원인을 보는 곳. 사유 = last_failure_reason."))

# ---- 잠금화면 갱신 ----------------------------------------------------------
row("잠금화면 갱신 지연 (보조) — 지하철 Live Activity, 정시 = 생성 + 60초 이내", 20)

panel("정시 / 전체 · 지연", "stat", 0, 21, 12, 5, [sql(RING + """
select
  count(*) filter (where on_time) || ' / ' || count(*) as "정시 / 전체",
  case when count(*) > 0 then round(100.0 * count(*) filter (where on_time) / count(*), 1) || '%' end as "정시율",
  round((percentile_cont(0.5) within group (order by delay_s) filter (where status = 'SENT'))::numeric, 2) || 's' as "p50",
  round((percentile_cont(0.95) within group (order by delay_s) filter (where status = 'SENT'))::numeric, 2) || 's' as "p95"
from c where $__timeFilter(due)""")], **stat,
      **desc("분모 = SENT + FAILED. SKIPPED(의도된 생략)는 뺀다. 1시간 10건 이상 && 95% 미만이면 Slack 알림."))

panel("시간별 p95 지연", "timeseries", 12, 21, 12, 5, [sql(RING + """
select date_trunc('hour', due) as time,
  percentile_cont(0.95) within group (order by delay_s) as "p95",
  percentile_cont(0.5) within group (order by delay_s) as "p50"
from c where status = 'SENT' and $__timeFilter(due)
group by 1 order by 1""", "time_series")], **line)

dash = {
    "uid": "nextroute-north-star",
    "title": "북극성 · 정시 알림",
    "tags": ["nextroute", "north-star"],
    "timezone": "Asia/Seoul",
    "schemaVersion": 39,
    "time": {"from": "now-7d", "to": "now"},
    "refresh": "5m",
    "panels": panels,
    "description": "docs/plan/observability-north-star/PLAN.md. 생성기: ops/observability/grafana/north_star_dashboard.py",
}
json.dump(dash, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
