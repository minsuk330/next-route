package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private final RouteOptionEtaCalculator etaCalculator;
    private final TransferPredictionInterpreter interpreter;
    private final List<BoardingTimeEstimator> boardingTimeEstimators;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final Clock clock;

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
     * @return 잴 수 없으면 {@code null}. <b>빈손이 아니다</b> — 기준선이 없으면 임계값 없이
     *         가장 이른 후보 하나만 내고 신뢰도를 낮춘다(D3)
     */
    private Baseline measureBaseline(JourneyLeg waitLeg, List<JourneyLeg> legs,
                                     TransferConnection transfer, RerouteContext context) {
        Optional<JourneyLegRouteOption> option = Optional
                .ofNullable(waitLeg.getSelectedRouteOptionId())
                .flatMap(routeOptionRepository::findById);
        BoardingTimeEstimator estimator = estimatorFor(waitLeg.getType());
        if (option.isEmpty() || estimator == null) {
            return null;
        }

        BoardingQuery query = new BoardingQuery(waitLeg.getType(), waitLeg.getStartStopId(),
                waitLeg.getStartName(), option.get().getRouteId(),
                option.get().getBoardingSeq(), option.get().getWayCode());
        BoardingOutcome outcome;
        try {
            outcome = estimator.estimate(query, transfer.getUserReadyAt(), context.now(),
                    interpreter.budgetMs());
        } catch (Exception e) {
            log.warn("[JourneyReroute] 기준선 예측 실패 journeyId={}: {}",
                    context.journeyId(), e.getMessage());
            return null;
        }
        if (!outcome.isPresent() || !outcome.estimate().boardable()) {
            // 지금 차를 아예 못 타는 상황이다. ③이라면 여기서 빈손이지만 ④는 계속 간다 —
            // "못 타는 사용자"야말로 다른 경로가 필요한 사람이다.
            return null;
        }
        Optional<RouteOptionEtaCalculator.Result> eta = etaCalculator.compute(
                legs, waitLeg, outcome.estimate().boardingAt());
        if (eta.isEmpty()) {
            return null;
        }
        int waitsAfter = (int) legs.stream()
                .filter(leg -> leg.getLegIndex() > waitLeg.getLegIndex())
                .filter(leg -> leg.getType().isWaiting())
                .count();
        return new Baseline(eta.get().destinationEtaAt(), waitsAfter);
    }

    /** 후보마다 예측하고 견준다. 누적 예산 안에서 돌고, 하나가 터져도 앞의 것을 버리지 않는다. */
    private List<RerouteCandidate> evaluate(List<Candidate> candidates, Baseline baseline,
                                            TransferConnection transfer, JourneyLeg waitLeg,
                                            RerouteContext context) {
        Instant startedAt = clock.instant();
        List<Scored> scored = new ArrayList<>();

        for (Candidate candidate : candidates) {
            long remainingMs = remainingBudgetMs(startedAt);
            if (remainingMs <= 0) {
                // 이미 평가한 것으로 제안을 만든다. 부분 결과가 빈손보다 낫다.
                metrics.recordCandidateFiltered("budget-exhausted");
                break;
            }
            if (baseline != null
                    && candidate.prepared().waitsAfterFirst() > baseline.waitsAfterFirst()) {
                // 생략한 대기 시간이 후보 쪽에만 더 많이 빠져 없는 개선폭이 만들어진다.
                metrics.recordCandidateFiltered("more-transfers");
                continue;
            }
            Attempt attempt = composeSafely(candidate, transfer, context,
                    Math.min(interpreter.budgetMs(), remainingMs));
            if (attempt.failed()) {
                metrics.recordCandidateFiltered(attempt.filterReason());
                continue;
            }
            metrics.recordCandidateEvaluated(1);

            if (baseline == null) {
                scored.add(new Scored(candidate, attempt, 0));
                continue;
            }
            int gainSec = (int) Duration.between(
                    attempt.eta().destinationEtaAt(), baseline.destinationEtaAt()).getSeconds();
            if (gainSec < properties.getRemainingPath().getMinimumEtaGainSeconds()) {
                metrics.recordCandidateFiltered("gain-below-threshold");
                continue;
            }
            scored.add(new Scored(candidate, attempt, gainSec));
        }

        if (baseline == null) {
            // 견줄 기준이 없다. 가장 이른 하나만 내고 개선폭은 0, 신뢰도는 낮춘다(D3).
            return scored.stream()
                    .min(Comparator.comparing(entry -> entry.attempt().eta().destinationEtaAt()))
                    .map(entry -> List.of(toCandidate(entry, waitLeg, false, context.now())))
                    .orElseGet(List::of);
        }
        // 개수 절단은 저장 쪽이 한다(maxCandidates). 여기서는 순서만 정한다.
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::gainSec).reversed())
                .map(entry -> toCandidate(entry, waitLeg, true, context.now()))
                .toList();
    }

    /**
     * 후보 하나가 터져도 <b>이미 모은 후보를 버리지 않는다.</b>
     *
     * <p>바깥 {@code try/catch} 만 있으면 두 번째 후보에서 난 일시적 장애가 첫 후보까지 날린다.
     */
    private Attempt composeSafely(Candidate candidate, TransferConnection transfer,
                                  RerouteContext context, long budgetMs) {
        try {
            return composer.compose(candidate.prepared(), transfer.getUserReadyAt(),
                    context.now(), budgetMs);
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

    private BoardingTimeEstimator estimatorFor(JourneyLegType waitType) {
        return boardingTimeEstimators.stream()
                .filter(estimator -> estimator.supports(waitType))
                .findFirst()
                .orElse(null);
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
        RouteOptionEtaCalculator.Result eta = attempt.eta();
        BoardingEstimate estimate = attempt.estimate();
        String confidence = (!hasBaseline || eta.hasFurtherTransfer() || estimate == null)
                // 이후 환승의 대기 시간이 빠져 있거나 견줄 기준이 없다. 절대 시각이 낙관적이다.
                ? CurrentLegArrival.Quality.APPROXIMATE.name()
                : estimate.quality().name();

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

    private record Scored(Candidate candidate, Attempt attempt, int gainSec) {
    }
}
