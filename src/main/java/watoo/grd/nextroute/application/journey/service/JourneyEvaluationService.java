package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationContextLoader.JourneyEvaluationContext;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 선점한 여정 하나를 평가한다.
 *
 * <p>요청이 없어도 서버가 스스로 하는 계산이다. 사용자가 화면을 보지 않는 동안에도 환승 위험이
 * 갱신돼야 알림을 보낼 근거가 생긴다.
 *
 * <h2>트랜잭션 경계</h2>
 *
 * <pre>
 * [짧은 읽기 트랜잭션] 여정·환승 상태 로드
 *        ↓  (트랜잭션 밖 — 여기서 외부 API 를 부른다)
 *   좌표 조립 → 이동 단계 ETA → 갈아탈 차량 예측 → 판정
 *        ↓
 * [짧은 쓰기 트랜잭션] 소유권 확인 → 판정·예측 저장 → 다음 평가 시각
 * </pre>
 *
 * <p>외부 호출을 어느 트랜잭션에도 넣지 않는 것이 핵심이다. provider 가 느린 동안 DB 커넥션을
 * 붙잡으면 커넥션 풀이 여정 수보다 훨씬 작아서 금방 마른다. 같은 실수를 Phase 3 에서 한 번 했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyEvaluationService {

    private final JourneyEvaluationContextLoader contextLoader;
    private final GetJourneyNavigationGeometryUseCase geometryUseCase;
    private final TransferRiskEvaluator transferRiskEvaluator;
    private final JourneyEvaluationScheduleWriter scheduleWriter;
    private final JourneyEvaluationIntervalPolicy intervalPolicy;
    private final JourneyEvaluationMetrics metrics;
    private final JourneyEvaluatorProperties properties;
    private final Clock clock;

    /** 평가 한 건의 결말. 실패도 종류를 갈라야 재시도 간격을 달리 줄 수 있다. */
    public enum Result {
        /** 계산하고 저장했다. */
        EVALUATED,
        /** 저장 직전에 소유권을 잃었다. 결과를 버렸다. 정상적으로 일어나는 일이다. */
        LOST_CLAIM,
        /** 평가할 상태가 아니다(이미 끝난 여정 등). */
        SKIPPED,
        /** 계산이나 저장이 실패했다. */
        FAILED
    }

    /**
     * @param claim 선점 정보. 저장 전 소유권 확인에 쓴다
     */
    public Report evaluate(JourneyDueClaim claim) {
        JourneyEvaluationContext context = contextLoader.load(claim.journeyId()).orElse(null);
        if (context == null) {
            // 이미 끝난 여정이다. 선점하면서 밀어 둔 다음 평가 시각은 종료 처리가 비운다.
            return Report.skipped();
        }

        Instant now = Instant.now(clock);
        Map<Integer, LegGeometry> geometry = loadGeometry(context);
        TransferAssessmentBatch batch = transferRiskEvaluator.assess(
                context.journeyId(), context.routeRevision(), geometry);

        boolean changed = hasChanged(batch.assessments(), context.previousAssessments());
        JourneyEvaluationOutcome outcome = new JourneyEvaluationOutcome(
                context.journeyId(), context.routeRevision(), batch, changed);

        Instant nextAt = nextEvaluationAt(context, batch, now, changed);
        boolean stored = scheduleWriter.finish(claim, outcome, nextAt);
        if (!stored) {
            return Report.lostClaim();
        }
        return new Report(Result.EVALUATED, batch.assessments(), batch.upstreamBlocked());
    }

    /**
     * 다음 평가 시각. 상황에 맞는 간격을 고르고, 여정 번호로 몇 초씩 흩뜨린다.
     *
     * <p>간격을 고르는 규칙은 {@link JourneyEvaluationIntervalPolicy} 가 가진다. 여기서는 그 결과에
     * 흩뜨리기만 더한다 — 모두 같은 시각에 만기가 되면 한 tick 이 외부 API 를 몰아 부른다.
     *
     * <p>직전 간격은 저장된 판정의 계산 시각에서 되읽는다. 값이 계속 그대로일 때 간격을 늘리려면
     * 직전에 얼마 만에 봤는지 알아야 하는데, 그 값이 이미 저장돼 있어 컬럼을 늘릴 필요가 없다.
     */
    private Instant nextEvaluationAt(JourneyEvaluationContext context, TransferAssessmentBatch batch,
                                     Instant now, boolean changed) {
        JourneyEvaluationIntervalPolicy.Decision decision = intervalPolicy.decide(
                batch.assessments(), context.currentLegIndex(), now,
                context.previousInterval(now), changed, batch.upstreamBlocked());

        metrics.recordInterval(decision.reason().name().toLowerCase(),
                decision.interval().toSeconds());

        long spread = Math.floorMod(context.journeyId(), properties.getJitterSeconds());
        return now.plus(decision.interval()).plusSeconds(spread);
    }

    /**
     * 앱에 보일 값이 달라졌는지.
     *
     * <p>여정 평가 번호를 올릴지 가르는 판단이다. 조회 API 는 그 번호로 "바뀐 것 없음(204)"을
     * 판단하므로 <b>도착 시각만 바뀐 경우에도 올려야 한다.</b> 상태만 비교하면 앱이 계속 204 를
     * 받아 새 도착 시각을 영원히 못 본다.
     */
    private boolean hasChanged(List<TransferRiskAssessment> assessments,
                               Map<Integer, TransferRiskAssessment> previous) {
        if (assessments.isEmpty()) {
            return false;
        }
        return assessments.stream()
                .anyMatch(assessment -> !assessment.sameAs(previous.get(assessment.transferIndex())));
    }

    /**
     * 좌표. 실패해도 평가를 포기하지 않는다.
     *
     * <p>좌표가 없으면 이동 단계 ETA 가 계획 소요시간으로 떨어질 뿐이고, 갈아탈 차량 예측과 환승
     * 판정은 좌표와 무관하게 동작한다.
     */
    private Map<Integer, LegGeometry> loadGeometry(JourneyEvaluationContext context) {
        try {
            JourneyNavigationGeometry geometry = geometryUseCase.get(
                    context.userId(), context.journeyId(), context.routeRevision());
            return geometry.legs().stream()
                    .collect(Collectors.toMap(LegGeometry::legIndex, Function.identity()));
        } catch (Exception e) {
            log.warn("[JourneyEvaluation] 좌표 조회 실패 — journeyId={}, 계획 소요시간으로 내려간다",
                    context.journeyId(), e);
            return Map.of();
        }
    }


    /**
     * 평가 한 건의 결과 보고.
     *
     * <p>결말만으로는 부족하다. 부르는 쪽이 계측을 남기려면 어떤 예측이 나왔는지(실시간·모델·모름)와
     * provider 가 막혔는지를 알아야 한다.
     *
     * @param assessments     저장한 판정. 계측용이며 저장은 이미 끝난 상태다
     * @param upstreamBlocked provider 차단이 있었는지. 다음 평가는 이미 늦춰 심어 뒀다
     */
    public record Report(Result result, List<TransferRiskAssessment> assessments,
                         boolean upstreamBlocked) {

        static Report skipped() {
            return new Report(Result.SKIPPED, List.of(), false);
        }

        static Report lostClaim() {
            return new Report(Result.LOST_CLAIM, List.of(), false);
        }
    }
}
