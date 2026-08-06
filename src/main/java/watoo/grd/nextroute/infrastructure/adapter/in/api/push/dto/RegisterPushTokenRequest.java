package watoo.grd.nextroute.infrastructure.adapter.in.api.push.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import watoo.grd.nextroute.application.push.dto.RegisterPushTokenCommand;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.domain.push.entity.PushPlatform;
import watoo.grd.nextroute.domain.push.entity.PushProvider;

/**
 * push token 등록 요청 본문.
 *
 * <p>{@code provider}·{@code environment} 는 생략 가능하다. 지금 앱은 FCM·운영 하나뿐이라 보내지
 * 않아도 되고, 서버가 기본값을 채운다.
 *
 * <p>token 에 {@code @Pattern} 을 걸지 않는다. FCM 이 token 알파벳을 문서화하지 않아 정규식은
 * 미래 포맷을 막는다. 길이 상한만 두고, 공백은 DB check 가 최후로 막는다.
 *
 * @param token 512자는 {@code user_push_token.token varchar(512)} 와 같은 값이어야 한다.
 *              어긋나면 400 이어야 할 요청이 500 {@code 22001 value too long} 이 된다
 */
public record RegisterPushTokenRequest(
        @NotBlank @Size(max = 512) String token,
        @NotNull PushPlatform platform,
        PushProvider provider,
        PushEnvironment environment) {

    public RegisterPushTokenCommand toCommand() {
        return new RegisterPushTokenCommand(
                token.trim(),
                platform,
                provider == null ? PushProvider.FCM : provider,
                environment == null ? PushEnvironment.PRODUCTION : environment);
    }
}
