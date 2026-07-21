package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 사용자별 검색 이력 저장 설정.
 *
 * <p>{@code maxPerUser} 는 유저당 보관 상한(cap N). executor 는 저장을 응답 경로에서 분리하는
 * 전용 bounded 풀 파라미터다. 큐를 무한정 키우지 않는다 — 포화는 숨기지 않고 drop 으로 드러낸다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "route.search.history")
public class RouteSearchHistoryProperties {

    /** 유저당 보관할 최근 이력 수. 초과분은 워커가 trim 으로 물리삭제한다. */
    private int maxPerUser = 50;

    private Executor executor = new Executor();

    @Getter
    @Setter
    public static class Executor {
        /** 상주 워커 스레드 수. */
        private int coreSize = 2;
        /** 최대 워커 스레드 수. */
        private int maxSize = 4;
        /** 대기열 길이. 초과 submit 은 거절(AbortPolicy)돼 drop 된다. */
        private int queueCapacity = 500;
    }
}
