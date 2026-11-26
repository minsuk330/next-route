package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 검색이 준 경로 하나를 <b>"목적지에 몇 시에 닿는 후보"</b>로 옮긴다.
 *
 * <h2>두 걸음으로 나눈 이유</h2>
 * {@link #prepare} 는 외부 호출 없이 경로를 쪼개고 내부 ID 를 채우기만 한다. 그래야 부르는 쪽이
 * <b>지금 타려던 노선과 같은 경로</b>를 유료 예측 전에 버릴 수 있다. 한 걸음으로 묶으면 버릴
 * 후보에도 예측이 나간다.
 *
 * <h2>꼭 지켜야 하는 두 가지</h2>
 * <ul>
 *   <li><b>보강({@code enrich})을 반드시 거친다.</b> 계산기는 {@code startStopId}·{@code routeId} 가
 *       빈 초안을 낸다. 빼먹으면 버스 후보가 전부 "예측 없음"이 되는데, 증상이 예측 API 문제로
 *       보여 엉뚱한 곳을 파게 된다</li>
 *   <li><b>첫 대기 앞의 도보를 앵커에 더한다.</b> 새 경로는 환승 정류장에서 <b>다른 정류장까지
 *       걸어간 뒤</b> 타는 모양일 수 있다. 그 도보를 빼면 정류장에 이미 서 있는 사용자로 평가해
 *       실제로는 못 타는 차를 후보로 만들고, 도착 시각도 도보 시간만큼 빨라진다</li>
 * </ul>
 *
 * <p>단계 번호는 <b>후보 안에서의 상대 번호(0부터)</b>로 다룬다. 저장된 여정의 번호로 옮기는 일은
 * 수락(⑤)이 하고, 여기서는 합산 하한만 맞으면 된다 — 미리 더하면 {@code pairedLegIndex} 같은
 * 초안 안의 참조가 서로 어긋난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemainingPathEtaComposer {

    private final JourneyCompiler compiler;
    private final JourneyTransitIdEnricher transitIdEnricher;
    private final BoardingChainEtaCalculator chainCalculator;

    /**
     * 외부 호출 없이 후보의 뼈대를 만든다.
     *
     * @return 첫 대기의 노선·순번(또는 방향)을 못 채웠으면 사유가 담긴 실패
     */
    public Prepared prepare(PathResult candidatePath) {
        JourneyPlan plan;
        try {
            plan = transitIdEnricher.enrich(compiler.compile(candidatePath));
        } catch (Exception e) {
            log.warn("[JourneyReroute] 후보 경로 보강 실패: {}", e.getMessage());
            return Prepared.failed("unresolved-route");
        }
        List<JourneyLegDraft> legs = plan.legs();
        if (legs.isEmpty()) {
            return Prepared.failed("unresolved-route");
        }

        Optional<JourneyLegDraft> firstWait = legs.stream()
                .filter(leg -> leg.type().isWaiting())
                .findFirst();
        if (firstWait.isEmpty()) {
            // 걷기만으로 목적지에 닿는 경로(D8). 탈 차가 없으니 예측도 없다.
            return new Prepared(legs, null, null, null);
        }

        JourneyLegDraft waitLeg = firstWait.get();
        String reason = unresolvedReason(waitLeg, firstOption(waitLeg));
        if (reason != null) {
            return Prepared.failed(reason);
        }
        Prepared prepared = new Prepared(legs, waitLeg, firstOption(waitLeg), null);
        if (prepared.usesRegionalRealtime() && hasUnresolvedLaterWait(legs, waitLeg)) {
            // 지역 후보는 뒤 대기까지 미리 본다 — 앞 대기에서 하루 상한이 있는 지역 몫을 쓰고 체인 중간에 버려지지 않게(PR-1 리뷰 M4).
            // 서울만 타는 후보는 예전처럼 overlay 에서 boarding-unresolved 로 끝낸다(보드 자리의 "평가 못 함" 동작 유지).
            return Prepared.failed("boarding-unresolved");
        }
        return prepared;
    }

    /**
     * 뼈대에 <b>승차 시각</b>을 붙여 목적지 도착 시각까지 낸다.
     *
     * <p>첫 대기만이 아니라 <b>모든 대기</b>에 물어본다({@link BoardingChainEtaCalculator}).
     * 하나라도 못 풀면 후보를 버린다 — 0으로 둔 대기는 그대로 없는 개선폭이 된다.
     *
     * @param anchorAt 사용자가 환승 정류장에서 준비를 마치는 시각({@code userReadyAt})
     * @return 대기를 못 풀거나 소요가 깨져 있으면 사유가 담긴 실패
     */
    public Attempt compose(Prepared prepared, Instant anchorAt, Instant now, long budgetMs) {
        return compose(prepared, anchorAt, now, BoardingChainEtaCalculator.Budget.of(budgetMs));
    }

    public Attempt compose(Prepared prepared, Instant anchorAt, Instant now,
                           BoardingChainEtaCalculator.Budget budget) {
        List<JourneyLegDraft> legs = prepared.legs();
        if (legs.isEmpty()) {
            return Attempt.failed("unresolved-route");
        }

        // 후보 안의 상대 번호를 그대로 쓴다. 첫 단계 앞에는 아무것도 없다는 뜻으로 하나 앞을 넘긴다
        // — 첫 대기 앞의 도보가 체인의 첫 구간이 되어 앵커를 자연히 민다.
        BoardingChainEtaCalculator.Result chained = chainCalculator.compute(
                steps(legs), legs.get(0).legIndex() - 1, anchorAt, now, budget);
        if (chained.failed()) {
            return Attempt.failed(chained.failureReason());
        }
        BoardingEstimate first = chained.firstEstimate();
        return new Attempt(prepared, chained, first, first == null ? now : first.observedAt(), null);
    }

    /**
     * 초안을 체인이 아는 모양으로 옮긴다.
     *
     * <p>대기 단계에는 <b>그 단계의 첫 노선 후보</b>를 붙인다(여정 시작과 같은 규칙 — 첫 후보가
     * 선택 노선이다). 후보가 없거나 값이 비면 질의를 만들지 못하는데, 그 경우 체인이
     * {@code no-prediction}/{@code boarding-unresolved} 로 끝난다 — 예측기가 미해석 값으로는
     * 유료 조회를 내보내지 않기 때문이다.
     */
    private List<BoardingChainEtaCalculator.Step> steps(List<JourneyLegDraft> legs) {
        List<BoardingChainEtaCalculator.Step> steps = new ArrayList<>(legs.size());
        for (JourneyLegDraft leg : legs) {
            if (!leg.type().isWaiting()) {
                steps.add(BoardingChainEtaCalculator.Step.of(
                        leg.legIndex(), leg.type(), leg.plannedDurationSec()));
                continue;
            }
            JourneyRouteOptionDraft option = leg.routeOptions().isEmpty()
                    ? null : leg.routeOptions().get(0);
            steps.add(new BoardingChainEtaCalculator.Step(leg.legIndex(), leg.type(), null,
                    query(leg, option)));
        }
        return steps;
    }

    private boolean hasUnresolvedLaterWait(List<JourneyLegDraft> legs, JourneyLegDraft firstWait) {
        return legs.stream()
                .filter(leg -> leg.type().isWaiting() && leg != firstWait)
                .anyMatch(leg -> unresolvedReason(leg, firstOption(leg)) != null);
    }

    private static JourneyRouteOptionDraft firstOption(JourneyLegDraft leg) {
        return leg.routeOptions().isEmpty() ? null : leg.routeOptions().get(0);
    }

    /** 지역(경기·TAGO) 버스 대기 — 서울 노선·순번 없이 지역 실시간으로 묻는다(regional-bus-parity PR-1). */
    static boolean isRegionalBusWait(JourneyLegDraft waitLeg, JourneyRouteOptionDraft option) {
        return waitLeg.type() == JourneyLegType.WAIT_BUS && option != null
                && RegionalArrivalLookup.providerOf(option.routeId(), option.sourceRouteId(), waitLeg.startStopId()) != null;
    }

    private String unresolvedReason(JourneyLegDraft waitLeg, JourneyRouteOptionDraft option) {
        if (isRegionalBusWait(waitLeg, option)) {
            return null;
        }
        if (option == null || option.routeId() == null || option.routeId().isBlank()) {
            return "unresolved-route";
        }
        if (waitLeg.type() == JourneyLegType.WAIT_SUBWAY && option.wayCode() == null) {
            // 방향을 모르면 반대 방향 열차를 집는다.
            return "no-direction";
        }
        if (waitLeg.type() == JourneyLegType.WAIT_BUS && option.boardingSeq() == null) {
            return "unresolved-route";
        }
        return waitLeg.startStopId() == null ? "unresolved-route" : null;
    }

    private BoardingQuery query(JourneyLegDraft waitLeg, JourneyRouteOptionDraft option) {
        return new BoardingQuery(waitLeg.type(), waitLeg.startStopId(), waitLeg.startName(),
                option == null ? null : option.routeId(),
                option == null ? null : option.boardingSeq(),
                option == null ? null : option.wayCode(),
                option == null ? null : option.sourceRouteId());
    }

    /**
     * 외부 호출 전에 만든 후보의 뼈대.
     *
     * @param firstWait   첫 대기 단계. 걷기만으로 닿는 경로면 null
     * @param firstOption 그 대기 단계에서 타기로 한 노선. 첫 후보가 선택 노선이다(여정 시작과 같은 규칙)
     */
    public record Prepared(List<JourneyLegDraft> legs, JourneyLegDraft firstWait,
                           JourneyRouteOptionDraft firstOption, String failureReason) {

        static Prepared failed(String reason) {
            return new Prepared(List.of(), null, null, reason);
        }

        public boolean failed() {
            return failureReason != null;
        }

        /** 지역 실시간으로 물을 대기가 하나라도 있다. */
        public boolean usesRegionalRealtime() {
            return regionalLookups() > 0;
        }

        /** 지역 실시간으로 물을 대기 수 = 이 후보를 평가할 때 나갈 지역 조회 수(최대). 회차당 상한을 셀 때 쓴다. */
        public int regionalLookups() {
            return (int) legs.stream()
                    .filter(leg -> leg.type().isWaiting())
                    .filter(leg -> isRegionalBusWait(leg, RemainingPathEtaComposer.firstOption(leg)))
                    .count();
        }

        /** 탈 것이 없는 경로. 예측을 건너뛴다(D8). */
        public boolean walkOnly() {
            return firstWait == null;
        }

        /** 지금 타려던 노선과 같은지 견주는 값. 해석하지 못했으면 null 이라 비교하지 않는다. */
        public String firstRouteId() {
            return firstOption == null ? null : firstOption.routeId();
        }

        /** 화면에 보일 <b>새 경로의 환승 수</b>. 구조가 바뀌므로 지금 경로와 다를 수 있다. */
        public int transferCount() {
            return (int) legs.stream().filter(leg -> leg.type().isWaiting()).count();
        }

        /**
         * 첫 대기 <b>이후에</b> 남는 대기 수. 이 값이 기준선보다 크면 후보를 버린다 —
         * 생략한 대기 시간이 후보 쪽에만 더 많이 빠져 없는 개선폭이 만들어진다.
         */
        public int waitsAfterFirst() {
            if (walkOnly()) {
                return 0;
            }
            return (int) legs.stream()
                    .filter(leg -> leg.legIndex() > firstWait.legIndex())
                    .filter(leg -> leg.type().isWaiting())
                    .count();
        }
    }

    /**
     * 후보 하나의 평가 결과 또는 실패 사유.
     *
     * @param estimate   승차 예측. 걷기만으로 닿는 경로면 null
     * @param observedAt 화면에 표시할 근거 시각. 걷기만인 경로는 지금 시각이다
     */
    public record Attempt(Prepared prepared, BoardingChainEtaCalculator.Result eta,
                          BoardingEstimate estimate, Instant observedAt, String filterReason) {

        static Attempt failed(String reason) {
            return new Attempt(null, null, null, null, reason);
        }

        public boolean failed() {
            return eta == null;
        }
    }
}
