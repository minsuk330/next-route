package watoo.grd.nextroute.application.auth.port.out;

import java.util.OptionalInt;

/**
 * 사용자의 현재 토큰 세대 번호를 알려준다.
 *
 * <p>토큰에 적힌 번호와 이 값을 대조해 로그아웃된 토큰을 걸러낸다. 인증된 요청마다 한 번씩
 * 불리는 자리이므로 구현은 기본키 단건 조회를 넘지 않아야 한다.
 */
public interface TokenGenerationPort {

    /**
     * @return 사용자가 없으면 빈 값. 호출자는 이 경우 인증을 거부해야 한다 —
     *         서명이 유효해도 사라진 계정의 토큰이기 때문이다
     */
    OptionalInt currentGeneration(long userId);
}
