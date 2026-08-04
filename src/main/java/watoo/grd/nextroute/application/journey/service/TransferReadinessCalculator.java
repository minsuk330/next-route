package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;

import java.time.Instant;

/**
 * 사용자가 환승 정류장에 <b>탈 준비를 마치고 서 있을</b> 시각을 구한다.
 *
 * <pre>
 * userReadyAt = currentLegArrivalAt + alightingBuffer + transferWalkSeconds
 * </pre>
 *
 * <p>버스가 정류장에 선 시각을 그대로 쓰면 안 된다. 내리는 데 시간이 걸리고, 환승 정류장까지
 * 걸어가야 한다. 이 둘을 빼먹으면 환승 여유가 실제보다 넉넉해 보여 "탈 수 있다"고 말한 환승을
 * 사용자가 놓친다.
 *
 * <p>도보 시간은 새로 계산하지 않고 환승 걷기 단계의 계획 소요시간을 쓴다. 그 값은 검색 때
 * TMAP 실측으로 채워진 것이라 직선거리 추정보다 낫다. 개인별 걷는 속도는 1차에서 학습하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TransferReadinessCalculator {

    private final JourneyTransferRiskProperties properties;

    /**
     * @param currentLegArrivalAt 지금 타고 있는 교통수단이 환승 정류장에 닿는 시각
     * @param transferWalkSeconds 환승 걷기 소요시간(초). 같은 정류장 환승이면 null 또는 0
     * @return 준비 완료 시각. 현재 leg 도착 시각을 모르면 null
     */
    public Instant userReadyAt(Instant currentLegArrivalAt, Integer transferWalkSeconds) {
        if (currentLegArrivalAt == null) {
            return null;
        }
        long walk = transferWalkSeconds == null ? 0L : Math.max(0, transferWalkSeconds);
        return currentLegArrivalAt
                .plusSeconds(properties.getAlightingBufferSeconds())
                .plusSeconds(walk);
    }

    /**
     * 환승 여유.
     *
     * <p>둘 중 하나라도 모르면 null 이다. 한쪽을 0으로 가정하고 계산하면 그럴듯하게 틀린 숫자가
     * 나오고, 그것이 알림으로 이어진다.
     */
    public Long transferSlackSeconds(Instant userReadyAt, Instant nextBusArrivalAt) {
        if (userReadyAt == null || nextBusArrivalAt == null) {
            return null;
        }
        return nextBusArrivalAt.getEpochSecond() - userReadyAt.getEpochSecond();
    }
}
