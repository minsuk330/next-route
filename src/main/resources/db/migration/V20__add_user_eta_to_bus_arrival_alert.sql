-- 사용자 정류소 예상 도착 시각. 이 시각 이후 오는 버스를 알림 대상으로 한다.
-- bus_arrival_alert 는 신규 테이블(V19)로 운영 데이터 없음 → NOT NULL 직접 추가 가능.
ALTER TABLE bus_arrival_alert
    ADD COLUMN user_eta timestamp(6) NOT NULL;
