package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.application.journey.exception.JourneyCompileFailedException;
import watoo.grd.nextroute.application.journey.exception.SnapshotOwnerMismatchException;
import watoo.grd.nextroute.application.journey.exception.SnapshotUnavailableException;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;

import java.time.Clock;

/**
 * 여정 시작. 트랜잭션 밖에서 준비하고, 저장만 짧은 트랜잭션에 맡긴다.
 *
 * <pre>
 * 1. 검색 스냅샷 조회(Redis)   ← 트랜잭션 밖
 * 2. 소유자 확인
 * 3. 경로 쪼개기
 * 4. 저장                      ← 트랜잭션 안(JourneyStartTxService)
 * </pre>
 *
 * <p>1~3 을 트랜잭션 안에 넣으면 외부 저장소를 기다리는 동안 사용자 행 잠금을 쥐고 있게 된다.
 * 같은 사람의 다음 요청이 그만큼 밀리고, Redis 가 느려지면 DB 커넥션까지 함께 묶인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyStartService implements StartJourneyUseCase {

    /** "사람당 진행 중인 여정 하나"를 지키는 인덱스(V35). 이 이름의 위반만 409 로 번역한다. */
    private static final String ACTIVE_JOURNEY_INDEX = "uq_journey_active_per_user";

    /** PostgreSQL 유일 제약 위반. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    private final RouteSearchSnapshotPort snapshotPort;
    private final JourneyCompiler compiler;
    private final JourneyPlanResolver planResolver;
    private final JourneyStartTxService startTxService;
    private final JourneyQueryService queryService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public JourneySnapshot start(Long userId, StartJourneyCommand command) {
        RouteSearchSnapshotPort.StoredPath stored = snapshotPort
                .find(command.searchSessionId(), command.pathKey())
                .orElseThrow(SnapshotUnavailableException::new);

        requireOwner(stored, userId);

        JourneyPlan plan = compiler.compile(stored.path());
        if (plan.isEmpty()) {
            log.warn("여정 쪼개기 실패 — searchSessionId={}, pathKey={}",
                    command.searchSessionId(), command.pathKey());
            throw new JourneyCompileFailedException();
        }
        plan = planResolver.resolve(plan);

        JsonNode routeSnapshot = objectMapper.valueToTree(stored.path());

        try {
            return startTxService.start(userId, plan, routeSnapshot,
                    destinationSummary(stored.path()), command.replace(), clock.instant());
        } catch (DataIntegrityViolationException e) {
            throw translate(e, userId);
        }
    }

    /**
     * 로그인 없이 검색한 결과로 시작하는 것은 허용한다(D4). 막는 것은 남의 검색 결과다.
     */
    private void requireOwner(RouteSearchSnapshotPort.StoredPath stored, Long userId) {
        if (stored.ownerUserId() != null && !stored.ownerUserId().equals(userId)) {
            throw new SnapshotOwnerMismatchException();
        }
    }

    /** 푸시 문구에 쓸 목적지 이름. 없으면 넣지 않는다. */
    private JsonNode destinationSummary(PathResult path) {
        if (path.info() == null || path.info().lastEndStation() == null) {
            return null;
        }
        return objectMapper.createObjectNode().put("name", path.info().lastEndStation());
    }

    /**
     * 잠금을 뚫고 들어온 동시 시작을 409 로 바꾼다.
     *
     * <p><b>모든 무결성 오류를 409 로 바꾸지 않는다.</b> 그러면 FK 위반이나 NOT NULL 위반 같은
     * 진짜 버그가 "이미 여정이 있습니다"로 둔갑해 조용히 묻힌다. 정확히 그 인덱스의 위반만 본다.
     *
     * <p>트랜잭션은 이미 되감긴 뒤라, 현재 진행 중인 여정은 별도 조회로 다시 읽는다. 그 사이에
     * 상대 여정이 끝났다면 번역할 대상이 없으므로 원래 예외를 그대로 올린다.
     */
    private RuntimeException translate(DataIntegrityViolationException e, Long userId) {
        if (!isActiveJourneyConflict(e)) {
            return e;
        }
        return queryService.getActive(userId)
                .<RuntimeException>map(ActiveJourneyExistsException::new)
                .orElse(e);
    }

    private boolean isActiveJourneyConflict(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cause
                && SQLSTATE_UNIQUE_VIOLATION.equals(cause.getSQLState())
                && ACTIVE_JOURNEY_INDEX.equalsIgnoreCase(cause.getConstraintName());
    }
}
