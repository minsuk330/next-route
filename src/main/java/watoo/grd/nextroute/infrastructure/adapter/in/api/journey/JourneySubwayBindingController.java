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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;

/**
 * 지하철 열차 선택(#265) — 역에서 탈 열차 후보·추적 가능 여부·선택.
 *
 * <p>사용자 ID 는 JWT 에서만 온다. 본문에 있어도 쓰지 않는다. 모션 증거 수집·사용자 보정 API 는 자동 판정기와 함께
 * 2026-10 제거했다(앱 호출 0) — 열차를 바꾸려면 선택을 다시 부른다.
 */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정")
public class JourneySubwayBindingController {

    private final SubwayTrainSelectionService trainSelectionService;

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
