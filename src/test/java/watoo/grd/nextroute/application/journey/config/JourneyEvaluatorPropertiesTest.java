package watoo.grd.nextroute.application.journey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 잘못 두면 조용히 나빠지는 설정 조합을 부팅에서 막는지 본다.
 *
 * <p>가장 중요한 것은 최대 간격과 낡음 기준의 관계다. 어겨도 예외가 나지 않고 앱에 "모름"만 늘어나서
 * 운영에서 원인을 찾기 어렵다.
 */
@DisplayName("평가기 설정 검증")
class JourneyEvaluatorPropertiesTest {

    @Test
    void TC_기본값은_통과한다() {
        assertThatCode(() -> new JourneyEvaluatorProperties().validate()).doesNotThrowAnyException();
    }

    @Test
    void TC_최대_간격이_낡음_기준보다_길면_부팅을_막는다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setStaleAfterSeconds(120);
        props.setMaxIntervalSeconds(180);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("낡았다고 버려");
    }

    @Test
    void TC_기본_간격이_최소와_최대_밖이면_부팅을_막는다() {
        JourneyEvaluatorProperties tooSmall = new JourneyEvaluatorProperties();
        tooSmall.setMinIntervalSeconds(60);   // 기본 30초가 최소보다 작다

        assertThatThrownBy(tooSmall::validate).isInstanceOf(IllegalStateException.class);

        JourneyEvaluatorProperties tooLarge = new JourneyEvaluatorProperties();
        tooLarge.setMaxIntervalSeconds(20);   // 기본 30초가 최대보다 크다

        assertThatThrownBy(tooLarge::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TC_임박_기준이_한가함_기준보다_길면_부팅을_막는다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setNearTransferSeconds(900);
        props.setFarTransferSeconds(300);

        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TC_배수가_1보다_작으면_부팅을_막는다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setUnchangedBackoffMultiplier(0);

        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }
}
