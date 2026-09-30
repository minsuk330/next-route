-- journey-reroute-nextstop PR-3 — 제안 쪽 알림 상태(V53 의 짝).
--
-- 표를 갈라 둔 이유는 테스트 때문이 아니라 <b>표마다 배포 단위가 다르기</b> 때문이다. 여정
-- 상태(V53)는 journey_session 만 있으면 서고, 이 파일은 reroute_proposal 이 있어야 선다.
-- 한 파일에 묶으면 그 둘을 함께 만들지 않는 자리(스키마 IT 의 @Sql 체인 등)가 통째로 막힌다.

-- 능동 발행으로 알림이 나간 제안인지. 사용자가 직접 요청한 제안은 무응답 개념이 없다 —
-- 그 화면은 사용자가 이미 보고 있다. 이 칸이 없으면 만료된 제안을 전부 "무시"로 세게 된다.
ALTER TABLE reroute_proposal
    ADD COLUMN alerted boolean not null default false;

COMMENT ON COLUMN reroute_proposal.alerted IS
    '서버가 먼저 알린 제안인가(능동 발행). 만료 시 무응답으로 세는 대상';

-- 사용자의 응답을 상태로 남긴다. SUPERSEDED 로 뭉치면 "새 제안이 밀어냈다"와 "사용자가
-- 거절했다"가 구별되지 않는데, 둘은 다음 알림을 보낼지에 정반대로 작용한다.
ALTER TABLE reroute_proposal DROP CONSTRAINT reroute_proposal_status_check;
ALTER TABLE reroute_proposal
    ADD CONSTRAINT reroute_proposal_status_check
        CHECK (status in ('ACTIVE', 'ACCEPTED', 'EXPIRED', 'SUPERSEDED', 'REJECTED', 'DISMISSED'));
