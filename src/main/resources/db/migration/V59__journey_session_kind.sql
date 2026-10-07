-- standalone-track PR-1 (#265). 여정 종류.
--
-- 단독 추적(TRACK)은 구간 1개짜리 여정으로 같은 기계를 타되, 재탐색(보드 갱신·예약·push)에서 빠진다.
-- 기존 행은 기본값으로 전부 NAVIGATION — 백필 없음. 칼럼을 매핑하는 앱보다 먼저 배치한다(ddl-auto: validate).
ALTER TABLE journey_session ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'NAVIGATION';
ALTER TABLE journey_session ADD CONSTRAINT journey_session_kind_check CHECK (kind IN ('NAVIGATION', 'TRACK'));
