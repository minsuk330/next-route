-- standalone-track PR-3 (#265). 사용자가 역에서 고른 열차.
--
-- 역 도착 시 도착 후보 중 사용자가 고르면 곧바로 CONFIRMED · source USER_SELECTED 로 묶는다(매처 검증 없음).
-- 감사 이벤트 USER_SELECTED 는 판단(DECISION) 종류다 — kind/type 정합 제약(V45)은 관측 두 이름만 EVIDENCE 로 묶으므로
-- 그대로 둔다. train_no 필수 조건(CONFIRMED 면 열차 식별 4칸)도 그대로 — 선택은 열차번호가 있다.
ALTER TABLE subway_leg_binding DROP CONSTRAINT subway_leg_binding_source_check;
ALTER TABLE subway_leg_binding ADD CONSTRAINT subway_leg_binding_source_check
    CHECK (source IS NULL OR source IN
           ('MOTION_AUTOMOTIVE', 'MOTION_UNKNOWN', 'USER_MANUAL', 'INHERITED_ON_REROUTE', 'USER_SELECTED'));

ALTER TABLE subway_binding_event DROP CONSTRAINT subway_binding_event_type_check;
ALTER TABLE subway_binding_event ADD CONSTRAINT subway_binding_event_type_check CHECK (event_type IN (
    'MOVEMENT_START', 'STATION_STOP_EPISODE',
    'PENDING', 'PROVISIONAL', 'CONFIRMED', 'CORRECTED', 'ABANDONED',
    'USER_RESET', 'USER_NOT_BOARDED', 'INHERITED_ON_REROUTE', 'USER_SELECTED'));
