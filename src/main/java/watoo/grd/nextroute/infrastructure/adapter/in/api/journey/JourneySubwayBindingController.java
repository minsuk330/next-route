package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import org.springframework.web.bind.annotation.GetMapping;
import watoo.grd.nextroute.application.journey.dto.SubwayTrackabilityView;
import watoo.grd.nextroute.application.journey.dto.SubwayTrainCandidate;
import watoo.grd.nextroute.application.journey.service.SubwayTrainSelectionService;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.SubwayTrainSelectionRequest;
import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.port.in.SubwayBindingUseCase;
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

    private final SubwayBindingUseCase subwayBindingUseCase;
    private final SubwayTrainSelectionService trainSelectionService;

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
        return subwayBindingUseCase.submit(userId, journeyId, request.routeRevision(),
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
        return subwayBindingUseCase.reset(userId, journeyId, request.routeRevision(),
                request.legIndex());
    }

    /** 타지 않았다. 이 단계에서는 더 이상 고정을 시도하지 않는다. */
    @PostMapping("/{journeyId}/subway/binding/not-boarded")
    public SubwayBindingView notBoarded(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody SubwayBindingCorrectionRequest request) {
        return subwayBindingUseCase.notBoarded(userId, journeyId, request.routeRevision(),
                request.legIndex());
    }

    /**
     * 역에서 탈 열차 후보(#265). 곧 올 열차(기본 2대, {@code kind=UPCOMING}) 뒤에 이 역을 막 떠난 열차({@code kind=DEPARTED}).
     *
     * <p>고를 수 있는 단계: 대상 탑승의 바로 앞 대기 / 그 탑승 중(떠난 열차만) / 앞 지하철 탑승 중인 지하 환승.
     * 그 밖이면 409 {@code NOT_WAITING_FOR_SUBWAY}. 방향을 모르면 빈 목록이다. 플래그
     * {@code journey.subway.selection.enabled} 가 꺼져 있으면 404.
     *
     * @param legIndex 대상 지하철 탑승 구간. 없으면 지금 단계 기준
     */
    @GetMapping("/{journeyId}/subway/train-candidates")
    public List<SubwayTrainCandidate> trainCandidates(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @RequestParam(required = false) Integer legIndex) {
        return trainSelectionService.candidates(userId, journeyId, legIndex);
    }

    /**
     * 이 지하철 탑승 구간을 실시간으로 추적할 수 있는지. 빈 후보("지금 열차가 없음")와 실시간 미지원 노선을 가른다.
     * 단계 제약 없음. 지하철 탑승 구간이 아니면 409 {@code NOT_WAITING_FOR_SUBWAY}.
     *
     * @param legIndex 대상 지하철 탑승 구간. 없으면 지금 단계 기준
     */
    @GetMapping("/{journeyId}/subway/trackability")
    public SubwayTrackabilityView trackability(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @RequestParam(required = false) Integer legIndex) {
        return trainSelectionService.trackability(userId, journeyId, legIndex);
    }

    /**
     * 열차를 골랐다. 곧바로 확정(CONFIRMED · USER_SELECTED)이고 진행 링도 바로 돈다(#304, 다음 갱신 tick). 열차가 아직 안 왔으면 다음 역=승차역.
     * 틀렸으면 탑승 중에 다시 고른다(같은 엔드포인트, 떠난 열차만 받는다). 탑승 전환은 앱이 따로 보낸다.
     */
    @PostMapping("/{journeyId}/subway/train-selection")
    public SubwayBindingView selectTrain(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody SubwayTrainSelectionRequest request) {
        return trainSelectionService.select(userId, journeyId, request.trainNo(), request.routeRevision(),
                request.legIndex());
    }
}
