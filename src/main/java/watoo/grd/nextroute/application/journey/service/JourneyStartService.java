package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

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

    private final RouteSearchSnapshotPort snapshotPort;
    private final JourneyCompiler compiler;
    private final JourneyTransitIdEnricher transitIdEnricher;
    private final JourneyGuidanceInfoEnricher guidanceInfoEnricher;
    private final JourneyStartTxService startTxService;
    private final JourneyStartConflictTranslator conflictTranslator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public JourneySnapshot start(Long userId, StartJourneyCommand command) {
        RouteSearchSnapshotPort.StoredPath stored = snapshotPort
                .find(command.searchSessionId(), command.pathKey())
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.SNAPSHOT_EXPIRED));

        requireOwner(stored, userId);

        JourneyPlan plan = compiler.compile(stored.path());
        if (plan.isEmpty()) {
            log.warn("여정 쪼개기 실패 — searchSessionId={}, pathKey={}",
                    command.searchSessionId(), command.pathKey());
            throw new BusinessException(JourneyErrorCode.JOURNEY_COMPILE_FAILED);
        }
        plan = guidanceInfoEnricher.enrich(transitIdEnricher.enrich(plan));

        JsonNode routeSnapshot = objectMapper.valueToTree(stored.path());

        try {
            // 급행 끄기는 검색 요청값 그대로 세션에 남긴다(D10 으로 풀렸어도) — 재탐색이 같은 선호로 찾는다(subway-express S5).
            return startTxService.start(userId, plan, routeSnapshot,
                    destinationSummary(stored.path()), command.replace(), clock.instant(),
                    JourneyKind.NAVIGATION, stored.excludeExpress(), command.searchSessionId());
        } catch (DataIntegrityViolationException e) {
            throw conflictTranslator.translate(e, userId);
        }
    }

    /**
     * 로그인 없이 검색한 결과로 시작하는 것은 허용한다(D4). 막는 것은 남의 검색 결과다.
     */
    private void requireOwner(RouteSearchSnapshotPort.StoredPath stored, Long userId) {
        if (stored.ownerUserId() != null && !stored.ownerUserId().equals(userId)) {
            throw new BusinessException(JourneyErrorCode.SNAPSHOT_OWNER_MISMATCH);
        }
    }

    /** 푸시 문구에 쓸 목적지 이름. 없으면 넣지 않는다. */
    private JsonNode destinationSummary(PathResult path) {
        if (path.info() == null || path.info().lastEndStation() == null) {
            return null;
        }
        return objectMapper.createObjectNode().put("name", path.info().lastEndStation());
    }
}
