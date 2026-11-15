package watoo.grd.nextroute.application.regionalsubway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RegionalServiceDayTest {

    @Test
    @DisplayName("04:00 KST 전은 전날 서비스일 + 86400, 이후는 그날")
    void rollover() {
        assertThat(RegionalServiceDay.of(Instant.parse("2026-11-16T18:30:00Z")))   // 11-17 03:30 KST
                .isEqualTo(new RegionalServiceDay(LocalDate.of(2026, 11, 16), 86400 + 3 * 3600 + 30 * 60));
        assertThat(RegionalServiceDay.of(Instant.parse("2026-11-16T19:00:00Z")))   // 11-17 04:00 KST
                .isEqualTo(new RegionalServiceDay(LocalDate.of(2026, 11, 17), 4 * 3600));
        assertThat(RegionalServiceDay.instantOf(LocalDate.of(2026, 11, 16), 86400 + 120))
                .isEqualTo(Instant.parse("2026-11-16T15:02:00Z"));                  // 11-17 00:02 KST
    }
}
