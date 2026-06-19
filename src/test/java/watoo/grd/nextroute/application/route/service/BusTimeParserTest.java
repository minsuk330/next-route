package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static watoo.grd.nextroute.application.route.service.BusTimeParser.SEOUL;

class BusTimeParserTest {

    @Test
    void TC_14자리_숫자_파싱() {
        Optional<Instant> result = BusTimeParser.parse("20260606130001");
        assertThat(result).isPresent();
        ZonedDateTime zdt = result.get().atZone(SEOUL);
        assertThat(zdt.getYear()).isEqualTo(2026);
        assertThat(zdt.getMonthValue()).isEqualTo(6);
        assertThat(zdt.getDayOfMonth()).isEqualTo(6);
        assertThat(zdt.getHour()).isEqualTo(13);
        assertThat(zdt.getSecond()).isEqualTo(1);
    }

    @Test
    void TC_17자리_숫자_파싱() {
        Optional<Instant> result = BusTimeParser.parse("20260606130001000");
        assertThat(result).isPresent();
        ZonedDateTime zdt = result.get().atZone(SEOUL);
        assertThat(zdt.getHour()).isEqualTo(13);
    }

    @Test
    void TC_datetime_소수점_포맷_파싱() {
        Optional<Instant> result = BusTimeParser.parse("2026-06-06 13:00:01.0");
        assertThat(result).isPresent();
        ZonedDateTime zdt = result.get().atZone(SEOUL);
        assertThat(zdt.getHour()).isEqualTo(13);
        assertThat(zdt.getSecond()).isEqualTo(1);
    }

    @Test
    void TC_datetime_포맷_파싱() {
        Optional<Instant> result = BusTimeParser.parse("2026-06-06 13:00:01");
        assertThat(result).isPresent();
        ZonedDateTime zdt = result.get().atZone(SEOUL);
        assertThat(zdt.getHour()).isEqualTo(13);
    }

    @Test
    void TC_null_empty_빈문자열_empty() {
        assertThat(BusTimeParser.parse(null)).isEmpty();
        assertThat(BusTimeParser.parse("")).isEmpty();
        assertThat(BusTimeParser.parse("   ")).isEmpty();
    }

    @Test
    void TC_잘못된_포맷_empty() {
        assertThat(BusTimeParser.parse("2026-06-06")).isEmpty();
        assertThat(BusTimeParser.parse("1234")).isEmpty();
    }

    @Test
    void TC_KST_기준_UTC_변환_검증() {
        // KST 13:00 = UTC 04:00
        Optional<Instant> result = BusTimeParser.parse("20260606130000");
        assertThat(result).isPresent();
        assertThat(result.get().atZone(java.time.ZoneOffset.UTC).getHour()).isEqualTo(4);
    }
}
