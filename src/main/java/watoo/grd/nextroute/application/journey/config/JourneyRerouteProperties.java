package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 재탐색의 운영값.
 *
 * <p><b>세 묶음이 한 파일에 있되 자리는 갈라 둔다.</b> 저장소 몫(TTL·후보 상한)은 최상위,
 * "언제 찾아볼지"는 {@link Trigger}, "무엇을 후보로 올릴지"는 {@link RemainingPath}·{@link NextStop}
 * 이다. 판정
 * 기준과 저장소 운영값이 <b>같은 자리에</b> 섞이면 부하 조정이 곧 재탐색 계약 변경이 된다.
 *
 * <p>(예전 주석은 "cooldown·최소 개선폭은 여기 없다"고 적었지만, ①이 {@code trigger.cooldown} 을
 * 이 파일에 넣었다. 한 접두어의 값을 두 파일이 나눠 갖는 쪽이
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

    /**
     * 시뮬레이터 수동 테스트용 계정. <b>기본은 비어 있다</b> — 비어 있으면 아무 동작도 바뀌지 않는다.
     *
     * <p>이 계정의 "다른 경로 찾기" 요청은 시간 창·cooldown·진행 근거 판정과 남은경로 최소 개선폭을
     * 건너뛴다. 시뮬레이터에서는 "환승 10분 이내 + 살아 있는 진행 + 5분 이상 빠른 경로"가 거의
     * 맞지 않아 제안→수락→경로 교체 흐름을 끝까지 볼 수 없었다. 환승 존재·지나간 환승 판정과
     * 수락 재검증은 그대로다 — 그 둘을 풀면 적용할 수 없는 제안이 만들어진다.
     *
     * <p>서버 능동 알림(push)에는 적용하지 않는다. prod 에 넣는 값이므로 본인 계정만 넣는다.
     */
    private Set<Long> testUserIds = new HashSet<>();

    /** {@link #testUserIds} 에 든 계정인가. */
    public boolean isTestUser(Long userId) {
        return userId != null && testUserIds.contains(userId);
    }

    private final Expiry expiry = new Expiry();

    private final Trigger trigger = new Trigger();

    private final RemainingPath remainingPath = new RemainingPath();

    private final NextStop nextStop = new NextStop();

    private final Alert alert = new Alert();

    private final Board board = new Board();

    /**
     * "서버가 먼저 알릴지"의 운영값(journey-reroute-nextstop PR-3).
     *
     * <p>이 블록이 재탐색의 <b>계약을 바꾸는 스위치</b>다. 지금까지 제안은 사용자가 눌러야만
     * 생겼고(pull), 여기를 켜면 서버가 위험을 감지해 스스로 만들어 알린다(push 한 줄 신호).
     * <b>바꾸는 것은 여전히 사용자만 한다</b> — 자동 적용은 없다.
     */
    /**
     * 주기 갱신 보드(#264, reroute-periodic-board).
     *
     * <p><b>기존 키와 값을 나누어 둔다.</b> {@code remaining-path.minimum-eta-gain-seconds}·
     * {@code next-stop.lead-time-sec} 는 사건 기반 경로(요청·알림·목록 거름)가 함께 쓴다. 보드 값으로
     * 바꾸면 플래그가 꺼져 있어도 기존 동작이 바뀐다.
     */
    @Getter
    @Setter
    public static class Board {

        /** 기본 꺼짐. 꺼져 있으면 기존 동작이 그대로여야 한다(모든 PR 의 회귀 조건). */
        private boolean enabled = false;

        /** 이 시간보다 가까운 하차 자리는 말풍선에서 내린다. 기점 여는 기준과 같은 값이다. */
        private int leadTimeSec = 60;

        /** 체인 전부 실시간 근거일 때의 표시 문턱. */
        private int realtimeThresholdSec = 120;

        /** ML 추정이 하나라도 섞였을 때(또는 근거를 모를 때)의 표시 문턱. */
        private int mixedThresholdSec = 180;

        /** 원래보다 늘어난 대기 1회마다 문턱에 더하는 값. */
        private int perExtraWaitSec = 60;

        /** 같은 자리의 다른 경로가 이만큼 빨라야 교체한다. */
        private int replaceMarginSec = 60;

        /** 숨긴 말풍선을 다시 띄우려면 문턱보다 이만큼 더 나아야 한다. */
        private int reshowMarginSec = 30;

        /** 보드 유효시간 = 마지막 갱신 + 이 값. 워커가 멈추면 낡은 보드가 저절로 사라진다. */
        private Duration ttl = Duration.ofMinutes(3);

        /**
         * 환승 위험 알림을 늦춰 넣는 시간(#264 PR-5). 그 사이 보드가 갱신돼 대안이 있으면 재탐색 push
         * 하나로 합친다. 갱신 마감(약 12초) + 선점 tick 보다 길어야 한다.
         */
        private int riskMergeDelaySeconds = 20;

        /** push 를 막는 하차 여유. 말풍선(60초)보다 길다 — 읽고 문 앞으로 갈 시간이다. */
        private int pushLeadTimeSec = 120;

        /** 여정 시작·수락 뒤 이 시간 동안은 "새 기회" push 를 보내지 않는다(위험+대안은 보낸다). */
        private Duration pushQuietAfterStart = Duration.ofMinutes(3);

        /** 같은 후보라도 직전 push 보다 이만큼 더 빨라지면 "새 기회"로 본다. */
        private int pushNoveltyGainSec = 120;

        /**
         * 재탐색 전용 경기 도착 조회 일일 상한(#264 PR-4). 키 한도의 절반을 넣는다(포털에서 확인한 값).
         * 다 쓰면 그 지역 버스 중간 정류장 기점만 끈다.
         */
        private int quotaGyeonggi = 5000;

        /** 재탐색 전용 TAGO 도착 조회 일일 상한. */
        private int quotaTago = 5000;

        /** 경기·TAGO 도착 조회 캐시(초). 같은 정류장·노선을 보는 여정끼리 호출을 나눠 쓴다. */
        private int regionalArrivalCacheSeconds = 15;

        private final Refresh refresh = new Refresh();

        /** 갱신 스케줄러(worker). */
        @Getter
        @Setter
        public static class Refresh {

            private long tickMs = 1000;

            /** 한 번에 선점할 여정 수. 실행기 크기의 두 배를 넘기면 뒤쪽이 lease 안에 못 돈다. */
            private int batchSize = 6;

            /** 선점 유지 시간. 한 회차의 최악 소요(검색·실시간·예측)보다 길어야 한다. */
            private int leaseSeconds = 60;

            /** 신호가 없을 때의 주기. */
            private int intervalSeconds = 60;

            /** 정류장 통과 신호의 최소 간격. 위험 하락은 우회한다. */
            private int minGapSeconds = 30;

            /** 여정 시작 뒤 첫 갱신까지. */
            private int initialDelaySeconds = 60;

            /**
             * 동시에 도는 갱신 수(전용 실행기 크기). 평가기 슬롯과 <b>나누지 않는다</b> — 갱신은 몇 초씩
             * 잡으므로 섞으면 환승 위험 판정이 굶는다.
             */
            private int permits = 3;
        }
    }

    @Getter
    @Setter
    public static class Alert {

        /**
         * 서버 능동 발행. <b>기본 꺼짐.</b>
         *
         * <p>꺼져 있으면 방아쇠는 예전처럼 세기만 한다(shadow). 켜면 방아쇠 하나가 외부 검색
         * (OTP·실시간·ML)을 부르므로, {@link JourneyRerouteProperties#enabled} 와 별도로 둔다 —
         * 사용자 요청 경로만 열어 두고 능동 발행은 닫아 둘 수 있어야 한다.
         */
        private boolean enabled = false;

        /**
         * 능동 발행 제안의 유효시간. 사용자가 알림을 보고 들어올 시간까지 살아 있어야 한다.
         *
         * <p>사용자 요청 제안({@link JourneyRerouteProperties#proposalTtl}, 2분)보다 길다. 그쪽은
         * 누른 사람이 화면을 보고 있지만, 이쪽은 주머니 속 진동에서 시작한다.
         */
        private Duration snapshotTtl = Duration.ofMinutes(5);

        /**
         * 같은 여정에 다시 알리기까지의 최소 간격.
         *
         * <p>방아쇠 cooldown(1분)과 <b>키가 다르다</b>. 방아쇠는 "찾아볼까"를 재는 것이고 이건
         * 사용자의 주머니를 울리는 간격이라, 같은 값을 쓰면 알림 피로가 관측 주기에 끌려다닌다.
         */
        private Duration cooldown = Duration.ofMinutes(5);

        /**
         * 알림 한 건이 되려면 필요한 개선폭(초).
         *
         * <p>후보 자체의 문턱({@code remaining-path.minimum-eta-gain-seconds})과 같은 값에서
         * 출발하지만 키는 따로 둔다 — 목록에 <b>보여 줄</b> 가치와 사용자를 <b>깨울</b> 가치는
         * 다르고, 조일 때 조여야 하는 쪽은 후자다.
         */
        private int minimumGainSeconds = 300;

        /**
         * 답 없는 알림을 여정당 몇 번까지 보낼지.
         *
         * <p>넘으면 그 여정에는 더 알리지 않는다 — 사용자가 이미 "관심 없다"고 행동으로 말했다.
         * 거절하면서 "계속 받기"를 켠 여정은 이 상한을 무시한다.
         */
        private int maxIgnored = 2;

        /**
         * 목록을 열었을 때 <b>지나간 하차점</b>을 걸러 낼지(pull validate).
         *
         * <p>끄면 알림이 만들어진 뒤 지나간 정류장이 그대로 목록에 남는다. 끌 수 있게 둔 것은
         * 검증용이고, 운영 기본값은 켜짐이다.
         */
        private boolean validateOnRead = true;
    }

    /**
     * "주행 중 어느 정류장에서 내릴 수 있는지"의 운영값(journey-reroute-nextstop PR-2).
     *
     * <p>{@link RemainingPath} 와 자리를 가르는 이유 — 그쪽은 <b>경로 하나를 얼마나 깊이 볼지</b>
     * (검색 예산·경로 수)이고 여기는 <b>원점을 몇 개나 열지</b>다. 원점이 하나 늘 때마다 검색이
     * 통째로 한 번 더 나가므로, 요금·대기가 걱정될 때 조여야 하는 손잡이가 서로 다르다.
     */
    @Getter
    @Setter
    public static class NextStop {

        /**
         * 주행 중인 버스의 <b>중간 정류장</b>을 원점으로 열지. 꺼도 재탐색은 계속 돈다 —
         * 계획 하차 정류장(환승 대기) 원점만 남아 ④의 옛 동작이 된다.
         *
         * <p>끌 수 있게 두는 이유는 PLAN 의 리스크("overlay A 제외율 폭주") 때문이다. 원점이 늘면
         * 검색·예측 호출이 배수로 늘어나는데, 그 폭이 라이브에서만 보인다.
         */
        private boolean enabled = true;

        /**
         * 이만큼은 남아 있어야 하차점으로 제안한다(초).
         *
         * <p>알림을 읽고, 판단하고, 문 앞으로 이동하는 시간이다. 이보다 가까운 정류장을 제안하면
         * 사용자는 이미 지나가는 정류장을 보며 서두르게 된다.
         */
        private int leadTimeSec = 90;

        /** 열어 볼 원점 수 상한. 원점 하나가 검색 한 번이다. */
        private int maxOrigins = 6;

        /**
         * 실시간 도착을 물어볼 정류장 수 상한.
         *
         * <p>{@link #maxOrigins} 와 다른 값인 이유 — 리드타임에 못 미쳐 <b>버려지는</b> 정류장도
         * 조회는 이미 나간 뒤다. 이 상한이 없으면 남은 정류장이 서른 개인 여정에서 서른 번 묻는다.
         */
        private int maxStopLookups = 10;

        /** 도착 조회 한 번의 시간 상한(ms). 주행 중 사용자가 이 응답을 기다린다. */
        private long arrivalBudgetMs = 1500;

        /** 내려서 다음 차로 걸어가기까지의 버퍼(초). 검색 출발 시각 = 하차 시각 + 이 값. */
        private int boardingBufferSec = 30;

        /**
         * 첫 승차까지의 여유가 이보다 작으면 후보에서 뺀다(초) — <b>miss-risk 가드</b>.
         *
         * <p>방금 환승을 놓칠 뻔한 사용자에게 "내려서 곧바로 뛰어 타면 됩니다"를 제안하면 같은
         * 일을 한 번 더 겪게 한다. 예측 오차가 그 여유보다 크기 때문이다.
         */
        private int missRiskMinSlackSec = 30;

        /**
         * 진행 관측이 이보다 오래됐으면 창을 열지 않는다(초).
         *
         * <p>지금 몇 번째 정류장인지는 앱이 보내 준 {@code remainingStops} 로 안다. 그 값이 낡으면
         * <b>이미 지나간 정류장</b>을 앞으로 올 정류장으로 착각하고, 그 제안은 사용자를 못 내리게
         * 한다. 모르는 편이 낫다.
         */
        private int progressMaxAgeSec = 180;

        /**
         * 수락을 반영하기 직전, 하차 정류장까지 이만큼은 남아 있어야 한다(초, PR-4).
         *
         * <p>{@link #leadTimeSec}(90) 보다 <b>짧다.</b> 제안을 만들 때는 "읽고 판단하고 움직일"
         * 시간을 재지만, 여기서는 사용자가 이미 읽고 <b>누른 뒤</b>라 남은 것은 문 앞으로 가는
         * 시간뿐이다. 같은 값을 쓰면 알림을 보고 바로 누른 사용자의 수락이 제 시간 안에 왔는데도
         * 거절된다.
         */
        private int acceptMinLeadSec = 30;
    }

    /**
     * "환승 정류장에서 목적지까지 다른 경로를 찾을지"의 운영값(Phase 6 ④).
     *
     * <p>값의 뜻은 {@link NextStop} 과 겹치지만 <b>키를 따로 둔다.</b> 이쪽은 "경로를 몇 개까지
     * 평가할지"이고 그쪽은 "하차 자리를 몇 개까지 열지"라, 요금이 걱정될 때 조여야 하는 쪽이
     * 서로 다르다.
     */
    @Getter
    @Setter
    public static class RemainingPath {

        /** 이만큼 빨라지지 않으면 제안하지 않는다. */
        private int minimumEtaGainSeconds = 300;

        /**
         * 검색이 준 경로 중 <b>실제로 평가할</b> 수 상한.
         *
         * <p>4 로 넓힌 근거(national-otp P2-F PR4a): 옛 값 2 는 ODsay <b>유료 호출 요금</b>이 근거였다.
         * OTP 전환으로 남은경로 검색이 내부 호출이 되면 요금 근거가 사라져, 더 나은 우회로를 놓치지
         * 않게 폭을 넓힌다. 상한을 두는 이유는 이제 <b>자원·라이브 대기</b>다 — 경로 하나마다 승차
         * 예측이 나가고 사용자가 주행 중 그 응답을 기다린다. 전체 상한은 {@code evaluationBudgetMs}.
         *
         * <p><b>주의(OTP 전제)</b>: 이 값은 OTP(무료) 가정이다. OTP 를 켜기 전 provider=ODSAY 로
         * 재탐색이 돌면 경로 수만큼 <b>유료</b> ODsay 호출이 는다. 재탐색은 사용자 요청 기반이고
         * shadow 상태라 사용량이 낮아 수용하되, OTP 활성 전 부하가 관측되면 되돌린다.
         */
        private int maxPaths = 4;

        /**
         * 후보 평가에 쓸 수 있는 누적 시간. 넘으면 이미 평가한 것으로 제안을 만든다.
         *
         * <p>4500 으로 올린 근거(otp-search-budget): OTP 검색 호출 예산이 otpMs(3500)라, 3000 이면
         * OTP 검색 한 번 뒤 예측 평가가 조기 컷된다. 검색(≤3.5s) + 예측 여유를 담게 넓힌다.
         */
        private long evaluationBudgetMs = 4500;

        /**
         * 경로 검색 한 번에 쓸 수 있는 시간. 캐시가 맞으면 쓰이지 않는다.
         *
         * <p>3000 으로 늘린 근거(PR4a): 옛 2000 은 ODsay 호출 1회의 요금·지연 상한이었다. OTP 내부
         * 호출로 바뀌면 근거가 요금에서 <b>지연·완결성</b>으로 옮겨간다 — 목적지가 멀고 환승이 많은
         * 경로가 2초에 잘려 후보가 빈약해지느니, 1초를 더 줘 완전한 답을 낸다. 전체 여정 deadline
         * 안에 든다.
         */
        private long searchBudgetMs = 3000;
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
         * <p>켜 두는 것이 기본값인 이유 — 하루에 몇 번 도는지 모르는 채로 후보 생성기(④)를
         * 켜면 요금이 먼저 온다. 그래도 끄는 스위치는 남긴다.
         */
        private boolean enabled = true;

        /**
         * 문이 열리는 폭. 환승 준비 시각까지 이 시간 안이면 찾아봐도 되는 구간으로 본다.
         *
         * <p>이것만으로는 아무 일도 일어나지 않는다 — 실제로 찾게 만드는 것은 방아쇠다.
         */
        private int decisionWindowSeconds = 600;

        /**
         * 같은 환승에서 다시 찾기까지의 최소 간격. 버튼 연타가 외부 검색 연타가 되면 안 된다.
         *
         * <p>1분으로 줄인 근거(PR4a): 옛 2분은 ODsay 요금 폭을 재는 shadow 전제였다. 요금 근거가
         * 옅어지면 지연 상황에서 <b>더 자주</b> 대안을 갱신하는 편이 UX 에 낫다 — 중복 억제(연타
         * 가드)는 1분으로도 충분하다.
         */
        private Duration cooldown = Duration.ofMinutes(1);

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
        if (nextStop.getLeadTimeSec() <= 0) {
            // 0 이면 "지금 문이 닫히는 정류장"이 제안으로 나간다.
            throw new IllegalStateException(
                    "journey.reroute.next-stop.lead-time-sec 는 1 이상이어야 한다");
        }
        if (nextStop.getMaxOrigins() <= 0 || nextStop.getMaxStopLookups() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.next-stop 의 max-origins·max-stop-lookups 는 1 이상이어야 한다");
        }
        if (nextStop.getArrivalBudgetMs() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.next-stop.arrival-budget-ms 는 1 이상이어야 한다");
        }
        if (nextStop.getBoardingBufferSec() < 0 || nextStop.getMissRiskMinSlackSec() < 0) {
            // 0 은 허용한다 — 가드를 사실상 끄고 제외율을 먼저 보고 싶을 때가 있다.
            throw new IllegalStateException(
                    "journey.reroute.next-stop 의 boarding-buffer-sec·miss-risk-min-slack-sec 는 "
                            + "음수일 수 없다");
        }
        if (nextStop.getProgressMaxAgeSec() <= 0) {
            throw new IllegalStateException(
                    "journey.reroute.next-stop.progress-max-age-sec 는 1 이상이어야 한다");
        }
        if (nextStop.getAcceptMinLeadSec() < 0) {
            // 0 은 허용한다 — 가드를 끄고 "문이 닫히는 순간의 수락"이 얼마나 오는지 보고 싶을 때가 있다.
            throw new IllegalStateException(
                    "journey.reroute.next-stop.accept-min-lead-sec 는 음수일 수 없다");
        }
        if (alert.getSnapshotTtl() == null || alert.getSnapshotTtl().isZero()
                || alert.getSnapshotTtl().isNegative()) {
            throw new IllegalStateException("journey.reroute.alert.snapshot-ttl 은 양수여야 한다");
        }
        if (alert.getCooldown() == null || alert.getCooldown().isNegative()) {
            // 0 은 허용한다 — 알림 간격을 끄고 발생률만 보고 싶을 때가 있다.
            throw new IllegalStateException("journey.reroute.alert.cooldown 은 음수일 수 없다");
        }
        if (alert.getMinimumGainSeconds() <= 0) {
            // 0 이면 "1초 빨라집니다"가 사용자의 주머니를 울린다.
            throw new IllegalStateException(
                    "journey.reroute.alert.minimum-gain-seconds 는 1 이상이어야 한다");
        }
        if (alert.getMaxIgnored() <= 0) {
            throw new IllegalStateException("journey.reroute.alert.max-ignored 는 1 이상이어야 한다");
        }
    }
}
