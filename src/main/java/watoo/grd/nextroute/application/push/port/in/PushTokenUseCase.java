package watoo.grd.nextroute.application.push.port.in;

import watoo.grd.nextroute.application.push.dto.RegisterPushTokenCommand;

/** 기기 push token을 등록·교체하거나 해제한다. */
public interface PushTokenUseCase {
    void register(Long userId, RegisterPushTokenCommand command);

    void unregister(Long userId, String token);
}
