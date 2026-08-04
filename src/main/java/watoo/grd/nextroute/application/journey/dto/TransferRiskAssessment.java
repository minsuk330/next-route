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
 * @param targetSource         갈아탈 차량 도착을 무엇으로 알아냈는지(실시간·모델). 모르면 null
 * @param modelVersion         모델이 추정한 경우의 모델 이름. 그 외에는 null
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
        Instant calculatedAt,
        CurrentLegArrival.Source targetSource,
        String modelVersion
) {

    /**
     * 이 판정이 앱에 보일 값 기준으로 직전 판정과 같은지.
     *
     * <p>{@code calculatedAt} 은 비교하지 않는다. 그것은 매 평가마다 달라지므로 넣으면 늘 "달라졌다"가
     * 되고, 그러면 여정 번호가 30초마다 올라 앱이 같은 응답을 계속 다시 받는다.
     *
     * <p>반대로 상태만 비교해도 안 된다. {@code CATCHABLE} 그대로인데 버스가 3분 뒤에서 1분 뒤로
     * 바뀐 경우가 가장 흔하고, 그때 번호가 안 오르면 앱은 조회해도 <b>바뀐 것 없음</b>(204)을 받아
     * 새 도착 시각을 영원히 못 본다.
     */
    public boolean sameAs(TransferRiskAssessment other) {
        if (other == null) {
            return false;
        }
        return status == other.status
                && java.util.Objects.equals(currentLegArrivalAt, other.currentLegArrivalAt)
                && java.util.Objects.equals(userReadyAt, other.userReadyAt)
                && java.util.Objects.equals(nextBusArrivalAt, other.nextBusArrivalAt)
                && java.util.Objects.equals(transferSlackSeconds, other.transferSlackSeconds)
                && targetSource == other.targetSource;
    }

    public boolean isUnknown() {
        return status == TransferStatus.UNKNOWN;
    }

    /** 아직 지나지 않은 환승인지. 갈아탈 대기 단계가 현재보다 뒤이거나 지금이면 남은 것이다. */
    public boolean isUpcoming(int currentLegIndex) {
        return toLegIndex >= currentLegIndex;
    }
}
