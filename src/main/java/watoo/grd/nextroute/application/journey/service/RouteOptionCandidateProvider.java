package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.RerouteCandidateProvider;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 같은 정류장에 서는 <b>다른 버스</b>를 후보로 올린다.
 *
 * <p>승·하차 정류장이 그대로라 <b>경로 검색(ODsay)을 한 번도 부르지 않는다</b>. 그것이 이 사다리
 * 칸의 존재 이유이자 완료 조건이다 — 노선 후보는 여정을 시작할 때 이미 저장해 뒀고
 * ({@code journey_leg_route_option}) 여기서는 그중 어느 것이 지금 더 빠른지만 묻는다.
 *
 * <h2>순서가 계약이다</h2>
 * <ol>
 *   <li><b>기준선을 먼저 잰다.</b> 저장된 {@code nextBusArrivalAt} 을 쓰지 않는다 — 최대 30초 낡은
 *       값과 방금 값을 빼면 개선폭이 가짜가 된다(D5)</li>
 *   <li>기준선이 못 타는 상태면 <b>빈손</b>이다. 비교할 기준이 없다. 그 상황은 사다리의 다음
 *       칸(④ 다른 경로 재검색)이 받는다</li>
 *   <li>대안을 {@code optionIndex} 순으로 상한까지, 누적 시간 예산 안에서 평가한다</li>
 *   <li>목적지 도착 시각으로 견준다. "다음 차가 언제 오냐"만 보면 <b>빨리 오는 차가 더 늦게
 *       도착</b>할 수 있다(D4)</li>
 * </ol>
 *
 * <p><b>트랜잭션 밖에서 돈다.</b> 실시간·ML 호출을 하는 코드가 DB 커넥션을 붙잡고 있으면 안 된다.
 *
 * <p>지하철은 다루지 않는다(D7). 같은 역의 다른 노선은 목적지가 달라 "같은 승·하차 구간"이라는
 * 전제가 깨진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteOptionCandidateProvider implements RerouteCandidateProvider {

    private final TransferConnectionRepository transferRepository;
    private final JourneyLegRepository legRepository;
    private final RerouteRouteOptionSource routeOptionSource;
    private final PredictTransferUseCase predictTransferUseCase;
    private final TransferPredictionInterpreter interpreter;
    private final RouteOptionEtaCalculator etaCalculator;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final Clock clock;

    @Override
    public RerouteProposalKind kind() {
        return RerouteProposalKind.ROUTE_OPTION;
    }

    @Override
    public int priority() {
        return 0;
    }

    /**
     * 예외를 삼키고 빈 목록을 낸다.
     *
     * <p>부르는 쪽에도 한 겹이 있지만 <b>그쪽은 지표를 남기지 않는다</b>. 여기서 잡아야 무엇이
     * 몇 번 걸러졌는지가 남는다.
     */
    @Override
    public List<RerouteCandidate> findCandidates(RerouteContext context) {
        try {
            return collect(context);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 노선 후보 생성 실패 journeyId={} transferIndex={}: {}",
                    context.journeyId(), context.transferIndex(), e.getMessage());
            return List.of();
        }
    }

    private List<RerouteCandidate> collect(RerouteContext context) {
        Optional<TransferConnection> found = transferRepository
                .findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                        context.journeyId(), context.routeRevision(), context.transferIndex());
        if (found.isEmpty() || found.get().getUserReadyAt() == null) {
            // 적격성 판정이 이미 걸렀을 상황이지만, 공급자는 혼자서도 성립해야 한다.
            return List.of();
        }
        TransferConnection transfer = found.get();

        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        context.journeyId(), context.routeRevision());
        Optional<JourneyLeg> waitLegFound = legs.stream()
                .filter(leg -> leg.getLegIndex() == transfer.getToLegIndex())
                .findFirst();
        if (waitLegFound.isEmpty() || waitLegFound.get().getType() != JourneyLegType.WAIT_BUS) {
            return List.of();
        }
        JourneyLeg waitLeg = waitLegFound.get();
        if (waitLeg.getStartStopId() == null) {
            return List.of();
        }

        // 후보 출처를 포트로 가른다(P2-F). 저장 구현은 지금까지와 동일(optionIndex 정렬 보존).
        // OTP 외부 조회를 위해 예산을 넘긴다 — 저장 구현은 이 값을 쓰지 않는다.
        RerouteRouteOptionSource.SourceOutcome sourced = routeOptionSource.optionsFor(
                waitLeg, RoutingDeadline.of(clock, properties.getRouteOption().getEvaluationBudgetMs()));
        if (!sourced.isOk()) {
            // 정상 0건이 아니라 출처 장애(OTP timeout/error 등) — 빈손이되 사유를 남긴다.
            metrics.recordCandidateFiltered("source-" + sourced.status().name().toLowerCase());
            return List.of();
        }
        List<JourneyLegRouteOption> options = sourced.options();

        Evaluation baseline = evaluateBaseline(waitLeg, options, legs, transfer, context);
        if (baseline == null) {
            return List.of();
        }

        List<RerouteCandidate> candidates = new ArrayList<>();
        Instant startedAt = clock.instant();
        int evaluated = 1;
        for (JourneyLegRouteOption option : alternatives(waitLeg, options)) {
            long remainingMs = remainingBudgetMs(startedAt);
            if (remainingMs <= 0) {
                // 이미 평가한 것으로 제안을 만든다. 부분 결과가 빈손보다 낫다.
                metrics.recordCandidateFiltered("budget-exhausted");
                break;
            }
            evaluated++;
            // 한 번 물어보는 몫과 남은 누적 예산 중 짧은 쪽. 예산을 "부르기 전"에만 보면 마감
            // 직전에 시작한 호출이 제 몫을 다 써서 총 시간이 예산을 넘는다.
            Attempt attempt = evaluateSafely(option, waitLeg, legs, transfer, context,
                    Math.min(interpreter.budgetMs(), remainingMs));
            if (attempt.failed()) {
                metrics.recordCandidateFiltered(attempt.filterReason());
                continue;
            }
            Evaluation evaluation = attempt.evaluation();
            int gainSec = (int) Duration.between(
                    evaluation.eta().destinationEtaAt(),
                    baseline.eta().destinationEtaAt()).getSeconds();
            if (gainSec < properties.getRouteOption().getMinimumEtaGainSeconds()) {
                metrics.recordCandidateFiltered("gain-below-threshold");
                continue;
            }
            candidates.add(toCandidate(option, waitLeg, legs, evaluation, gainSec, context.now()));
        }
        metrics.recordCandidateEvaluated(evaluated);

        // 개수 절단은 저장 쪽이 한다(maxCandidates). 여기서는 순서만 정한다.
        return candidates.stream()
                .sorted(Comparator.comparingInt(
                        (RerouteCandidate candidate) -> candidate.display().etaGainSec()).reversed())
                .toList();
    }

    /**
     * 지금 타기로 돼 있는 노선을 <b>같은 방법으로</b> 다시 잰다.
     *
     * <p>기준선과 후보가 같은 계산기·같은 상수항을 쓰지 않으면 개선폭이 <b>계산 방식의 차이</b>가
     * 된다. 그래서 기준선도 예측부터 다시 부른다.
     *
     * @return 기준을 세울 수 없으면 null. 그때는 이 칸이 통째로 빈손이다
     */
    private Evaluation evaluateBaseline(JourneyLeg waitLeg, List<JourneyLegRouteOption> options,
                                        List<JourneyLeg> legs, TransferConnection transfer,
                                        RerouteContext context) {
        Optional<JourneyLegRouteOption> selected = options.stream()
                .filter(option -> option.getId().equals(waitLeg.getSelectedRouteOptionId()))
                .filter(this::isUsable)
                .findFirst();
        if (selected.isEmpty()) {
            log.debug("[JourneyReroute] 기준선 노선을 해석하지 못했다 journeyId={} legIndex={}",
                    context.journeyId(), waitLeg.getLegIndex());
            return null;
        }
        // 기준선은 누적 예산에서 빼지 않는다(D7 계열의 결정). 기준선이 없으면 개선폭을 잴 수
        // 없어 어차피 빈 결과이므로, 예산을 아껴 봐야 아무것도 못 낸다. 따라서 요청 전체의
        // 최악 시간은 "기준선 1회(≤predict-deadline) + 대안 누적 예산"이다.
        Attempt attempt = evaluate(selected.get(), waitLeg, legs, transfer, context,
                interpreter.budgetMs());
        if (attempt.failed()) {
            // 지금 경로로 아예 못 타는 상황이다. 개선폭을 잴 기준이 없어 여기서는 빈손이고,
            // 사다리의 다음 칸(④)이 받는다. 빈도는 지표로 본다.
            //
            // 후보 쪽 사유({@code unboardable} 등)로 세지 않는다. 섞으면 "대안이 별로였다"와
            // "기준선이 무너졌다"가 한 숫자가 되는데, 손대야 할 곳이 서로 다르다.
            metrics.recordCandidateFiltered("baseline-unboardable");
            metrics.recordCandidateEvaluated(1);
            return null;
        }
        return attempt.evaluation();
    }

    /**
     * 대안 하나를 평가하되, <b>터져도 이미 모은 후보를 버리지 않는다</b>.
     *
     * <p>바깥 {@code try/catch} 만 있으면 세 번째 후보에서 난 일시적 장애가 앞의 두 후보까지
     * 통째로 날린다. 예산이 떨어졌을 때 부분 결과를 내는 것과 같은 이유로, 여기서도 그 후보만
     * 버리고 계속한다.
     */
    private Attempt evaluateSafely(JourneyLegRouteOption option, JourneyLeg waitLeg,
                                   List<JourneyLeg> legs, TransferConnection transfer,
                                   RerouteContext context, long budgetMs) {
        try {
            return evaluate(option, waitLeg, legs, transfer, context, budgetMs);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 후보 평가 실패 journeyId={} routeId={}: {}",
                    context.journeyId(), option.getRouteId(), e.getMessage());
            return Attempt.failed("no-prediction");
        }
    }

    /**
     * 노선 하나를 예측하고 목적지 ETA 까지 낸다.
     *
     * <p>앵커는 {@code userReadyAt} 이다. <b>{@code now} 로 대신하지 않는다</b> — 사용자가 도착하기
     * 전에 지나갈 차를 답으로 고르게 된다.
     *
     * <p>지표는 <b>남기지 않는다</b>. 같은 실패라도 기준선이면 뜻이 다르므로 부르는 쪽이 센다.
     *
     * @param budgetMs 이 호출 하나에 쓸 수 있는 시간. 남은 누적 예산보다 길면 안 된다 —
     *                 예산을 부르기 전에만 보면 마감 직전에 시작한 호출이 예산을 넘긴다
     * @return 탈 수 없거나 소요가 깨져 있으면 사유가 담긴 실패
     */
    private Attempt evaluate(JourneyLegRouteOption option, JourneyLeg waitLeg,
                             List<JourneyLeg> legs, TransferConnection transfer,
                             RerouteContext context, long budgetMs) {
        Instant anchor = transfer.getUserReadyAt();
        TransferPredictionResult prediction = predictTransferUseCase.predict(
                waitLeg.getStartStopId(),
                option.getRouteId(),
                option.getBoardingSeq(),
                anchor,
                interpreter.modeFor(anchor, context.now()),
                budgetMs);

        if (prediction.predictedArrivalAt() == null) {
            return Attempt.failed("no-prediction");
        }
        if (!interpreter.isBoardable(prediction)) {
            // 탈 수 있는 차가 없으면 예측이 이미 지나간 차를 돌려주기도 한다. 그 시각을 그대로
            // 쓰면 "지나간 버스를 타면 5분 빠릅니다"가 제안으로 나간다.
            return Attempt.failed("unboardable");
        }
        Optional<RouteOptionEtaCalculator.Result> eta =
                etaCalculator.compute(legs, waitLeg, prediction.predictedArrivalAt());
        if (eta.isEmpty()) {
            return Attempt.failed("missing-duration");
        }
        return new Attempt(new Evaluation(prediction, eta.get()), null);
    }

    /**
     * 평가할 대안 목록.
     *
     * <p>지금 선택된 노선은 <b>기준선으로만</b> 쓰고 대안에서 뺀다. {@code (routeId, directionKey)}
     * 가 같은 후보도 하나만 남긴다 — 같은 노선이 여러 lane 으로 들어오면 같은 예측을 두 번 부르고
     * 평가 상한만 깎는다.
     */
    private List<JourneyLegRouteOption> alternatives(JourneyLeg waitLeg,
                                                     List<JourneyLegRouteOption> options) {
        Set<String> seen = new LinkedHashSet<>();
        List<JourneyLegRouteOption> alternatives = new ArrayList<>();
        for (JourneyLegRouteOption option : options) {
            if (!isUsable(option) || option.getId().equals(waitLeg.getSelectedRouteOptionId())) {
                continue;
            }
            if (!seen.add(option.getRouteId() + "|" + option.getDirectionKey())) {
                continue;
            }
            alternatives.add(option);
            if (alternatives.size() >= properties.getRouteOption().getMaxEvaluations()) {
                break;
            }
        }
        return alternatives;
    }

    private boolean isUsable(JourneyLegRouteOption option) {
        return option.getRouteId() != null && !option.getRouteId().isBlank()
                && option.getBoardingSeq() != null;
    }

    /**
     * 대안 평가에 남은 시간(ms). 0 이하면 그만 본다.
     *
     * <p>이 값을 <b>다음 호출의 상한으로도 쓴다.</b> 예산은 "몇 번 부를까"가 아니라 "사용자가
     * 얼마나 기다리나"의 문제라, 마감 직전에 시작한 호출이 제 몫을 다 쓰면 예산이 무의미해진다.
     */
    private long remainingBudgetMs(Instant startedAt) {
        long budget = properties.getRouteOption().getEvaluationBudgetMs();
        return budget - Duration.between(startedAt, clock.instant()).toMillis();
    }

    /**
     * 화면 값으로 옮긴다.
     *
     * <p>{@code candidateId} 는 비워 둔다 — 저장 쪽이 UUID 를 채운다. 순번을 ID 로 쓰면 정렬이
     * 바뀌거나 후보 하나가 걸러졌을 때 다른 경로를 가리킨다.
     */
    private RerouteCandidate toCandidate(JourneyLegRouteOption option, JourneyLeg waitLeg,
                                         List<JourneyLeg> legs, Evaluation evaluation,
                                         int gainSec, Instant now) {
        RouteOptionEtaCalculator.Result eta = evaluation.eta();
        String confidence = eta.hasFurtherTransfer()
                // 이후 환승의 대기 시간이 빠져 있어 절대 시각이 낙관적이다. 예측이 아무리 좋아도
                // 화면에 "정확하다"고 말할 수 없다.
                ? CurrentLegArrival.Quality.APPROXIMATE.name()
                : interpreter.qualityOf(evaluation.prediction()).name();

        return new RerouteCandidate(null, RerouteProposalKind.ROUTE_OPTION,
                new RerouteCandidateApply.RouteOptionApply(waitLeg.getLegIndex(),
                        option.getOptionIndex(), option.getRouteId(), option.getDirectionKey()),
                new RerouteCandidateDisplay(
                        eta.destinationEtaAt(),
                        (int) Math.max(0, Duration.between(now, eta.destinationEtaAt()).getSeconds()),
                        gainSec,
                        remainingTransfers(legs, waitLeg),
                        eta.walkingSec(),
                        sourceTag(evaluation.prediction()),
                        confidence,
                        evaluation.prediction().calculatedAt(),
                        summaries(option, waitLeg, legs)));
    }

    /** 이 환승을 포함해 남은 환승 수. 경로 구조가 안 바뀌므로 기준선과 같은 값이다. */
    private int remainingTransfers(List<JourneyLeg> legs, JourneyLeg waitLeg) {
        return (int) legs.stream()
                .filter(leg -> leg.getLegIndex() >= waitLeg.getLegIndex())
                .filter(leg -> leg.getType().isWaiting())
                .count();
    }

    private String sourceTag(TransferPredictionResult prediction) {
        return interpreter.sourceOf(prediction) == CurrentLegArrival.Source.REALTIME_ARRIVAL
                ? "REALTIME" : "ML";
    }

    /**
     * 앱이 그대로 그리는 줄들. 서버와 앱이 다른 숫자를 말하지 않도록 문장까지 서버가 만든다.
     *
     * <p><b>이름은 비어 있을 수 있다.</b> 정류장·노선 이름을 해석하지 못한 여정이 있고, 그 하나가
     * 후보 전체를 못 만들게 하면 안 된다 — 이름이 없다고 "더 빠른 버스"라는 사실이 사라지지 않는다.
     */
    private List<String> summaries(JourneyLegRouteOption option, JourneyLeg waitLeg,
                                   List<JourneyLeg> legs) {
        String routeName = blankToNull(option.getRouteName()) == null
                ? option.getRouteId() : option.getRouteName();
        String boardName = legName(waitLeg.getStartName());
        String alightName = legs.stream()
                .filter(leg -> leg.getLegIndex() == waitLeg.getLegIndex() + 1)
                .map(leg -> blankToNull(leg.getEndName()))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseGet(() -> legName(waitLeg.getEndName()));
        return List.of(routeName + " 승차", boardName + " → " + alightName);
    }

    private String legName(String name) {
        return blankToNull(name) == null ? "-" : name;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 한 노선의 평가 결과. 예측 원본을 함께 들고 다녀야 근거·신선도를 화면에 옮길 수 있다. */
    private record Evaluation(TransferPredictionResult prediction,
                              RouteOptionEtaCalculator.Result eta) {
    }

    /**
     * 평가 한 번의 결과 또는 실패 사유.
     *
     * <p>사유를 값으로 돌려주는 이유는 <b>같은 실패가 자리에 따라 다른 뜻</b>이기 때문이다.
     * 기준선이 못 타는 것은 "이 칸으로는 해결 못 한다"이고, 대안이 못 타는 것은 "그 노선은
     * 아니다"이다. 평가하는 쪽이 지표를 남기면 그 구분이 사라진다.
     */
    private record Attempt(Evaluation evaluation, String filterReason) {

        static Attempt failed(String reason) {
            return new Attempt(null, reason);
        }

        boolean failed() {
            return evaluation == null;
        }
    }
}
