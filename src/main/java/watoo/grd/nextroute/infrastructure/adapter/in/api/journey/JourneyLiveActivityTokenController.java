package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.port.in.ManageLiveActivityTokenUseCase;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.LiveActivityTokenRegisterRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.LiveActivityTokenReleaseRequest;

/**
 * 잠금화면 Live Activity 의 수신 주소 등록·해제.
 *
 * <p>사용자 ID 는 JWT 에서만 온다. 본문에 있어도 쓰지 않는다.
 *
 * <p><b>두 API 다 몸통을 돌려주지 않는다.</b> 등록된 토큰을 응답에 실으면 그것이 로그·프록시
 * 캐시·클라이언트 저장소로 번진다.
 */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정")
public class JourneyLiveActivityTokenController {

    private final ManageLiveActivityTokenUseCase useCase;

    /**
     * 등록(재등록 포함).
     *
     * <p>ActivityKit 토큰은 Activity 가 사는 동안에도 회전한다. 앱은 회전할 때마다 이걸 다시
     * 부르고, 서버는 같은 토큰이면 갱신하고 아니면 새 행으로 받는다.
     */
    @PostMapping("/{journeyId}/live-activity-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody LiveActivityTokenRegisterRequest request) {
        useCase.register(userId, journeyId, request.activityToken(), request.pushToken(),
                request.environmentOrDefault());
    }

    /**
     * 해제. 앱이 Activity 를 끝낼 때 부른다.
     *
     * <p>없는 토큰이어도 {@code 204} 다. {@code 404} 를 주면 임의 토큰 문자열이 우리 표에 있는지
     * 없는지가 샌다.
     */
    @DeleteMapping("/{journeyId}/live-activity-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody LiveActivityTokenReleaseRequest request) {
        useCase.unregister(userId, journeyId, request.activityToken());
    }
}
