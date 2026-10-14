-- 이벤트 기반 재평가(#415-3). 실시간 데이터가 바뀌면 그 여정의 다음 평가를 앞당긴다.
--
-- evaluation_claimed_until: 평가기가 지금 이 여정을 처리 중인지. next_evaluation_at 이 선점 증서를
--   겸해서 "처리 중"과 "미래에 예정됨"을 구분할 수 없었다. 처리 중이면 앞당기지 않고 표시만 한다 —
--   덮어쓰면 진행 중 평가가 결과를 버리고, 이벤트가 잦은 여정은 매번 버려져 영영 저장되지 않는다.
-- evaluation_dirty: 처리 중에 데이터가 바뀌었다는 표시. 평가를 마칠 때 다음 평가를 곧바로 잡는다.
-- last_evaluated_at: 마지막으로 평가를 마친 시각. 이벤트가 몰려도 여정당 평가 간격의 하한을 지킨다.
--
-- 세 칸 모두 엔티티에 매핑하지 않는다(SQL 전용). JourneySession 을 통째로 저장하는 경로가 옛 값을
-- 되써 표시를 지우지 않게 하려는 것이다(재탐색 보드 칸과 같은 선례).
ALTER TABLE journey_session
    ADD COLUMN evaluation_claimed_until timestamptz,
    ADD COLUMN evaluation_dirty boolean NOT NULL DEFAULT false,
    ADD COLUMN last_evaluated_at timestamptz;

-- 진행 중 길안내 여정만 훑는 관심 키 색인 재구성(#415-3 PR-A2)이 활성 행에서 출발하게 한다.
CREATE INDEX idx_journey_session_active_nav ON journey_session (id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL AND kind = 'NAVIGATION';
