package watoo.grd.nextroute.application.push.dto;

import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.domain.push.entity.PushPlatform;
import watoo.grd.nextroute.domain.push.entity.PushProvider;

/**
 * push token 등록 요청.
 *
 * <p>{@code userId} 가 없다. 신원은 JWT 에서만 온다 — 본문으로 받으면 남의 계정에 token 을
 * 심을 수 있다.
 */
public record RegisterPushTokenCommand(
        String token,
        PushPlatform platform,
        PushProvider provider,
        PushEnvironment environment) {
}
