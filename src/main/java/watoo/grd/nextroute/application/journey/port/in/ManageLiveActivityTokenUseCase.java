package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.domain.push.entity.PushEnvironment;

/**
 * 잠금화면 Live Activity 의 수신 주소 등록·해제.
 *
 * <p>돌려주는 값이 없다. 등록된 토큰을 응답에 실으면 그것이 로그·프록시 캐시·클라이언트 저장소로
 * 번지고, 토큰은 개인식별에 준해 다루기로 한 값이다(V39 규칙).
 */
public interface ManageLiveActivityTokenUseCase {

    /**
     * 등록(재등록 포함).
     *
     * <p>ActivityKit 토큰은 Activity 가 사는 동안에도 회전한다. 앱은 회전할 때마다 다시 보내고,
     * 서버는 같은 토큰이면 갱신, 아니면 새 행으로 받는다.
     *
     * @param pushToken 그 기기의 FCM registration token. 발송에 두 토큰이 다 필요하다
     */
    void register(Long userId, Long journeyId, String activityToken, String pushToken,
                  PushEnvironment environment);

    /**
     * 해제. 앱이 Activity 를 끝낼 때 부른다.
     *
     * <p>없는 토큰이어도 성공이다 — 404 를 주면 임의 토큰 문자열이 우리 표에 있는지 없는지가 샌다.
     */
    void unregister(Long userId, Long journeyId, String activityToken);
}
