package watoo.grd.nextroute.domain.notification.entity;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteWeekdaysTest {

    // 2026-07-06 은 월요일.
    private static final LocalDateTime MON_0900 = LocalDateTime.of(2026, 7, 6, 9, 0);
    private static final LocalTime AT_1000 = LocalTime.of(10, 0);
    private static final LocalTime AT_0800 = LocalTime.of(8, 0);

    @Test
    void bitMapping_monIsBit0_sunIsBit6() {
        assertThat(RouteWeekdays.bit(DayOfWeek.MONDAY)).isEqualTo(1);
        assertThat(RouteWeekdays.bit(DayOfWeek.SUNDAY)).isEqualTo(1 << 6);
    }

    @Test
    void maskFromCodes_roundTrip() {
        int mask = RouteWeekdays.maskFromCodes(List.of("MON", "wed", " FRI "));
        assertThat(RouteWeekdays.toCodes(mask)).containsExactly("MON", "WED", "FRI");
        assertThat(RouteWeekdays.fromMask(mask))
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
    }

    @Test
    void maskFromCodes_nullIsZero() {
        assertThat(RouteWeekdays.maskFromCodes(null)).isZero();
    }

    @Test
    void parse_unknownCode_throws() {
        assertThatThrownBy(() -> RouteWeekdays.parse("MODNAY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── nextOccurrence 일회성 ────────────────────────────────────────────────
    @Test
    void oneTime_timeLater_today() {
        LocalDateTime next = RouteWeekdays.nextOccurrence(MON_0900, AT_1000, 0, false);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 6, 10, 0));
    }

    @Test
    void oneTime_timePassed_tomorrow() {
        LocalDateTime next = RouteWeekdays.nextOccurrence(MON_0900, AT_0800, 0, false);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 7, 8, 0));
    }

    // ── nextOccurrence 반복 ──────────────────────────────────────────────────
    @Test
    void recurring_todaySelected_timeLater_today() {
        int mask = RouteWeekdays.maskFromCodes(List.of("MON"));
        LocalDateTime next = RouteWeekdays.nextOccurrence(MON_0900, AT_1000, mask, true);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 6, 10, 0));
    }

    @Test
    void recurring_todaySelected_timePassed_nextWeekSameDay() {
        int mask = RouteWeekdays.maskFromCodes(List.of("MON"));
        LocalDateTime next = RouteWeekdays.nextOccurrence(MON_0900, AT_0800, mask, true);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 13, 8, 0));
    }

    @Test
    void recurring_todayNotSelected_nextSelectedDay() {
        // 월요일 now, 선택=수/금 → 수요일(7/8)
        int mask = RouteWeekdays.maskFromCodes(List.of("WED", "FRI"));
        LocalDateTime next = RouteWeekdays.nextOccurrence(MON_0900, AT_0800, mask, true);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 8, 8, 0));
    }

    @Test
    void recurring_todaySelectedButPassed_picksNextSelectedInWeek() {
        // 월요일 08:00 지남, 선택=월/화 → 화요일(7/7)
        int mask = RouteWeekdays.maskFromCodes(List.of("MON", "TUE"));
        LocalDateTime next = RouteWeekdays.nextOccurrence(MON_0900, AT_0800, mask, true);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 7, 8, 0));
    }

    @Test
    void recurring_weekWrap_sundayOnly() {
        // 월요일 now, 선택=일 → 다음 일요일(7/12)
        int mask = RouteWeekdays.maskFromCodes(List.of("SUN"));
        LocalDateTime next = RouteWeekdays.nextOccurrence(MON_0900, AT_1000, mask, true);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 12, 10, 0));
    }
}
