package watoo.grd.nextroute.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;

import java.util.concurrent.Semaphore;

/**
 * 평가기가 동시에 외부 provider 를 부를 수 있는 수를 제한하는 Semaphore.
 *
 * <p>버스 API 호출은 검색 요청과 공용 Semaphore({@code busSearchConcurrencyLimiter}, permit 8)를
 * 나눠 쓴다. 지금 평가기가 permit 을 최대 4개까지만 쥐는 이유는 워커 스레드가 4개이기 때문이지,
 * 그렇게 정한 코드가 있어서가 아니다. 처리량을 올리려고 {@code poolSize} 를 키우는 순간
 * <b>사용자 검색 몫이 조용히 줄어든다.</b>
 *
 * <p>그래서 평가기 앞에 자기 몫을 세는 Semaphore 를 하나 더 둔다. 공용 permit 수는 그대로이므로
 * provider 입장에서 총 동시 호출 수는 늘지 않는다.
 *
 * <p><b>worker 프로필로 제한하지 않는다.</b> 이 Semaphore 를 주입받는 {@code JourneyEvaluationService}
 * 에 프로필 조건이 없어서, 조건을 걸면 다른 프로필에서 주입 실패로 부팅이 깨진다. 평가기가 꺼져
 * 있으면 permit 을 아무도 쓰지 않을 뿐이다.
 */
@Configuration
public class JourneyEvaluatorProviderLimiterConfig {

    /** fair 로 두는 이유 — 먼저 기다린 여정이 먼저 나간다. 특정 여정이 계속 밀리지 않는다. */
    @Bean("journeyEvaluatorProviderLimiter")
    public Semaphore journeyEvaluatorProviderLimiter(JourneyEvaluatorProperties properties) {
        return new Semaphore(properties.getProviderPermits(), true);
    }
}
