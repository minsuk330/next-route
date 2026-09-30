-- V23 의 좌표 컬럼(start_x/start_y/end_x/end_y)이 Hibernate CamelCaseToUnderscores 규칙과 불일치.
-- 끝자리 단일 대문자(endX, startY)는 underscore 를 붙이지 않으므로 엔티티는 startx/starty/endx/endy 를 기대한다.
-- (V1 baseline 도 startx/endx 로 이 규칙을 따름.) 컬럼명을 엔티티 기대값으로 rename.

ALTER TABLE route_arrival_alert RENAME COLUMN start_x TO startx;
ALTER TABLE route_arrival_alert RENAME COLUMN start_y TO starty;
ALTER TABLE route_arrival_alert RENAME COLUMN end_x TO endx;
ALTER TABLE route_arrival_alert RENAME COLUMN end_y TO endy;
