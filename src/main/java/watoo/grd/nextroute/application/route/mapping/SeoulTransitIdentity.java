package watoo.grd.nextroute.application.route.mapping;

/**
 * OTP(GTFS) leg 을 서울 실시간/ML provider 식별로 규칙 해석한 결과(방법 B — 표 없이 요청시 즉석 해석).
 * 스코프: 서울 버스 + 지하철 1~9호선. 그 밖은 resolve 가 empty 를 돌려 호출자가 STATIC 으로 처리한다.
 */
public sealed interface SeoulTransitIdentity permits SeoulBusIdentity, SeoulSubwayIdentity {
}
