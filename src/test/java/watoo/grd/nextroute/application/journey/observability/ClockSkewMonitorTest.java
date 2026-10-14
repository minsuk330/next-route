package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("시계 차이 감시")
class ClockSkewMonitorTest {

    private static final Instant APP_NOW = Instant.parse("2026-10-14T10:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ClockSkewMonitor monitor =
            new ClockSkewMonitor(jdbc, Clock.fixed(APP_NOW, ZoneId.of("UTC")), registry);

    @Test
    @DisplayName("앱 시계가 DB 보다 3초 빠르면 +3 을 낸다")
    void reportsAppMinusDb() {
        given(jdbc.queryForObject(anyString(), eq(Timestamp.class)))
                .willReturn(Timestamp.from(APP_NOW.minusSeconds(3)));

        monitor.check();

        assertThat(registry.get(ClockSkewMonitor.SKEW).gauge().value()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("DB 를 못 읽으면 값을 비운 채(NaN) 둔다 — 0 으로 두면 정상처럼 보인다")
    void unreadableDbLeavesNaN() {
        given(jdbc.queryForObject(anyString(), eq(Timestamp.class)))
                .willThrow(new DataAccessResourceFailureException("down"));

        monitor.check();

        assertThat(registry.get(ClockSkewMonitor.SKEW).gauge().value()).isNaN();
    }
}
