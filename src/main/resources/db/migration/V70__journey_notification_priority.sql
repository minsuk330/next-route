-- 여정 알림 발송 우선순위(#451 PR-2). 적재 때 한 번 정하고 바꾸지 않는다.
--
-- priority: 0 = 다음 행동이 급함(환승 놓침·빠듯), 1 = 곧(갈아탈 차 접근 N2), 2 = 여유(재탐색·보드·관리자 시험).
--   값은 NotificationPriority 한 곳에서 정한다. 기존 행은 기본값 2 — 백필 없음(이미 쌓인 PENDING 은 길어야 30분 산다).
--
-- 엔티티에 매핑하지 않는다(SQL 전용). 적재(enqueue)와 claim 만 읽고 쓴다 — V67·V69 선례와 같이, 엔티티 저장 경로가
-- 옛 값을 되쓰지 못하게 하려는 것이다.
ALTER TABLE journey_notification
    ADD COLUMN priority smallint NOT NULL DEFAULT 2;

-- PRIORITY_FIRST claim 경로. 정렬식이 "created_at <= 기준이면 0 아니면 priority" 라 이 인덱스로 정렬을 끝내지는 못한다
-- (PENDING 은 수백 건 규모라 수용, 노트에 EXPLAIN). aging 이 없는 순서(priority, scheduled_at)로 집을 때와, 나중에
-- aging 을 빼기로 할 때 그대로 탈 수 있는 모양으로 둔다.
--
-- idx_journey_notification_claim(scheduled_at, 같은 부분 조건)은 지우지 않는다. 기본 SCHEDULED 모드의
-- ORDER BY scheduled_at 이 그 인덱스를 타고, 이 인덱스는 priority 가 앞이라 그 순서를 대신하지 못한다.
CREATE INDEX idx_journey_notification_claim_priority
    ON journey_notification (priority, scheduled_at)
    WHERE status = 'PENDING' AND deleted_at IS NULL;
