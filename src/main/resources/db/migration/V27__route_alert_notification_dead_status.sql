-- 경로 알림 outbox 에 DEAD 상태와 requeue 추적을 추가한다.
-- DEAD = 재시도 소진(수동 개입 대기, admin requeue 가능). FAILED 는 permanent 실패 전용으로 의미 축소.
-- (기존 status check 제약명은 V23__create_route_arrival_alert.sql 참조.)

ALTER TABLE route_arrival_alert_notification
    DROP CONSTRAINT route_alert_notification_status_check;
ALTER TABLE route_arrival_alert_notification
    ADD CONSTRAINT route_alert_notification_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD', 'CANCELED'));

-- requeue 예산 추적: 무제한 재시도 방지(반복 requeue 시 requeue_count 로 상한).
ALTER TABLE route_arrival_alert_notification
    ADD COLUMN requeue_count integer NOT NULL DEFAULT 0,
    ADD COLUMN last_requeued_at timestamp(6);

-- admin DEAD 스캔용(테이블 소규모라 저비용).
CREATE INDEX idx_route_alert_notification_dead
    ON route_arrival_alert_notification (created_at) WHERE status = 'DEAD';
