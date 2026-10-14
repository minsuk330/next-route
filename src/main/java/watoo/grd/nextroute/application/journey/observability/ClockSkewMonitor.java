package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 이 인스턴스의 시계가 DB 시계와 얼마나 어긋났는지 잰다.
 *
 * <p>평가기 선점·완료와 재평가 트리거(#415-3)는 앱 시계로 "지금"을 정해 SQL 에 넘긴다. 인스턴스가
 * 여럿이면 각자의 시계가 "처리 중인가" 비교에 쓰이므로, 시계가 어긋나면 그만큼 판단이 틀어진다.
 * DB {@code now()} 로 바꾸는 대신(시각을 조종하는 시험이 많다) 어긋남을 감시한다. 2초를 넘으면 경보 대상.
 *
 * <p>값 = 앱 시각 − DB 시각(초). 왕복 시간의 절반만큼 오차가 있다.
 */
@Slf4j
@Component
public class ClockSkewMonitor {

    public static final String SKEW = "journey.clock.skew";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final AtomicReference<Double> skewSeconds = new AtomicReference<>(Double.NaN);

    public ClockSkewMonitor(JdbcTemplate jdbcTemplate, Clock clock, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        Gauge.builder(SKEW, skewSeconds, AtomicReference::get)
                .baseUnit("seconds")
                .description("app clock minus database clock")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${journey.navigation.reeval.clock-skew-check-ms:60000}",
            initialDelayString = "${journey.navigation.reeval.clock-skew-check-ms:60000}")
    public void check() {
        try {
            Instant before = Instant.now(clock);
            Timestamp db = jdbcTemplate.queryForObject("select now()", Timestamp.class);
            Instant after = Instant.now(clock);
            if (db == null) {
                return;
            }
            Instant appMid = before.plus(Duration.between(before, after).dividedBy(2));
            skewSeconds.set(Duration.between(db.toInstant(), appMid).toMillis() / 1000.0);
        } catch (DataAccessException e) {
            log.debug("[ClockSkew] DB 시각을 읽지 못함: {}", e.getMessage());
        }
    }
}
