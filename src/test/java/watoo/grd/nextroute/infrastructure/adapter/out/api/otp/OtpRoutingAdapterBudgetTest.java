package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OtpRoutingAdapterBudgetTest {

    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final OtpRoutingAdapter adapter = new OtpRoutingAdapter(
            RestClient.builder(), new ObjectMapper(), new OtpPlanConnectionMapper(),
            new OtpRoutingProperties(), Clock.fixed(now, ZoneOffset.UTC),
            new watoo.grd.nextroute.application.route.mapping.OtpLegCoverageResolver(
                    org.mockito.Mockito.mock(watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver.class)));

    @Test
    @DisplayName("provider() == OTP")
    void provider() {
        assertThat(adapter.provider()).isEqualTo(RoutingProvider.OTP);
    }

    @Test
    @DisplayName("예산 소진 시 HTTP 없이 TIMEOUT 실패 반환(예외 아님)")
    void noBudgetReturnsTimeout() {
        RoutingQuery q = new RoutingQuery(126.9789, 37.5693, "청계천", 126.9816, 37.4765, "사당",
                now, Set.of(), null, false, "v1", RoutingProvider.OTP, null);
        RoutingDeadline expired = new RoutingDeadline(now); // 이미 만료
        RoutingOutcome outcome = adapter.search(q, expired);
        assertThat(outcome.success()).isFalse();
        assertThat(((RoutingFailure) outcome).kind()).isEqualTo(RoutingFailure.Kind.TIMEOUT);
    }
}
