package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationContextLoader.JourneyEvaluationContext;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 방아쇠를 세는 쪽의 계약.
 *
 * <p>이 클래스는 <b>제안을 만들지 않는다</b>(shadow). 그 사실은 생성자가 보장한다 —
 * {@code RerouteProposalService}·{@code RerouteProposalTxService} 를 주입받지 않으므로 실수로
 * 켜질 수도 없다. 그래서 여기서 확인하는 것은 "세는 방식"이다.
 */
@DisplayName("재탐색 방아쇠 계측")
class RerouteTriggerEvaluatorTest {

    private static final Long JOURNEY_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private final JourneyRerouteMetrics metrics = new JourneyRerouteMetrics(registry);
    /** 알림 발행은 PR-3 의 별도 관심사다. 여기서는 "방아쇠가 넘긴다"만 보면 된다. */
    private final RerouteAlertPublisher alertPublisher =
            org.mockito.Mockito.mock(RerouteAlertPublisher.class);

    /** cooldown 호출 횟수를 눈으로 센다. mock 보다 "몇 번 물었나"가 바로 읽힌다. */
    private final AtomicInteger cooldownAsked = new AtomicInteger();

    /** 어떤 용도로 잠금을 물었는지. 사용자 요청 잠금을 태우면 안 된다(D6). */
    private final List<RerouteCooldownPort.Scope> askedScopes = new ArrayList<>();

    @Test
    @DisplayName("shadow 방아쇠는 TRIGGER 잠금만 태운다 — 사용자 요청 잠금을 먹지 않는다")
    void triggerBurnsOnlyItsOwnCooldownKey() {
        RerouteTriggerEvaluator evaluator = evaluator(admitting(true));

        evaluator.evaluate(context(), batch(TransferStatus.AT_RISK), NOW);

        assertThat(askedScopes)
                .as("아무것도 만들지 않는 셈이 사용자 요청을 2분간 막으면 안 된다")
                .containsExactly(RerouteCooldownPort.Scope.TRIGGER);
    }

    @Test
    @DisplayName("통과하면 켜짐과 통과가 둘 다 오른다")
    void admittedCountsBoth() {
        RerouteTriggerEvaluator evaluator = evaluator(admitting(true));

        evaluator.evaluate(context(), batch(TransferStatus.AT_RISK), NOW);

        assertThat(count(JourneyRerouteMetrics.TRIGGER_FIRED)).isEqualTo(1);
        assertThat(count(JourneyRerouteMetrics.TRIGGER_ADMITTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("cooldown 이 막으면 켜짐만 오른다 — 두 수의 차이가 깎인 양이다")
    void cooldownIsVisibleAsTheGap() {
        RerouteTriggerEvaluator evaluator = evaluator(admitting(false));

        evaluator.evaluate(context(), batch(TransferStatus.AT_RISK), NOW);

        assertThat(count(JourneyRerouteMetrics.TRIGGER_FIRED)).isEqualTo(1);
        assertThatThrownBy(() -> count(JourneyRerouteMetrics.TRIGGER_ADMITTED))
                .as("막힌 신호가 통과로 세어지면 ③의 외부 호출 수를 못 맞춘다")
                .isInstanceOf(MeterNotFoundException.class);
    }

    @Test
    @DisplayName("신호가 없으면 cooldown 을 묻지 않는다")
    void noSignalNeverTouchesCooldown() {
        RerouteTriggerEvaluator evaluator = evaluator(admitting(true));

        evaluator.evaluate(context(), batch(TransferStatus.COMFORTABLE), NOW);

        assertThat(cooldownAsked)
                .as("대부분의 평가에는 신호가 없다. 매번 물으면 초당 200 평가가 Redis 왕복이 된다")
                .hasValue(0);
    }

    @Test
    @DisplayName("꺼져 있으면 아무것도 하지 않는다")
    void disabledTriggerIsInert() {
        properties.getTrigger().setEnabled(false);
        RerouteTriggerEvaluator evaluator = evaluator(admitting(true));

        evaluator.evaluate(context(), batch(TransferStatus.AT_RISK), NOW);

        assertThat(cooldownAsked).hasValue(0);
        assertThatThrownBy(() -> count(JourneyRerouteMetrics.TRIGGER_FIRED))
                .isInstanceOf(MeterNotFoundException.class);
    }

    @Test
    @DisplayName("판정이 비어 있으면 아무것도 하지 않는다")
    void emptyBatchIsInert() {
        RerouteTriggerEvaluator evaluator = evaluator(admitting(true));

        evaluator.evaluate(context(), TransferAssessmentBatch.empty(), NOW);

        assertThat(cooldownAsked).hasValue(0);
    }

    @Test
    @DisplayName("cooldown 이 터져도 평가 결과를 흔들지 않는다")
    void cooldownFailurePropagatesToCaller() {
        RerouteTriggerEvaluator evaluator = evaluator((journeyId, revision, scope) -> {
            throw new IllegalStateException("redis down");
        });

        // 이 예외를 삼키는 것은 부르는 쪽(JourneyEvaluationService)의 책임이다. 여기서 던지는
        // 것 자체는 계약 위반이 아니지만, 던진다는 사실을 시험으로 고정해 둔다 — 부르는 쪽의
        // try/catch 를 나중에 걷어내면 커밋된 평가가 실패로 기록된다.
        assertThatThrownBy(() -> evaluator.evaluate(context(), batch(TransferStatus.AT_RISK), NOW))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(JourneyRerouteMetrics.TRIGGER_FIRED))
                .as("터지기 전에 켜진 것은 세어져 있어야 한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("cooldown 확인은 신호가 가리키는 여정·개정본으로 묻는다 — 환승 번호는 키가 아니다")
    void cooldownIsAskedWithSignalScope() {
        RerouteTriggerEvaluator evaluator = evaluator((journeyId, revision, scope) -> {
            cooldownAsked.incrementAndGet();
            assertThat(journeyId).isEqualTo(JOURNEY_ID);
            assertThat(revision).isEqualTo(3);
            return true;
        });

        evaluator.evaluate(new JourneyEvaluationContext(JOURNEY_ID, 7L, 3, 0,
                Map.of(0, assessment(TransferStatus.COMFORTABLE))),
                batch(TransferStatus.AT_RISK), NOW);

        assertThat(cooldownAsked).hasValue(1);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private RerouteTriggerEvaluator evaluator(RerouteCooldownPort cooldownPort) {
        return new RerouteTriggerEvaluator(
                new RerouteTriggerPolicy(properties), alertPublisher, cooldownPort,
                metrics, properties);
    }

    private RerouteCooldownPort admitting(boolean admit) {
        return (journeyId, routeRevision, scope) -> {
            cooldownAsked.incrementAndGet();
            askedScopes.add(scope);
            return admit;
        };
    }

    /** 직전은 언제나 {@code COMFORTABLE}. 하락 여부는 이번 판정이 정한다. */
    private JourneyEvaluationContext context() {
        return new JourneyEvaluationContext(JOURNEY_ID, 7L, 1, 0,
                Map.of(0, assessment(TransferStatus.COMFORTABLE)));
    }

    private TransferAssessmentBatch batch(TransferStatus status) {
        return new TransferAssessmentBatch(List.of(assessment(status)), Map.of(), false);
    }

    private TransferRiskAssessment assessment(TransferStatus status) {
        return new TransferRiskAssessment(0, 0, 2, status, NOW.plusSeconds(60),
                NOW.plusSeconds(120), NOW.plusSeconds(180), 60L,
                CurrentLegArrival.Quality.GOOD, NOW,
                CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }
}
