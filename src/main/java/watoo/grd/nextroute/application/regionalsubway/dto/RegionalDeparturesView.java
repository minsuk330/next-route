package watoo.grd.nextroute.application.regionalsubway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 역의 다음 열차(공식 시간표 기준).
 *
 * @param dayType          그날 요일(평일/토/공휴일/명절)
 * @param timetableDayType 실제로 쓴 표. 대전 토요일이면 HO(공식이 같은 표)
 * @param directions       방향 = 노선 끝 역 쪽. 내릴 역을 주면 그 방향 하나
 */
public record RegionalDeparturesView(
        RegionalStationSeed station,
        RegionalDayType dayType,
        RegionalDayType timetableDayType,
        List<Direction> directions
) {

    /** @param towardTerminal 이 방향 노선 끝 역 이름(표시용 "○○ 방면") */
    public record Direction(String towardTerminal, boolean increasing, List<Departure> departures) {
    }

    /** @param scheduledAt 공식 시간표 시각. 부산·광주 원천은 도착 시각(정차 20~30초 전) */
    public record Departure(Instant scheduledAt, int minutesUntil, String terminalName, int terminalSeq) {
    }
}
