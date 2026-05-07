package watoo.grd.nextroute.application.subway.service;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

/// todo -> api로 정확하게 db에 적재해두기
public class KoreanPublicHolidayCalendar implements HolidayCalendar {

    private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
        MonthDay.of(1, 1),   // 신정
        MonthDay.of(3, 1),   // 삼일절
        MonthDay.of(5, 5),   // 어린이날
        MonthDay.of(6, 6),   // 현충일
        MonthDay.of(8, 15),  // 광복절
        MonthDay.of(10, 3),  // 개천절
        MonthDay.of(10, 9),  // 한글날
        MonthDay.of(12, 25)  // 크리스마스
    );

    // 음력 기반 공휴일 및 대체공휴일 (연도별 하드코딩)
    private static final Set<LocalDate> VARIABLE_HOLIDAYS = Set.of(
        // 2025
        LocalDate.of(2025, 1, 28),  // 설날 연휴
        LocalDate.of(2025, 1, 29),  // 설날
        LocalDate.of(2025, 1, 30),  // 설날 연휴
        LocalDate.of(2025, 5, 6),   // 어린이날 대체공휴일
        LocalDate.of(2025, 6, 6),   // 현충일
        LocalDate.of(2025, 8, 15),  // 광복절
        LocalDate.of(2025, 10, 5),  // 추석 연휴
        LocalDate.of(2025, 10, 6),  // 추석
        LocalDate.of(2025, 10, 7),  // 추석 연휴
        LocalDate.of(2025, 10, 8),  // 추석 대체공휴일
        // 2026
        LocalDate.of(2026, 1, 27),  // 설날 연휴
        LocalDate.of(2026, 1, 28),  // 설날 연휴
        LocalDate.of(2026, 1, 29),  // 설날
        LocalDate.of(2026, 1, 30),  // 설날 연휴
        LocalDate.of(2026, 3, 2),   // 삼일절 대체공휴일
        LocalDate.of(2026, 5, 25),  // 부처님오신날
        LocalDate.of(2026, 9, 24),  // 추석 연휴
        LocalDate.of(2026, 9, 25),  // 추석 연휴
        LocalDate.of(2026, 9, 26),  // 추석
        LocalDate.of(2026, 9, 27)   // 추석 연휴
    );

    @Override
    public boolean isHoliday(LocalDate date) {
        return FIXED_HOLIDAYS.contains(MonthDay.from(date))
            || VARIABLE_HOLIDAYS.contains(date);
    }
}
