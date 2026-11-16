-- 선택 노선의 주인(#614). DEFAULT = 서버(여정 시작 때 묶인 버스 중 먼저 오는 노선, 없으면 첫 후보), USER = 사용자 선택(#598).
-- 서버가 대기 중 선택을 옮기는 일은 DEFAULT 일 때만 한다. 기존 행은 null(조회에서 선택이 있으면 DEFAULT 로 읽는다).
ALTER TABLE journey_leg ADD COLUMN route_option_selected_by varchar(16);
ALTER TABLE journey_leg ADD CONSTRAINT journey_leg_route_option_selected_by_check
    CHECK (route_option_selected_by IS NULL OR route_option_selected_by IN ('DEFAULT', 'USER'));

-- 노선 후보의 정적 탑승시간(초, 검색 lane rideSeconds). 사용자가 묶인 노선 중 다른 것을 고르면 탑승 단계 계획 소요를 이 값으로 바꾼다.
ALTER TABLE journey_leg_route_option ADD COLUMN ride_seconds integer;
