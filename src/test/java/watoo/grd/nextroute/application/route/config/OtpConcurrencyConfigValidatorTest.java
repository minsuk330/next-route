package watoo.grd.nextroute.application.route.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OTP 동시성 lease 는 실제 OTP 호출 예산보다 길어야 한다")
class OtpConcurrencyConfigValidatorTest {

    @Test
    @DisplayName("기본값은 통과한다")
    void defaultsPass() {
        assertThatCode(() -> validator(new OtpConcurrencyProperties(), new RouteSearchDeadlineProperties())
                .validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("otpMs + 여유가 lease 를 넘으면 기동을 막는다 — odsayMs 만 보던 예전 검증은 놓쳤다")
    void otpBudgetAboveLeaseFails() {
        OtpConcurrencyProperties concurrency = new OtpConcurrencyProperties();
        concurrency.setLeaseMs(8000);
        RouteSearchDeadlineProperties routeSearch = new RouteSearchDeadlineProperties();
        routeSearch.setOtpMs(8000);

        assertThatThrownBy(() -> validator(concurrency, routeSearch).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-ms(8000)");
    }

    private OtpConcurrencyConfigValidator validator(OtpConcurrencyProperties concurrency,
                                                    RouteSearchDeadlineProperties routeSearch) {
        return new OtpConcurrencyConfigValidator(concurrency, routeSearch, new JourneyRerouteProperties());
    }
}
