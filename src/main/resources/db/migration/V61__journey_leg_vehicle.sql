-- standalone-track PR-4 (#265). 단독 추적 버스가 실제로 탄 차량.
--
-- vehicle_id 는 V35 부터 있던 legacy 칼럼(미사용)이다. 종류(VEH_ID = 원천 차량 id, PLAIN_NO = 차량번호)·출처·판정 시각을
-- 더한다. vehicle_matched_at 이 차 있으면 판정이 끝났다는 뜻이다 — vehicle_id 가 비어 있으면 "못 찾음/포기/사용자가 해제".
-- 쓰기는 worker 의 부분 UPDATE 만 한다(엔티티는 이 칼럼들을 갱신하지 않는다 — @DynamicUpdate 가 없어 api 의 단계 저장이
-- worker 가 쓴 값을 덮는다).
ALTER TABLE journey_leg
    ADD COLUMN vehicle_id_type varchar(16),
    ADD COLUMN vehicle_source varchar(16),
    ADD COLUMN vehicle_matched_at timestamptz;
ALTER TABLE journey_leg ADD CONSTRAINT journey_leg_vehicle_id_type_check
    CHECK (vehicle_id_type IS NULL OR vehicle_id_type IN ('VEH_ID', 'PLAIN_NO'));
ALTER TABLE journey_leg ADD CONSTRAINT journey_leg_vehicle_source_check
    CHECK (vehicle_source IS NULL OR vehicle_source IN ('AUTO_MATCH'));
ALTER TABLE journey_leg ADD CONSTRAINT journey_leg_vehicle_identity_check
    CHECK ((vehicle_id IS NULL AND vehicle_id_type IS NULL AND vehicle_source IS NULL)
        OR (vehicle_id IS NOT NULL AND vehicle_id_type IS NOT NULL AND vehicle_source IS NOT NULL));
