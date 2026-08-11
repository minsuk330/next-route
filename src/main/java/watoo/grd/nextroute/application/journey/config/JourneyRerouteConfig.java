package watoo.grd.nextroute.application.journey.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import watoo.grd.nextroute.application.journey.port.out.RerouteEligibilityPolicy;

import java.util.Optional;

/**
 * 재탐색의 기본 배선.
 *
 * <p>기본값이 <b>안전한 방향</b>이다. 트리거(①)가 아직 없는 동안 적격성 판정을 허용으로 두면
 * 아무 제한 없이 외부 검색이 돈다.
 */
@Configuration
public class JourneyRerouteConfig {

    /**
     * 항상 거절하는 적격성 판정. ①이 실 구현을 빈으로 올리면 자동으로 대체된다.
     *
     * <p>{@code disabled} 라는 사유는 지표 태그로도 쓰인다 — 이 값이 전부라면 아직 트리거가
     * 붙지 않았다는 뜻이다.
     */
    @Bean
    @ConditionalOnMissingBean(RerouteEligibilityPolicy.class)
    public RerouteEligibilityPolicy disabledRerouteEligibilityPolicy() {
        return (journeyId, transferIndex, now) -> Optional.of("disabled");
    }
}
