-- subway_arrival_raw unique key에 direction 추가
-- direction이 다르면 별도 관측으로 취급하기 위해 constraint 컬럼 확장
--
-- 안전성 근거:
--   1. Flyway는 PostgreSQL 마이그레이션을 트랜잭션 안에서 실행하므로
--      DROP + ADD CONSTRAINT가 원자적으로 수행됨 (race window 없음)
--   2. unique key에 컬럼을 추가하면 제약이 더 느슨해짐 (6컬럼 → 7컬럼).
--      기존 6컬럼 constraint를 통과한 모든 데이터는 7컬럼 constraint도 통과함
--      → 기존 데이터 위반 위험 없음
ALTER TABLE subway_arrival_raw
    DROP CONSTRAINT uk_subway_arrival_raw_observation;

ALTER TABLE subway_arrival_raw
    ADD CONSTRAINT uk_subway_arrival_raw_observation
        UNIQUE (line_id, station_id, direction, train_no, received_at, arrival_code, current_message);
