package watoo.grd.nextroute.application.nearby.dto;

import watoo.grd.nextroute.application.subway.service.SubwayLineColors;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

public record NearbySubwayStationResult(
        String stationId,
        String stationName,
        String lineId,
        String lineName,
        double latitude,
        double longitude,
        int distanceMeters,
        /** OTP GTFS 정류장 id(#298). 단독 추적 시작 {@code boardStopId}. 기동 워밍업 전이거나 못 이으면 null. 지방 역은 null(서울 실시간 추적 전제) */
        String gtfsStopId,
        /**
         * 도착 정보 출처. {@code includeRegional=true} 요청에만 싣는다(옛 앱 응답은 그대로).
         * {@code REALTIME} 서울(역 도착 API), {@code TIMETABLE} 지방(/api/subway/regional/stations/{stationId}/departures)
         */
        @JsonInclude(JsonInclude.Include.NON_NULL) String arrivalSource
) {

    /** 지하철 공식 노선색({@code #RRGGBB}, {@link SubwayLineColors}). 모르는 노선은 null. */
    @JsonProperty(value = "lineColor", access = JsonProperty.Access.READ_ONLY)
    public String lineColor() {
        return SubwayLineColors.of(lineId, lineName);
    }

    public NearbySubwayStationResult withArrivalSource(String source) {
        return new NearbySubwayStationResult(stationId, stationName, lineId, lineName, latitude, longitude,
                distanceMeters, gtfsStopId, source);
    }
}
