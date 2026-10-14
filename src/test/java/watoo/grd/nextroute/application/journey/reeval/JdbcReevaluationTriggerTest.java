package watoo.grd.nextroute.application.journey.reeval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("재평가 트리거 — 실패 격리")
class JdbcReevaluationTriggerTest {

    @Test
    @DisplayName("한 등급의 DB 실패는 그 등급 여정만 미루고 다른 등급은 반영한다")
    void failureDefersOnlyThatTier() {
        JourneySessionRepository repository = mock(JourneySessionRepository.class);
        given(repository.requestReevaluation(argThat(ids -> ids != null && ids.contains(1L)), any(), anyLong()))
                .willThrow(new DataAccessResourceFailureException("down"));
        given(repository.requestReevaluation(argThat(ids -> ids != null && ids.contains(2L)), any(), anyLong()))
                .willReturn(List.of());
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        given(tm.getTransaction(any())).willReturn(mock(TransactionStatus.class));

        ReevaluationProperties properties = new ReevaluationProperties();
        properties.setEnabled(true);
        properties.setShadow(false);
        properties.setShedBacklogThreshold(0);
        JdbcReevaluationTrigger trigger = new JdbcReevaluationTrigger(repository, properties,
                new JourneyReevalMetrics(new SimpleMeterRegistry()), tm, Clock.systemUTC());

        TriggerResult result = trigger.request(Map.of(
                ReevaluationTier.URGENT, Set.of(1L),
                ReevaluationTier.CAUTION, Set.of(2L)));

        assertThat(result.deferred()).containsExactly(1L);
        assertThat(result.delivered()).containsExactly(2L);
    }
}
