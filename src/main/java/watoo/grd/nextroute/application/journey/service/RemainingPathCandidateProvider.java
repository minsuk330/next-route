package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.BoardingTimeEstimator;
import watoo.grd.nextroute.application.journey.port.out.RerouteCandidateProvider;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Waypoints;
import watoo.grd.nextroute.application.journey.service.RemainingPathEtaComposer.Attempt;
import watoo.grd.nextroute.application.journey.service.RemainingPathEtaComposer.Prepared;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 환승 정류장에서 목적지까지 <b>다른 경로</b>를 찾아 후보로 올린다(Phase 6 ④).
 *
 * <p>③(같은 정류장의 다른 버스)이 빈손일 때 받는 칸이다. 지도 앱에서 길찾기를 다시 누르는 것이고,
 * 출발지는 "지금 내 위치"가 아니라 <b>곧 내릴 환승 정류장</b>이다 — 서버는 정밀 좌표를 받지 않는다.
 *
 * <h2>순서가 곧 요금이다</h2>
 * <ol>
 *   <li><b>검색을 먼저</b> 한다. 검색이 빈손이면 기준선 예측이 통째로 낭비다</li>
 *   <li>경로를 쪼개 보강한다. 여기까지는 유료 호출이 없다. <b>지금 타려던 노선과 같은 경로</b>는
 *       이 단계에서 버린다 — 예측을 부르기 전이다</li>
 *   <li>남는 경로가 있을 때만 <b>기준선</b>을 잰다. 기준선은 ④가 스스로 다시 잰다(D7) —
 *       ③에게 값을 받으면 공급자 사이에 순서 의존이 생긴다</li>
 *   <li>후보마다 승차를 예측하고 목적지 도착 시각으로 견준다</li>
 * </ol>
 *
 * <h2>환승이 늘어나는 후보는 버린다</h2>
 * 첫 대기 이후의 대기 시간은 0으로 두는데(D4), ③에서는 그 값이 기준선과 후보에 <b>똑같이</b> 빠져
 * 뺄셈에서 상쇄됐다. ④는 경로 구조 자체를 바꾸므로 그 전제가 깨진다 — 후보의 환승이 더 많으면
 * 후보만 낙관돼 <b>없는 개선폭</b>이 만들어지고, 사용자는 더 느린 길로 갈아탄다.
 *
 * <p><b>트랜잭션 밖에서 돈다.</b> 외부 호출을 하는 코드가 DB 커넥션을 붙잡고 있으면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemainingPathCandidateProvider implements RerouteCandidateProvider {

    private final TransferConnectionRepository transferRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final JourneyWaypointResolver waypointResolver;
    private final RemainingRouteSearchService searchService;
    private final RemainingPathEtaComposer composer;
    private final BoardingChainEtaCalculator chainCalculator;
    private final TransferPredictionInterpreter interpreter;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final Clock clock;

    @Qualifier("rerouteOverlayExecutor")
    private final ExecutorService overlayExecutor;

    @Override
    public RerouteProposalKind kind() {
        return RerouteProposalKind.REMAINING_PATH;
    }

    /** ③ 다음이다. 같은 정류장에서 해결되면 경로를 통째로 바꿀 이유가 없다. */
    @Override
    public int priority() {
        return 1;
    }

    @Override
    public List<RerouteCandidate> findCandidates(RerouteContext context) {
        try {
            return collect(context);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 남은 구간 후보 생성 실패 journeyId={} transferIndex={}: {}",
                    context.journeyId(), context.transferIndex(), e.getMessage());
            return List.of();
        }
    }

    private List<RerouteCandidate> collect(RerouteContext context) {
        Optional<TransferConnection> found = transferRepository
                .findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                        context.journeyId(), context.routeRevision(), context.transferIndex());
        if (found.isEmpty() || found.get().getUserReadyAt() == null) {
            return List.of();
        }
        TransferConnection transfer = found.get();

        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        context.journeyId(), context.routeRevision());
        Optional<JourneyLeg> waitLegFound = legs.stream()
                .filter(leg -> leg.getLegIndex() == transfer.getToLegIndex())
                .findFirst();
        if (waitLegFound.isEmpty() || !waitLegFound.get().getType().isWaiting()) {
            return List.of();
        }
        JourneyLeg waitLeg = waitLegFound.get();
        if (waitLeg.getStartStopId() == null) {
            // 미해석 정류장으로 검색하면 캐시 키가 "…:null:{목적지}" 가 되어 다른 정류장끼리
            // 경로를 나눠 쓴다.
            return List.of();
        }

        Optional<Waypoints> waypoints = waypointResolver.resolve(
                context.journeyId(), context.routeRevision(), transfer.getToLegIndex());
        if (waypoints.isEmpty()) {
            return List.of();
        }

        List<PathResult> paths = searchService.search(waitLeg.getStartStopId(), waypoints.get(),
                properties.getRemainingPath().getSearchBudgetMs());
        if (paths.isEmpty()) {
            metrics.recordRemainingPaths(0, 0);
            return List.of();
        }

        List<Candidate> candidates = prepare(paths, waitLeg);
        metrics.recordRemainingPaths(paths.size(), candidates.size());
        if (candidates.isEmpty()) {
            // 남는 경로가 없으면 기준선을 재지 않는다. 잴 이유가 없다.
            return List.of();
        }

        Baseline baseline = measureBaseline(waitLeg, legs, transfer, context);
        return evaluate(candidates, baseline, transfer, waitLeg, context);
    }

    /**
     * 유료 호출 <b>전에</b> 할 수 있는 걸러내기.
     *
     * <p>지금 타려던 노선으로 시작하는 경로는 여기서 버린다. 검색은 방금 그 경로를 만들어 준
     * 곳이라 "지금과 같은 답"이 섞여 오는 것이 정상이고, 그것을 예측까지 보내면 요금만 나간다.
     */
    private List<Candidate> prepare(List<PathResult> paths, JourneyLeg waitLeg) {
        String currentRouteId = waitLeg.getRouteId();
        List<Candidate> candidates = new ArrayList<>();
        for (PathResult path : paths) {
            if (candidates.size() >= properties.getRemainingPath().getMaxPaths()) {
                break;
            }
            Prepared prepared = composer.prepare(path);
            if (prepared.failed()) {
                metrics.recordCandidateFiltered(prepared.failureReason());
                continue;
            }
            if (isSameAsCurrent(prepared, currentRouteId)) {
                metrics.recordCandidateFiltered("same-as-current");
                continue;
            }
            candidates.add(new Candidate(path, prepared));
        }
        return candidates;
    }

    /** <b>둘 다 해석된 경우에만</b> 견준다. 양쪽이 null 이면 "같다"가 아니라 "모른다"이다. */
    private boolean isSameAsCurrent(Prepared prepared, String currentRouteId) {
        return currentRouteId != null && !currentRouteId.isBlank()
                && currentRouteId.equals(prepared.firstRouteId());
    }

    /**
     * 지금 경로로 목적지에 언제 닿는지. <b>④가 스스로 다시 잰다</b>(D7).
     *
     * <p><b>후보와 같은 체인을 탄다</b>({@link BoardingChainEtaCalculator}). 기준선만 "이후 대기 0"
     * 으로 재면 그 빠진 대기 시간이 그대로 후보의 개선폭처럼 보인다 — 근거 출처를 통일하는 것이
     * 개선폭을 정직하게 만드는 유일한 방법이다.
     *
     * @return 잴 수 없으면 {@code null}. <b>빈손이 아니다</b> — 기준선이 없으면 임계값 없이
     *         가장 이른 후보 하나만 내고 신뢰도를 낮춘다(D3)
     */
    private Baseline measureBaseline(JourneyLeg waitLeg, List<JourneyLeg> legs,
                                     TransferConnection transfer, RerouteContext context) {
        List<BoardingChainEtaCalculator.Step> steps;
        try {
            steps = storedSteps(legs);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 기준선 단계 구성 실패 journeyId={}: {}",
                    context.journeyId(), e.getMessage());
            return null;
        }

        BoardingChainEtaCalculator.Result chained;
        try {
            chained = chainCalculator.compute(steps, waitLeg.getLegIndex() - 1,
                    transfer.getUserReadyAt(), context.now(), baselineBudget());
        } catch (Exception e) {
            log.warn("[JourneyReroute] 기준선 예측 실패 journeyId={}: {}",
                    context.journeyId(), e.getMessage());
            return null;
        }
        if (chained.failed()) {
            // 지금 차를 아예 못 타는 상황이다. ③이라면 여기서 빈손이지만 ④는 계속 간다 —
            // "못 타는 사용자"야말로 다른 경로가 필요한 사람이다.
            return null;
        }
        int waitsAfter = (int) legs.stream()
                .filter(leg -> leg.getLegIndex() > waitLeg.getLegIndex())
                .filter(leg -> leg.getType().isWaiting())
                .count();
        return new Baseline(chained.destinationEtaAt(), waitsAfter);
    }

    /**
     * 저장된 단계를 체인이 아는 모양으로 옮긴다.
     *
     * <p>대기 단계마다 <b>그 단계가 고른 노선</b>을 읽는다. 첫 대기만 읽으면 뒤의 대기를 물어볼
     * 수 없어 기준선이 다시 "이후 대기 0"으로 되돌아간다.
     */
    private List<BoardingChainEtaCalculator.Step> storedSteps(List<JourneyLeg> legs) {
        List<BoardingChainEtaCalculator.Step> steps = new ArrayList<>(legs.size());
        for (JourneyLeg leg : legs) {
            if (!leg.getType().isWaiting()) {
                steps.add(BoardingChainEtaCalculator.Step.of(
                        leg.getLegIndex(), leg.getType(), leg.getPlannedDurationSec()));
                continue;
            }
            JourneyLegRouteOption option = Optional.ofNullable(leg.getSelectedRouteOptionId())
                    .flatMap(routeOptionRepository::findById)
                    .orElse(null);
            BoardingQuery query = new BoardingQuery(leg.getType(), leg.getStartStopId(),
                    leg.getStartName(),
                    option == null ? null : option.getRouteId(),
                    option == null ? null : option.getBoardingSeq(),
                    option == null ? null : option.getWayCode());
            steps.add(new BoardingChainEtaCalculator.Step(leg.getLegIndex(), leg.getType(),
                    null, query));
        }
        return steps;
    }

    /**
     * 기준선 체인의 예산.
     *
     * <p>후보 예산에서 빼지 않는 것은 예전과 같다 — 기준선이 없으면 개선폭을 못 재므로 아껴 봐야
     * 아무것도 못 낸다. 달라진 것은 <b>호출 수</b>다: 환승이 남아 있으면 그만큼 예측이 더 나가므로
     * 총 상한을 두고 그 안에서 돈다.
     */
    private BoardingChainEtaCalculator.Budget baselineBudget() {
        return new BoardingChainEtaCalculator.Budget(
                properties.getRemainingPath().getEvaluationBudgetMs(), interpreter.budgetMs());
    }

    /**
     * 후보마다 예측하고 견준다. 누적 예산 안에서 돌고, 하나가 터져도 앞의 것을 버리지 않는다.
     *
     * <h3>경로끼리는 나란히 돈다</h3>
     * 한 후보의 overlay 는 <b>대기 수만큼</b> 예측을 부른다(체인). 후보를 차례로 돌리면 그 곱만큼
     * 사용자가 기다리고, 예산이 앞의 한둘에서 다 소진돼 뒤의 후보가 늘 {@code budget-exhausted}
     * 로 잘린다 — <b>검색이 준 순서가 곧 당선 순서</b>가 되는 셈이다. 그래서 경로끼리는 나란히
     * 돌린다(체인 안의 대기는 앞 대기의 결과가 다음 앵커라 순차일 수밖에 없다).
     */
    private List<RerouteCandidate> evaluate(List<Candidate> candidates, Baseline baseline,
                                            TransferConnection transfer, JourneyLeg waitLeg,
                                            RerouteContext context) {
        Instant startedAt = clock.instant();
        List<Candidate> admitted = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (baseline != null
                    && candidate.prepared().waitsAfterFirst() > baseline.waitsAfterFirst()) {
                // 생략한 대기 시간이 후보 쪽에만 더 많이 빠져 없는 개선폭이 만들어진다.
                metrics.recordCandidateFiltered("more-transfers");
                continue;
            }
            admitted.add(candidate);
        }

        List<Attempt> attempts = overlayAll(admitted, transfer, context, startedAt);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < admitted.size(); i++) {
            Attempt attempt = attempts.get(i);
            if (attempt.failed()) {
                metrics.recordCandidateFiltered(attempt.filterReason());
                continue;
            }
            metrics.recordCandidateEvaluated(1);

            if (baseline == null) {
                scored.add(new Scored(admitted.get(i), attempt, 0, transfer.getUserReadyAt()));
                continue;
            }
            int gainSec = (int) Duration.between(
                    attempt.eta().destinationEtaAt(), baseline.destinationEtaAt()).getSeconds();
            if (gainSec < properties.getRemainingPath().getMinimumEtaGainSeconds()) {
                metrics.recordCandidateFiltered("gain-below-threshold");
                continue;
            }
            scored.add(new Scored(admitted.get(i), attempt, gainSec, transfer.getUserReadyAt()));
        }

        if (baseline == null) {
            // 견줄 기준이 없다. 가장 좋은 하나만 내고 개선폭은 0, 신뢰도는 낮춘다(D3).
            Scored best = RerouteRanking.best(scored);
            return best == null
                    ? List.of()
                    : List.of(toCandidate(best, waitLeg, false, context.now()));
        }
        // 개수 절단은 저장 쪽이 한다(maxCandidates). 여기서는 순서만 정한다 — 표준 규칙으로.
        return RerouteRanking.rank(scored).stream()
                .map(entry -> toCandidate(entry, waitLeg, true, context.now()))
                .toList();
    }

    /**
     * 후보들의 overlay 를 나란히 돌리고 <b>입력 순서 그대로</b> 돌려준다.
     *
     * <p>순서를 지키는 이유는 지표 때문이다 — 어느 후보가 왜 걸러졌는지를 후보와 짝지어 읽을 수
     * 있어야 한다. 풀이 포화면 그 자리에서 직접 돈다: 거절해 후보를 잃느니 조금 느린 편이 낫다
     * (예산 가드가 전체 대기를 여전히 막는다).
     */
    private List<Attempt> overlayAll(List<Candidate> candidates, TransferConnection transfer,
                                     RerouteContext context, Instant startedAt) {
        List<CompletableFuture<Attempt>> futures = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            futures.add(overlayAsync(candidate, transfer, context, startedAt));
        }
        List<Attempt> attempts = new ArrayList<>(futures.size());
        for (CompletableFuture<Attempt> future : futures) {
            attempts.add(awaitOverlay(future, context, startedAt));
        }
        return attempts;
    }

    private CompletableFuture<Attempt> overlayAsync(Candidate candidate, TransferConnection transfer,
                                                    RerouteContext context, Instant startedAt) {
        try {
            return CompletableFuture.supplyAsync(
                    () -> composeSafely(candidate, transfer, context, startedAt), overlayExecutor);
        } catch (RejectedExecutionException e) {
            // 풀이 가득 찼다. 후보를 버리지 않고 이 스레드에서 돈다.
            return CompletableFuture.completedFuture(
                    composeSafely(candidate, transfer, context, startedAt));
        }
    }

    /**
     * 예산이 끝나면 기다리기를 멈춘다.
     *
     * <p>이미 끝난 것은 그대로 쓰고, 아직 안 끝난 것만 {@code budget-exhausted} 다. 예전처럼
     * "예산이 끝났으니 남은 후보는 시작도 안 한다"로 두면 병렬로 이미 나간 호출의 결과를 버리게 된다.
     */
    private Attempt awaitOverlay(CompletableFuture<Attempt> future, RerouteContext context,
                                 Instant startedAt) {
        long waitMs = remainingBudgetMs(startedAt);
        try {
            return future.get(Math.max(0, waitMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Attempt(null, null, null, null, "budget-exhausted");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt(null, null, null, null, "budget-exhausted");
        } catch (ExecutionException e) {
            log.warn("[JourneyReroute] 남은 구간 후보 평가 실패 journeyId={}: {}",
                    context.journeyId(), e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return new Attempt(null, null, null, null, "no-prediction");
        }
    }

    /**
     * 후보 하나가 터져도 <b>이미 모은 후보를 버리지 않는다.</b>
     *
     * <p>바깥 {@code try/catch} 만 있으면 두 번째 후보에서 난 일시적 장애가 첫 후보까지 날린다.
     */
    private Attempt composeSafely(Candidate candidate, TransferConnection transfer,
                                  RerouteContext context, Instant startedAt) {
        long remainingMs = remainingBudgetMs(startedAt);
        if (remainingMs <= 0) {
            return new Attempt(null, null, null, null, "budget-exhausted");
        }
        try {
            return composer.compose(candidate.prepared(), transfer.getUserReadyAt(), context.now(),
                    new BoardingChainEtaCalculator.Budget(remainingMs, interpreter.budgetMs()));
        } catch (Exception e) {
            log.warn("[JourneyReroute] 남은 구간 후보 평가 실패 journeyId={}: {}",
                    context.journeyId(), e.getMessage());
            return new Attempt(null, null, null, null, "no-prediction");
        }
    }

    /**
     * 후보 평가에 남은 시간(ms).
     *
     * <p>이 값을 <b>다음 호출의 상한으로도 쓴다.</b> 부르기 전에만 보면 마감 직전에 시작한 호출이
     * 제 몫을 다 써서 총 대기가 예산을 넘는다.
     */
    private long remainingBudgetMs(Instant startedAt) {
        return properties.getRemainingPath().getEvaluationBudgetMs()
                - Duration.between(startedAt, clock.instant()).toMillis();
    }

    /**
     * 화면 값으로 옮긴다.
     *
     * <p>{@code apply} 에는 <b>검색이 준 경로 원본</b>을 그대로 싣는다. 수락(⑤)이 그것으로 새
     * 단계를 만들기 때문에, 요약만 저장하면 수락 시점에 다시 검색해야 하고 그때 다른 답이 나오면
     * 사용자가 고른 것과 다른 경로로 여정이 바뀐다.
     */
    private RerouteCandidate toCandidate(Scored scored, JourneyLeg waitLeg,
                                         boolean hasBaseline, Instant now) {
        Attempt attempt = scored.attempt();
        BoardingChainEtaCalculator.Result eta = attempt.eta();
        BoardingEstimate estimate = attempt.estimate();
        // 이제 모든 대기를 예측하므로 "뒤에 대기가 남아 있다"는 이유로 낮추지 않는다. 대신
        // 체인에서 가장 나쁜 고리의 신뢰도를 쓴다 — 두 번째 차가 모델 추정이면 목적지 시각도
        // 그만큼만 믿을 수 있다. 견줄 기준이 없으면 그대로 낮춘다(D3).
        String confidence = hasBaseline
                ? eta.quality().name()
                : CurrentLegArrival.Quality.APPROXIMATE.name();

        return new RerouteCandidate(null, RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(waitLeg.getLegIndex(),
                        scored.candidate().path()),
                new RerouteCandidateDisplay(
                        eta.destinationEtaAt(),
                        (int) Math.max(0, Duration.between(now, eta.destinationEtaAt()).getSeconds()),
                        scored.gainSec(),
                        scored.candidate().prepared().transferCount(),
                        eta.walkingSec(),
                        sourceTag(estimate),
                        confidence,
                        attempt.observedAt(),
                        summaries(scored.candidate().prepared())));
    }

    /** 걷기만으로 닿는 경로에는 예측이 없다. 실시간도 모델도 아니므로 정적 값이라고 말한다. */
    private String sourceTag(BoardingEstimate estimate) {
        if (estimate == null) {
            return "STATIC";
        }
        return estimate.source() == CurrentLegArrival.Source.REALTIME_ARRIVAL ? "REALTIME" : "ML";
    }

    /**
     * 앱이 그대로 그리는 줄들.
     *
     * <p><b>이름이 비어 있어도 후보를 버리지 않는다.</b> 이름을 해석 못 했다고 "이 길이 더 빠르다"는
     * 사실이 사라지지 않는다. {@code Stream.findFirst()} 에 null 이 흘러들지 않게 먼저 거른다.
     */
    private List<String> summaries(Prepared prepared) {
        if (prepared.walkOnly()) {
            List<JourneyLegDraft> legs = prepared.legs();
            return List.of("도보 이동",
                    legName(legs.get(0).startName()) + " → "
                            + legName(legs.get(legs.size() - 1).endName()));
        }
        JourneyLegDraft waitLeg = prepared.firstWait();
        String routeName = blankToNull(prepared.firstOption().routeName()) == null
                ? prepared.firstRouteId() : prepared.firstOption().routeName();
        String alightName = prepared.legs().stream()
                .filter(leg -> leg.legIndex() == waitLeg.legIndex() + 1)
                .map(leg -> blankToNull(leg.endName()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> legName(waitLeg.endName()));
        return List.of(legName(routeName) + " 승차",
                legName(waitLeg.startName()) + " → " + alightName);
    }

    private String legName(String name) {
        return blankToNull(name) == null ? "-" : name;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 검색이 준 경로 원본과 그것을 쪼갠 결과. 원본은 수락 재료로 그대로 저장된다. */
    private record Candidate(PathResult path, Prepared prepared) {
    }

    /** 지금 경로의 기준값. {@code waitsAfterFirst} 는 환승 수 관문에 쓴다. */
    private record Baseline(Instant destinationEtaAt, int waitsAfterFirst) {
    }

    /**
     * 평가를 마친 후보. 줄 세우기 값은 {@link RerouteRanking} 이 읽는다 — 정렬 규칙을 여기서 다시
     * 쓰지 않는 이유는 ③·④·(PR-2 의) 정류장 목록이 <b>같은 순서 규칙</b>을 써야 하기 때문이다.
     */
    private record Scored(Candidate candidate, Attempt attempt, int gainSec, Instant anchorAt)
            implements RerouteRanking.Ranked {

        @Override
        public Instant destinationEtaAt() {
            return attempt.eta().destinationEtaAt();
        }

        @Override
        public int transferCount() {
            return candidate.prepared().transferCount();
        }

        @Override
        public int walkingSec() {
            return attempt.eta().walkingSec();
        }

        /** 첫 차를 탈 때까지의 여유. 걷기만으로 닿는 경로엔 탈 차가 없어 0이다. */
        @Override
        public int slackSec() {
            BoardingEstimate first = attempt.estimate();
            if (first == null || anchorAt == null) {
                return 0;
            }
            return (int) Math.max(0, Duration.between(anchorAt, first.boardingAt()).getSeconds());
        }
    }
}
