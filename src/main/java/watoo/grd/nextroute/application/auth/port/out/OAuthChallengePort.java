package watoo.grd.nextroute.application.auth.port.out;

/**
 * 애플 로그인용 일회용 값(nonce) 저장소.
 *
 * <p>앱이 만든 값을 그대로 돌려받아 비교하면 훔친 토큰의 재전송을 막지 못한다. 서버가 발급하고
 * 서버가 소비해야 "한 번만"이 성립한다.
 */
public interface OAuthChallengePort {

    /** 새 일회용 값을 만들어 저장하고 돌려준다. */
    String issue();

    /**
     * 값을 <b>삭제하면서</b> 확인한다. 두 번째 호출은 반드시 false 다.
     *
     * @return 저장돼 있던 값이면 true, 없거나 만료됐으면 false
     */
    boolean consume(String nonce);
}
