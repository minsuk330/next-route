package watoo.grd.nextroute.application.journey.reeval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 깨우기 상한 계약(#427 처방 2차): 처리량 → 상한, 바닥·천장, 콜드 스타트, 고정 모드로 되돌리기, 등급 순서·포화 차단 유지.
 */
@DisplayName("재평가 깨우기 상한 — 처리량으로 계산")
class ReevaluationWakeCapTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");

    @Test
    @DisplayName("상한 = 처리량 × 다음 수집까지 × 여유 배수 (올림)")
    void capIsRateTimesHorizonTimesHeadroom() {
        // 59.4건/s × 15초 × 1.5 = 1336.5 → 1337 (지하철 사다리 2,000 처리량)
        assertThat(ReevaluationWakeCap.compute(59.4, 15, 1.5, 500, 5000)).isEqualTo(1337);
        assertThat(ReevaluationWakeCap.compute(100, 15, 1.0, 0, 5000)).isEqualTo(1500);
    }

    @Test
    @DisplayName("바닥 아래면 바닥, 천장 위면 천장")
    void clampsToMinAndMax() {
        assertThat(ReevaluationWakeCap.compute(5, 15, 1.5, 500, 5000)).isEqualTo(500);
        assertThat(ReevaluationWakeCap.compute(1_000, 15, 1.5, 500, 5000)).isEqualTo(5000);
        // 천장이 안전 장치라 바닥과 엇갈리게 넣으면 천장이 이긴다
        assertThat(ReevaluationWakeCap.compute(1_000, 15, 1.5, 800, 300)).isEqualTo(300);
    }

    @Test
    @DisplayName("콜드 스타트(처리량 0)·모름(음수·NaN) 은 바닥 — 예전 고정값과 같은 동작")
    void coldStartAndUnknownFallToFloor() {
        assertThat(ReevaluationWakeCap.compute(0, 15, 1.5, 500, 5000)).isEqualTo(500);
        assertThat(ReevaluationWakeCap.compute(-1, 15, 1.5, 500, 5000)).isEqualTo(500);
        assertThat(ReevaluationWakeCap.compute(Double.NaN, 15, 1.5, 500, 5000)).isEqualTo(500);
        assertThat(ReevaluationWakeCap.compute(50, 0, 1.5, 500, 5000)).isEqualTo(500);
    }

    @Test
    @DisplayName("처리량은 DB(인스턴스 전체)의 최근 창 안 판정 저장 수 ÷ 창, 캐시 안에서는 다시 세지 않는다")
    void rateComesFromDbAndIsCached() {
        JourneySessionRepository repository = mock(JourneySessionRepository.class);
        // 최근 10초에 800건 저장 = 80건/s → 80 × 15 × 1.5 = 1800
        given(repository.countEvaluatedSince(NOW.minusSeconds(10))).willReturn(800L);
        MeterRegistry registry = new SimpleMeterRegistry();
        ReevaluationWakeCap cap = new ReevaluationWakeCap(repository, capacityProps(),
                new JourneyReevalMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(cap.current()).isEqualTo(1800);
        assertThat(cap.current()).isEqualTo(1800);

        verify(repository, times(1)).countEvaluatedSince(any());
        assertThat(registry.get(JourneyReevalMetrics.WAKE_CAP).gauge().value()).isEqualTo(1800);
        assertThat(registry.get(JourneyReevalMetrics.WAKE_RATE).gauge().value()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("처리량을 세지 못하면(DB 실패) 바닥값")
    void dbFailureFallsToFloor() {
        JourneySessionRepository repository = mock(JourneySessionRepository.class);
        given(repository.countEvaluatedSince(any())).willThrow(new DataAccessResourceFailureException("down"));
        ReevaluationWakeCap cap = new ReevaluationWakeCap(repository, capacityProps(),
                new JourneyReevalMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(cap.current()).isEqualTo(500);
    }

    @Test
    @DisplayName("fixed 모드는 예전처럼 max-journeys-per-request 를 그대로 쓰고 DB 를 세지 않는다")
    void fixedModeKeepsOldBehaviour() {
        JourneySessionRepository repository = mock(JourneySessionRepository.class);
        ReevaluationProperties properties = capacityProps();
        properties.setWakeCapMode(ReevaluationProperties.WakeCapMode.FIXED);
        properties.setMaxJourneysPerRequest(500);
        ReevaluationWakeCap cap = new ReevaluationWakeCap(repository, properties,
                new JourneyReevalMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(cap.current()).isEqualTo(500);
        verify(repository, never()).countEvaluatedSince(any());
    }

    @Test
    @DisplayName("트리거: 처리량이 받쳐 주면 500 을 넘겨 깨우고, 고정 모드면 500 에서 넘친다")
    void triggerUsesComputedCap() {
        Set<Long> urgent = ids(1, 1000);

        JourneySessionRepository fast = repositoryWithRate(800L); // 1800
        TriggerResult capacity = trigger(fast, capacityProps())
                .request(Map.of(ReevaluationTier.URGENT, urgent));
        assertThat(capacity.delivered()).hasSize(1000);
        assertThat(capacity.deferred()).isEmpty();

        ReevaluationProperties fixed = capacityProps();
        fixed.setWakeCapMode(ReevaluationProperties.WakeCapMode.FIXED);
        TriggerResult old = trigger(repositoryWithRate(800L), fixed)
                .request(Map.of(ReevaluationTier.URGENT, urgent));
        assertThat(old.delivered()).hasSize(500);
        assertThat(old.deferred()).hasSize(500);
    }

    @Test
    @DisplayName("트리거: 상한 안에서도 급한 등급부터 담고, 넘친 쪽은 미뤄 돌려준다(기준선 유지)")
    void triggerKeepsUrgentFirst() {
        // 처리량 0 → 바닥 500. 긴급 400 은 다, 주의 400 중 100 만
        TriggerResult result = trigger(repositoryWithRate(0L), capacityProps()).request(Map.of(
                ReevaluationTier.URGENT, ids(1, 400),
                ReevaluationTier.CAUTION, ids(1001, 1400)));

        assertThat(result.delivered()).containsAll(ids(1, 400));
        assertThat(result.delivered()).hasSize(500);
        assertThat(result.deferred()).hasSize(300).allMatch(id -> id > 1000);
    }

    @Test
    @DisplayName("트리거: 평가기가 포화면 상한이 커도 주의·여유는 미룬다(shed 유지)")
    void triggerKeepsShed() {
        JourneySessionRepository repository = repositoryWithRate(800L);
        given(repository.countOverdueSince(any())).willReturn(10_000L);
        ReevaluationProperties properties = capacityProps();
        properties.setShedBacklogThreshold(200);

        TriggerResult result = trigger(repository, properties).request(Map.of(
                ReevaluationTier.URGENT, ids(1, 10),
                ReevaluationTier.RELAXED, ids(101, 110)));

        assertThat(result.delivered()).isEqualTo(ids(1, 10));
        assertThat(result.deferred()).isEqualTo(ids(101, 110));
    }

    private static ReevaluationProperties capacityProps() {
        ReevaluationProperties properties = new ReevaluationProperties();
        properties.setEnabled(true);
        properties.setShadow(false);
        properties.setShedBacklogThreshold(0);
        return properties;
    }

    private static JourneySessionRepository repositoryWithRate(long evaluatedInWindow) {
        JourneySessionRepository repository = mock(JourneySessionRepository.class);
        given(repository.countEvaluatedSince(any())).willReturn(evaluatedInWindow);
        given(repository.requestReevaluation(any(), any(), anyLong())).willReturn(List.of());
        return repository;
    }

    private static JdbcReevaluationTrigger trigger(JourneySessionRepository repository,
                                                   ReevaluationProperties properties) {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        given(tm.getTransaction(any())).willReturn(mock(TransactionStatus.class));
        return new JdbcReevaluationTrigger(repository, properties,
                new JourneyReevalMetrics(new SimpleMeterRegistry()), tm, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Set<Long> ids(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().collect(Collectors.toSet());
    }
}
