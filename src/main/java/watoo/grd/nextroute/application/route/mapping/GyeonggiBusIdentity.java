package watoo.grd.nextroute.application.route.mapping;

/**
 * 경기 버스 식별. OTP {@code 1:BS_TAGO_GGB<stationId>} / {@code 1:BR_TAGO_GGB<routeId>[_U|_D]} 에서
 * prefix·방향 접미사를 뗀 값으로, 경기도 버스도착 API(GBIS)의 stationId/routeId 와 같다(2026-10-05 실측).
 *
 * <p>{@link SeoulBusIdentity} 와 달리 우리 DB 존재 검증을 하지 않는다 — 경기 노선은 DB 에 적재하지 않고,
 * GBIS 는 순번 없이 (정류장, 노선)만으로 조회되기 때문이다. 틀린 id 면 GBIS 가 빈 결과를 줄 뿐이다.
 * 서울 전용 의미를 지키려고 {@link SeoulTransitIdentity}(sealed)는 구현하지 않는다.
 *
 * @param routeName OTP shortName(버스번호, 예 "5600")
 */
public record GyeonggiBusIdentity(String stationId, String routeId, String routeName) {
}
