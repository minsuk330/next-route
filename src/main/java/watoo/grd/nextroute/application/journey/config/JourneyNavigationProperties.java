package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Journey lease의 초기 운영값. 환경변수로 조정하되 잘못된 음수·역전 설정은 시작 시 거절한다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.navigation")
public class JourneyNavigationProperties {

    private long guidanceLeaseSeconds = 180;
    private long expirationGraceSeconds = 120;
    private long subwayProtectionBufferSeconds = 300;
    private long subwayProtectionCapSeconds = 3600;

    /**
     * lease 쓰기를 합치는 간격.
     *
     * <p>progress 는 15초(하차 임박 5초)마다 들어오는데 그때마다 여정 행을 쓰면 hot row 가 된다.
     * lease 가 180초이므로 60초에 한 번만 늘려도 만료되지 않는다.
     */
    private long leaseWriteCoalesceSeconds = 60;

    @PostConstruct
    void validate() {
        if (guidanceLeaseSeconds <= 0 || expirationGraceSeconds < 0
                || subwayProtectionBufferSeconds < 0 || subwayProtectionCapSeconds <= 0
                || leaseWriteCoalesceSeconds < 0) {
            throw new IllegalStateException("journey.navigation lease 설정은 0 이상의 유효한 값이어야 한다");
        }
        if (leaseWriteCoalesceSeconds >= guidanceLeaseSeconds) {
            throw new IllegalStateException(
                    "lease 쓰기 합치기 간격은 lease 보다 짧아야 한다. 아니면 늘리기 전에 만료된다");
        }
        if (subwayProtectionBufferSeconds > subwayProtectionCapSeconds) {
            throw new IllegalStateException("지하철 보호 buffer는 cap보다 클 수 없다");
        }
    }

    public Duration guidanceLease() {
        return Duration.ofSeconds(guidanceLeaseSeconds);
    }

    public Duration expirationGrace() {
        return Duration.ofSeconds(expirationGraceSeconds);
    }

    public Duration subwayProtectionBuffer() {
        return Duration.ofSeconds(subwayProtectionBufferSeconds);
    }

    public Duration subwayProtectionCap() {
        return Duration.ofSeconds(subwayProtectionCapSeconds);
    }

    public Duration leaseWriteCoalesce() {
        return Duration.ofSeconds(leaseWriteCoalesceSeconds);
    }
}
