package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 역에서 고른 열차(#265). {@code trainNo} 는 후보 조회가 준 값 그대로.
 *
 * @param routeRevision 앱이 보고 있는 경로 개정본. 다르면 409 {@code STALE_ROUTE_REVISION}
 */
public record SubwayTrainSelectionRequest(
        @NotBlank String trainNo,
        @Min(1) int routeRevision
) {
}
