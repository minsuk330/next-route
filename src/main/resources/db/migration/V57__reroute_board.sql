-- reroute-periodic-board PR-1 (#264). 재탐색 "보드" — 여정 개정본마다 ACTIVE 제안 한 행을 제자리에서 갱신한다.
--
-- V56(#267 regional_bus_route_stop) 다음 번호다. VPS 수동 배치 순서도 #267 → #264.
--
-- 보드는 기존 reroute_proposal 행을 그대로 쓴다(여정당 ACTIVE 1개, V51 부분 unique). 새 표를 두지 않는
-- 이유: 수락·경로 조회가 같은 행을 같은 리더로 읽어야 "본 것 = 적용될 것"이 지켜진다(#261).
-- candidates jsonb 는 봉투 v2({version:2, slots}) 가 되고, 기존 사건 기반 경로는 v1 을 그대로 쓴다.

ALTER TABLE reroute_proposal
    -- 갱신마다 +1. 조건부 UPDATE(낙관 잠금)의 기준이고 앱 ETag 의 재료다.
    ADD COLUMN board_version      bigint      not null default 0,
    -- 마지막으로 찾아본 시각. 후보가 0개여도 "찾아봤다"를 말하려면 필요하다.
    ADD COLUMN refreshed_at       timestamptz,
    -- push 멱등 키 순번. 보드는 개정본 동안 행이 하나라 제안 id 만으로는 두 번째 push 가 중복으로 삼켜진다.
    ADD COLUMN push_seq           integer     not null default 0,
    ADD COLUMN last_push_slot     varchar(96),
    ADD COLUMN last_push_route    varchar(128),
    ADD COLUMN last_push_gain     integer,
    ADD COLUMN last_pushed_at     timestamptz,
    -- 직전 push 에 사용자가 답했는가(수락·경로 조회·dismiss). 무시 집계를 push 단위로 하기 위한 칸.
    ADD COLUMN last_push_answered boolean;

ALTER TABLE journey_session
    -- 다음 보드 갱신 예정 시각. 선점하면 lease 로 덮는다(next_evaluation_at 과 같은 방식).
    ADD COLUMN reroute_refresh_at      timestamptz,
    -- 직전 갱신 시각. 정류장 통과 신호의 30초 최소 간격 판정에 쓴다.
    ADD COLUMN reroute_last_refresh_at timestamptz,
    -- 선점 중에 들어온 갱신 신호. lease 를 앞당기면 같은 여정을 두 워커가 돌므로 표시만 남긴다.
    ADD COLUMN reroute_refresh_dirty   boolean     not null default false,
    -- 마지막 수락 시각. 수락 후 3분 동안 "새 기회" push 를 막는다(수락이 push 를 영구 차단하지 않게).
    ADD COLUMN reroute_accepted_at     timestamptz;

CREATE INDEX ix_journey_reroute_refresh_due
    ON journey_session (reroute_refresh_at)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL AND reroute_refresh_at IS NOT NULL;
