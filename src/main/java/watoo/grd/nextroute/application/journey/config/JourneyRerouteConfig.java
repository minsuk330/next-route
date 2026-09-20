package watoo.grd.nextroute.application.journey.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import watoo.grd.nextroute.application.journey.port.out.RerouteEligibilityPolicy;

import java.util.Optional;

/**
 * 재탐색의 기본 배선.
 *
 * <p>기본값이 <b>안전한 방향</b>이다. 판정이 없는 동안 적격성을 허용으로 두면 아무 제한 없이
 * 외부 검색이 돈다.
 */
@Configuration
public class JourneyRerouteConfig {

    /**
     * 항상 거절하는 적격성 판정.
     *
     * <p><b>운영 배선에서는 더 이상 쓰이지 않는다.</b> 실 판정
     * ({@code WindowCooldownEligibilityPolicy})이 빈으로 올라오면서 {@link ConditionalOnMissingBean} 이 이 빈을 만들지 않는다. 남겨 두는
     * 이유는 <b>기본값의 방향</b>이다 — 실 구현을 빼거나 프로필로 걸러 내는 배선에서 이것이 없으면
     * 주입이 실패하는 대신 판정 없이 외부 검색이 도는 쪽으로 흐를 수 있다.
     *
     * <p>{@code disabled} 라는 사유는 지표 태그로도 쓰인다 — 이 값이 전부라면 실 판정이 배선되지
     * 않았다는 뜻이다.
     */
    @Bean
    @ConditionalOnMissingBean(RerouteEligibilityPolicy.class)
    public RerouteEligibilityPolicy disabledRerouteEligibilityPolicy() {
        return (journey, transferIndex, now) -> Optional.of("disabled");
    }
}
