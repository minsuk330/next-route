package watoo.grd.nextroute.application.subway.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.subway.entity.ScheduledTimeSource;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
public class TimetableConverter {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final HolidayCalendar holidayCalendar;

    public TimetableConverter(HolidayCalendar holidayCalendar) {
        this.holidayCalendar = holidayCalendar;
    }

    /**
     * 상행/내선 → "U", 하행/외선 → "D", 그 외 → null
     */
    public String toTimetableDirection(String rawDirection) {
        if (rawDirection == null) return null;
        return switch (rawDirection) {
            case "상행", "내선" -> "U";
            case "하행", "외선" -> "D";
            default -> null;
        };
    }

    /**
     * SATURDAY → "02", SUNDAY → "03", 공휴일 → "03", 평일 → "01"
     */
    public String toDayType(LocalDate serviceDate) {
        DayOfWeek dow = serviceDate.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) return "02";
        if (dow == DayOfWeek.SUNDAY) return "03";
        if (holidayCalendar.isHoliday(serviceDate)) return "03";
        return "01";
    }

    /**
     * arrTime이 "0"이 아니면 시간 파싱, hh >= 4 이면 당일, hh < 4 이면 익일.
     * arrTime이 "0"이면 depTime으로 위 규칙 적용 후 30초 뺀다.
     * 파싱 실패/null → null
     */
    public LocalDateTime toScheduledArrivalAt(LocalDate serviceDate, String arrTime, String depTime) {
        try {
            if (arrTime != null && !arrTime.equals("0")) {
                return parseTime(serviceDate, arrTime);
            } else {
                // arrTime == "0": use depTime - 30s
                LocalDateTime depDateTime = parseTime(serviceDate, depTime);
                if (depDateTime == null) return null;
                return depDateTime.minusSeconds(30);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseTime(LocalDate serviceDate, String timeStr) {
        if (timeStr == null || timeStr.equals("0")) return null;
        if (timeStr.length() != 6) return null;
        int hh = Integer.parseInt(timeStr.substring(0, 2));
        int mm = Integer.parseInt(timeStr.substring(2, 4));
        int ss = Integer.parseInt(timeStr.substring(4, 6));
        if (hh >= 4) {
            return serviceDate.atTime(hh, mm, ss);
        } else {
            return serviceDate.plusDays(1).atTime(hh, mm, ss);
        }
    }

    /**
     * arrTime == "0" → DEP_TIME_MINUS_30S_FOR_ZERO_ARRIVAL, 그 외 → ARR_TIME
     */
    public ScheduledTimeSource sourceOf(String arrTime) {
        if ("0".equals(arrTime)) return ScheduledTimeSource.DEP_TIME_MINUS_30S_FOR_ZERO_ARRIVAL;
        return ScheduledTimeSource.ARR_TIME;
    }

    /**
     * serviceDate 당일 04:00:00을 기준(0)으로 한 도착 예정 시각의 초 단위 오프셋.
     * toScheduledArrivalAt이 null이면 Long.MAX_VALUE 반환.
     */
    public long toTimetableOrderKey(LocalDate serviceDate, String arrTime, String depTime) {
        LocalDateTime ts = toScheduledArrivalAt(serviceDate, arrTime, depTime);
        if (ts == null) return Long.MAX_VALUE;
        var base = serviceDate.atTime(4, 0, 0).atZone(SEOUL);
        var tsZoned = ts.atZone(SEOUL);
        return Duration.between(base, tsZoned).toSeconds();
    }

    /**
     * serviceDate 당일 04:00:00을 기준(0)으로 한 실제 도착 시각의 초 단위 오프셋.
     */
    public long toEventOrderKey(LocalDate serviceDate, LocalDateTime arrivedAt) {
        var base = serviceDate.atTime(4, 0, 0).atZone(SEOUL);
        var arrivedAtZoned = arrivedAt.atZone(SEOUL);
        return Duration.between(base, arrivedAtZoned).toSeconds();
    }
}
