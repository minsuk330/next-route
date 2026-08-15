package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Live Activity 수신 주소 해제.
 *
 * <p>토큰을 경로가 아니라 본문으로 받는다. URL 은 접근 로그·프록시·브라우저 이력에 그대로 남는데
 * 이 값은 개인식별에 준해 다루기로 한 값이다.
 */
public record LiveActivityTokenReleaseRequest(
        @NotBlank @Size(max = 512) String activityToken
) {
}
