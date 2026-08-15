package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;

/**
 * Live Activity 수신 주소 등록.
 *
 * @param activityToken ActivityKit push token. Activity 단위로 폐기·회전된다
 * @param pushToken     그 기기의 FCM registration token. 발송에 두 토큰이 다 필요하다
 * @param environment   생략하면 {@code PRODUCTION}. 개발 빌드는 APNs sandbox 라 구분해야 한다
 */
public record LiveActivityTokenRegisterRequest(
        /* 길이는 엔티티 칼럼과 반드시 같게 유지한다 — 어긋나면 400 이 500 이 된다. */
        @NotBlank @Size(max = 512) String activityToken,
        @NotBlank @Size(max = 512) String pushToken,
        PushEnvironment environment
) {

    public PushEnvironment environmentOrDefault() {
        return environment == null ? PushEnvironment.PRODUCTION : environment;
    }
}
