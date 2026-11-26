-- 여정을 시작한 검색(route_search_event.search_session_id, V79). 앱은 이미 여정 시작 요청에 이 값을 보낸다
-- (StartJourneyRequest.searchSessionId) → 앱 수정 없이 검색 → 여정 연결. 단독 추적(TRACK)·이전 여정은 null.
ALTER TABLE journey_session ADD COLUMN search_session_id varchar(64);

CREATE INDEX idx_journey_session_search_session
    ON journey_session (search_session_id) WHERE search_session_id IS NOT NULL;
