package watoo.grd.nextroute.application.subway.service;

import java.time.LocalDate;
import java.util.Optional;

public interface HolidayCalendar {
    boolean isHoliday(LocalDate date);

    /**
     * 공휴일 이름(data.go.kr 특일정보 {@code dateName}). 예 {@code "설날"}, {@code "추석"}, {@code "대체공휴일(설날)"}.
     * 공휴일이 아니거나 이름을 모르면(하드코딩 폴백) empty. 광주 1호선 명절 시간표 판정에 쓴다.
     */
    default Optional<String> holidayName(LocalDate date) {
        return Optional.empty();
    }
}
