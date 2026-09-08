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
}
