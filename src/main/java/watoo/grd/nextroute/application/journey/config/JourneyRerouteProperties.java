package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 재탐색 제안의 운영값.
 *
 * <p>cooldown·최소 개선폭은 여기 없다. 그것은 트리거(①)와 후보 생성기(③)의 소유다 — 판정 기준과
 * 저장소의 운영값을 한 곳에 섞으면 부하 조정이 곧 재탐색 계약 변경이 된다
 * ({@link JourneyExpiryProperties} 가 같은 이유로 판정 기준을 갖지 않는다).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.reroute")
public class JourneyRerouteProperties {

    /**
     * 기능 플래그. 기본 꺼짐.
     *
     * <p>꺼져 있으면 조회는 항상 빈 결과, 요청은 {@code disabled} 로 거절된다. 이 PR 은 트리거도
     * 후보 생성기도 없어 켜도 아무 일이 없지만, 스위치는 그것들이 붙기 <b>전에</b> 있어야 한다.
     */
    private boolean enabled = false;

    /** 제안 유효시간. 2분을 넘기면 근거로 삼은 실시간 값이 이미 다른 이야기를 한다. */
    private Duration proposalTtl = Duration.ofMinutes(2);

    /** 화면에 보이는 후보 수 상한. 넘게 들어오면 잘라 저장한다. */
    private int maxCandidates = 3;

    private final Expiry expiry = new Expiry();

    @Getter
    @Setter
    public static class Expiry {

        /**
         * sweep 주기. TTL(2분)의 절반이라 자리를 오래 막지 않는다.
         *
         * <p>lease 만료(30초)보다 느슨해도 되는 이유는 여기서 늦는 대가가 "새 제안이 조금 늦게
         * 생긴다"뿐이기 때문이다.
         */
        private long intervalMs = 60_000;

        /** 한 번에 접는 제안 수. {@link JourneyExpiryProperties} 와 같은 자리에서 출발한다. */
        private int batchSize = 200;

        /** 한 tick 의 상한. 없으면 밀린 만큼 한 tick 이 끝없이 돈다. */
        private int maxSweepsPerTick = 5;
    }

    @PostConstruct
    void validate() {
        if (maxCandidates <= 0) {
            throw new IllegalStateException("journey.reroute.max-candidates 는 1 이상이어야 한다");
        }
        if (proposalTtl == null || proposalTtl.isZero() || proposalTtl.isNegative()) {
            throw new IllegalStateException("journey.reroute.proposal-ttl 은 양수여야 한다");
        }
        if (expiry.getBatchSize() <= 0 || expiry.getMaxSweepsPerTick() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.expiry 의 batch-size·max-sweeps-per-tick 은 1 이상이어야 한다");
        }
    }
}
