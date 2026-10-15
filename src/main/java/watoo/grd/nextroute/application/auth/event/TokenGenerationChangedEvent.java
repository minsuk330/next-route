package watoo.grd.nextroute.application.auth.event;

/**
 * 사용자의 토큰 세대가 바뀌었다(로그아웃으로 번호가 오름, 또는 탈퇴로 "없는 사용자"가 됨).
 *
 * <p>요청마다 세대를 확인하는 캐시({@code CachedTokenGenerationAdapter})가 이 사건을 커밋 <b>뒤</b>에 받아
 * 새 값으로 덮어쓴다. 커밋 전에 덮으면 롤백된 로그아웃이 캐시에만 남는다.
 */
public record TokenGenerationChangedEvent(long userId) {
}
