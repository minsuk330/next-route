package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 재탐색의 운영값.
 *
 * <p><b>세 묶음이 한 파일에 있되 자리는 갈라 둔다.</b> 저장소 몫(TTL·후보 상한)은 최상위,
 * "언제 찾아볼지"는 {@link Trigger}, "무엇을 후보로 올릴지"는 {@link RouteOption} 이다. 판정
 * 기준과 저장소 운영값이 <b>같은 자리에</b> 섞이면 부하 조정이 곧 재탐색 계약 변경이 된다.
 *
 * <p>(예전 주석은 "cooldown·최소 개선폭은 여기 없다"고 적었지만, ①이 {@code trigger.cooldown} 을
 * ③이 {@code route-option.*} 을 이 파일에 넣었다. 한 접두어의 값을 두 파일이 나눠 갖는 쪽이
 * 오히려 어디를 고쳐야 할지 못 찾게 만든다 — 대신 중첩 클래스로 소유를 가른다.)
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

    private final RouteOption routeOption = new RouteOption();

    private final RemainingPath remainingPath = new RemainingPath();

    /**
     * "환승 정류장에서 목적지까지 다른 경로를 찾을지"의 운영값(Phase 6 ④).
     *
     * <p>{@link RouteOption} 과 값의 뜻은 같지만 <b>키를 따로 둔다.</b> ③은 저장된 노선 후보만
     * 보고 ④는 유료 경로 검색(ODsay)을 부르므로, 요금이 걱정될 때 조여야 하는 쪽이 서로 다르다.
     * 한 값을 나눠 쓰면 ③을 넓히려다 ④의 요금이 함께 늘어난다.
     */
    @Getter
    @Setter
    public static class RemainingPath {

        /** 이만큼 빨라지지 않으면 제안하지 않는다. ③과 같은 값이지만 키는 별도다. */
        private int minimumEtaGainSeconds = 300;

        /**
         * 검색이 준 경로 중 <b>실제로 평가할</b> 수 상한.
         *
         * <p>2 인 이유는 시간이다. 경로 하나마다 승차 예측이 한 번 나가고, 그 앞에 검색(최대 2초)과
         * 기준선(최대 1.5초)이 이미 있다.
         */
        private int maxPaths = 2;

        /** 후보 평가에 쓸 수 있는 누적 시간. 넘으면 이미 평가한 것으로 제안을 만든다. */
        private long evaluationBudgetMs = 3000;

        /** 경로 검색 한 번에 쓸 수 있는 시간. 캐시가 맞으면 쓰이지 않는다. */
        private long searchBudgetMs = 2000;

        /**
         * 검색 응답을 Redis 에 들고 있는 시간.
         *
         * <p>여정과 무관한 <b>공유 키</b>(정류장→목적지)라 다른 사용자의 요청도 이 캐시를 맞힌다.
         * 10분인 이유는 그 사이 경로 <b>구조</b>가 바뀌지 않기 때문이다 — 바뀌는 것은 차가 언제
         * 오느냐이고, 그것은 캐시하지 않고 매번 예측한다.
         */
        private Duration cacheTtl = Duration.ofMinutes(10);
    }

    /**
     * "같은 정류장의 다른 노선을 후보로 올릴지"의 운영값(Phase 6 ③).
     *
     * <p>여기 값이 전부 <b>요금과 대기시간</b>에 대한 것이다. 후보 하나마다 실시간·ML 조회가
     * 나가고, 그 요청을 사용자가 화면에서 기다린다.
     */
    @Getter
    @Setter
    public static class RouteOption {

        /**
         * 이만큼 빨라지지 않으면 제안하지 않는다.
         *
         * <p>낮추면 "5초 빨라집니다" 같은 제안이 나가는데, 그 차이는 예측 오차보다 작다. 사용자가
         * 뛰어서 갈아탈 이유가 되지 못하는 값을 제안으로 만들면 제안 전체의 신뢰가 깎인다.
         */
        private int minimumEtaGainSeconds = 300;

        /**
         * 한 요청에서 예측을 물어볼 <b>대안</b> 노선 수 상한. 기준선은 여기 포함되지 않는다.
         *
         * <p>3 인 이유는 시간이다. 후보 하나가 최대 1500ms 이므로 5 면 기준선 포함 9초가 되는데,
         * 사용자가 화면에서 기다리는 요청이다.
         */
        private int maxEvaluations = 3;

        /**
         * <b>대안</b> 평가에 쓸 수 있는 누적 시간. 넘으면 남은 후보를 건너뛰고 이미 평가한
         * 것으로 제안을 만든다 — 부분 결과가 빈손보다 낫다.
         *
         * <p>남은 예산은 <b>다음 호출의 상한으로도</b> 쓴다. 부르기 전에만 확인하면 마감 직전에
         * 시작한 호출이 제 몫을 다 써서 예산을 넘긴다.
         *
         * <p>기준선 평가는 이 예산에서 빼지 않는다. 기준선이 없으면 개선폭을 잴 수 없어 어차피
         * 빈 결과이므로 아껴 봐야 아무것도 못 낸다. 그래서 <b>요청 하나의 최악 대기 시간은
         * {@code journey.evaluator.predict-deadline-ms}(기준선 1회) + 이 값</b>이다.
         */
        private long evaluationBudgetMs = 3000;
    }

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
        if (routeOption.getMinimumEtaGainSeconds() <= 0) {
            // 0 이면 "1초 빨라집니다"가 제안이 된다. 예측 오차보다 작은 차이다.
            throw new IllegalStateException(
                    "journey.reroute.route-option.minimum-eta-gain-seconds 는 1 이상이어야 한다");
        }
        if (routeOption.getMaxEvaluations() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.route-option.max-evaluations 는 1 이상이어야 한다");
        }
        if (routeOption.getEvaluationBudgetMs() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.route-option.evaluation-budget-ms 는 1 이상이어야 한다");
        }
        if (remainingPath.getMinimumEtaGainSeconds() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.remaining-path.minimum-eta-gain-seconds 는 1 이상이어야 한다");
        }
        if (remainingPath.getMaxPaths() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.remaining-path.max-paths 는 1 이상이어야 한다");
        }
        if (remainingPath.getEvaluationBudgetMs() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.remaining-path.evaluation-budget-ms 는 1 이상이어야 한다");
        }
        if (remainingPath.getSearchBudgetMs() <= 0) {
            // 0 이하면 OdSayApiPort 계약상 "부르지 말라"는 뜻이라 이 칸이 통째로 죽는다.
            throw new IllegalStateException(
                    "journey.reroute.remaining-path.search-budget-ms 는 1 이상이어야 한다");
        }
        if (remainingPath.getCacheTtl() == null || remainingPath.getCacheTtl().isZero()
                || remainingPath.getCacheTtl().isNegative()) {
            // Redis 는 TTL 0 을 거부한다. 만료 없는 키가 남으면 그 구간이 영영 옛 경로를 돌려준다.
            throw new IllegalStateException("journey.reroute.remaining-path.cache-ttl 은 양수여야 한다");
        }
    }
}
