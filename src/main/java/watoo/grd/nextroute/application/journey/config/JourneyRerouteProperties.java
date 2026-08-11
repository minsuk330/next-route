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

    private final Trigger trigger = new Trigger();

    /**
     * "언제 찾아볼지"의 운영값(Phase 6 ①).
     *
     * <p><b>{@link JourneyRerouteProperties#enabled} 와 다른 스위치다.</b> 그쪽은 제안 기능
     * 자체의 플래그(기본 꺼짐)이고, 방아쇠는 <b>기능이 꺼져 있는 동안 발생률을 재는 것</b>이
     * 일이다. 두 스위치를 묶으면 재탐색을 켜기 전까지 아무것도 측정하지 못한다.
     */
    @Getter
    @Setter
    public static class Trigger {

        /**
         * 기본 켜짐. 방아쇠는 지표·로그만 남기고 제안도 외부 호출도 만들지 않는다(shadow).
         *
         * <p>켜 두는 것이 기본값인 이유 — 하루에 몇 번 도는지 모르는 채로 후보 생성기(③)를
         * 켜면 요금이 먼저 온다. 그래도 끄는 스위치는 남긴다.
         */
        private boolean enabled = true;

        /**
         * 문이 열리는 폭. 환승 준비 시각까지 이 시간 안이면 찾아봐도 되는 구간으로 본다.
         *
         * <p>이것만으로는 아무 일도 일어나지 않는다 — 실제로 찾게 만드는 것은 방아쇠다.
         */
        private int decisionWindowSeconds = 600;

        /** 같은 환승에서 다시 찾기까지의 최소 간격. 버튼 연타가 외부 검색 연타가 되면 안 된다. */
        private Duration cooldown = Duration.ofMinutes(2);

        public Duration decisionWindow() {
            return Duration.ofSeconds(decisionWindowSeconds);
        }
    }

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
        if (trigger.getDecisionWindowSeconds() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.trigger.decision-window-seconds 는 1 이상이어야 한다");
        }
        if (trigger.getCooldown() == null || trigger.getCooldown().isNegative()) {
            // 0 은 허용한다 - cooldown 을 사실상 끄고 원시 발생률만 보고 싶을 때가 있다.
            throw new IllegalStateException("journey.reroute.trigger.cooldown 은 음수일 수 없다");
        }
    }
}
