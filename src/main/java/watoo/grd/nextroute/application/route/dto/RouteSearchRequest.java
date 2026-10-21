package watoo.grd.nextroute.application.route.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 경로 검색 요청.
 *
 * <p>{@code departureAt} 는 offset 을 포함한 ISO-8601 문자열(예: {@code 2026-09-08T08:00:00+09:00})이며
 * <b>선택</b>이다. 생략하면 요청 경계에서 주입 Clock 으로 "지금"으로 확정한다(P2-B). 경계에서 확정하는 이유는
 * provider·캐시 키·serviceDate 해석이 모두 같은 한 값을 봐야 하기 때문이다. 아직 검색 흐름에 배선하지 않았고
 * (PR2 에서 RoutingQuery 로 연결), 값 해석/검증은 {@code DepartureTimeResolver} 가 한다.
 *
 * <p>{@code excludeExpress}(subway-express S2, D2): {@code true} 면 급행 열차 경로를 빼고 찾는다. 생략·{@code false} 면 급행·일반을
 * 둘 다 보인다. 급행만 남아 0건이 되면 필터를 풀고 응답 {@code meta.degraded} 에 {@code express_filter}(다시 묻기가 실패·시간
 * 초과면 {@code express_filter_unverified})를 남긴다(D10). OTP provider 만 적용된다(ODsay 는 급행을 판정하지 않는다).
 */
public record RouteSearchRequest(
        @NotNull Double startX,
        @NotNull Double startY,
        @NotNull Double endX,
        @NotNull Double endY,
        String startName,
        String endName,
        String departureAt,
        Boolean excludeExpress
) {

    /** departureAt 없는 기존 호출부/테스트용(생략=요청 시각으로 확정). */
    public RouteSearchRequest(Double startX, Double startY, Double endX, Double endY,
                              String startName, String endName) {
        this(startX, startY, endX, endY, startName, endName, null, null);
    }

    /** 급행 끄기 없는 기존 호출부/테스트용(null = 급행 포함). */
    public RouteSearchRequest(Double startX, Double startY, Double endX, Double endY,
                              String startName, String endName, String departureAt) {
        this(startX, startY, endX, endY, startName, endName, departureAt, null);
    }

    /** 급행을 빼고 찾는가. null 은 false(급행 포함)다. */
    public boolean excludesExpress() {
        return Boolean.TRUE.equals(excludeExpress);
    }
}
