package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Instant;

/**
 * 환승 하나의 위험도 판정 결과.
 *
 * <p>상태만 주지 않고 근거가 된 세 시각을 함께 남긴다. "왜 위험하다고 했는가"를 나중에 따질 수
 * 있어야 임계값을 운영 데이터로 조정할 수 있다.
 *
 * @param transferIndex        여정 안에서의 환승 순번
 * @param fromLegIndex         갈아타기 전 타고 있는 단계 순번
 * @param toLegIndex           갈아탈 차량을 기다리는 단계 순번
 * @param status               다섯 단계 중 하나
 * @param currentLegArrivalAt  타고 있는 교통수단이 환승 지점에 닿는 시각
 * @param userReadyAt          하차·도보를 더해 사용자가 탈 준비를 마치는 시각
 * @param nextBusArrivalAt     갈아탈 차량이 오는 시각
 * @param transferSlackSeconds 여유(초). 음수면 이미 놓쳤다
 * @param confidence           판정을 얼마나 믿을 수 있는지
 * @param calculatedAt         판정 시각
 */
public record TransferRiskAssessment(
        int transferIndex,
        int fromLegIndex,
        int toLegIndex,
        TransferStatus status,
        Instant currentLegArrivalAt,
        Instant userReadyAt,
        Instant nextBusArrivalAt,
        Long transferSlackSeconds,
        CurrentLegArrival.Quality confidence,
        Instant calculatedAt
) {

    public boolean isUnknown() {
        return status == TransferStatus.UNKNOWN;
    }

    /** 아직 지나지 않은 환승인지. 갈아탈 대기 단계가 현재보다 뒤이거나 지금이면 남은 것이다. */
    public boolean isUpcoming(int currentLegIndex) {
        return toLegIndex >= currentLegIndex;
    }
}
