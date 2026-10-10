package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 역에서 고른 열차(#265). {@code trainNo} 는 후보 조회가 준 값 그대로.
 *
 * @param routeRevision 앱이 보고 있는 경로 개정본. 다르면 409 {@code STALE_ROUTE_REVISION}
 * @param legIndex      고르는 대상 지하철 <b>탑승</b> 구간. 없으면 지금 단계 기준(대기면 다음 탑승, 탑승 중이면 그 구간).
 *                      지하 환승에서 앞 열차를 탄 채 다음 열차를 고를 때 보낸다
 */
public record SubwayTrainSelectionRequest(
        @NotBlank String trainNo,
        @Min(1) int routeRevision,
        @Min(0) Integer legIndex
) {
}
