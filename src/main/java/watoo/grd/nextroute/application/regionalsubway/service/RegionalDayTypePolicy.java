package watoo.grd.nextroute.application.regionalsubway.service;

import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.subway.service.HolidayCalendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 날짜 → 그날 쓸 시간표 요일, 행이 없을 때 대체 순서. 운영사 공식 표 구분에 맞춘다(2026-11-16 확인).
 *
 * <p>판정 순서(Opus 검토 M4): 명절(광주만) → 공휴일 → 토요일 → 일요일 → 평일.
 * 공휴일이 토요일이면 공휴일표다(2027-02-06 설날, 2026-10-03 개천절).
 *
 * <ul>
 *   <li>명절 = data.go.kr 특일정보 이름이 설날·추석(대체공휴일(설날)/(추석) 포함). 광주교통공사만 명절표가 따로 있다.</li>
 *   <li>대전: 공식이 "주말, 휴일" 한 표 → 토요일도 공휴일표.</li>
 *   <li>부산·대구: 명절은 공휴일표.</li>
 * </ul>
 */
public final class RegionalDayTypePolicy {

    private static final Pattern LUNAR_HOLIDAY = Pattern.compile("설날|추석");

    private RegionalDayTypePolicy() {
    }

    public static RegionalDayType resolve(String lineCode, LocalDate date, HolidayCalendar calendar) {
        if ("GJ1".equals(lineCode)) {
            String name = calendar.holidayName(date).orElse("");
            if (LUNAR_HOLIDAY.matcher(name).find()) {
                return RegionalDayType.MH;
            }
        }
        if (calendar.isHoliday(date)) {
            return RegionalDayType.HO;
        }
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) {
            return RegionalDayType.SA;
        }
        if (dow == DayOfWeek.SUNDAY) {
            return RegionalDayType.HO;
        }
        return RegionalDayType.WD;
    }

    /** 조회 요일에 행이 없을 때 이어서 볼 표. 표에 없는 대체는 하지 않는다(빈 결과 = 시간표 없음). */
    public static List<RegionalDayType> candidates(String lineCode, RegionalDayType requested) {
        return switch (requested) {
            case SA -> "DJ1".equals(lineCode) ? List.of(RegionalDayType.SA, RegionalDayType.HO) : List.of(RegionalDayType.SA);
            case MH -> List.of(RegionalDayType.MH, RegionalDayType.HO);
            default -> List.of(requested);
        };
    }
}
