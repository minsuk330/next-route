package watoo.grd.nextroute.application.subway.service;

import java.time.LocalDate;

public interface HolidayCalendar {
    boolean isHoliday(LocalDate date);
}
