package watoo.grd.nextroute.common.config;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.common.observability.SyntheticRequest;

/**
 * {@code http.server.requests} 에 {@code synthetic="true|false"} 를 붙인다.
 *
 * <p>합성 모니터링 호출(헤더 {@link SyntheticRequest#HEADER})을 대시보드·주간 요약의 요청 통계에서
 * {@code synthetic!="true"} 로 뺄 수 있게 한다. 값이 둘뿐이라 시계열은 많아야 두 배다.
 * Spring Boot 가 이 빈을 MVC 관측 규칙으로 집어 간다(WebMvcObservationAutoConfiguration).
 */
@Component
public class SyntheticAwareServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    static final String KEY = "synthetic";

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        boolean synthetic = SyntheticRequest.isSynthetic(context.getCarrier());
        return super.getLowCardinalityKeyValues(context).and(KeyValue.of(KEY, String.valueOf(synthetic)));
    }
}
