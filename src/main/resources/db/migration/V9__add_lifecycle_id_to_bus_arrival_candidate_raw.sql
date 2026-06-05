-- 같은 차량 추적 구간(active 생성~finalize)을 식별하는 lifecycle_id.
-- DB insert를 idempotent하게 만들어, DB 커밋 후 Redis 삭제 전 크래시 시 중복 finalize를 막는다.
alter table bus_arrival_candidate_raw
    add column lifecycle_id varchar(255);

-- Postgres는 unique index에서 다중 NULL을 허용하므로 lifecycle_id 없는 과거 행은 영향받지 않는다.
create unique index uq_bus_arrival_candidate_raw_lifecycle
    on bus_arrival_candidate_raw (lifecycle_id);
