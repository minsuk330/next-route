package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService.Report;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService.Result;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

/**
 * tick 하나가 여정 하나 때문에 죽지 않는지 본다.
 *
 * <p>평가기는 외부 API 를 부르므로 실패가 흔하다. 여정 하나의 실패가 배치 전체를 멈추면, 문제
 * 하나가 모든 사용자의 안내를 멈추는 셈이 된다.
 */
@DisplayName("평가 tick")
class JourneyDueEvaluatorSchedulerTest {

    private final JourneyDueClaimService claimService = mock(JourneyDueClaimService.class);
    private final JourneyEvaluationService evaluationService = mock(JourneyEvaluationService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JourneyEvaluationMetrics metrics = new JourneyEvaluationMetrics(registry);
    private final JourneyEvaluatorProperties properties = new JourneyEvaluatorProperties();

    private ThreadPoolTaskExecutor executor;
    private JourneyDueEvaluatorScheduler scheduler;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.initialize();
        scheduler = new JourneyDueEvaluatorScheduler(
                claimService, evaluationService, metrics, properties, executor);
    }

    @Test
    @DisplayName("선점한 여정마다 평가를 맡긴다")
    void evaluatesEachClaim() {
        given(claimService.claimDue()).willReturn(claims(3));
        given(evaluationService.evaluate(any())).willReturn(evaluated());

        scheduler.run();

        then(evaluationService).should(times(3)).evaluate(any());
        assertThat(registry.counter(JourneyEvaluationMetrics.CLAIMED).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("여정 하나가 실패해도 나머지는 평가된다")
    void oneFailureDoesNotStopBatch() {
        given(claimService.claimDue()).willReturn(claims(3));
        given(evaluationService.evaluate(any()))
                .willThrow(new IllegalStateException("provider 응답 없음"))
                .willReturn(evaluated())
                .willReturn(evaluated());

        scheduler.run();

        then(evaluationService).should(times(3)).evaluate(any());
        assertThat(registry.counter(JourneyEvaluationMetrics.FAILURES, "reason", "transient").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("저장 실패는 일시 오류와 따로 센다")
    void storageFailureHasItsOwnReason() {
        given(claimService.claimDue()).willReturn(claims(1));
        given(evaluationService.evaluate(any()))
                .willThrow(new DataAccessResourceFailureException("커넥션 없음"));

        scheduler.run();

        assertThat(registry.counter(JourneyEvaluationMetrics.FAILURES, "reason", "storage").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("소유권을 잃은 결과를 따로 센다 — 늘어나면 설정이 잘못됐다는 신호다")
    void countsLostClaim() {
        given(claimService.claimDue()).willReturn(claims(1));
        given(evaluationService.evaluate(any()))
                .willReturn(new Report(Result.LOST_CLAIM, List.of(), false));

        scheduler.run();

        assertThat(registry.counter(JourneyEvaluationMetrics.LOST_CLAIM).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("선점할 여정이 없으면 아무것도 하지 않는다")
    void noClaimsMeansNoWork() {
        given(claimService.claimDue()).willReturn(List.of());

        scheduler.run();

        then(evaluationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("선점 자체가 실패해도 tick 은 예외를 밖으로 내지 않는다")
    void swallowsClaimFailure() {
        willThrow(new DataAccessResourceFailureException("DB 없음")).given(claimService).claimDue();

        assertThatCode(scheduler::run).doesNotThrowAnyException();
    }

    private List<JourneyDueClaim> claims(int count) {
        Instant leaseUntil = Instant.now().plusSeconds(60);
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(id -> new JourneyDueClaim((long) id, leaseUntil, leaseUntil.minusSeconds(30)))
                .toList();
    }

    private Report evaluated() {
        return new Report(Result.EVALUATED, List.of(), false);
    }
}
