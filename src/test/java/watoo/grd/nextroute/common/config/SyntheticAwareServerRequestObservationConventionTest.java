package watoo.grd.nextroute.common.config;

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import watoo.grd.nextroute.common.observability.SyntheticRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticAwareServerRequestObservationConventionTest {

    private final SyntheticAwareServerRequestObservationConvention convention =
            new SyntheticAwareServerRequestObservationConvention();

    private String syntheticTag(MockHttpServletRequest request) {
        var context = new ServerRequestObservationContext(request, new MockHttpServletResponse());
        return convention.getLowCardinalityKeyValues(context).stream()
                .filter(kv -> kv.getKey().equals("synthetic"))
                .map(KeyValue::getValue)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 합성_헤더가_있으면_true() {
        var request = new MockHttpServletRequest("GET", "/api/route/search");
        request.addHeader(SyntheticRequest.HEADER, "1");

        assertThat(syntheticTag(request)).isEqualTo("true");
    }

    @Test
    void 헤더가_없으면_false() {
        assertThat(syntheticTag(new MockHttpServletRequest("GET", "/api/route/search"))).isEqualTo("false");
    }

    @Test
    void 기본_태그는_그대로_남는다() {
        var context = new ServerRequestObservationContext(
                new MockHttpServletRequest("GET", "/api/route/search"), new MockHttpServletResponse());

        assertThat(convention.getLowCardinalityKeyValues(context).stream().map(KeyValue::getKey))
                .contains("method", "status", "uri", "outcome", "exception", "synthetic");
    }
}
