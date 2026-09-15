package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OTP 글로벌 동시성 gate 설정. 상한(permit)·lease TTL·재시도 간격을 운영값으로 노출한다.
 *
 * <p><b>lease TTL 계약</b>: {@link #leaseMs} 는 반드시 OTP 호출 최대 예산보다 커야 한다. 작으면
 * 유효 호출 중인 permit 이 만료·재할당되어 상한이 무너진다. 이 관계는 {@code OtpConcurrencyConfigValidator}
 * 가 기동 시 강제한다({@code leaseMs ≥ max(api, worker 예산) + safetyMarginMs}).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "routing.otp.concurrency")
public class OtpConcurrencyProperties {

    /** 전역 동시 OTP 호출 상한(api+worker 합산). best-effort — 하드 상한은 OTP 컨테이너 mem_limit. */
    private int permits = 4;

    /**
     * permit lease TTL(ms). holder 가 crash 로 release 를 못 타도 이 시간 뒤 ZSET score 만료로
     * 자동 회수된다(leak 방지). OTP 호출 최대 예산 + 여유보다 커야 한다(기동 검증).
     */
    private long leaseMs = 8000;

    /** acquire 재시도 polling 간격(ms). 실제 sleep 은 {@code min(이 값, 남은 예산)}. */
    private long retryIntervalMs = 20;

    /** lease TTL 검증 여유(ms). {@code leaseMs ≥ max(예산) + safetyMarginMs} 를 요구한다. */
    private long safetyMarginMs = 2000;
}
