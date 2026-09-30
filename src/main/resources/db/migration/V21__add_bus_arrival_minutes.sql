-- 등록 시점 버스 예상 도착(분). GET 응답 표시용.
-- bus_arrival_alert 는 신규 테이블로 운영 데이터 없음 → NOT NULL 직접 추가 가능.
ALTER TABLE bus_arrival_alert
    ADD COLUMN bus_arrival_minutes integer NOT NULL;
