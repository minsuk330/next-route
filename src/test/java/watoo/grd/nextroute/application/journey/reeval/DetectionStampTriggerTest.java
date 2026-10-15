package watoo.grd.nextroute.application.journey.reeval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.application.journey.port.out.DetectionStampPort;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 재평가 트리거가 처음 본 시각을 남기는 계약(#446). 미뤄지는 여정에도 남겨야 한다 — 다음 수집에 다시 오거나 간격 폴링이
 * 먼저 평가해도 처음 본 시각은 첫 관측이어야 "처음 본 → 접수" 가 짧게 재지지 않는다.
 */
@DisplayName("재평가 트리거 — 처음 본 시각 남기기")
class DetectionStampTriggerTest {

    private static final Instant OBSERVED = Instant.parse("2026-10-16T09:00:00Z");

    private final JourneySessionRepository repository = mock(JourneySessionRepository.class);
    private final DetectionStampPort stamps = mock(DetectionStampPort.class);
    private ReevaluationProperties properties;

    @BeforeEach
    void setUp() {
        given(repository.requestReevaluation(any(), any(), anyLong())).willReturn(List.of());
        properties = new ReevaluationProperties();
        properties.setEnabled(true);
        properties.setShadow(false);
        properties.setShedBacklogThreshold(0);
        // 상한 1 — 나머지는 미뤄진다
        properties.setWakeCapMode(ReevaluationProperties.WakeCapMode.FIXED);
        properties.setMaxJourneysPerRequest(1);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("깨우는 여정·미루는 여정 모두에 가장 급한 등급으로 남긴다")
    void stampsDeliveredAndDeferredAtMostUrgentTier() {
        TriggerResult result = trigger().request(Map.of(
                ReevaluationTier.URGENT, Set.of(1L),
                ReevaluationTier.CAUTION, Set.of(1L, 2L, 3L)), OBSERVED);

        assertThat(result.deferred()).containsExactlyInAnyOrder(2L, 3L);
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(stamps).stamp(captor.capture(), org.mockito.ArgumentMatchers.eq(OBSERVED));
        Map<ReevaluationTier, Collection<Long>> stamped = captor.getValue();
        assertThat(stamped.get(ReevaluationTier.URGENT)).containsExactly(1L);
        assertThat(stamped.get(ReevaluationTier.CAUTION)).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("처음 본 시각이 없거나(옛 호출) 그림자 모드면 남기지 않는다")
    void skipsWithoutObservationOrInShadow() {
        trigger().request(Map.of(ReevaluationTier.URGENT, Set.of(1L)));
        properties.setShadow(true);
        trigger().request(Map.of(ReevaluationTier.URGENT, Set.of(1L)), OBSERVED);

        verify(stamps, never()).stamp(any(), any());
    }

    private JdbcReevaluationTrigger trigger() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        given(tm.getTransaction(any())).willReturn(mock(TransactionStatus.class));
        return new JdbcReevaluationTrigger(repository, properties,
                new JourneyReevalMetrics(new SimpleMeterRegistry()), tm, Clock.systemUTC(), stamps);
    }
}
