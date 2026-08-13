-- 수락된 후보가 무엇이었는지 남긴다(Phase 6 ⑤).
--
-- 없으면 늦게 도착한 수락 요청을 가릴 수 없다: 같은 후보의 재전송(200 + 최신 스냅샷)과
-- 다른 후보를 고른 요청(409)이 둘 다 "이미 ACCEPTED" 로만 보인다.
ALTER TABLE reroute_proposal ADD COLUMN accepted_candidate_id varchar(36);

COMMENT ON COLUMN reroute_proposal.accepted_candidate_id IS
    '수락된 후보의 candidateId. 재전송(같은 후보)과 다른 후보 요청을 가른다';
