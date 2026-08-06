package watoo.grd.nextroute.infrastructure.adapter.in.api.push.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * push token 해제 요청 본문.
 *
 * <p>어느 token 을 뗄지 지정해야 한다. 사용자당 여러 기기를 허용하므로(SC-1) 식별자 없이 지우면
 * 한 기기의 로그아웃이 다른 기기의 알림까지 끊는다.
 */
public record UnregisterPushTokenRequest(
        @NotBlank @Size(max = 512) String token) {
}
