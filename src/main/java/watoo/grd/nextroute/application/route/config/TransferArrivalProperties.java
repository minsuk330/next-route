package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 환승 도착예측 기능 토글 + 운영 하드닝(PR4) 파라미터.
 * enabled=false면 fan-out 전체 차단 (REALTIME 포함).
 * ML은 ml.predictor.enabled로 별도 제어 가능.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "route.transfer-arrival")
public class TransferArrivalProperties {
    private boolean enabled = false;

    /** 검색 1건 전체 enrich 상한(ms). 초과 시 미처리 버스 승차는 status=ERROR. 0 이하면 무제한. */
    private long deadlineMs = 1500;

    /** 검색용 버스 API Redis 캐시 TTL(초). 같은 stopId/routeId 중복 호출 억제. 0 이하면 캐시 비활성. */
    private long cacheTtlSeconds = 15;

    /** 검색 fan-out provider 동시 호출 상한(전역 Semaphore). 0 이하면 무제한. */
    private int maxConcurrentExternalCalls = 8;

    /** 동시 호출 슬롯 획득 대기 상한(ms). 초과 시 그 호출은 빈 결과로 생략. */
    private long externalCallAcquireMs = 500;

    /** 검색 1건이 외부 버스 API를 호출할 수 있는 최대 횟수. 초과분 승차는 status=NONE. 0 이하면 무제한. */
    private int maxExternalCallsPerSearch = 12;

    /**
     * ETA 합성(P2-E) 탑승 여유(초). 실시간/드리프트로 재평가할 때 "선택편 출발 ≥ userReadyAt + 이 값" 이어야
     * 탈 수 있다고 본다. 정적(OTP 그대로) 편은 OTP 가 이미 환승여유를 계획했으므로 이 buffer 를 재적용하지 않는다
     * (중복 가산 금지). waitSeconds 는 boardAt-userReadyAt 로 이 여유를 이미 포함한다.
     */
    private long boardingBufferSeconds = 60;

    /**
     * ETA 합성 실시간 값 freshness 상한(초). observedAt 이 계산시각보다 이 값 넘게 과거면 stale 로 보고
     * 정적으로 fallback 한다(단 운행취소는 fallback 하지 않고 후보 제외).
     */
    private long realtimeStaleAfterSeconds = 90;

    /**
     * 앞 버스 결과가 없어 이 정류장 도착 시각을 모르는 버스도 실시간 도착을 조회해 {@code ARRIVAL_ONLY}(다음 도착만, 대기 없음)로
     * 싣는다(search-regional-bus-wait PR-2). 외부 호출이 는다 — 검색당 호출 상한·기한은 그대로.
     */
    private boolean arrivalOnlyEnabled = false;

    /** 경기(GBIS)·TAGO 버스도 검색에서 실시간 도착을 조회한다(search-regional-bus-wait PR-2). */
    private boolean regionalEnabled = false;

    /** 검색이 경기·TAGO 조회를 기다리는 상한(ms). 남은 보강 기한과 짧은 쪽. 넘으면 포기(호출은 공유 캐시를 채운다). */
    private long regionalTimeoutMs = 1200;

    /**
     * 검색 1건의 경기·TAGO 조회 상한(정류장·노선 단위, 중복 제거 뒤). 0 이하 = 상한 없음 — 결과의 지역 버스를 모두 묻는다
     * (경로 수가 상한, 2026-11-09 사용자 결정). 몫·풀·기한은 그대로 적용된다.
     */
    private int regionalMaxCallsPerSearch = 0;

    /** 공급자별 검색 전용 풀 스레드 수(넘치면 큐 4배, 큐가 차면 LIMITED). TAGO 서버 합산 자리를 검색이 독점하지 않게. */
    private int regionalMaxConcurrent = 4;

    /** 검색 전용 경기 도착 일일 몫(호출 전 셈). 공급자 10,000 = 재탐색 5,000 + 공개 5,000(어댑터가 miss 때 셈) 안. */
    private int regionalGyeonggiDailyLimit = 2000;

    /** 검색 전용 TAGO 도착 일일 몫(호출 전 셈). 공급자 한도 500,000/일(2026-11-09 사용자 확인). */
    private int regionalTagoDailyLimit = 100000;
}
