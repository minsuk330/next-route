package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 검색 경로의 실시간 버스 조회 설정.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "route.search.bus")
public class SearchTimeBusProperties {

    /**
     * 같은 키의 진행 중인 provider 호출에 합류했을 때 기다릴 최대 시간.
     * 버스 조회는 이미 짧은 timeout(~1.5s)을 쓰므로 그보다 조금 길게 잡아 owner 를 기다릴 여지를 준다.
     * 이 시간을 넘기면 조회를 못 한 것으로 처리(LIMITED)하고 기본 경로는 그대로 반환한다.
     */
    private long joinWaitMs = 1800;

    /** 서버 사이 single-flight(PR-2). 기본 꺼짐 — 켜면 캐시 miss 때 Redis SET NX 로 서버들 중 한 대만 공급자를 부른다. */
    private final CrossInstance crossInstance = new CrossInstance();

    @Getter
    @Setter
    public static class CrossInstance {
        private boolean enabled = false;
        /** 대기자가 결과 키를 다시 보는 간격. */
        private long pollMs = 50;
        /** 리더가 남기는 결과 키 수명. 대기자의 최대 대기(joinWaitMs)보다 길어야 한다. */
        private long resultTtlMs = 2000;
        /** 호출 기한이 있는 요청의 잠금 수명 = 기한 + 이 여유(차단기·몫 Redis 왕복 몫). */
        private long lockMarginMs = 1000;
        /**
         * 기한 없는 요청(Long.MAX_VALUE)의 잠금 수명이자 대기자의 대기 상한. 세마포어 대기 500 + connect 1500 + read 1500 +
         * 차단기·몫 Redis 왕복 + 여유. 갱신하지 않으므로 넘기면 두 번째 리더가 한 번 더 부른다(정합성 문제 아님).
         */
        private long unboundedLockTtlMs = 6000;
    }
}
