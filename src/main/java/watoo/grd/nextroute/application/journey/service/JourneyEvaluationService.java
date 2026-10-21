package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.DetectionStamp;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.NotificationContext;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationProperties;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;
import watoo.grd.nextroute.application.journey.port.out.DetectionStampPort;
import watoo.grd.nextroute.application.journey.port.out.NotificationChannelResolver;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationContextLoader.JourneyEvaluationContext;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
    private final TransferAssessmentGracePolicy gracePolicy;
    private final JourneyEvaluationMetrics metrics;
    private final JourneyEvaluatorProperties properties;
    /** 평가 중 데이터가 바뀌었을 때 다음 평가까지 둘 최소 간격의 출처(#415-3). */
    private final ReevaluationProperties reevaluationProperties;
    /** 이 사용자에게 어느 길로 보낼지. 평가 트랜잭션 <b>밖</b>에서 부른다. */
    private final NotificationChannelResolver channelResolver;
    private final TransferTargetApproachPolicy approachPolicy;
    /** 재탐색 방아쇠(Phase 6 ①). 지금은 세기만 한다 — 제안을 만들지 않는다. */
    private final RerouteTriggerEvaluator triggerEvaluator;
    private final Clock clock;
    /** 처음 본 시각 칸(#446 처음부터 끝까지 지표). 측정 전용 — 실패해도 평가는 그대로다. */
    private final DetectionStampPort detectionStamps;

    /** 평가기 몫의 provider 동시 호출 수 — 버스 단계가 있는 여정. 워커 스레드 수와 분리하는 장치다. */
    @Qualifier("journeyEvaluatorProviderLimiter")
    private final Semaphore providerLimiter;

    /** 버스 단계가 없는 여정(지하철·도보)의 슬롯. 지하철 실시간은 일일 한도가 없어 따로 넓게 둔다(#427 처방 2차). */
    @Qualifier("journeyEvaluatorSubwayProviderLimiter")
    private final Semaphore subwayProviderLimiter;

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
        // 이 평가를 부른 변화를 처음 본 시각(#446). 계산 <b>전에</b> 꺼낸다 — 계산하는 동안 들어온 관측은 이 평가가 못 본
        // 것일 수 있으니 다음 평가 몫으로 남긴다.
        DetectionStamp detection = detectionStamps.consume(claim.journeyId()).orElse(null);
        TransferAssessmentBatch computed = computeWithProviderSlot(context);
        if (computed == null) {
            // 평가기 몫의 provider 슬롯을 못 잡았다. 선점을 풀어 곧 다시 잡히게 한다 — 그대로 두면 lease(60초)가
            // 만료될 때까지 아무도 평가하지 않는다. 워커(16)가 버스 슬롯(4)보다 많아 버스 여정이 몰리면 생긴다.
            releaseForRetry(claim);
            // 평가를 못 했으니 처음 본 시각도 다음 평가에 돌려준다.
            detectionStamps.restore(claim.journeyId(), detection);
            return Report.skipped();
        }

        TransferAssessmentBatch batch = applyGrace(computed, context, now);
        Map<Integer, TransferTargetArrival> outside = outsideAssessments(batch, context.currentLegIndex());
        boolean changed = hasChanged(batch.assessments(), context.previousAssessments())
                || boardingsChanged(outside, context.previousBoardings(), now);
        // 저장은 허용 폭 안이면 직전 값을 유지한다 — 저장값과 평가 번호가 늘 같은 것을 가리키게(AssessmentChangeRule)
        TransferAssessmentBatch toStore = new TransferAssessmentBatch(
                AssessmentChangeRule.stabilize(batch.assessments(), context.previousAssessments(),
                        properties.changeTolerance()),
                stabilizeBoardings(batch.boardingByLegIndex(), outside, context.previousBoardings(), now),
                batch.upstreamBlocked());
        JourneyEvaluationOutcome outcome = new JourneyEvaluationOutcome(
                context.journeyId(), context.routeRevision(), toStore, changed);

        Schedule next = nextEvaluation(context, batch, toStore, now, changed);
        NotificationContext notification = notificationContext(context, batch, now, detection);
        // 마친 시각은 저장 직전에 다시 잰다. 계산 전 시각을 쓰면 외부 호출이 최소 간격보다 오래 걸린
        // 평가에서 "직전 평가 + 최소 간격"이 이미 지난 시각이 돼, 느린 여정일수록 간격 하한이 사라진다.
        boolean stored = scheduleWriter.finish(claim, outcome, next.at(), next.urgent(),
                next.dirtyGapSeconds(), Instant.now(clock), notification);
        if (!stored) {
            return Report.lostClaim();
        }
        evaluateRerouteTrigger(context, batch, now);
        return new Report(Result.EVALUATED, batch.assessments(), batch.upstreamBlocked(), changed);
    }

    /**
     * 재탐색 방아쇠를 본다. <b>소유권을 확인한 뒤·트랜잭션 밖</b>이다.
     *
     * <p>순서가 중요하다 — 앞에 두면 lease 를 잃은 워커가 cooldown 을 태워 이긴 워커의 방아쇠를
     * 먹는다(알림이 CAS 뒤에 오는 것과 같은 이유). 트랜잭션 밖인 것은 cooldown 확인이 DB
     * 커넥션을 붙잡지 않게 하기 위해서다.
     *
     * <p><b>예외를 삼킨다.</b> 이 시점에는 판정·예측이 <b>이미 커밋됐다</b>. 여기서 던지면
     * {@code JourneyDueEvaluatorScheduler.evaluateOne} 이 그것을 잡아 성공한 평가를
     * {@code storage}·{@code transient} 실패로 기록한다 — 지표가 거짓말을 하고, 진짜 저장 실패
     * 신호가 그 밑에 묻힌다. 방아쇠는 관측일 뿐이라 실패해도 여정은 멀쩡해야 한다.
     */
    private void evaluateRerouteTrigger(JourneyEvaluationContext context,
                                        TransferAssessmentBatch batch, Instant now) {
        try {
            triggerEvaluator.evaluate(context, batch, now);
        } catch (Exception e) {
            log.warn("[JourneyEvaluation] 재탐색 방아쇠 판정 실패 — journeyId={}: {}",
                    context.journeyId(), e.getMessage());
        }
    }

    /**
     * 이번 결과에 알릴 것이 있으면 발송 정보를 미리 만든다.
     *
     * <p>채널 결정을 <b>평가 트랜잭션 밖</b>에서 한다. PR3 의 구현은 DB 를 읽는데, 그 읽기가
     * 트랜잭션 안에 들어오면 쓰기 트랜잭션이 그만큼 길어진다.
     *
     * <p>알릴 것이 하나도 없으면 아예 부르지 않는다 — 대부분의 평가가 여기에 해당하므로,
     * 매번 부르면 아무 알림도 안 나가는 여정에 조회가 하나씩 붙는다.
     *
     * <p><b>알릴 것은 두 종류다.</b> 위험 알림은 상태가 나쁠 때, 접근 알림은 반대로 상태가
     * <b>안전할 때</b> 나간다. 위험만 조건으로 두면 접근 알림은 코드가 다 있어도 여기서 null 이
     * 되어 한 건도 나가지 않는다. 접근 조건은 좁아서(대기 단계 + 임박 창) 대부분의 평가는
     * 여전히 null 이다.
     *
     * @param now 이 평가의 기준 시각. 판정기가 쓰는 것과 같은 값이어야 한다
     * @return 알릴 것이 없으면 null
     */
    private NotificationContext notificationContext(JourneyEvaluationContext context,
                                                    TransferAssessmentBatch batch, Instant now,
                                                    DetectionStamp detection) {
        boolean anyRisk = batch.assessments().stream()
                .anyMatch(a -> (a.status() == TransferStatus.AT_RISK
                        || a.status() == TransferStatus.MISSED)
                        && a.isUpcoming(context.currentLegIndex()));
        boolean anyApproach = batch.assessments().stream()
                .anyMatch(a -> approachPolicy.isApproaching(a, context.currentLegIndex(), now));
        if (!anyRisk && !anyApproach) {
            return null;
        }
        return new NotificationContext(context.userId(), context.currentLegIndex(),
                channelResolver.resolve(context.userId()),
                detection != null ? detection : DetectionStamp.evaluation(now, tierBefore(context, now)));
    }

    /**
     * 표시가 없을 때(간격 폴링이 먼저 잡음·Redis 빔) 처음 본 시각은 이 평가의 시작 시각(하한)이고, 등급은 <b>평가 직전</b>
     * 판정으로 색인과 같은 규칙(평가 간격 정책의 등급)을 다시 매긴다 — 방금 나온 위험으로 매기면 위험 알림은 늘 긴급이
     * 돼 등급별 지표가 뜻을 잃는다.
     */
    private ReevaluationTier tierBefore(JourneyEvaluationContext context, Instant now) {
        return intervalPolicy.tierOf(List.copyOf(context.previousAssessments().values()),
                context.currentLegIndex(), now, false);
    }

    /**
     * 외부 호출 구간. 평가기 몫의 슬롯을 잡은 동안에만 돈다.
     *
     * <p><b>DB 작업은 이 안에 넣지 않는다.</b> 넣으면 provider 동시 호출 제한이 DB 처리 시간까지
     * 붙잡아, 슬롯이 실제 외부 호출보다 훨씬 오래 잠긴다.
     *
     * @return 슬롯을 못 잡았으면 null
     */
    private TransferAssessmentBatch computeWithProviderSlot(JourneyEvaluationContext context) {
        // 버스 단계가 있으면 버스(한도 있는) 몫, 없으면 지하철 몫. 여정 단위로 고르는 이유: 공급자 어댑터는 검색과
        // 같이 쓰므로 거기서 "평가기 호출인지"를 가릴 수 없다. 지난 단계까지 보수적으로 센다(컨텍스트 로더).
        Semaphore providerLimiter = context.usesBusProvider() ? this.providerLimiter : subwayProviderLimiter;
        String gate = context.usesBusProvider() ? "bus" : "subway";
        boolean acquired;
        try {
            acquired = providerLimiter.tryAcquire(
                    properties.getProviderAcquireMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.recordProviderSlot("interrupted", gate);
            return null;
        }
        if (!acquired) {
            metrics.recordProviderSlot("rejected", gate);
            log.debug("[JourneyEvaluation] provider 슬롯 없음 — journeyId={}, 이번 tick 은 건너뛴다",
                    context.journeyId());
            return null;
        }
        try {
            metrics.recordProviderSlot("acquired", gate);
            Map<Integer, LegGeometry> geometry = loadGeometry(context);
            return transferRiskEvaluator.assess(
                    context.journeyId(), context.routeRevision(), context.currentLegIndex(),
                    geometry);
        } finally {
            providerLimiter.release();
        }
    }

    private void releaseForRetry(JourneyDueClaim claim) {
        try {
            scheduleWriter.releaseForRetry(claim, Instant.now(clock).plusSeconds(properties.getSlotRetrySeconds()));
        } catch (RuntimeException e) {
            // 못 풀면 예전처럼 lease 만료 뒤에 다시 잡힌다.
            log.warn("[JourneyEvaluation] 슬롯 없음 — 선점을 풀지 못함 journeyId={}: {}",
                    claim.journeyId(), e.getMessage());
        }
    }

    /**
     * 조회가 실패한 환승은 직전 판정을 잠시 붙잡는다.
     *
     * <p>여기서 갈아치우기 때문에 뒤따르는 "값이 바뀌었나" 비교가 <b>유지 결정 뒤의 판정</b>을 본다.
     * 순서가 뒤집히면 직전 값을 유지했는데도 평가 번호가 올라, 앱이 같은 값을 다시 받는다.
     */
    private TransferAssessmentBatch applyGrace(TransferAssessmentBatch batch,
                                               JourneyEvaluationContext context, Instant now) {
        if (batch.isEmpty()) {
            return batch;
        }
        List<TransferRiskAssessment> chosen = batch.assessments().stream()
                .map(fresh -> {
                    TransferRiskAssessment kept = gracePolicy.choose(
                            fresh, context.previousAssessments().get(fresh.transferIndex()), now);
                    if (kept != fresh) {
                        metrics.recordGraceHeld();
                    }
                    return kept;
                })
                .toList();
        return new TransferAssessmentBatch(chosen, batch.boardingByLegIndex(),
                batch.upstreamBlocked());
    }

    /**
     * 다음 평가 시각. 상황에 맞는 간격을 고르고, 여정 번호로 몇 초씩 흩뜨린다.
     *
     * <p>간격을 고르는 규칙은 {@link JourneyEvaluationIntervalPolicy} 가 가진다. 여기서는 그 결과에
     * 흩뜨리기만 더한다 — 모두 같은 시각에 만기가 되면 한 tick 이 외부 API 를 몰아 부른다.
     *
     * <p>직전 간격은 저장된 판정의 계산 시각에서 되읽는다. 값이 계속 그대로일 때 간격을 늘리려면
     * 직전에 얼마 만에 봤는지 알아야 하는데, 그 값이 이미 저장돼 있어 컬럼을 늘릴 필요가 없다.
     *
     * <p>급한지도 같은 결정에서 꺼낸다. 간격을 최소로 줄인 바로 그 이유(위험·임박)가 평가기가 밀릴 때
     * 먼저 잡을 이유이기도 하다 — 기준을 두 벌로 두면 둘이 어긋난다.
     */
    private Schedule nextEvaluation(JourneyEvaluationContext context, TransferAssessmentBatch batch,
                                    TransferAssessmentBatch toStore, Instant now, boolean changed) {
        JourneyEvaluationIntervalPolicy.WaitsAhead waits = waitsAhead(toStore, context.currentLegIndex());
        JourneyEvaluationIntervalPolicy.Decision decision = intervalPolicy.decide(
                batch.assessments(), context.currentLegIndex(), now,
                context.previousInterval(now), changed, batch.upstreamBlocked(), waits);

        metrics.recordInterval(decision.reason().name().toLowerCase(),
                decision.interval().toSeconds());

        long spread = Math.floorMod(context.journeyId(), properties.getJitterSeconds());
        ReevaluationTier tier = intervalPolicy.tierOf(batch.assessments(), context.currentLegIndex(), now,
                batch.upstreamBlocked(), waits);
        return new Schedule(now.plus(decision.interval()).plusSeconds(spread), decision.urgent(),
                reevaluationProperties.minGapSeconds(tier));
    }

    /**
     * 다음 평가 시각과, 그때 먼저 잡을지.
     *
     * @param dirtyGapSeconds 평가하는 동안 데이터가 바뀌었으면(#415-3) 다음 평가까지 둘 최소 간격
     */
    private record Schedule(Instant at, boolean urgent, long dirtyGapSeconds) {
    }

    /**
     * 앱에 보일 값이 달라졌는지.
     *
     * <p>여정 평가 번호를 올릴지 가르는 판단이다. 조회 API 는 그 번호로 "바뀐 것 없음(204)"을
     * 판단하므로 <b>도착 시각만 바뀐 경우에도 올려야 한다.</b> 상태만 비교하면 앱이 계속 204 를
     * 받아 새 도착 시각을 영원히 못 본다.
     */
    /**
     * 판정이 담지 못하는 대기 예측(#480) — 지금이거나 앞으로 올 대기 중 환승 대상이 아니거나, 그 환승의 앞 단계가 아직 시작 전인
     * 것. 앞 단계가 진행 중인 환승의 대기는 판정이 맡는다(변화·안정화 모두 판정 쪽 규칙).
     */
    private static Map<Integer, TransferTargetArrival> outsideAssessments(TransferAssessmentBatch batch,
                                                                         int currentLegIndex) {
        Map<Integer, TransferTargetArrival> outside = new java.util.HashMap<>();
        batch.boardingByLegIndex().forEach((legIndex, target) -> {
            boolean covered = batch.assessments().stream()
                    .anyMatch(a -> a.toLegIndex() == legIndex && a.fromLegIndex() <= currentLegIndex);
            if (legIndex >= currentLegIndex && !covered) {
                outside.put(legIndex, target);
            }
        });
        return outside;
    }

    /**
     * 판정 밖 대기 예측이 저장값과 달라졌는지(#480). 환승 없는 여정은 판정이 비어 {@link #hasChanged} 가 늘 false 라, 이게 없으면
     * 평가 번호가 안 올라 앱이 새 도착 예정을 못 본다(204).
     *
     * <ul>
     *   <li>이번에 못 구했으면: 저장기가 직전 예측을 지킬 때({@link JourneyEvaluationScheduleWriter#stillUsable})는 같다, 지울 때는
     *       바뀜이다 — 지워졌는데 번호가 그대로면 앱이 지난 차의 도착 예정을 계속 본다</li>
     *   <li>허용 폭(판정과 같은 30초) 안이면 같다. 둘 다 "지금 무렵"(지금 + 폭 이전)이면 같다 — 열차가 역에 서 있는 동안 매 평가
     *       "지금"이 새로 나와 번호가 오르지 않게</li>
     *   <li>저장값이 곧(미래 폭 안) 지날 참인데 새 값이 그 폭 너머로 밀렸으면 폭 안이어도 바뀜이다 — 옛 값이 지나 조회가 모름이 된다</li>
     * </ul>
     */
    private boolean boardingsChanged(Map<Integer, TransferTargetArrival> outside,
                                     Map<Integer, JourneyEvaluationContextLoader.StoredBoarding> previous, Instant now) {
        return outside.entrySet().stream()
                .anyMatch(entry -> !sameBoarding(entry.getValue().arrival().arrivalAt(),
                        previous.get(entry.getKey()), now));
    }

    private boolean sameBoarding(Instant fresh, JourneyEvaluationContextLoader.StoredBoarding storedBoarding,
                                 Instant now) {
        Instant stored = storedBoarding == null ? null : storedBoarding.predictedEndAt();
        if (fresh == null) {
            return stored == null
                    || JourneyEvaluationScheduleWriter.stillUsable(stored, storedBoarding.observedAt(), now);
        }
        if (stored == null) {
            return false;
        }
        if (fresh.equals(stored)) {
            return true;
        }
        Duration tolerance = properties.changeTolerance();
        Instant soon = now.plus(tolerance);
        if (!stored.isAfter(soon) && !fresh.isAfter(soon)) {
            return true;
        }
        if (stored.isAfter(now) && !stored.isAfter(soon)) {
            return false;
        }
        return Duration.between(stored, fresh).abs().compareTo(tolerance) <= 0;
    }

    /**
     * 판정 밖 대기 예측이 저장값과 같으면(허용 폭 안) 저장값 시각을 그대로 둔다 — 근거(출처·품질·관측 시각)만 새것. 폭 안의 변화를
     * 매번 저장하면 기준이 조금씩 따라 움직여, 몇 분이 밀려도 한 번에 폭을 넘지 않아 평가 번호가 영영 안 오른다. 이미 지난
     * 저장값은 붙잡지 않는다(조회가 모름으로 본다).
     */
    private Map<Integer, TransferTargetArrival> stabilizeBoardings(
            Map<Integer, TransferTargetArrival> all, Map<Integer, TransferTargetArrival> outside,
            Map<Integer, JourneyEvaluationContextLoader.StoredBoarding> previous, Instant now) {
        if (outside.isEmpty()) {
            return all;
        }
        Map<Integer, TransferTargetArrival> out = new java.util.HashMap<>(all);
        outside.forEach((legIndex, target) -> {
            CurrentLegArrival fresh = target.arrival();
            JourneyEvaluationContextLoader.StoredBoarding storedBoarding = previous.get(legIndex);
            Instant stored = storedBoarding == null ? null : storedBoarding.predictedEndAt();
            if (fresh.arrivalAt() == null || stored == null || !stored.isAfter(now)
                    || fresh.arrivalAt().equals(stored) || !sameBoarding(fresh.arrivalAt(), storedBoarding, now)) {
                return;
            }
            out.put(legIndex, new TransferTargetArrival(new CurrentLegArrival(stored,
                    (int) Math.max(0, Duration.between(now, stored).toSeconds()), fresh.remainingDistanceMeters(),
                    fresh.remainingStops(), fresh.source(), fresh.quality(), fresh.observedAt()),
                    target.modelVersion(), target.upstreamBlocked()));
        });
        return Map.copyOf(out);
    }

    /** 판정 밖 남은 대기 — 간격 규칙용(#480). 이른 예측은 저장할 값(안정화 뒤) 기준이다 — 그 값이 지나기 전에 다시 본다. */
    private static JourneyEvaluationIntervalPolicy.WaitsAhead waitsAhead(TransferAssessmentBatch toStore,
                                                                         int currentLegIndex) {
        Map<Integer, TransferTargetArrival> outside = outsideAssessments(toStore, currentLegIndex);
        if (outside.isEmpty()) {
            return JourneyEvaluationIntervalPolicy.WaitsAhead.NONE;
        }
        Instant earliest = outside.values().stream()
                .map(target -> target.arrival().arrivalAt())
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        return new JourneyEvaluationIntervalPolicy.WaitsAhead(true, earliest);
    }

    private boolean hasChanged(List<TransferRiskAssessment> assessments,
                               Map<Integer, TransferRiskAssessment> previous) {
        if (assessments.isEmpty()) {
            return false;
        }
        // 시각은 허용 폭(기본 30초) 안의 흔들림을 같은 것으로 본다. 상태·출처 변화는 즉시 바뀜.
        return AssessmentChangeRule.anyChanged(assessments, previous, properties.changeTolerance());
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
     * @param changed         저장한 판정이 직전과 달라졌는지. {@code EVALUATED} 일 때만 뜻이 있다
     */
    public record Report(Result result, List<TransferRiskAssessment> assessments,
                         boolean upstreamBlocked, boolean changed) {

        static Report skipped() {
            return new Report(Result.SKIPPED, List.of(), false, false);
        }

        static Report lostClaim() {
            return new Report(Result.LOST_CLAIM, List.of(), false, false);
        }
    }
}
