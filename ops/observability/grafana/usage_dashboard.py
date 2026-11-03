# 사용 통계 대시보드 생성기. 실행: python3 ops/observability/grafana/usage_dashboard.py ops/observability/grafana/dashboards/usage.json
import json, sys
PG = {"type": "grafana-postgresql-datasource", "uid": "nextroute-pg"}
PROM = {"type": "prometheus", "uid": "prometheus"}
# naive timestamp 칸(created_at)은 KST 벽시계. timestamptz 칸은 UTC 저장.
FROM_D = "($__timeFrom()::timestamptz AT TIME ZONE 'Asia/Seoul')::date"
TO_D = "($__timeTo()::timestamptz AT TIME ZONE 'Asia/Seoul')::date"
FROM_N = "($__timeFrom()::timestamptz AT TIME ZONE 'Asia/Seoul')"
TO_N = "($__timeTo()::timestamptz AT TIME ZONE 'Asia/Seoul')"
TODAY = "(now() AT TIME ZONE 'Asia/Seoul')::date"
# 앱인토스(토스 로그인) 사용자는 통계에서 뺀다(사용자 지시 2026-11-03). 토스 계정이 하나라도 있는 user_id 제외
NOT_TOSS = "{col} not in (select t.user_id from auth_identity t where t.provider = 'TOSS' and t.user_id is not null)"
def nt(col="user_id"): return NOT_TOSS.format(col=col)

panels = []
pid = [0]
def panel(title, typ, x, y, w, h, targets, ds=PG, **extra):
    pid[0] += 1
    p = {"id": pid[0], "title": title, "type": typ, "datasource": ds,
         "gridPos": {"x": x, "y": y, "w": w, "h": h}, "targets": targets}
    p.update(extra)
    panels.append(p)
def sql(q, fmt="time_series", ref="A"):
    return {"refId": ref, "datasource": PG, "editorMode": "code", "format": fmt, "rawQuery": True, "rawSql": q.strip()}
def desc(t): return {"description": t}

stat_opts = {"options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "none", "graphMode": "none", "textMode": "value_and_name"},
             "fieldConfig": {"defaults": {"unit": "none", "decimals": 0}, "overrides": []}}
bar = {"fieldConfig": {"defaults": {"custom": {"drawStyle": "bars", "fillOpacity": 80, "stacking": {"mode": "normal"}, "lineWidth": 1}, "decimals": 0}, "overrides": []},
       "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}}
line = {"fieldConfig": {"defaults": {"custom": {"drawStyle": "line", "lineWidth": 2, "pointSize": 6, "showPoints": "always"}, "decimals": 0}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}}

panel("오늘·7일·30일 활성 사용자", "stat", 0, 0, 12, 4, [sql(f"""
select
  count(distinct user_id) filter (where activity_date = {TODAY}) as "오늘 (DAU)",
  count(distinct user_id) filter (where activity_date > {TODAY} - 7) as "7일 (WAU)",
  count(distinct user_id) as "30일 (MAU)"
from user_daily_activity
where activity_date > {TODAY} - 30 and {nt()}""", "table")], **stat_opts, **desc("인증된 요청을 보낸 사용자 수(사용자당 하루 1번 기록, 토스 사용자 제외). 기록은 2026-11-03 배포부터."))

panel("기간 요약", "stat", 12, 0, 12, 4, [sql(f"""
select
  (select count(*) from auth_identity where provider = 'DEVICE' and created_at >= {FROM_N} and created_at < {TO_N}) as "신규 설치",
  count(*) as "여정 시작",
  count(distinct user_id) as "여정 사용자",
  round(100.0 * count(*) filter (where status = 'COMPLETED') / nullif(count(*) filter (where status <> 'ACTIVE'), 0)) as "완료율 %"
from journey_session
where created_at >= {FROM_N} and created_at < {TO_N} and {nt()}""", "table")], **stat_opts, **desc("대시보드 시간 범위 안, 토스 사용자 제외. 신규 설치 = 새 DEVICE(기기) 계정. 완료율 = 완료 / (시작 − 진행 중)."))

panel("일별 활성 사용자 (DAU)", "timeseries", 0, 4, 12, 8, [sql(f"""
select activity_date::timestamp at time zone 'Asia/Seoul' as time, count(*) as "DAU"
from user_daily_activity
where activity_date between {FROM_D} and {TO_D} and {nt()}
group by 1 order by 1""")], **line, **desc("토스 사용자 제외."))

panel("일별 신규 계정 (provider)", "timeseries", 12, 4, 12, 8, [sql(f"""
select date_trunc('day', created_at) at time zone 'Asia/Seoul' as time, provider as metric, count(*) as value
from auth_identity
where created_at >= {FROM_N} and created_at < {TO_N} and provider <> 'TOSS'
group by 1, 2 order by 1""")], **bar, **desc("DEVICE = 앱 첫 실행 때 만드는 기기 계정 ≈ 신규 설치. TOSS 계정은 뺀다."))

panel("일별 여정 시작 (끝난 상태별)", "timeseries", 0, 12, 12, 8, [sql(f"""
select date_trunc('day', created_at) at time zone 'Asia/Seoul' as time, status as metric, count(*) as value
from journey_session
where created_at >= {FROM_N} and created_at < {TO_N} and {nt()}
group by 1, 2 order by 1""")], **bar, **desc("토스 사용자 제외."))

panel("일별 여정 사용자 수", "timeseries", 12, 12, 12, 8, [sql(f"""
select date_trunc('day', created_at) at time zone 'Asia/Seoul' as time, count(distinct user_id) as "여정 사용자"
from journey_session
where created_at >= {FROM_N} and created_at < {TO_N} and {nt()}
group by 1 order by 1""")], **line, **desc("토스 사용자 제외."))

panel("일별 경로 검색 요청", "timeseries", 0, 20, 12, 8, [{
    "refId": "A", "datasource": PROM, "editorMode": "code", "range": True, "interval": "1d",
    "expr": 'sum(increase(http_server_requests_seconds_count{uri="/api/route/search"}[1d]))',
    "legendFormat": "검색 요청"}], ds=PROM, **bar, **desc("api 의 /api/route/search 요청 수(Prometheus). 사용자를 모르니 토스 요청도 섞일 수 있다(2026-10 이후 토스 활성 0). 재시작 직후 첫 건은 빠질 수 있다."))

panel("앱 빌드별 사용자 (기간)", "bargauge", 12, 20, 12, 8, [sql(f"""
select coalesce(app_build, '(알 수 없음)') as build, count(distinct user_id) as "사용자"
from user_daily_activity
where activity_date between {FROM_D} and {TO_D} and {nt()}
group by 1 order by 2 desc""", "table")],
      options={"orientation": "horizontal", "displayMode": "basic", "reduceOptions": {"calcs": ["lastNotNull"], "values": True}},
      fieldConfig={"defaults": {"decimals": 0}, "overrides": []}, **desc("User-Agent 첫 토큰. app/N = 출시 앱, Dev/N = 개발 빌드."))

panel("재방문 (첫 활동 주 코호트)", "table", 0, 28, 12, 8, [sql(f"""
with first_day as (
  select user_id, min(activity_date) as d0 from user_daily_activity where {nt()} group by user_id
)
select date_trunc('week', f.d0)::date as "첫 활동 주",
       count(*) as "사용자",
       count(*) filter (where exists (select 1 from user_daily_activity a
                                       where a.user_id = f.user_id and a.activity_date between f.d0 + 1 and f.d0 + 7)) as "7일 안 재방문",
       round(100.0 * count(*) filter (where exists (select 1 from user_daily_activity a
                                       where a.user_id = f.user_id and a.activity_date between f.d0 + 1 and f.d0 + 7)) / count(*)) as "재방문 %"
from first_day f
group by 1 order by 1 desc""", "table")], **desc("첫 활동일 다음 날부터 7일 안에 다시 온 사용자. 최근 7일 코호트는 아직 덜 찼다."))

panel("사용자별 (최근 30일)", "table", 12, 28, 12, 8, [sql(f"""
select left(md5(a.user_id::text), 6) as "사용자",
       min(a.activity_date) as "첫 활동",
       max(a.activity_date) as "마지막 활동",
       count(*) as "활동 일수",
       (select count(*) from journey_session j
         where j.user_id = a.user_id and j.created_at >= ({TODAY} - 30)::timestamp) as "여정 수",
       max(a.app_build) as "빌드"
from user_daily_activity a
where a.activity_date > {TODAY} - 30 and {nt('a.user_id')}
group by a.user_id
order by "활동 일수" desc, "마지막 활동" desc""", "table")], **desc("사용자 id 는 md5 앞 6자로만. 목적지·좌표·이름은 싣지 않는다."))

dash = {"uid": "nextroute-usage", "title": "사용 통계", "tags": ["nextroute", "usage"],
        "timezone": "Asia/Seoul", "time": {"from": "now-30d", "to": "now"}, "refresh": "",
        "schemaVersion": 39, "editable": True, "panels": panels, "templating": {"list": []}, "annotations": {"list": []}}
json.dump(dash, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
print(len(panels), "panels")
