-- journey-reroute-nextstop PR-B stage5(컷오버) 2/2 — 옛 주소(transfer_index)를 걷는다.
--
-- 앞 배포(V51)가 "여정당 살아 있는 제안 하나"를 세우고 코드의 키를 전부 옮겼다. 이 파일은
-- 그 배포가 라이브에서 TTL(5분)+sweep 을 넘긴 뒤에 돈다 — 그 사이에 만들어진 ACTIVE 행은
-- 이미 origin 을 들고 있고, 옛 행은 전부 접혀 있다.
--
-- 왜 지우나. 주소가 둘이면 어느 쪽이 진짜인지 코드가 매번 고르게 되고, 그 선택이 한 군데라도
-- 어긋나면 엉뚱한 자리를 가리킨다. transfer_index 는 leg 경계밖에 못 가리켜 주행 중 정류장
-- 원점(RerouteOrigin.RIDE_INTERIOR)을 표현할 수 없으므로 남겨 둘 이유가 없다.

-- 1) 주소가 없는 채 살아 있는 행을 접는다. 컬럼이 사라지면 이 행들은 "어디에 대한 제안인지"를
--    영영 말할 수 없다 — 지우지 않고 접는 것은 늦은 수락을 404 와 409 로 가르기 위해서다.
UPDATE reroute_proposal
   SET status = 'SUPERSEDED',
       resolved_at = now(),
       resolved_reason = 'cutover-no-origin',
       updated_at = now()
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL
   AND origin IS NULL;

-- 2) 옛 부분 unique 를 먼저 지운다. 컬럼을 먼저 지우면 인덱스가 함께 사라져 순서가 보이지 않는다.
DROP INDEX IF EXISTS uq_reroute_proposal_active;

ALTER TABLE reroute_proposal DROP COLUMN transfer_index;

COMMENT ON COLUMN reroute_proposal.origin IS
    'RerouteOriginEnvelope{version,origin}. 이 제안이 가리키는 자리(서울 좌표계). stage5 2/2 부터 유일한 주소이고 새 행은 반드시 채워진다';
