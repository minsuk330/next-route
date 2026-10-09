package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.journey.service.TrackJourneyStartService;
import watoo.grd.nextroute.application.journey.service.TrackSelectionQueryService;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.StartTrackRequest;

import java.util.List;

/**
 * 단독 추적(#265): 경로 검색 없이 노선·방향·승하차만 골라 지하철·버스를 추적한다.
 *
 * <p><b>경로가 {@code /api/journeys/track/**} 인 이유</b>: {@code SecurityConfig} 는 적어 둔 경로만 인증하고 나머지는
 * 공개다. {@code /api/journeys/**} 아래에 두어 인증을 물려받는다(조회도 OTP 를 부르므로 익명에 열지 않는다).
 *
 * <p>플래그 {@code journey.track.enabled} 가 꺼져 있으면 전부 404.
 */
@RestController
@RequestMapping("/api/journeys/track")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정 — 단독 추적")
public class JourneyTrackController {

    private final TrackSelectionQueryService queryService;
    private final TrackJourneyStartService startService;

    /** 좌표 주변 정류장/역. {@code mode} = BUS 또는 SUBWAY(철도 포함), 비우면 전부. */
    @GetMapping("/stops/near")
    public List<TrackViews.Stop> stopsNear(@AuthenticationPrincipal Long userId,
                                           @RequestParam double lat, @RequestParam double lon,
                                           @RequestParam(required = false) RoutingMode mode) {
        return queryService.stopsNear(lat, lon, mode);
    }

    /** 그 정류장에서 탈 수 있는 노선·방향. */
    @GetMapping("/stops/{gtfsStopId}/patterns")
    public List<TrackViews.Direction> directions(@AuthenticationPrincipal Long userId,
                                                 @PathVariable String gtfsStopId) {
        return queryService.directions(gtfsStopId);
    }

    /** 그 노선·방향으로 {@code from} 다음부터 내릴 수 있는 정류장. */
    @GetMapping("/routes/{routeGtfsId}/stops")
    public List<TrackViews.AlightStop> alightStops(@AuthenticationPrincipal Long userId,
                                                   @PathVariable String routeGtfsId,
                                                   @RequestParam String from,
                                                   @RequestParam(required = false) String pattern) {
        return queryService.alightStops(routeGtfsId, from, pattern);
    }

    /**
     * 하차 선택 지도의 노선 선(#312). 시작 후 안내와 같은 GTFS 형상 slice. {@code to} 를 비우면 한 바퀴 끝까지(고르기 전
     * 선 전체), 형상이 없으면 정류장 직선({@code quality=STOP_LINE}).
     */
    @GetMapping("/routes/{routeGtfsId}/shape")
    public TrackViews.Shape shape(@AuthenticationPrincipal Long userId,
                                  @PathVariable String routeGtfsId,
                                  @RequestParam String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) String pattern) {
        return queryService.shape(routeGtfsId, from, to, pattern);
    }

    /**
     * 단독 추적 시작. 응답·409 규칙은 일반 시작({@code POST /api/journeys})과 같다. {@code replace=true} 면 진행 중인
     * 일반 여정도 접힌다.
     */
    @PostMapping
    public ResponseEntity<JourneySnapshot> start(@AuthenticationPrincipal Long userId,
                                                 @RequestParam(defaultValue = "false") boolean replace,
                                                 @Valid @RequestBody StartTrackRequest request) {
        JourneySnapshot snapshot = startService.start(userId, request.toCommand(replace));
        return ResponseEntity.status(HttpStatus.CREATED).body(snapshot);
    }
}
