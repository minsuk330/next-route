package watoo.grd.nextroute.application.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 버스 도착 알림 스케줄러/디스패치 설정. 발송은 항상 outbox 경로(polling=enqueue, relay=send). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notification.bus-arrival")
public class BusArrivalAlertProperties {

    /** 스케줄러 활성화. */
    private boolean enabled = true;

    /** 폴링(=enqueue) 주기(ms). polling 은 Toss 를 호출하지 않으므로 고빈도 가능. */
    private long pollIntervalMs = 1000;

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

    /** 사이클당 userKey enqueue cap(한 폴링 사이클에서 동일 유저 과다 적재 방지). */
    private int perUserCycleCap = 5;

    /** transient 재시도 백오프(초). */
    private int backoffSeconds = 60;

    // ── outbox relay ─────────────────────────────────────────────────────────
    /**
     * relay fallback sweep 주기(ms). 평소엔 enqueue afterCommit wake 로 즉시 발송하고, wake 신호가 없는
     * 경우(재시도 row backoff 복귀, 신호 유실, 재시작)만 이 주기로 재스캔한다. relay 루프의 await timeout.
     */
    private long outboxSweepIntervalMs = 1000;
    /** relay 스캔당 처리 상한. */
    private int outboxRelayBatchSize = 200;
    /** relay 발송 병렬도(Step C). claim 은 이미 원자(SKIP LOCKED)라 느린 Toss I/O 만 팬아웃. ≤1 이면 인라인(Step B 동치). */
    private int outboxRelayWorkers = 4;
    /** drain-until-empty 무한 점유 방어: 한 번 깨어날 때 최대 배치 수. 초과 시 await 로 yield. */
    private int outboxRelayMaxBatches = 1000;
    /** drain-until-empty 무한 점유 방어: 한 번 깨어날 때 최대 소요(ms). 초과 시 await 로 yield. */
    private long outboxDrainMaxMillis = 5000;
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
}
