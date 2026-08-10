package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Instant;
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
    private final Clock clock;

    /**
     * 판정만 한다. 저장은 부르는 쪽의 책임이다.
     *
     * @param geometryByLegIndex 단계별 좌표. 없으면 빈 map 을 넘겨도 된다
     */
    public TransferAssessmentBatch assess(Long journeyId, int routeRevision, int currentLegIndex,
                                          Map<Integer, LegGeometry> geometryByLegIndex) {
        List<TransferConnection> transfers = transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        journeyId, routeRevision);
        if (transfers.isEmpty()) {
            return TransferAssessmentBatch.empty();
        }
        Map<Integer, JourneyLeg> legsByIndex = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        journeyId, routeRevision)
                .stream()
                .collect(Collectors.toMap(JourneyLeg::getLegIndex, Function.identity()));

        Instant now = Instant.now(clock);
        Map<Integer, TransferTargetArrival> boardingByLegIndex = new java.util.HashMap<>();
        List<TransferRiskAssessment> assessments = transfers.stream()
                .map(transfer -> assessOne(transfer, legsByIndex, geometryByLegIndex, now,
                        currentLegIndex, boardingByLegIndex))
                .toList();
        boolean upstreamBlocked = boardingByLegIndex.values().stream()
                .anyMatch(TransferTargetArrival::upstreamBlocked);
        return new TransferAssessmentBatch(assessments, Map.copyOf(boardingByLegIndex), upstreamBlocked);
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
                                             Map<Integer, LegGeometry> geometryByLegIndex,
                                             Instant now, int currentLegIndex,
                                             Map<Integer, TransferTargetArrival> boardingByLegIndex) {
        CurrentLegArrival fromArrival = predictCurrent(
                transfer.getFromLegIndex(), legsByIndex, geometryByLegIndex);

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

    /** 타고 있는(또는 걷고 있는) 단계가 언제 끝나는지. 외부 호출이 없는 계산이다. */
    private CurrentLegArrival predictCurrent(int legIndex, Map<Integer, JourneyLeg> legsByIndex,
                                             Map<Integer, LegGeometry> geometryByLegIndex) {
        JourneyLeg leg = legsByIndex.get(legIndex);
        if (leg == null) {
            return CurrentLegArrival.unknown();
        }
        return arrivalService.predict(leg, geometryByLegIndex.get(legIndex));
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
