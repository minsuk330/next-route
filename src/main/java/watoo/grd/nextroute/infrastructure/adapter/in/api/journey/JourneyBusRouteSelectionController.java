package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.service.BusRouteSelectionService;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.BusRouteSelectionRequest;

/**
 * 탄(탈) 버스 노선 선택(#598) — 한 구간에 버스가 여러 개 묶였을 때 사용자가 실제로 탄 노선을 알린다.
 *
 * <p>사용자 ID 는 JWT 에서만 온다.
 */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정")
public class JourneyBusRouteSelectionController {

    private final BusRouteSelectionService selectionService;
    private final JourneySnapshotEtaAttacher etaAttacher;

    /**
     * 묶인 버스 중 탄(탈) 노선을 고른다. 그 노선으로 도착 예측·환승 판정·탑승 위치를 다시 잰다(다음 평가, 수 초).
     *
     * <p>경로 개정본과 명령 번호({@code commandVersion})는 그대로다 — 탑승 전이는 앱이 따로 보내고, 이 선택 전에 읽은
     * 명령 번호로 보내도 된다. 지도 좌표의 버전 키는 바뀔 수 있다(색·정류장 원천) — 응답을 받은 뒤 좌표를 다시 받는다.
     *
     * <p>고를 수 있는 때: 대상 탑승 구간이 아직 안 끝났을 때(기다리는 중·탑승 중). 탑승 중에도 다시 고를 수 있다.
     * 같은 후보를 다시 보내면 아무것도 바뀌지 않는다.
     *
     * <ul>
     *   <li>404 {@code JOURNEY_NOT_FOUND} — 내 여정이 아니다</li>
     *   <li>409 {@code JOURNEY_NOT_ACTIVE} — 끝난 여정</li>
     *   <li>409 {@code STALE_ROUTE_REVISION} — 개정본이 바뀌었다(details = 현재 개정본)</li>
     *   <li>422 {@code BUS_ROUTE_SELECTION_NOT_ALLOWED} — 버스 구간이 아니거나 이미 내린 구간</li>
     *   <li>422 {@code BUS_ROUTE_OPTION_NOT_FOUND} — 그 구간에 묶인 후보가 아니다</li>
     * </ul>
     */
    @PostMapping("/{journeyId}/bus/route-selection")
    public JourneySnapshot selectRoute(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody BusRouteSelectionRequest request) {
        return etaAttacher.forWrite(userId, selectionService.select(userId, journeyId,
                request.routeOptionId(), request.routeRevision(), request.legIndex()));
    }
}
