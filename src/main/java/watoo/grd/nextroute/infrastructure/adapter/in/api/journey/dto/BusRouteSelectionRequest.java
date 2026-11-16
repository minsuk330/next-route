package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 탄(탈) 버스 노선 선택(#598).
 *
 * @param routeOptionId 여정 조회 응답 {@code legs[].routeOptions[].optionId} 중 하나
 * @param routeRevision 앱이 보고 있는 경로 개정본. 다르면 409 {@code STALE_ROUTE_REVISION}
 * @param legIndex      대상 버스 <b>탑승</b> 구간(RIDE_BUS)의 순번. 없으면 지금 단계 기준 — 탑승 중이면 그 구간,
 *                      버스를 기다리는 중이면 바로 다음 구간
 */
public record BusRouteSelectionRequest(
        @NotNull Long routeOptionId,
        @Min(1) int routeRevision,
        @Min(0) Integer legIndex
) {
}
