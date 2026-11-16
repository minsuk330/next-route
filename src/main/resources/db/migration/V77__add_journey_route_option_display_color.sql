-- 여정 노선 후보의 노선 표시색(#607). 검색 lane 의 routeDisplayColor(#RRGGBB)를 여정 시작 때 그대로 저장한다.
-- 묶인 버스 노선마다 색이 다를 수 있어 후보 행마다 둔다. 기존 행은 null(앱은 구간 색·회색으로 대체).
ALTER TABLE journey_leg_route_option ADD COLUMN route_display_color varchar(16);
