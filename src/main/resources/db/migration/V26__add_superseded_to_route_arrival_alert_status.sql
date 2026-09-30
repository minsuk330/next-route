-- 스케줄 수정(edit) 기능: 수정으로 대체된 미발송 occurrence 를 SUPERSEDED 로 표시(soft-delete 동반).
-- 사용자 취소(CANCELED)와 상태로 구분. V23 의 status check 제약에 값 추가.
-- untracked convention(로컬/VPS 수동 반영). V25 이후 적용.

ALTER TABLE route_arrival_alert DROP CONSTRAINT route_arrival_alert_status_check;
ALTER TABLE route_arrival_alert ADD CONSTRAINT route_arrival_alert_status_check
    CHECK (status IN ('ACTIVE', 'DONE', 'EXPIRED', 'CANCELED', 'SUPERSEDED'));
