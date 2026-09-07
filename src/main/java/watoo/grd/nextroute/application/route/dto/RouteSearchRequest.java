package watoo.grd.nextroute.application.route.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 경로 검색 요청.
 *
 * <p>{@code departureAt} 는 offset 을 포함한 ISO-8601 문자열(예: {@code 2026-09-08T08:00:00+09:00})이며
 * <b>선택</b>이다. 생략하면 요청 경계에서 주입 Clock 으로 "지금"으로 확정한다(P2-B). 경계에서 확정하는 이유는
 * provider·캐시 키·serviceDate 해석이 모두 같은 한 값을 봐야 하기 때문이다. 아직 검색 흐름에 배선하지 않았고
 * (PR2 에서 RoutingQuery 로 연결), 값 해석/검증은 {@code DepartureTimeResolver} 가 한다.
 */
public record RouteSearchRequest(
        @NotNull Double startX,
        @NotNull Double startY,
        @NotNull Double endX,
        @NotNull Double endY,
        String startName,
        String endName,
        String departureAt
) {

    /** departureAt 없는 기존 호출부/테스트용(생략=요청 시각으로 확정). */
    public RouteSearchRequest(Double startX, Double startY, Double endX, Double endY,
                              String startName, String endName) {
        this(startX, startY, endX, endY, startName, endName, null);
    }
}
