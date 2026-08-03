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

    @PostConstruct
    void validate() {
        if (guidanceLeaseSeconds <= 0 || expirationGraceSeconds < 0
                || subwayProtectionBufferSeconds < 0 || subwayProtectionCapSeconds <= 0) {
            throw new IllegalStateException("journey.navigation lease 설정은 0 이상의 유효한 값이어야 한다");
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
}
