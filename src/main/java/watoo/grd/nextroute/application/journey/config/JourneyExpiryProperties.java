package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 만료 처리 배치의 운영값.
 *
 * <p>만료 판정 기준(lease 3분 + 유예 2분)은 {@link JourneyNavigationProperties} 가 가진다. 여기
 * 있는 값은 "얼마나 자주, 한 번에 몇 건씩 훑을지"뿐이다. 판정 기준과 훑는 속도를 한 곳에 섞으면
 * 부하 조정이 곧 만료 계약 변경이 된다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.navigation.expiry")
public class JourneyExpiryProperties {

    /** 끌 수 있어야 한다. 만료가 잘못 접는 상황에서 배포를 기다릴 수는 없다. */
    private boolean enabled = true;

    /**
     * 한 번에 고르는 여정 수.
     *
     * <p>크게 잡으면 한 트랜잭션이 오래 걸리고 다른 워커가 건너뛸 행이 늘어난다. 작게 잡으면
     * 밀린 만큼 여러 번 돌아야 한다. 200 은 outbox relay 배치와 같은 자리에서 출발한 값이고,
     * 실제 값은 ⑧ 부하 측정에서 정한다.
     */
    private int batchSize = 200;

    /**
     * 한 tick 에 반복할 최대 횟수.
     *
     * <p>대상이 배치 크기만큼 나오면 아직 더 있다는 뜻이라 이어서 훑는다. 다만 한 tick 이 끝없이
     * 도는 것은 막아야 한다 — 그 사이 다른 잡이 스케줄러 풀을 못 쓴다.
     */
    private int maxSweepsPerTick = 5;

    @PostConstruct
    void validate() {
        if (batchSize <= 0 || maxSweepsPerTick <= 0) {
            throw new IllegalStateException(
                    "journey.navigation.expiry 의 batch-size·max-sweeps-per-tick 은 1 이상이어야 한다");
        }
    }
}
