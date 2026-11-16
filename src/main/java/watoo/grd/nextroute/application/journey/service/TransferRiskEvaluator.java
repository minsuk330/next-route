package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Instant;
import watoo.grd.nextroute.application.journey.dto.LaneReselection;
import watoo.grd.nextroute.domain.journey.entity.RouteOptionSelectedBy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 여정의 환승들이 지금 안전한지 판정한다.
 *
 * <p>세 값을 모아 여유를 구한다.
 * <ul>
 *   <li>타고 있는 교통수단이 환승 지점에 닿는 시각 — 이동 leg ETA</li>
 *   <li>거기서 내려 걸어가 준비를 마치는 시각 — 하차 버퍼 + 환승 걷기</li>
 *   <li>갈아탈 차량이 <b>그 시각에</b> 오는 시각 — 대기 leg ETA</li>
 * </ul>
 *
 * <p>세 값 중 하나라도 모르면 {@code UNKNOWN} 이다. 한쪽을 0으로 가정해 계산하면 그럴듯하게
 * 틀린 숫자가 나오고, 그것이 그대로 알림이 된다.
 *
 * <h2>계산 순서가 중요하다</h2>
 *
 * 갈아탈 차량을 물어보려면 <b>사용자가 그 정류장에 언제 서는지</b>를 먼저 알아야 한다. 그래서
 * 준비 시각을 구한 뒤에 대기 leg 을 묻는다. 지금 시각으로 물으면 사용자가 도착하기 전에 지나갈
 * 차량을 답으로 받는다.
 *
 * <p>지하철을 타는 단계에서 고른 열차가 아직 승차역 전이면 그 열차를 기다리는 시간도 앞 단계 도착에 들어간다(#476).
 * 규칙은 ETA 조립기의 현재 단계 도착과 같은 {@link ChosenSubwayTrainWait} 다.
 *
 * <h2>저장하는 메서드와 하지 않는 메서드</h2>
 *
 * 판정 결과를 저장하는 이유는 떨림 방지가 <b>직전 상태</b>를 필요로 하기 때문이다. 다만 쓰는
 * 주체는 하나여야 한다 — 평가기와 조회 요청이 같은 행에 쓰면 서로 다른 시점에 계산한 두 결과가
 * 커밋 순서로 승부가 난다. 그래서 계산만 하는 {@link #assess}와 저장까지 하는
 * {@link #assessAndStore}를 나눠 두고, 부르는 쪽이 자기가 쓰는 주체인지에 따라 고른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferRiskEvaluator {

    private final TransferConnectionRepository transferRepository;
    private final JourneyLegRepository legRepository;
    private final CurrentLegArrivalService arrivalService;
    private final TransferTargetArrivalService targetArrivalService;
    private final TransferReadinessCalculator readinessCalculator;
    private final TransferRiskPolicy riskPolicy;
    private final TransferAssessmentWriter assessmentWriter;
    /** 지금 타는 지하철의 링을 스냅샷과 같은 방법으로 읽는다(#476). */
    private final JourneySubwayGuidanceComposer subwayGuidanceComposer;
    /** 고른 지하철 열차의 대기. ETA 조립기와 같은 규칙이다(#469·#476). */
    private final ChosenSubwayTrainWait chosenTrainWait;
    private final JourneyEvaluationMetrics metrics;
    private final JourneyTransferRiskProperties riskProperties;
    private final Clock clock;
    /** 묶인 버스 대기 중 먼저 오는 노선 고르기(#614 PR-2). 평가기 경로에서만 쓴다. */
    private final MergedBusLaneChooser laneChooser;

    /**
     * 판정만 한다. 저장은 부르는 쪽의 책임이다.
     *
     * @param geometryByLegIndex 단계별 좌표. 없으면 빈 map 을 넘겨도 된다
     */
    public TransferAssessmentBatch assess(Long journeyId, int routeRevision, int currentLegIndex,
                                          Map<Integer, LegGeometry> geometryByLegIndex) {
        return assess(journeyId, routeRevision, currentLegIndex, geometryByLegIndex, false);
    }

    /**
     * @param reselectLanes 묶인 버스 대기 중 서버 기본 선택을 먼저 오는 노선으로 옮겨 볼지(#614 PR-2). 평가기만 true —
     *                      읽어 온 단계 엔티티의 선택을 <b>메모리에서</b> 바꾼 뒤 판정·예측을 새 노선으로 잰다. 저장은
     *                      {@link JourneyEvaluationScheduleWriter#finish} 가 소유권 확인 뒤 다시 확인하고 한다. 트랜잭션 안이면
     *                      (변경이 dirty checking 으로 새어 나갈 수 있어) 고르지 않는다
     */
    public TransferAssessmentBatch assess(Long journeyId, int routeRevision, int currentLegIndex,
                                          Map<Integer, LegGeometry> geometryByLegIndex, boolean reselectLanes) {
        List<TransferConnection> transfers = transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        journeyId, routeRevision);
        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        journeyId, routeRevision);
        // 환승이 없어도 남은 대기(첫 승차)는 예측한다(#480). 둘 다 없거나 스위치가 꺼졌으면 할 일이 없다.
        boolean predictAllWaits = riskProperties.isPredictAllWaits();
        if (transfers.isEmpty() && (!predictAllWaits || legs.stream()
                .noneMatch(leg -> leg.getLegIndex() >= currentLegIndex && leg.getType().isWaiting()))) {
            return TransferAssessmentBatch.empty();
        }
        Map<Integer, JourneyLeg> legsByIndex = legs.stream()
                .collect(Collectors.toMap(JourneyLeg::getLegIndex, Function.identity()));

        Instant now = Instant.now(clock);
        LaneReselection reselection = reselectLanes ? reselectCurrentLane(legsByIndex, currentLegIndex, now) : null;
        SubwayGuidance currentGuidance = currentSubwayGuidance(
                journeyId, routeRevision, currentLegIndex, legs, legsByIndex, transfers);
        Map<Integer, TransferTargetArrival> boardingByLegIndex = new java.util.HashMap<>();
        // 단계 도착은 단계마다 한 번만 센다 — 환승 판정과 남은 대기 예측이 같은 값을 쓰고, 열차 대기 규칙도 한 번만 탄다.
        Map<Integer, CurrentLegArrival> arrivals = new java.util.HashMap<>();
        java.util.function.IntFunction<CurrentLegArrival> arrivalOf = legIndex -> arrivals.computeIfAbsent(legIndex,
                index -> predictCurrent(index, legsByIndex, geometryByLegIndex, currentLegIndex, currentGuidance, now));
        List<TransferRiskAssessment> assessments = transfers.stream()
                .map(transfer -> assessOne(transfer, legsByIndex, now, currentLegIndex, arrivalOf,
                        boardingByLegIndex))
                .toList();
        if (predictAllWaits) {
            predictWaitsAhead(legsByIndex, transfers, currentLegIndex, arrivalOf, now, boardingByLegIndex);
        }
        boolean upstreamBlocked = boardingByLegIndex.values().stream()
                .anyMatch(TransferTargetArrival::upstreamBlocked);
        return new TransferAssessmentBatch(assessments, Map.copyOf(boardingByLegIndex), upstreamBlocked, reselection);
    }

    /**
     * 지금 대기 단계의 서버 기본 선택을 먼저 오는 노선으로 옮긴다(#614 PR-2) — 메모리에서만. 읽어 온 엔티티는 트랜잭션 밖이라
     * (평가기 경로, open-in-view 꺼짐) 저장되지 않는다. 혹시 트랜잭션 안이면 고르지 않는다.
     */
    private LaneReselection reselectCurrentLane(Map<Integer, JourneyLeg> legsByIndex, int currentLegIndex, Instant now) {
        JourneyLeg wait = legsByIndex.get(currentLegIndex);
        if (!MergedBusLaneChooser.eligible(wait)) {
            return null;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("[LaneReselect] 트랜잭션 안의 평가 — 노선 고르기 건너뜀 journeyId={}", wait.getJourneyId());
            return null;
        }
        JourneyLeg ride = legsByIndex.get(currentLegIndex + 1);
        if (ride != null && ride.getType() != JourneyLegType.RIDE_BUS) {
            ride = null;
        }
        if (ride != null && !java.util.Objects.equals(ride.getSelectedRouteOptionId(), wait.getSelectedRouteOptionId())) {
            // 저장 쪽 재확인과 같은 조건 — 짝이 다른 후보를 가리키면 저장이 매번 버림 경로로 가 그 대기 결과가 영영 안 남는다.
            return null;
        }
        try {
            LaneReselection chosen = laneChooser.choose(wait, ride, now).orElse(null);
            if (chosen == null) {
                return null;
            }
            applyInMemory(wait, ride, chosen);
            return chosen;
        } catch (RuntimeException e) {
            log.warn("[LaneReselect] 노선 고르기 실패 — 지금 선택 유지 journeyId={}: {}", wait.getJourneyId(), e.toString());
            return null;
        }
    }

    /** 저장 경로({@link JourneyEvaluationScheduleWriter})와 같은 변경. 짝 탑승의 계획 소요도 — 다음 대기 앵커가 이 값을 쓴다. */
    static void applyInMemory(JourneyLeg wait, JourneyLeg ride, LaneReselection chosen) {
        wait.reselectRouteOption(chosen.toOptionId(), chosen.toRouteId(), RouteOptionSelectedBy.DEFAULT);
        if (ride != null) {
            ride.reselectRouteOption(chosen.toOptionId(), chosen.toRouteId(), RouteOptionSelectedBy.DEFAULT);
            ride.replanDurationSec(chosen.toRideSeconds());
        }
    }

    /**
     * 판정하고 저장한다. 평가기가 꺼져 있을 때의 조회 경로가 쓴다.
     *
     * <p>계산이 끝난 뒤에 저장한다. 실시간 조회가 트랜잭션 안에 들어가면 provider 가 느린 동안
     * DB 커넥션을 붙잡는다.
     */
    public List<TransferRiskAssessment> assessAndStore(Long journeyId, int routeRevision,
                                                       int currentLegIndex,
                                                       Map<Integer, LegGeometry> geometryByLegIndex) {
        TransferAssessmentBatch batch = assess(journeyId, routeRevision, currentLegIndex,
                geometryByLegIndex);
        assessmentWriter.write(journeyId, routeRevision, batch.assessments());
        return batch.assessments();
    }

    private TransferRiskAssessment assessOne(TransferConnection transfer,
                                             Map<Integer, JourneyLeg> legsByIndex,
                                             Instant now, int currentLegIndex,
                                             java.util.function.IntFunction<CurrentLegArrival> arrivalOf,
                                             Map<Integer, TransferTargetArrival> boardingByLegIndex) {
        CurrentLegArrival fromArrival = arrivalOf.apply(transfer.getFromLegIndex());

        Instant userReadyAt = readinessCalculator.userReadyAt(
                fromArrival.arrivalAt(), walkSeconds(transfer, legsByIndex),
                progressOf(transfer, currentLegIndex), now);

        // 준비 시각을 앵커로 갈아탈 차량을 묻는다. 앵커가 없으면(타고 있는 단계 ETA 를 모르면)
        // 물어볼 기준이 없으므로 예측기가 모른다고 답한다.
        TransferTargetArrival target = targetArrivalService.predictBoarding(
                legsByIndex.get(transfer.getToLegIndex()), userReadyAt, now);
        boardingByLegIndex.put(transfer.getToLegIndex(), target);
        CurrentLegArrival toArrival = target.arrival();

        Long slack = readinessCalculator.transferSlackSeconds(userReadyAt, toArrival.arrivalAt());
        TransferStatus status = riskPolicy.evaluate(slack, transfer.getStatus());
        CurrentLegArrival.Quality confidence = confidenceOf(fromArrival, toArrival, slack);

        return new TransferRiskAssessment(transfer.getTransferIndex(),
                transfer.getFromLegIndex(), transfer.getToLegIndex(), status,
                fromArrival.arrivalAt(), userReadyAt, toArrival.arrivalAt(),
                slack, confidence, now, toArrival.source(), target.modelVersion());
    }

    /**
     * 환승 판정이 답을 못 낸 남은 대기의 승차 예측(#480). 지금 단계부터 시계를 굴린다 — 대기면 그 예측, 도보·탑승이면
     * 계획 소요시간을 더하고, 그 시각을 다음 대기의 앵커로 쓴다({@link BoardingChainEtaCalculator} 와 같은 굴림).
     *
     * <ul>
     *   <li><b>첫 승차 대기</b>는 어떤 환승의 도착 쪽도 아니라 예측 대상이 아니었다 — 안내 시작부터 탈 때까지 도착 예정이
     *       비었다("시간 확인 중"). 지금 그 대기에 서 있으면 앵커는 지금이다</li>
     *   <li><b>아직 시작 전인 환승</b>은 출발 단계 도착을 몰라(앵커 없음) 대기 예측이 비었다. 여기서 굴린 시각으로 채운다</li>
     * </ul>
     *
     * <p>환승 판정(상태·여유·알림)은 바꾸지 않는다 — 계획 시간으로 굴린 앵커로 먼 환승을 "위험"이라 부르지 않게. 채운 값은
     * 대기 단계 행의 도착 예측으로만 쓰인다(목적지 도착·대기 단계 도착). 앞 대기를 못 풀면 그 뒤는 앵커가 없어 멈춘다.
     *
     * <p>앞 단계가 이미 시작된 환승의 대기({@link #covered})는 판정이 방금 물었다 — 그 답을 그대로 쓰고 다시 묻지 않는다(못
     * 구했어도). 실패한 조회를 같은 tick 에 한 번 더 부르지 않게. 시작 전 환승도 판정이 답을 냈으면 그 답을 쓴다.
     */
    private void predictWaitsAhead(Map<Integer, JourneyLeg> legsByIndex, List<TransferConnection> transfers,
                                   int currentLegIndex, java.util.function.IntFunction<CurrentLegArrival> arrivalOf,
                                   Instant now, Map<Integer, TransferTargetArrival> boardingByLegIndex) {
        Instant cursor = null;
        for (int index = currentLegIndex; legsByIndex.containsKey(index); index++) {
            JourneyLeg leg = legsByIndex.get(index);
            if (!leg.getType().isWaiting()) {
                if (index == currentLegIndex) {
                    CurrentLegArrival current = arrivalOf.apply(index);
                    cursor = current == null ? null : current.arrivalAt();
                } else if (cursor != null && leg.getPlannedDurationSec() != null) {
                    cursor = cursor.plusSeconds(leg.getPlannedDurationSec());
                } else {
                    cursor = null;
                }
                if (cursor == null) {
                    return;
                }
                continue;
            }
            TransferTargetArrival known = boardingByLegIndex.get(index);
            boolean answered = known != null && known.arrival().arrivalAt() != null;
            // 판정이 이미 답한 대기(진행 중 환승, 또는 앵커가 잡혀 답이 나온 환승)는 그 답을 쓴다 — 같은 대기를 한 평가에서 두 번
            // 묻지 않고, 판정 카드와 대기 예측이 다른 값을 갖지 않게.
            if (!covered(transfers, index, currentLegIndex) && !answered) {
                Instant anchor = index == currentLegIndex ? now : cursor;
                if (anchor == null) {
                    return;
                }
                TransferTargetArrival predicted = targetArrivalService.predictBoarding(leg, anchor, now);
                metrics.recordWaitAhead(index == currentLegIndex ? "current" : "later",
                        predicted.upstreamBlocked() ? "blocked"
                                : predicted.arrival().arrivalAt() == null ? "unknown" : "known");
                if (known == null || predicted.arrival().arrivalAt() != null) {
                    boardingByLegIndex.put(index, predicted);
                }
                known = predicted;
            }
            cursor = known == null ? null : known.arrival().arrivalAt();
            if (cursor == null) {
                return;
            }
        }
    }

    /** 앞 단계가 진행 중이거나 지난 환승의 대기인가 — 판정이 그 대기를 이미 물었다. */
    static boolean covered(List<TransferConnection> transfers, int waitLegIndex, int currentLegIndex) {
        return transfers.stream()
                .anyMatch(t -> t.getToLegIndex() == waitLegIndex && t.getFromLegIndex() <= currentLegIndex);
    }

    /**
     * 타고 있는(또는 걷고 있는) 단계가 언제 끝나는지. 외부 호출이 없는 계산이다.
     *
     * <p>지금 단계면 고른 지하철 열차의 대기를 넣는다(#476). 빼면 준비 시각이 열차 대기만큼 일러, 그 시각 기준으로
     * 고른 버스를 실제로는 놓치는데도 여유가 있다고 판정한다. 안내는 지금 단계의 것이라 다른 단계에는 대지 않는다.
     */
    private CurrentLegArrival predictCurrent(int legIndex, Map<Integer, JourneyLeg> legsByIndex,
                                             Map<Integer, LegGeometry> geometryByLegIndex,
                                             int currentLegIndex, SubwayGuidance currentGuidance,
                                             Instant now) {
        JourneyLeg leg = legsByIndex.get(legIndex);
        if (leg == null) {
            return CurrentLegArrival.unknown();
        }
        CurrentLegArrival moving = arrivalService.predict(leg, geometryByLegIndex.get(legIndex));
        if (moving == null) {
            moving = CurrentLegArrival.unknown();
        }
        if (legIndex != currentLegIndex) {
            return moving;
        }
        return chosenTrainWait.afterChosenTrainArrives(currentGuidance, leg, moving, now);
    }

    /**
     * 지금 타는 지하철의 안내(링). 그 단계에서 내려 갈아타는 환승이 있을 때만 읽는다(#476).
     *
     * <p>판정기는 스냅샷 없이 돈다(평가기 워커). 그래서 스냅샷과 <b>같은 조립기</b>로 읽는다 — 실시간 노선 여부, 고정
     * 상태, 링 세대 확인이 스냅샷의 안내와 같아야 화면의 현재 단계 도착과 환승 판정이 같은 열차를 본다. 노선 이름은
     * 쓰지 않으므로 노선 후보는 넘기지 않는다.
     *
     * <p>읽는 값은 고정 한 행(DB, 단계 id 유일 색인)과 링 한 키(Redis GET)다. 외부 API 는 부르지 않는다. 지하철을
     * 타는 중이 아니거나 그 단계 뒤에 환승이 없으면 아무것도 읽지 않는다.
     *
     * @return 읽을 이유가 없거나 못 읽었으면 null — 열차 대기 없이 지금처럼 계산한다
     */
    private SubwayGuidance currentSubwayGuidance(Long journeyId, int routeRevision, int currentLegIndex,
                                                 List<JourneyLeg> legs, Map<Integer, JourneyLeg> legsByIndex,
                                                 List<TransferConnection> transfers) {
        JourneyLeg current = legsByIndex.get(currentLegIndex);
        boolean ridingSubwayIntoTransfer = current != null
                && current.getType() == JourneyLegType.RIDE_SUBWAY
                && transfers.stream().anyMatch(transfer -> transfer.getFromLegIndex() == currentLegIndex);
        if (!ridingSubwayIntoTransfer) {
            return null;
        }
        try {
            return subwayGuidanceComposer.compose(routeRevision, currentLegIndex, legs, Map.of());
        } catch (RuntimeException e) {
            // 링은 판정을 더 정확하게 할 뿐이다. 못 읽었다고 판정 전체를 버리지 않는다(링 없음과 같게 둔다).
            log.warn("[TransferRisk] 지하철 안내 조회 실패 — journeyId={}, 열차 대기 없이 판정한다: {}",
                    journeyId, e.getMessage());
            return null;
        }
    }

    /**
     * 사용자가 이 환승에서 어디까지 왔는지.
     *
     * <p>단계 번호만으로 정한다. 갈아탈 대기 단계에 이미 들어왔으면 <b>서 있는 것</b>이고, 앞
     * 교통수단을 지났지만 대기 단계 전이면 <b>걷는 중</b>이다.
     *
     * <p>이 구분이 없으면 이미 끝난 하차·도보를 계속 더해, 정류장에 서 있는 사용자의 준비 시각이
     * 3분 넘게 미래로 밀린다 — 곧 오는 차가 "이미 지나간 차"로 판정된다.
     */
    private TransferReadinessCalculator.Progress progressOf(TransferConnection transfer,
                                                            int currentLegIndex) {
        if (currentLegIndex >= transfer.getToLegIndex()) {
            return TransferReadinessCalculator.Progress.WAITING;
        }
        if (currentLegIndex > transfer.getFromLegIndex()) {
            return TransferReadinessCalculator.Progress.WALKING;
        }
        return TransferReadinessCalculator.Progress.RIDING;
    }

    /**
     * 환승 걷기 시간. 같은 정류장 환승이면 걷기 단계가 없어 0이다.
     *
     * <p>쪼개기가 "같은 정류장"이라고 판별한 환승은 걷기 단계를 만들지 않는다. 그때 걷는 시간을
     * 임의로 넣으면 없는 여유를 깎는다.
     */
    private Integer walkSeconds(TransferConnection transfer, Map<Integer, JourneyLeg> legsByIndex) {
        if (transfer.getWalkLegIndex() == null) {
            return 0;
        }
        JourneyLeg walkLeg = legsByIndex.get(transfer.getWalkLegIndex());
        return walkLeg == null ? 0 : walkLeg.getPlannedDurationSec();
    }

    /**
     * 판정의 신뢰도. 두 ETA 중 나쁜 쪽을 따른다.
     *
     * <p>여유를 못 구했으면 신뢰도를 말하는 것 자체가 무의미하므로 모름이다.
     */
    private CurrentLegArrival.Quality confidenceOf(CurrentLegArrival from, CurrentLegArrival to,
                                                   Long slack) {
        if (slack == null) {
            return CurrentLegArrival.Quality.UNKNOWN;
        }
        boolean bothGood = from.quality() == CurrentLegArrival.Quality.GOOD
                && to.quality() == CurrentLegArrival.Quality.GOOD;
        return bothGood ? CurrentLegArrival.Quality.GOOD : CurrentLegArrival.Quality.APPROXIMATE;
    }
}
