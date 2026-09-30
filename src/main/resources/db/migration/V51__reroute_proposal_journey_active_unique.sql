-- journey-reroute-nextstop PR-B stage5(컷오버) 1/2 — 살아 있는 제안을 "환승당 하나"에서
-- "여정당 하나"로 좁힌다.
--
-- 왜 좁히나. 재탐색의 원점은 이제 환승 대기(leg 경계)만이 아니라 주행 중인 버스의 중간
-- 정류장까지 가리킨다(RerouteOrigin). transfer_index 는 그런 자리를 표현할 수 없고, 한 여정에
-- 여러 원점의 제안이 동시에 살아 있으면 사용자는 "어느 것이 지금 제안인지"를 알 수 없다.
-- 수락은 여정을 끝내는 행동이라(한 여정 1회 모델) 살아 있는 제안도 하나면 족하다.
--
-- 순서가 계약이다. 옛 (journey_id, transfer_index) unique 는 <b>이 단계에서 지우지 않는다</b> —
-- 새 인덱스가 더 좁으므로 둘이 공존해도 새 삽입을 막지 않고, 롤백하면 옛 코드가 그대로 돈다.
-- 컬럼·옛 인덱스 제거는 TTL(5분)과 sweep 이 지난 뒤 stage5 2/2 가 한다.

-- 1) 자리를 하나로 줄인다. 같은 여정에 ACTIVE 가 여럿이면 가장 최근 것만 남긴다.
--    지우지 않고 SUPERSEDED 로 접는 이유는 늦게 도착한 수락이 404 인지 409 인지 갈라야 해서다.
UPDATE reroute_proposal p
   SET status = 'SUPERSEDED',
       resolved_at = now(),
       resolved_reason = 'cutover',
       updated_at = now()
 WHERE p.status = 'ACTIVE'
   AND p.deleted_at IS NULL
   AND p.id <> (SELECT keep.id
                  FROM reroute_proposal keep
                 WHERE keep.journey_id = p.journey_id
                   AND keep.status = 'ACTIVE'
                   AND keep.deleted_at IS NULL
                 ORDER BY keep.created_at DESC, keep.id DESC
                 LIMIT 1);

-- 2) 여정당 살아 있는 제안은 하나. 부분 인덱스인 것이 옛 인덱스와 같은 이유로 중요하다 —
--    전체 unique 로 걸면 지난 제안이 자리를 잡아 그 여정에서 두 번째 제안을 영영 못 만든다.
CREATE UNIQUE INDEX uq_reroute_proposal_journey_active
    ON reroute_proposal (journey_id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

COMMENT ON INDEX uq_reroute_proposal_journey_active IS
    '여정당 ACTIVE 제안 1개(stage5). 옛 uq_reroute_proposal_active 는 stage5 2/2 에서 제거';
