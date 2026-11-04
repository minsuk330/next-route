package watoo.grd.nextroute.application.arrival.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 경기·TAGO 버스 도착 조회를 서버 사이에서 공유하는 설정(bus-multi-instance PR-4). 기본 꺼짐 — 꺼지면 어댑터는 기존
 * JVM 캐시·JVM 세마포어·JVM 차단기 경로 그대로다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "regional-bus.shared")
public class RegionalBusSharedProperties {

    private boolean enabled = false;
    /** 공유 캐시 수명(초). 기존 JVM 캐시와 같은 15초. */
    private long cacheTtlSeconds = 15;
    /** 경기 공개 조회 일일 몫(Redis 버킷 gg-arrival). 공급자 10,000 − 재탐색 버킷 기본 5,000(journey.reroute.board.quota-gyeonggi). */
    private int gyeonggiDailyLimit = 5000;
    /** TAGO 동시 호출 상한 — 서버 합산(포털 키 동시 세션 30 을 GBIS·TAGO·지하철 TAGO 가 공유). */
    private int tagoMaxConcurrent = 8;
    /** TAGO 자리 lease(ms). 가장 긴 호출(connect 3s + read 5s + 자리 대기 1s) 에 본문 느린 수신 여유를 더해 15초. */
    private long tagoLeaseMs = 15000;
    /** 서버 사이 single-flight 잠금 수명(ms). 지역 어댑터는 기한 인자가 없어 공급자별 상수. */
    private long gyeonggiLockTtlMs = 9000;
    private long tagoLockTtlMs = 10000;
    private long pollMs = 50;
    private long resultTtlMs = 2000;
}
