package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase.JourneyVersions;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** SSE·long-poll 판단 재료(처방 4차) — 모르는 것은 재지 않고, 늦은 앱만 잰다. */
@DisplayName("여정 조건부 조회 지표")
class JourneyQueryMetricsTest {

    private static final long NOW = 1_700_000_010_000L;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JourneyQueryMetrics metrics = new JourneyQueryMetrics(registry);

    @Test
    @DisplayName("앱 번호가 낮고 변경 시각을 알면 커밋→수신 지연을 단계 종류 태그로 남긴다")
    void staleClientIsRecorded() {
        JourneyVersions v = new JourneyVersions(3, 5, false, "WAIT_BUS", NOW - 4_000);

        metrics.modified(v, 3, 5, 3L, 4L, NOW);

        Timer t = registry.find(JourneyQueryMetrics.DELIVERY_LAG).tag("leg", "WAIT").timer();
        assertThat(t).isNotNull();
        assertThat(t.count()).isEqualTo(1);
        assertThat(t.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(4_000);
        assertThat(registry.counter(JourneyQueryMetrics.CONDITIONAL, "result", "modified").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("변경 시각을 모르면(캐시를 DB 에서 채움) 재지 않는다 — 지어내지 않는다")
    void unknownChangedAtIsSkipped() {
        metrics.modified(new JourneyVersions(3, 5, false, "RIDE_BUS", null), 3, 5, 2L, 5L, NOW);
        metrics.modified(null, 3, 5, 2L, 5L, NOW);

        assertThat(registry.find(JourneyQueryMetrics.DELIVERY_LAG).timer()).isNull();
        assertThat(registry.counter(JourneyQueryMetrics.CONDITIONAL, "result", "modified").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("캐시가 이번 변경을 아직 못 받았거나(번호 다름) 링만 바뀐 200 이면 재지 않는다")
    void otherChangeIsSkipped() {
        metrics.modified(new JourneyVersions(3, 4, true, "WAIT_SUBWAY", NOW - 1_000), 3, 5, 3L, 4L, NOW);
        metrics.modified(new JourneyVersions(3, 5, true, "WAIT_SUBWAY", NOW - 1_000), 3, 5, 3L, 5L, NOW);

        assertThat(registry.find(JourneyQueryMetrics.DELIVERY_LAG).timer()).isNull();
    }

    @Test
    @DisplayName("단계 종류 태그: 대기·탑승·도보(환승 도보 포함)")
    void legKind() {
        assertThat(JourneyQueryMetrics.legKind("WAIT_SUBWAY")).isEqualTo("WAIT");
        assertThat(JourneyQueryMetrics.legKind("RIDE_BUS")).isEqualTo("RIDE");
        assertThat(JourneyQueryMetrics.legKind("TRANSFER_WALK")).isEqualTo("WALK");
        assertThat(JourneyQueryMetrics.legKind("WALK")).isEqualTo("WALK");
        assertThat(JourneyQueryMetrics.legKind(null)).isEqualTo("NONE");
    }
}
