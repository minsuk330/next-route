-- ODSAY 원천 식별자와 서버 내부 식별자를 분리한다.
-- 내부 ID만 실시간 버스 API·지하철 cache·ML 입력에 사용하고, ODSAY 값은 재해석·진단용으로 남긴다.

ALTER TABLE journey_leg
    ADD COLUMN source_start_stop_id varchar(64),
    ADD COLUMN source_end_stop_id   varchar(64);

ALTER TABLE journey_leg_route_option
    ADD COLUMN way_code      integer,
    ADD COLUMN boarding_seq  integer check (boarding_seq >= 0),
    ADD COLUMN alighting_seq integer check (alighting_seq >= 0);
