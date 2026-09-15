package watoo.grd.nextroute.application.route.routing;

/**
 * OTP 동시성 gate 가 발급한 토큰. {@link OtpConcurrencyGate#release} 로 반납하기 전까지
 * permit 하나를 점유한다. token 은 재시도마다 새로 만든 UUID 로, Redis ZSET 멤버 식별자다.
 */
public record OtpPermit(String token) {}
