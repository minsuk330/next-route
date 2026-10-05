package watoo.grd.nextroute.application.route.mapping;

/**
 * 서울·경기 밖 버스 식별(TAGO). OTP {@code 1:BS_TAGO_BSB167850202} / {@code 1:BR_TAGO_BSB5200186000} 에서
 * {@code 1:BS_TAGO_}·{@code 1:BR_TAGO_} 만 뗀 <b>접두사 포함</b> id 가 TAGO 버스도착 API 의 nodeId/routeId 다(2026-10-05 실측).
 * 접두사가 provider 판별과 도시코드 둘 다를 들고 있어 그대로 둔다.
 *
 * <p>DB 존재 검증 없음({@link GyeonggiBusIdentity} 와 같은 이유 — 카탈로그 미적재, 틀리면 빈 결과뿐).
 *
 * @param cityCode {@link TagoCityCodes} 로 정한 도시코드
 */
public record TagoBusIdentity(String cityCode, String nodeId, String routeId, String routeName) {
}
