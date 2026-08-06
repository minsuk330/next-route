package watoo.grd.nextroute.application.push.port.in;

import watoo.grd.nextroute.application.push.dto.RegisterPushTokenCommand;

/**
 * 기기 push token 등록·교체.
 *
 * <p>앱은 최초 실행뿐 아니라 provider 가 token 을 갱신할 때마다(재설치·데이터 삭제·장기 미사용)
 * 다시 부른다. 그래서 "등록"이 아니라 "등록·교체"다.
 */
public interface RegisterPushTokenUseCase {

    void register(Long userId, RegisterPushTokenCommand command);
}
