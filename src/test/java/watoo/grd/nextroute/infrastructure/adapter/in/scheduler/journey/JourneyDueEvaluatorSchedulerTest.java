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

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T09:00:00Z"), ZoneId.of("Asia/Seoul"));

    private ThreadPoolTaskExecutor executor;
    private JourneyDueEvaluatorScheduler scheduler;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.initialize();
        // 아래 시험들은 배치를 통째로 잡고 끝나기를 기다리는 경로(DUE_TIME)를 본다. 빈자리만큼 채우는
        // 경로(URGENT_FIRST)는 맨 아래 시험들이 따로 본다.
        properties.setClaimOrder(JourneyEvaluatorProperties.ClaimOrder.DUE_TIME);
        scheduler = new JourneyDueEvaluatorScheduler(
                claimService, evaluationService, metrics, properties, executor, clock);
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
                .willReturn(new Report(Result.LOST_CLAIM, List.of(), false, false));

        scheduler.run();

        assertThat(registry.counter(JourneyEvaluationMetrics.LOST_CLAIM).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("급한 환승 먼저 모드는 워커가 곧바로 집을 만큼만 선점한다")
    void urgentFirstClaimsOnlyFreeCapacity() {
        properties.setClaimOrder(JourneyEvaluatorProperties.ClaimOrder.URGENT_FIRST);
        properties.setPoolSize(2);
        // 끝날 때마다 보충하므로, 만기 여정이 더 없을 때는 빈 목록을 돌려줘야 멈춘다
        given(claimService.claimDue(anyInt())).willReturn(claims(4)).willReturn(List.of());
        given(evaluationService.evaluate(any())).willReturn(evaluated());

        scheduler.run();

        // 워커 2개 + 큐 여분 2개. 배치(200)를 통째로 잡으면 큐가 순서를 정해 급한 환승이 밀린다.
        then(claimService).should().claimDue(4);
        then(claimService).should(never()).claimDue();
        then(evaluationService).should(timeout(2_000).times(4)).evaluate(any());
        // 이 모드도 선점 쿼리 시간과 한 바퀴를 잰다(#415-1). 완료 보충의 선점도 쿼리 시간에는 들어간다
        assertThat(registry.timer(JourneyEvaluationMetrics.CLAIM_DURATION).count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.timer(JourneyEvaluationMetrics.TICK_DURATION, "claimed", "some").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("급한 환승 먼저 모드는 평가가 끝나 빈자리가 생기면 tick 을 기다리지 않고 바로 채운다 — 처리량 상한이 tick 박자가 아니라 워커 속도")
    void refillsOnCompletionWithoutWaitingForTick() {
        properties.setClaimOrder(JourneyEvaluatorProperties.ClaimOrder.URGENT_FIRST);
        properties.setPoolSize(2);
        given(claimService.claimDue(anyInt()))
                .willReturn(claims(4))
                .willReturn(claims(4))
                .willReturn(List.of());
        given(evaluationService.evaluate(any())).willReturn(evaluated());

        scheduler.run();

        // tick 은 한 번뿐인데 두 번째 묶음까지 평가된다
        then(evaluationService).should(timeout(3_000).times(8)).evaluate(any());
        assertThat(registry.counter(JourneyEvaluationMetrics.REFILL, "trigger", "completion").count())
                .isPositive();
    }

    @Test
    @DisplayName("완료 보충을 끄면 tick 에서만 선점한다 — 되돌리는 스위치")
    void refillOnCompletionCanBeTurnedOff() throws Exception {
        properties.setClaimOrder(JourneyEvaluatorProperties.ClaimOrder.URGENT_FIRST);
        properties.setRefillOnCompletion(false);
        properties.setPoolSize(2);
        given(claimService.claimDue(anyInt())).willReturn(claims(4)).willReturn(claims(4));
        given(evaluationService.evaluate(any())).willReturn(evaluated());

        scheduler.run();
        then(evaluationService).should(timeout(2_000).times(4)).evaluate(any());
        Thread.sleep(300);

        then(claimService).should(times(1)).claimDue(anyInt());
    }

    @Test
    @DisplayName("급한 환승 먼저 모드는 빈자리가 없으면 선점하지 않는다")
    void urgentFirstSkipsWhenWorkersAreFull() throws Exception {
        properties.setClaimOrder(JourneyEvaluatorProperties.ClaimOrder.URGENT_FIRST);
        properties.setPoolSize(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            scheduler.run();

            then(claimService).should(never()).claimDue(anyInt());
            assertThat(registry.timer(JourneyEvaluationMetrics.TICK_DURATION, "claimed", "none").count())
                    .as("빈자리가 없어 선점 안 한 tick 도 센다 — 포화 신호")
                    .isEqualTo(1);
        } finally {
            release.countDown();
        }
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

    @Test
    @DisplayName("선점 쿼리 시간과 tick 한 바퀴를 빈 tick·일한 tick 으로 갈라 잰다")
    void recordsClaimAndTickDuration() {
        given(claimService.claimDue()).willReturn(List.of()).willReturn(claims(2));
        given(evaluationService.evaluate(any())).willReturn(evaluated());

        scheduler.run();
        scheduler.run();

        assertThat(registry.timer(JourneyEvaluationMetrics.CLAIM_DURATION).count()).isEqualTo(2);
        assertThat(registry.timer(JourneyEvaluationMetrics.TICK_DURATION, "claimed", "none").count())
                .isEqualTo(1);
        assertThat(registry.timer(JourneyEvaluationMetrics.TICK_DURATION, "claimed", "some").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("예정 시각에서 평가 시작까지 늦어진 시간을 여정마다 잰다")
    void recordsStartLagFromDueAt() {
        Instant now = clock.instant();
        given(claimService.claimDue()).willReturn(List.of(
                new JourneyDueClaim(1L, now.plusSeconds(60), now.minusSeconds(40)),
                new JourneyDueClaim(2L, now.plusSeconds(60), now.minusSeconds(4))));
        given(evaluationService.evaluate(any())).willReturn(evaluated());

        scheduler.run();

        Timer lag = registry.timer(JourneyEvaluationMetrics.START_LAG);
        assertThat(lag.count()).isEqualTo(2);
        assertThat(lag.totalTime(TimeUnit.SECONDS)).isEqualTo(44.0);
        assertThat(lag.max(TimeUnit.SECONDS)).isEqualTo(40.0);
        assertThat(registry.timer(JourneyEvaluationMetrics.QUEUE_WAIT).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("예정보다 일찍 시작한 것처럼 보이면(시계 차이) 0으로 센다")
    void negativeStartLagIsZero() {
        Instant now = clock.instant();
        given(claimService.claimDue()).willReturn(List.of(
                new JourneyDueClaim(1L, now.plusSeconds(60), now.plusSeconds(5))));
        given(evaluationService.evaluate(any())).willReturn(evaluated());

        scheduler.run();

        assertThat(registry.timer(JourneyEvaluationMetrics.START_LAG).totalTime(TimeUnit.SECONDS))
                .isZero();
    }

    @Test
    @DisplayName("지연 지표는 SLO 경계(10초·30초·120초) 구간을 내보낸다 — 없으면 p95 를 못 구한다")
    void startLagExposesSloBuckets() {
        given(claimService.claimDue()).willReturn(claims(1));
        given(evaluationService.evaluate(any())).willReturn(evaluated());

        scheduler.run();

        CountAtBucket[] buckets = registry.timer(JourneyEvaluationMetrics.START_LAG)
                .takeSnapshot().histogramCounts();
        assertThat(Arrays.stream(buckets).map(b -> (long) b.bucket(TimeUnit.SECONDS)))
                .contains(10L, 30L, 120L);
    }

    @Test
    @DisplayName("판정이 바뀌었는지 센다. 소유권을 잃은 결과는 저장되지 않았으니 세지 않는다")
    void countsJudgmentChangeOnlyForStoredResults() {
        given(claimService.claimDue()).willReturn(claims(3));
        given(evaluationService.evaluate(any()))
                .willReturn(new Report(Result.EVALUATED, List.of(), false, true))
                .willReturn(new Report(Result.EVALUATED, List.of(), false, false))
                .willReturn(new Report(Result.LOST_CLAIM, List.of(), false, false));

        scheduler.run();

        assertThat(registry.counter(JourneyEvaluationMetrics.JUDGMENT, "changed", "true").count())
                .isEqualTo(1);
        assertThat(registry.counter(JourneyEvaluationMetrics.JUDGMENT, "changed", "false").count())
                .isEqualTo(1);
    }

    private List<JourneyDueClaim> claims(int count) {
        Instant leaseUntil = clock.instant().plusSeconds(60);
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(id -> new JourneyDueClaim((long) id, leaseUntil, clock.instant().minusSeconds(30)))
                .toList();
    }

    private Report evaluated() {
        return new Report(Result.EVALUATED, List.of(), false, false);
    }
}
