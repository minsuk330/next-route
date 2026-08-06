package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회가 실패했을 때 직전 판정을 붙잡는 조건을 확인한다.
 *
 * <p>기본값 기준 유예는 120초. 그보다 오래된 값이나 이미 지나간 버스 시각은 붙잡지 않는다.
 */
@DisplayName("판정 유예 규칙")
class TransferAssessmentGracePolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-06T09:00:00Z");

    private TransferAssessmentGracePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new TransferAssessmentGracePolicy(new JourneyEvaluatorProperties());
    }

    /** 조회에 성공한 판정. */
    private TransferRiskAssessment good(Duration untilArrival, Duration age) {
        return new TransferRiskAssessment(0, 1, 2, TransferStatus.COMFORTABLE,
                NOW.plusSeconds(60), NOW.plusSeconds(90), NOW.plus(untilArrival),
                180L, CurrentLegArrival.Quality.GOOD, NOW.minus(age),
                CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
    }

    /** 조회를 못 해 근거가 없는 판정. */
    private TransferRiskAssessment unknown() {
        return new TransferRiskAssessment(0, 1, 2, TransferStatus.UNKNOWN,
                null, null, null, null, CurrentLegArrival.Quality.UNKNOWN, NOW, null, null);
    }

    @Test
    void TC_직전_판정이_아직_쓸_만하면_유지한다() {
        TransferRiskAssessment previous = good(Duration.ofMinutes(5), Duration.ofSeconds(30));

        assertThat(policy.choose(unknown(), previous, NOW)).isSameAs(previous);
    }

    @Test
    void TC_직전_판정이_가리키는_버스가_이미_지나갔으면_모름으로_바꾼다() {
        // 이미 떠난 버스를 계속 알려 주면 사용자는 오지 않을 차를 기다린다.
        TransferRiskAssessment departed = good(Duration.ofMinutes(-1), Duration.ofSeconds(30));

        assertThat(policy.choose(unknown(), departed, NOW).isUnknown()).isTrue();
    }

    @Test
    void TC_유예를_넘긴_직전_판정은_붙잡지_않는다() {
        TransferRiskAssessment stale = good(Duration.ofMinutes(5), Duration.ofSeconds(121));

        assertThat(policy.choose(unknown(), stale, NOW).isUnknown()).isTrue();
    }

    @Test
    void TC_유예_경계값은_붙잡는다() {
        TransferRiskAssessment onEdge = good(Duration.ofMinutes(5), Duration.ofSeconds(120));

        assertThat(policy.choose(unknown(), onEdge, NOW)).isSameAs(onEdge);
    }

    @Test
    void TC_직전_판정이_없으면_그대로_모름() {
        assertThat(policy.choose(unknown(), null, NOW).isUnknown()).isTrue();
    }

    @Test
    void TC_직전_판정도_모름이면_그대로_모름() {
        assertThat(policy.choose(unknown(), unknown(), NOW).isUnknown()).isTrue();
    }

    @Test
    void TC_이번_조회에_성공했으면_항상_새_판정을_쓴다() {
        // 유예는 실패했을 때만 동작한다. 성공한 계산을 옛 값으로 되돌리면 안 된다.
        TransferRiskAssessment fresh = good(Duration.ofMinutes(3), Duration.ZERO);
        TransferRiskAssessment previous = good(Duration.ofMinutes(5), Duration.ofSeconds(30));

        assertThat(policy.choose(fresh, previous, NOW)).isSameAs(fresh);
    }

    @Test
    void TC_직전_판정의_도착_시각이_비어_있으면_붙잡지_않는다() {
        TransferRiskAssessment noArrival = new TransferRiskAssessment(0, 1, 2,
                TransferStatus.COMFORTABLE, NOW, NOW, null, null,
                CurrentLegArrival.Quality.APPROXIMATE, NOW, null, null);

        assertThat(policy.choose(unknown(), noArrival, NOW).isUnknown()).isTrue();
    }
}
