package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.domain.subway.entity.ScheduledTimeSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TimetableConverterTest {

    // FakeHolidayCalendar: 2026-05-05(어린이날) 공휴일로 등록
    static class FakeHolidayCalendar implements HolidayCalendar {
        private final Set<LocalDate> holidays;

        FakeHolidayCalendar(LocalDate... dates) {
            this.holidays = Set.of(dates);
        }

        @Override
        public boolean isHoliday(LocalDate date) {
            return holidays.contains(date);
        }
    }

    TimetableConverter converter;

    @BeforeEach
    void setUp() {
        converter = new TimetableConverter(new FakeHolidayCalendar(LocalDate.of(2026, 5, 5)));
    }

    @Test
    void TC_방향_상행_내선은_U로_변환된다() {
        assertThat(converter.toTimetableDirection("상행")).isEqualTo("U");
        assertThat(converter.toTimetableDirection("내선")).isEqualTo("U");
    }

    @Test
    void TC_방향_하행_외선은_D로_변환된다() {
        assertThat(converter.toTimetableDirection("하행")).isEqualTo("D");
        assertThat(converter.toTimetableDirection("외선")).isEqualTo("D");
    }

    @Test
    void TC_방향_null은_null을_반환한다() {
        assertThat(converter.toTimetableDirection(null)).isNull();
        assertThat(converter.toTimetableDirection("기타")).isNull();
    }

    @Test
    void TC_평일은_dayType_01이다() {
        // 2026-05-04 월요일
        LocalDate monday = LocalDate.of(2026, 5, 4);
        assertThat(converter.toDayType(monday)).isEqualTo("01");
    }

    @Test
    void TC_토요일은_dayType_02이다() {
        // 2026-05-02 토요일
        LocalDate saturday = LocalDate.of(2026, 5, 2);
        assertThat(converter.toDayType(saturday)).isEqualTo("02");
    }

    @Test
    void TC_일요일은_dayType_03이다() {
        // 2026-05-03 일요일
        LocalDate sunday = LocalDate.of(2026, 5, 3);
        assertThat(converter.toDayType(sunday)).isEqualTo("03");
    }

    @Test
    void TC_공휴일은_dayType_03이다() {
        // 2026-05-05 어린이날 (화요일이지만 공휴일)
        LocalDate holiday = LocalDate.of(2026, 5, 5);
        assertThat(converter.toDayType(holiday)).isEqualTo("03");
    }

    @Test
    void TC_arrTime_053000은_당일_05시30분이다() {
        LocalDate serviceDate = LocalDate.of(2026, 5, 3);
        LocalDateTime result = converter.toScheduledArrivalAt(serviceDate, "053000", "060000");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 5, 3, 5, 30, 0));
    }

    @Test
    void TC_arrTime_010000은_익일_01시00분이다() {
        LocalDate serviceDate = LocalDate.of(2026, 5, 3);
        LocalDateTime result = converter.toScheduledArrivalAt(serviceDate, "010000", "010500");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 5, 4, 1, 0, 0));
    }

    @Test
    void TC_arrTime_0이면_depTime_변환후_30초_뺀다() {
        // depTime="050000" → 05:00:00 당일 → -30s → 04:59:30
        LocalDate serviceDate = LocalDate.of(2026, 5, 3);
        LocalDateTime result = converter.toScheduledArrivalAt(serviceDate, "0", "050000");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 5, 3, 4, 59, 30));
    }

    @Test
    void TC_orderKey_23시50분이_익일_00시00분보다_작다() {
        // serviceDate=2026-05-03
        // 23:50 arrTime: base=04:00, 23:50 → 19시간50분 = 71400초
        // 00:00 arrTime: 다음날 00:00 → 20시간 = 72000초
        LocalDate serviceDate = LocalDate.of(2026, 5, 3);
        long key2350 = converter.toTimetableOrderKey(serviceDate, "235000", "235500");
        long key0000 = converter.toTimetableOrderKey(serviceDate, "000000", "000500");
        assertThat(key2350).isLessThan(key0000);
    }

    @Test
    void TC_arrTime_0_depTime_000010의_orderKey가_235000보다_크다() {
        // depTime=000010 → 익일 00:00:10 - 30s → 전날 23:59:40 (이전날)
        // 아니면: 익일 00:00:10 - 30s = 익일 23:59:40이 기준 04:00 대비 19:59:40 = 71980초
        // 23:50:00 arrTime: 당일 23:50:00, base=04:00 → 71400초
        // 00:00:10 depTime - 30s = 익일 23:59:40 → 71980초
        // 71980 > 71400 → true
        LocalDate serviceDate = LocalDate.of(2026, 5, 3);
        long key235000 = converter.toTimetableOrderKey(serviceDate, "235000", "235500");
        long keyDepMinus30 = converter.toTimetableOrderKey(serviceDate, "0", "000010");
        assertThat(keyDepMinus30).isGreaterThan(key235000);
    }
}
