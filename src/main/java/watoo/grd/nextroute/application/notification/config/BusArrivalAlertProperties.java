package watoo.grd.nextroute.application.notification.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 버스 도착 알림 스케줄러/디스패치 설정. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notification.bus-arrival")
public class BusArrivalAlertProperties {

    /** 스케줄러 활성화. */
    private boolean enabled = false;

    /** 폴링 주기(ms). */
    private long pollIntervalMs = 30000;

    /** "곧 도착" 임계(초). predictTime ≤ 이 값이면 발송. */
    private int thresholdSeconds = 120;

    /** 구독 TTL(분). 초과 시 EXPIRED. */
    private int ttlMinutes = 60;

    /** 사이클당 디스패치 로드 상한. */
    private int batchSize = 200;

    /** 고착 PROCESSING reclaim 기준(분). */
    private int claimTimeoutMinutes = 3;

    /** transient 실패 최대 시도. 초과 시 FAILED. */
    private int maxAttempts = 3;

    /** 사이클당 userKey 발송 cap(토스 분당 10회 제한 대응). */
    private int perUserCycleCap = 5;

    /** transient 재시도 백오프(초). */
    private int backoffSeconds = 60;

    // ── outbox (Step B) ──────────────────────────────────────────────────────
    /** outbox 발송 분리 활성. 기본 off. on 이면 polling 은 enqueue 만(Toss 미호출), 발송은 relay. */
    private boolean outboxEnabled = false;
    /** relay 스캔 주기(ms). */
    private long outboxRelayIntervalMs = 15000;
    /** relay 스캔당 처리 상한. */
    private int outboxRelayBatchSize = 200;
    /** relay 발송 병렬도(Step C). claim 은 이미 원자(SKIP LOCKED)라 느린 Toss I/O 만 팬아웃. ≤1 이면 인라인(Step B 동치). */
    private int outboxRelayWorkers = 4;
    /** DEAD requeue 누적 상한. */
    private int maxRequeue = 3;

    /**
     * 발송 조건 판단 시 실시간 관측 캐시 재사용(Step E). on 이면 그룹 도착조회를 캐시 우선(miss 시 provider+prime)으로
     * 수행해 collector/검색과 캐시를 공유, busApiCalls 를 줄인다. off 면 항상 provider 직접 호출(기존 동작).
     */
    private boolean cacheReadEnabled = false;

    private final ActiveHours activeHours = new ActiveHours();

    @Getter
    @Setter
    public static class ActiveHours {
        private String start = "05:00";
        private String end = "01:00";
    }

    /**
     * 토스 분당 10회/userKey 제한 보장: cap × (분당 사이클 수) ≤ 10.
     * 설정이 한도를 깨면 부팅 차단(fail-fast).
     */
    @PostConstruct
    void validateRate() {
        double cyclesPerMinute = 60000.0 / pollIntervalMs;
        double maxPerMinute = perUserCycleCap * cyclesPerMinute;
        if (maxPerMinute > 10.0) {
            throw new IllegalStateException(
                    "notification.bus-arrival: userKey 분당 발송 한도 초과 가능 ("
                            + String.format("%.1f", maxPerMinute)
                            + " > 10). per-user-cycle-cap 또는 poll-interval-ms 조정 필요.");
        }
    }
}
