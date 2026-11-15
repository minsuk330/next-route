package watoo.grd.nextroute.application.regionalsubway.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 서비스일. 04:00 KST 전은 전날 서비스일의 연장이다(적재의 {@link RegionalTimetableText#serviceSeconds} 와 같은 규칙).
 *
 * @param date    서비스일
 * @param seconds 서비스일 0시 기준 지금 초(04:00 전이면 86400 이상)
 */
public record RegionalServiceDay(LocalDate date, int seconds) {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static RegionalServiceDay of(Instant at) {
        ZonedDateTime t = at.atZone(KST);
        int sec = t.toLocalTime().toSecondOfDay();
        if (sec < RegionalTimetableText.SERVICE_DAY_START_SECONDS) {
            return new RegionalServiceDay(t.toLocalDate().minusDays(1), sec + 86400);
        }
        return new RegionalServiceDay(t.toLocalDate(), sec);
    }

    /** 서비스일 기준 초 → 실제 시각. */
    public static Instant instantOf(LocalDate serviceDate, int depSeconds) {
        return serviceDate.atTime(LocalTime.MIDNIGHT).atZone(KST).toInstant().plusSeconds(depSeconds);
    }
}
