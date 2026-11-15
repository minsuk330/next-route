package watoo.grd.nextroute.infrastructure.adapter.in.api.subway;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayServingProperties;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalDepartureQueryService;
import watoo.grd.nextroute.infrastructure.adapter.in.api.subway.dto.RegionalDeparturesResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 지방 지하철(부산1~4, 대구1~3, 대전1, 광주1) 역의 다음 열차 — 운영사 공식 시간표 기준.
 * {@code stationKey} 는 근처 역 응답({@code includeRegional=true})의 {@code stationId}.
 */
@RestController
@RequestMapping("/api/subway/regional")
@RequiredArgsConstructor
public class RegionalSubwayStationController {

    private final RegionalDepartureQueryService queryService;
    private final RegionalSubwayServingProperties serving;
    private final Clock clock;

    @GetMapping("/stations/{stationKey}/departures")
    @Operation(summary = "지방 지하철 역의 다음 열차(공식 시간표 기준, 실시간 아님)")
    public ResponseEntity<RegionalDeparturesResponse> departures(@PathVariable String stationKey,
                                                                 @RequestParam(defaultValue = "3") int limit) {
        if (!serving.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return queryService.next(stationKey, null, limit, clock.instant())
                .map(v -> ResponseEntity.ok(RegionalDeparturesResponse.from(v)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown regional station"));
    }

    /**
     * 검색 결과의 지방 지하철 구간용. 앱은 서울 구간처럼 구간마다 따로 부른다(검색 응답 계약은 그대로).
     *
     * @param boardStopId  구간 {@code stations[0].stationID}(OTP 정류장 id)
     * @param alightStopId 구간 마지막 {@code stations[].stationID}. 없으면 역 화면(두 방향) — 앱 근처 역 목록은 지방 역을 OTP id 로만 안다
     * @param at           그 구간에 닿는 시각(ISO-8601, 예 plannedBoardAt − plannedWaitSeconds). 없으면 지금
     */
    @GetMapping("/departures")
    @Operation(summary = "지방 지하철 구간의 다음 열차(공식 시간표 기준, 실시간 아님)")
    public ResponseEntity<RegionalDeparturesResponse> legDepartures(
            @RequestParam String boardStopId,
            @RequestParam(required = false) String alightStopId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at,
            @RequestParam(defaultValue = "3") int limit) {
        if (!serving.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Instant anchor = at == null ? clock.instant() : at.toInstant();
        return queryService.nextForLeg(boardStopId, alightStopId, limit, anchor)
                .map(v -> ResponseEntity.ok(RegionalDeparturesResponse.from(v)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not a regional subway leg"));
    }
}
