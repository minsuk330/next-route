package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
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
 * 여정의 환승들이 지금 안전한지 판정하고 그 결과를 남긴다.
 *
 * <p>세 값을 모아 여유를 구한다.
 * <ul>
 *   <li>타고 있는 교통수단이 환승 지점에 닿는 시각 — 이동 leg ETA</li>
 *   <li>거기서 내려 걸어가 준비를 마치는 시각 — 하차 버퍼 + 환승 걷기</li>
 *   <li>갈아탈 차량이 오는 시각 — 대기 leg ETA</li>
 * </ul>
 *
 * <p>세 값 중 하나라도 모르면 {@code UNKNOWN} 이다. 한쪽을 0으로 가정해 계산하면 그럴듯하게
 * 틀린 숫자가 나오고, 그것이 그대로 알림이 된다.
 *
 * <p>판정 결과를 저장하는 이유는 떨림 방지가 <b>직전 상태</b>를 필요로 하기 때문이다. 매번
 * 새로 계산하면 좋아지는 방향에 유예를 걸 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferRiskEvaluator {

    /** 이 판정이 무엇으로 계산됐는지. 진단과 계측에서 서버 계산과 모델 추정을 가른다. */
    private static final String SOURCE = "CLIENT_PROGRESS_AND_REALTIME";

    private final TransferConnectionRepository transferRepository;
    private final JourneyLegRepository legRepository;
    private final CurrentLegArrivalService arrivalService;
    private final TransferReadinessCalculator readinessCalculator;
    private final TransferRiskPolicy riskPolicy;
    private final Clock clock;

    /**
     * @param geometryByLegIndex 단계별 좌표. 없으면 빈 map 을 넘겨도 된다
     */
    @Transactional
    public List<TransferRiskAssessment> evaluate(Long journeyId, int routeRevision,
                                                 Map<Integer, LegGeometry> geometryByLegIndex) {
        List<TransferConnection> transfers = transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        journeyId, routeRevision);
        if (transfers.isEmpty()) {
            return List.of();
        }
        Map<Integer, JourneyLeg> legsByIndex = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        journeyId, routeRevision)
                .stream()
                .collect(Collectors.toMap(JourneyLeg::getLegIndex, Function.identity()));

        Instant now = Instant.now(clock);
        return transfers.stream()
                .map(transfer -> assess(transfer, legsByIndex, geometryByLegIndex, now))
                .toList();
    }

    private TransferRiskAssessment assess(TransferConnection transfer,
                                          Map<Integer, JourneyLeg> legsByIndex,
                                          Map<Integer, LegGeometry> geometryByLegIndex,
                                          Instant now) {
        CurrentLegArrival fromArrival = predict(transfer.getFromLegIndex(), legsByIndex, geometryByLegIndex);
        CurrentLegArrival toArrival = predict(transfer.getToLegIndex(), legsByIndex, geometryByLegIndex);

        Instant userReadyAt = readinessCalculator.userReadyAt(
                fromArrival.arrivalAt(), walkSeconds(transfer, legsByIndex));
        Long slack = readinessCalculator.transferSlackSeconds(userReadyAt, toArrival.arrivalAt());

        TransferStatus status = riskPolicy.evaluate(slack, transfer.getStatus());
        CurrentLegArrival.Quality confidence = confidenceOf(fromArrival, toArrival, slack);

        transfer.applyAssessment(status,
                fromArrival.arrivalAt(),
                userReadyAt,
                toArrival.arrivalAt(),
                slack == null ? null : Math.toIntExact(slack),
                SOURCE,
                confidence.name(),
                now,
                fromArrival.observedAt());

        return new TransferRiskAssessment(transfer.getTransferIndex(), status,
                fromArrival.arrivalAt(), userReadyAt, toArrival.arrivalAt(),
                slack, confidence, now);
    }

    private CurrentLegArrival predict(int legIndex, Map<Integer, JourneyLeg> legsByIndex,
                                      Map<Integer, LegGeometry> geometryByLegIndex) {
        JourneyLeg leg = legsByIndex.get(legIndex);
        if (leg == null) {
            return CurrentLegArrival.unknown();
        }
        return arrivalService.predict(leg, geometryByLegIndex.get(legIndex));
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
