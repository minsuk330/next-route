package watoo.grd.nextroute.infrastructure.adapter.in.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparturesView;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 지방 지하철 역의 다음 열차(공식 시간표 기준). 실시간이 아니다 — {@code source} 는 항상 {@code TIMETABLE}.
 *
 * @param dayType          그날 요일 WEEKDAY | SATURDAY | HOLIDAY | LUNAR_HOLIDAY(명절)
 * @param timetableDayType 실제로 쓴 표(대전 토요일 = HOLIDAY). 표가 없으면 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegionalDeparturesResponse(
        String stationKey,
        String stationName,
        String lineCode,
        String lineName,
        String source,
        String dayType,
        String timetableDayType,
        List<Direction> directions
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record Direction(String towardTerminal, List<Departure> departures) {
    }

    public record Departure(OffsetDateTime scheduledAt, int minutesUntil, String terminalName) {
    }

    public static RegionalDeparturesResponse from(RegionalDeparturesView v) {
        return new RegionalDeparturesResponse(
                v.station().stationKey(), v.station().stationName(), v.station().lineCode(), v.station().lineName(),
                "TIMETABLE", label(v.dayType()), label(v.timetableDayType()),
                v.directions().stream().map(d -> new Direction(d.towardTerminal(), d.departures().stream()
                        .map(x -> new Departure(x.scheduledAt().atZone(KST).toOffsetDateTime(), x.minutesUntil(), x.terminalName()))
                        .toList())).toList());
    }

    public static String label(RegionalDayType t) {
        if (t == null) {
            return null;
        }
        return switch (t) {
            case WD -> "WEEKDAY";
            case SA -> "SATURDAY";
            case HO -> "HOLIDAY";
            case MH -> "LUNAR_HOLIDAY";
        };
    }
}
