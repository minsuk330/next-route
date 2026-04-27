package watoo.grd.nextroute.infrastructure.adapter.in.api.realtime;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeResponse;
import watoo.grd.nextroute.application.subway.port.in.SubwayRealtimeQueryUseCase;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/realtime/subway")
@RequiredArgsConstructor
@Tag(name = "[실시간] 지하철 열차 위치")
public class SubwayRealtimeController {

    private final SubwayRealtimeQueryUseCase queryUseCase;

    /**
     * GET /api/realtime/subway
     *   ?lineId=1002              (optional)
     *   &stationNames=강남,역삼    (optional, CSV)
     *   &direction=상행            (optional)
     *
     * Returns 200 for all non-error cases (ACTIVE/OFF_HOURS/COLLECTOR_ERROR/COLD_START).
     * Returns 503 if Redis itself is down.
     */
    @GetMapping
    public ResponseEntity<SubwayRealtimeResponse> getRealtimeSubway(
            @RequestParam(required = false) String lineId,
            @RequestParam(required = false) String stationNames,
            @RequestParam(required = false) String direction) {

        List<String> stationList = stationNames != null
                ? Arrays.asList(stationNames.split(","))
                : null;
        try {
            SubwayRealtimeResponse response = queryUseCase.query(lineId, stationList, direction);
            return ResponseEntity.ok(response);
        } catch (DataAccessResourceFailureException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
