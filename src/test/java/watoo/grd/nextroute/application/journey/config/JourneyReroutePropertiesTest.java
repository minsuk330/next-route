package watoo.grd.nextroute.application.journey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 재탐색 운영값 계약 가드(national-otp P2-F PR4a). REVIEW 항목 값 재산정을 고정한다 —
 * 무심코 되돌아가면 이 테스트가 잡는다. 값 근거는 policy-inventory.md / JourneyRerouteProperties javadoc.
 */
class JourneyReroutePropertiesTest {

    private final JourneyRerouteProperties props = new JourneyRerouteProperties();

    @Test
    @DisplayName("PR4a 재산정 기본값: max-paths=4 / search-budget=3000 / max-evaluations=10 / cooldown=1분")
    void reestimatedDefaults() {
        assertThat(props.getRemainingPath().getMaxPaths())
                .as("남은경로 평가 폭(OTP 무료 전제로 2→4)").isEqualTo(4);
        assertThat(props.getRemainingPath().getSearchBudgetMs())
                .as("남은경로 검색 예산(지연·완결성 근거로 2000→3000)").isEqualTo(3000);
        assertThat(props.getRouteOption().getMaxEvaluations())
                .as("대안노선 평가 상한(사실상 전부, 3→10)").isEqualTo(10);
        assertThat(props.getTrigger().getCooldown())
                .as("재탐색 최소간격(UX 근거로 2분→1분)").isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("PR4a 는 값만 재산정 — 플래그(마스터 enabled·trigger shadow)는 안 건드림(구현 완료 후 배포 결정)")
    void flagsUntouched() {
        assertThat(props.isEnabled())
                .as("마스터 기능 플래그: 코드 기본 꺼짐 유지").isFalse();
        assertThat(props.getTrigger().isEnabled())
                .as("trigger shadow: 켜짐 유지(4b·4c 완료 후 실제 제안 ON)").isTrue();
    }

    @Test
    @DisplayName("KEEP 항목은 그대로: min-eta-gain=300")
    void keepItemsUnchanged() {
        assertThat(props.getRouteOption().getMinimumEtaGainSeconds()).isEqualTo(300);
        assertThat(props.getRemainingPath().getMinimumEtaGainSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("재산정 값이 validate() 를 통과한다(0/무제한 아님)")
    void validatePasses() {
        assertThatCode(props::validate).doesNotThrowAnyException();
    }
}
