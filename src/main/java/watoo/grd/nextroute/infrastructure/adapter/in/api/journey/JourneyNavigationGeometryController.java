package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;

/**
 * 여정 좌표 조회.
 *
 * <p>여정 명령·조회와 다른 컨트롤러로 둔다. 이 응답은 좌표 덩어리라서 캐시 수명과 호출 빈도가
 * 다르다 — 앱은 개정본이 바뀔 때만 한 번 받는다.
 *
 * <p>오류 응답은 {@link JourneyControllerAdvice} 가 공통 형식으로 만든다.
 */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
public class JourneyNavigationGeometryController {

    private final GetJourneyNavigationGeometryUseCase getNavigationGeometryUseCase;

    /**
     * @param routeRevision 앱이 보고 있던 개정본. 생략하면 현재 개정본을 준다
     */
    @GetMapping("/{journeyId}/navigation-geometry")
    public JourneyNavigationGeometry get(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @RequestParam(required = false) Integer routeRevision) {
        return getNavigationGeometryUseCase.get(userId, journeyId, routeRevision);
    }
}
