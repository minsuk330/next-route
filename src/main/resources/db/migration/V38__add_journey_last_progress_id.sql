-- progress 요청의 멱등 키. command 의 event_id 와 별개다.
--
-- 같은 progress 를 재전송했을 때 evaluation_version 을 두 번 올리지 않으려면 "마지막으로 반영한
-- progress 가 무엇이었나"를 알아야 한다. 전체 이력은 남기지 않는다 — 정밀 좌표를 서버에 쌓지
-- 않는다는 결정(O10)에 따라 leg 별 최신 요약 한 벌만 유지한다.
ALTER TABLE journey_leg
    ADD COLUMN last_progress_id uuid;
