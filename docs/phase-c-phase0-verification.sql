-- Phase C (code=3 NO_RAW_EVENT 보완) — Phase 0 사전 검증 SQL
-- 실행 환경: prod read replica (READ ONLY). 코드 변경 없음.
-- 대상 라인: 1002,1005,1006,1007 (batch.inferred-completion.line-ids 기본값)
-- 사용법: psql 에서 :service_date 를 검증 대상 운행일로 치환.
--   예) \set service_date '2026-05-17'
-- 참고 스키마:
--   subway_arrival_raw.received_at         = TEXT 'yyyy-MM-dd HH:mm:ss'
--   subway_arrival_raw.arrival_code        = '1'(도착) / '3'(전역출발)
--   subway_arrival_event.direction         = 원시('상행'/'하행'/'내선'/'외선')
--   subway_arrival_event_match_issue.direction = 'U'/'D'
--   subway_segment.depart_station_id/arrive_station_id = 역'명'(ID 아님)
--   raw 방향→UD: 상행/내선→U, 하행/외선→D

\set svc '2026-05-17'
\set from_ts '2026-05-17 04:00:00'
\set to_ts   '2026-05-18 04:00:00'

-- ─────────────────────────────────────────────────────────────
-- Q1. NO_RAW_EVENT 부분집합의 segment 매핑률 (참고용 — 라인 확장/제외 판단)
--     NO key(line,station,dir)에 대응하는 code=3의 prev→station 역명쌍이
--     subway_segment.travel_time 으로 매핑되는 비율.
-- ─────────────────────────────────────────────────────────────
WITH no_keys AS (
    SELECT DISTINCT i.line_id, i.station_id, i.direction AS dir_ud
    FROM subway_arrival_event_match_issue i
    WHERE i.service_date = :'svc'
      AND i.issue_type = 'NO_RAW_EVENT'
      AND i.line_id IN ('1002','1005','1006','1007')
),
code3 AS (
    SELECT r.line_id, r.station_id,
           CASE WHEN r.direction IN ('상행','내선') THEN 'U'
                WHEN r.direction IN ('하행','외선') THEN 'D' END AS dir_ud,
           r.prev_station_id
    FROM subway_arrival_raw r
    WHERE r.arrival_code = '3'
      AND r.received_at >= :'from_ts' AND r.received_at < :'to_ts'
      AND r.line_id IN ('1002','1005','1006','1007')
      AND r.prev_station_id IS NOT NULL
),
joined AS (
    SELECT c.line_id,
           ps.statn_nm AS prev_name,
           xs.statn_nm AS station_name,
           seg.travel_time
    FROM code3 c
    JOIN no_keys nk
      ON nk.line_id = c.line_id AND nk.station_id = c.station_id AND nk.dir_ud = c.dir_ud
    LEFT JOIN subway_station ps ON ps.statn_id = c.prev_station_id
    LEFT JOIN subway_station xs ON xs.statn_id = c.station_id
    LEFT JOIN subway_segment seg
      ON seg.line_id = c.line_id
     AND seg.depart_station_id = ps.statn_nm
     AND seg.arrive_station_id = xs.statn_nm
)
SELECT line_id,
       count(*)                                        AS code3_in_no_keys,
       count(travel_time)                              AS segment_mapped,
       round(count(travel_time)::numeric / NULLIF(count(*),0), 4) AS map_ratio
FROM joined
GROUP BY line_id
ORDER BY line_id;

-- ─────────────────────────────────────────────────────────────
-- Q2. 기대 NO 감소량: NO slot 수 vs segment 매핑된 code=3 압축후보(근사)
--     (압축은 앱에서 10분 split 수행 — 여기선 같은 (key,train) distinct 로 근사)
-- ─────────────────────────────────────────────────────────────
WITH no_cnt AS (
    SELECT line_id, count(*) AS no_slots
    FROM subway_arrival_event_match_issue
    WHERE service_date = :'svc' AND issue_type = 'NO_RAW_EVENT'
      AND line_id IN ('1002','1005','1006','1007')
    GROUP BY line_id
),
cand AS (
    SELECT r.line_id,
           count(DISTINCT (r.station_id || '|' ||
                 CASE WHEN r.direction IN ('상행','내선') THEN 'U'
                      WHEN r.direction IN ('하행','외선') THEN 'D' END
                 || '|' || r.train_no)) AS code3_candidate_groups
    FROM subway_arrival_raw r
    WHERE r.arrival_code = '3'
      AND r.received_at >= :'from_ts' AND r.received_at < :'to_ts'
      AND r.line_id IN ('1002','1005','1006','1007')
      AND r.prev_station_id IS NOT NULL
    GROUP BY r.line_id
)
SELECT n.line_id, n.no_slots, c.code3_candidate_groups
FROM no_cnt n LEFT JOIN cand c ON c.line_id = n.line_id
ORDER BY n.line_id;

-- ─────────────────────────────────────────────────────────────
-- Q3. D1 시간창 중복 분포: OBSERVED_CODE_1 event 와 같은 (line,station,UD)에서
--     code=3 후보가 ±5분 내 겹치는 비율 (W 기본값 타당성 점검)
--     event.direction 원시 → UD 변환 후 비교.
-- ─────────────────────────────────────────────────────────────
WITH obs AS (
    SELECT e.line_id, e.station_id,
           CASE WHEN e.direction IN ('상행','내선') THEN 'U'
                WHEN e.direction IN ('하행','외선') THEN 'D' END AS dir_ud,
           e.arrived_at
    FROM subway_arrival_event e
    WHERE e.service_date = :'svc'
      AND e.event_source = 'OBSERVED_CODE_1'
      AND e.line_id IN ('1002','1005','1006','1007')
),
c3 AS (
    SELECT r.line_id, r.station_id,
           CASE WHEN r.direction IN ('상행','내선') THEN 'U'
                WHEN r.direction IN ('하행','외선') THEN 'D' END AS dir_ud,
           to_timestamp(r.received_at, 'YYYY-MM-DD HH24:MI:SS') AS rt
    FROM subway_arrival_raw r
    WHERE r.arrival_code = '3'
      AND r.received_at >= :'from_ts' AND r.received_at < :'to_ts'
      AND r.line_id IN ('1002','1005','1006','1007')
)
SELECT count(*) AS code3_rows,
       count(*) FILTER (
         WHERE EXISTS (
           SELECT 1 FROM obs o
           WHERE o.line_id = c3.line_id AND o.station_id = c3.station_id
             AND o.dir_ud = c3.dir_ud
             AND abs(extract(epoch FROM (o.arrived_at - c3.rt))) <= 300
         )) AS within_5min_of_observed
FROM c3;

-- ─────────────────────────────────────────────────────────────
-- Q4. prevStationId → 역명 해소율 (분석문서의 100% 매핑 주장 검증)
-- ─────────────────────────────────────────────────────────────
SELECT count(*)                                 AS code3_rows,
       count(s.statn_nm)                         AS prev_name_resolved,
       round(count(s.statn_nm)::numeric / NULLIF(count(*),0), 4) AS resolve_ratio
FROM subway_arrival_raw r
LEFT JOIN subway_station s ON s.statn_id = r.prev_station_id
WHERE r.arrival_code = '3'
  AND r.received_at >= :'from_ts' AND r.received_at < :'to_ts'
  AND r.line_id IN ('1002','1005','1006','1007')
  AND r.prev_station_id IS NOT NULL;

-- ─────────────────────────────────────────────────────────────
-- Q5. (spot) 향후 라인 확장 대비 — 9호선(1009) 등 급행/지선 code=3 분포
--     통과역에 code=3가 비정상적으로 적은지 육안 확인용.
-- ─────────────────────────────────────────────────────────────
SELECT r.line_id, r.station_name, count(*) AS code3_cnt
FROM subway_arrival_raw r
WHERE r.arrival_code = '3'
  AND r.received_at >= :'from_ts' AND r.received_at < :'to_ts'
  AND r.line_id IN ('1009')
GROUP BY r.line_id, r.station_name
ORDER BY code3_cnt
LIMIT 30;
