package watoo.grd.nextroute.application.regionalbus.dto;

/**
 * TAGO 노선 목록({@code getRouteNoList})의 한 노선.
 *
 * <p>경기 노선도 {@code GGB} 접두사로 이 목록에 있다. 노선 하나는 도시 하나에만 나온다(2026-10-06 실측, 중복 0).
 *
 * @param routeId  접두사 포함 id. 숫자만이 아니다({@code BSB5201002F00}, {@code GMB171-110})
 */
public record TagoRoute(String cityCode, String routeId, String routeNo) {

    /** 경기 노선이면 경기 API 로 정류장을 받는다 — 경기 실시간과 같은 순번 체계라서. */
    public boolean isGyeonggi() {
        return routeId != null && routeId.startsWith("GGB");
    }
}
