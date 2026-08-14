package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.port.in.CorrectSubwayBindingUseCase;
import watoo.grd.nextroute.application.journey.port.in.SubmitSubwayBoardingEvidenceUseCase;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.SubwayBindingCorrectionRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.SubwayBoardingEvidenceRequest;

/**
 * 지하철 탑승 관측 수집과 사용자 보정.
 *
 * <p>사용자 ID 는 JWT 에서만 온다. 본문에 있어도 쓰지 않는다.
 *
 * <p><b>이 PR 만으로는 아무 일도 일어나지 않는다.</b> 관측은 쌓이지만 열차를 고르는 판정기가
 * 아직 없어 상태는 계속 {@code PENDING} 이다. 그것이 의도한 기본 상태다 — 스키마와 수집 경로가
 * 먼저 굳어야 판정기를 그 위에 얹을 수 있다.
 */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정")
public class JourneySubwayBindingController {

    private final SubmitSubwayBoardingEvidenceUseCase submitUseCase;
    private final CorrectSubwayBindingUseCase correctUseCase;

    /**
     * 탑승 관측 수집.
     *
     * <p>{@code 202} 인 이유. 서버는 "받았다"까지만 약속한다. 어느 열차인지는 뒤에서 정하고,
     * 그때 재료가 없으면 다음 기회에 다시 시도한다 — 이 응답은 그 결과를 기다리지 않는다.
     *
     * <p>같은 관측을 다시 보내도 {@code 202} 이고, 몸통에는 <b>지금 상태</b>가 담긴다.
     * 고정된 값을 돌려주면 앱은 재전송 뒤 자기 화면을 되돌리게 된다.
     */
    @PostMapping("/{journeyId}/subway/boarding-evidence")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubwayBindingView submit(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody SubwayBoardingEvidenceRequest request) {
        return submitUseCase.submit(userId, journeyId, request.routeRevision(),
                request.legIndex(), request.toEvidence());
    }

    /**
     * 다른 열차를 탔다.
     *
     * <p>거부한 열차를 기억해 다시 고르지 않는다. 상태만 되돌리면 판정기가 같은 관측을 다시 읽어
     * 방금 거부한 열차를 그대로 고른다.
     */
    @PostMapping("/{journeyId}/subway/binding/reset")
    public SubwayBindingView reset(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody SubwayBindingCorrectionRequest request) {
        return correctUseCase.reset(userId, journeyId, request.routeRevision(),
                request.legIndex());
    }

    /** 타지 않았다. 이 단계에서는 더 이상 고정을 시도하지 않는다. */
    @PostMapping("/{journeyId}/subway/binding/not-boarded")
    public SubwayBindingView notBoarded(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody SubwayBindingCorrectionRequest request) {
        return correctUseCase.notBoarded(userId, journeyId, request.routeRevision(),
                request.legIndex());
    }
}
