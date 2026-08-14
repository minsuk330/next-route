package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingCandidate;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingDecision;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.service.DestinationNormalizer;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탑승 열차 판정의 계약.
 *
 * <p>여기 있는 시험 대부분은 <b>"고르지 않는다"</b>를 고정한다. 잘못 고정하면 사용자가 엉뚱한
 * 역에서 내리고, 못 고르면 안내가 "확인 중"에 머무를 뿐이다. 두 실패의 값이 다르므로 관문을
 * 헐겁게 만드는 변경은 전부 여기서 걸려야 한다.
 */
class SubwayTrainMatcherTest {

    private static final Instant BASE = Instant.parse("2026-08-14T09:00:00Z");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 14);
    private static final String LINE = "1001";
    private static final String OTHER_LINE = "1002";
    private static final String UP = "상행";
    private static final String DOWN = "하행";
    private static final String BOARD_STATION = "1001000133";

    private JourneySubwayBindingProperties properties;
    private SubwayTrainMatcher matcher;

    @BeforeEach
    void setUp() {
        properties = new JourneySubwayBindingProperties();
        // 경계를 눈으로 셀 수 있게 좁혀 둔다. 기본값은 실기기 관측 전의 잠정값이라 경계 예제로 못 쓴다.
        properties.setMotionUncertainty(Duration.ofSeconds(5));
        properties.setClockSkewBudget(Duration.ofSeconds(15));
        properties.setAbsoluteMaxSkew(Duration.ofSeconds(150));
        properties.setHeadwayBeta(0.5);
        properties.setMatchWindow(Duration.ofMinutes(6));
        properties.setConfirmWindow(Duration.ofMinutes(8));
        properties.setEpisodeMatchTolerance(Duration.ofSeconds(90));
        matcher = new SubwayTrainMatcher(properties, new DestinationNormalizer());
    }

    // ── 배제 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("노선·역 식별자를 해석하지 못했으면 판정하지 않는다")
    void doesNotDecideWhenLegIsUnresolved() {
        assertThat(matcher.match(pending(List.of()).withLineId(null).build()).reason())
                .isEqualTo("leg-unresolved");
        assertThat(matcher.match(pending(List.of()).withBoardStation(null).build()).reason())
                .isEqualTo("leg-unresolved");
    }

    @Test
    @DisplayName("방향이 미확정이면 판정 자체를 하지 않는다 — 지어내면 반대 방향에 고정된다")
    void doesNotDecideWithoutDirection() {
        SubwayBindingDecision decision =
                matcher.match(pending(List.of(candidate("1001", -5, 5))).withDirection(null).build());

        assertThat(decision.kind()).isEqualTo(SubwayBindingDecision.Kind.NONE);
        assertThat(decision.reason()).isEqualTo("direction-unknown");
    }

    @Test
    @DisplayName("이동 시작 근거가 없으면 판정하지 않는다")
    void doesNotDecideWithoutMovementStart() {
        assertThat(matcher.match(pending(List.of()).withMovementStart(null).build()).reason())
                .isEqualTo("no-movement-start");
    }

    @Test
    @DisplayName("출발 사건이 없으면 후보가 0 이다")
    void noDepartureMeansNoCandidate() {
        assertThat(matcher.match(pending(List.of()).build()).reason()).isEqualTo("no-departure");
    }

    @Test
    @DisplayName("반대 방향·다른 노선은 후보에서 빠진다")
    void dropsOppositeDirectionAndOtherLine() {
        SubwayBindingCandidate wrongWay = new SubwayBindingCandidate("2222", SERVICE_DATE, LINE,
                DOWN, at(-5), at(5), SubwayTrainEvent.Quality.OBSERVED, null,
                SubwayExpressType.NORMAL, 0);
        SubwayBindingCandidate wrongLine = new SubwayBindingCandidate("3333", SERVICE_DATE,
                OTHER_LINE, UP, at(-5), at(5), SubwayTrainEvent.Quality.OBSERVED, null,
                SubwayExpressType.NORMAL, 0);

        assertThat(matcher.match(pending(List.of(wrongWay, wrongLine)).build()).reason())
                .isEqualTo("no-departure");
    }

    @Test
    @DisplayName("급행·특급은 후보에서 빠진다 — 서는 역이 달라 계획 체인과 대조할 수 없다")
    void dropsExpressTrains() {
        SubwayBindingCandidate express = new SubwayBindingCandidate("4444", SERVICE_DATE, LINE, UP,
                at(-5), at(5), SubwayTrainEvent.Quality.OBSERVED, null,
                SubwayExpressType.EXPRESS, 0);

        assertThat(matcher.match(pending(List.of(express)).build()).reason())
                .isEqualTo("no-departure");
    }

    @Test
    @DisplayName("종착역으로는 배제하지 않는다 — 잘못 빼면 관문이 헐거워져 '잘못 고정' 쪽으로 틀어진다")
    void doesNotExcludeByTerminal() {
        SubwayBindingCandidate shortTurn = new SubwayBindingCandidate("5555", SERVICE_DATE, LINE,
                UP, at(-5), at(5), SubwayTrainEvent.Quality.OBSERVED, "1001000120",
                SubwayExpressType.NORMAL, 0);

        assertThat(matcher.match(pending(List.of(shortTurn)).build()).kind())
                .isEqualTo(SubwayBindingDecision.Kind.PROVISIONAL);
    }

    @Test
    @DisplayName("사용자가 거부한 열차는 다시 후보가 되지 않는다")
    void doesNotReselectRejectedTrain() {
        SubwayBindingCandidate rejected = candidate("1001", -5, 5);
        SubwayBindingDecision decision = matcher.match(
                pending(List.of(rejected))
                        .withRejected(Set.of(rejected.identity()))
                        .build());

        assertThat(decision.reason()).isEqualTo("no-departure");
    }

    // ── 관문 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("관문 (1): 유일 후보여도 오차가 크면 고정하지 않는다")
    void gate1RejectsFarCandidateEvenIfUnique() {
        SubwayBindingDecision decision =
                matcher.match(pending(List.of(candidate("1001", 200, 210))).build());

        assertThat(decision.reason()).isEqualTo("gate1");
    }

    @Test
    @DisplayName("관문 (2): 2위가 충분히 멀면 잠정 판정한다")
    void gate2AcceptsWhenSecondIsFarEnough() {
        SubwayBindingDecision decision = matcher.match(pending(List.of(
                candidate("1001", 0, 10), candidate("2002", 95, 105))).build());

        assertThat(decision.kind()).isEqualTo(SubwayBindingDecision.Kind.PROVISIONAL);
        assertThat(decision.chosen().trainNo()).isEqualTo("1001");
    }

    @Test
    @DisplayName("관문 (2) 경계: 2위가 시계 오차 폭 안이면 고르지 않는다")
    void gate2RejectsWhenSecondIsWithinSkew() {
        SubwayBindingDecision decision = matcher.match(pending(List.of(
                candidate("1001", 0, 10), candidate("2002", 20, 30))).build());

        assertThat(decision.reason()).isEqualTo("gate2");
    }

    @Test
    @DisplayName("후보가 1대뿐이어도 잠정까지다 — 자동 확정은 없다")
    void singleCandidateNeverConfirmsByItself() {
        assertThat(matcher.match(pending(List.of(candidate("1001", -5, 5))).build()).kind())
                .isEqualTo(SubwayBindingDecision.Kind.PROVISIONAL);
    }

    @Test
    @DisplayName("기기 시각이 의심스러우면 여유폭이 두 배가 되어 통과하던 것이 막힌다")
    void clockSuspectDoublesTheSkewBudget() {
        List<SubwayBindingCandidate> departures = List.of(
                candidate("1001", -5, 5), candidate("2002", 40, 50));

        assertThat(matcher.match(pending(departures).build()).kind())
                .isEqualTo(SubwayBindingDecision.Kind.PROVISIONAL);
        assertThat(matcher.match(pending(departures).withClockSuspect(true).build()).reason())
                .isEqualTo("gate2");
    }

    @Test
    @DisplayName("관문 (3): 배차간격을 잴 수 있으면 1·2위 차이가 그 절반은 돼야 한다")
    void gate3NeedsHalfOfLocalHeadway() {
        SubwayBindingDecision decision = matcher.match(pending(List.of(
                candidate("1001", -5, 5), candidate("2002", 55, 65),
                candidate("3003", 175, 185), candidate("4004", 295, 305))).build());

        assertThat(decision.reason()).isEqualTo("gate3");
    }

    @Test
    @DisplayName("관문 (3)을 넘기면 잠정 판정한다")
    void gate3PassesWhenGapIsWideEnough() {
        SubwayBindingDecision decision = matcher.match(pending(List.of(
                candidate("1001", -5, 5), candidate("2002", 125, 135),
                candidate("3003", 175, 185), candidate("4004", 295, 305))).build());

        assertThat(decision.kind()).isEqualTo(SubwayBindingDecision.Kind.PROVISIONAL);
        assertThat(decision.chosen().trainNo()).isEqualTo("1001");
    }

    @Test
    @DisplayName("배차간격을 못 재면 관문 (3)을 건너뛴다 — 표본이 없다고 판정을 막지 않는다")
    void skipsGate3WhenHeadwayIsUnknown() {
        assertThat(matcher.match(pending(List.of(
                candidate("1001", -5, 5), candidate("2002", 55, 65))).build()).kind())
                .isEqualTo(SubwayBindingDecision.Kind.PROVISIONAL);
    }

    @Test
    @DisplayName("이동 시작이 판정 창을 넘기면 접는다")
    void abandonsAfterMatchWindow() {
        SubwayBindingDecision decision = matcher.match(
                pending(List.of(candidate("1001", -5, 5))).withNow(at(400)).build());

        assertThat(decision.kind()).isEqualTo(SubwayBindingDecision.Kind.ABANDON);
        assertThat(decision.reason()).isEqualTo("match-window-expired");
    }

    // ── 후행 확정 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("판정 창을 넘긴 잠정 판정도 확정 창까지는 산다 — 두 창의 기준 시각이 다르다")
    void confirmWindowIsMeasuredFromTheProvisionalDecision() {
        SubwayBindingDecision decision = matcher.match(
                provisional()
                        .withMovementStart(at(-420))
                        .withProvisionalDecidedAt(at(-60))
                        .withNow(at(0))
                        .build());

        assertThat(decision.kind()).isNotEqualTo(SubwayBindingDecision.Kind.ABANDON);
    }

    @Test
    @DisplayName("확정 창을 넘기면 접는다")
    void abandonsAfterConfirmWindow() {
        SubwayBindingDecision decision = matcher.match(
                provisional().withProvisionalDecidedAt(at(-600)).build());

        assertThat(decision.reason()).isEqualTo("confirm-window-expired");
    }

    @Test
    @DisplayName("정차 관측이 없으면 확정하지 않는다")
    void doesNotConfirmWithoutEpisode() {
        assertThat(matcher.match(provisional().withEpisode(null, null).build()).reason())
                .isEqualTo("no-episode");
    }

    @Test
    @DisplayName("계획 역 체인이 없으면 확정하지 않는다 — 어느 역과 비교할지 모른다")
    void doesNotConfirmWithoutPlannedChain() {
        assertThat(matcher.match(provisional().withPlannedNextStation(null).build()).reason())
                .isEqualTo("no-planned-chain");
    }

    @Test
    @DisplayName("후보 전원이 다음 역 도착을 놓쳐도 오판하지 않는다")
    void missingNextStationForEveryCandidateDoesNotConfirm() {
        // 도착 로그로 비교역을 뽑았다면 전원 "종각"으로 합의돼 한 구간 어긋난 채 확정됐을 상황이다.
        SubwayBindingDecision decision = matcher.match(
                provisional()
                        .withArrival(train("1001"), "종각", 0, 10)
                        .withArrival(train("2002"), "종각", 120, 130)
                        .build());

        assertThat(decision.reason()).isEqualTo("not-discriminative");
    }

    @Test
    @DisplayName("다른 역 도착은 비교에 쓰이지 않는다 — 앞차의 N+2 가 뒷차의 N+1 을 이기면 안 된다")
    void comparesOnlyTheSameStation() {
        SubwayBindingDecision decision = matcher.match(
                provisional()
                        // 1001 은 비교역(시청)에 늦게 왔지만, 다른 역(종각)에는 정확히 그때 있었다
                        .withArrival(train("1001"), "종각", 0, 10)
                        .withArrival(train("1001"), "시청", 200, 210)
                        .withArrival(train("2002"), "시청", 0, 10)
                        .build());

        assertThat(decision.isConfirming()).isTrue();
        assertThat(decision.chosen().trainNo()).isEqualTo("2002");
    }

    @Test
    @DisplayName("비교 가능한 후보가 1대뿐이면 확정하지 않는다 — 결측은 '틀림'이 아니라 '모름'이다")
    void doesNotConfirmWhenOnlyOneCandidateHasLogs() {
        SubwayBindingDecision decision = matcher.match(
                provisional().withArrival(train("1001"), "시청", 0, 10).build());

        assertThat(decision.reason()).isEqualTo("not-discriminative");
    }

    @Test
    @DisplayName("역 이름 표기가 달라도 같은 역으로 대조한다")
    void normalizesStationNames() {
        SubwayBindingDecision decision = matcher.match(
                provisional()
                        .withPlannedNextStation("시청역")
                        .withArrival(train("1001"), "시청", 0, 10)
                        .withArrival(train("2002"), "시청(경기)", 200, 210)
                        .build());

        assertThat(decision.kind()).isEqualTo(SubwayBindingDecision.Kind.CONFIRMED);
        assertThat(decision.chosen().trainNo()).isEqualTo("1001");
    }

    @Test
    @DisplayName("동점이면 확정하지 않는다")
    void doesNotConfirmOnTie() {
        SubwayBindingDecision decision = matcher.match(
                provisional()
                        .withArrival(train("1001"), "시청", 0, 10)
                        .withArrival(train("2002"), "시청", 0, 10)
                        .build());

        assertThat(decision.reason()).isEqualTo("not-discriminative");
    }

    @Test
    @DisplayName("모두 허용 오차 밖이면 잠정 판정을 유지한다")
    void keepsProvisionalWhenEveryCandidateIsOutOfTolerance() {
        SubwayBindingDecision decision = matcher.match(
                provisional()
                        .withArrival(train("1001"), "시청", 300, 310)
                        .withArrival(train("2002"), "시청", 500, 510)
                        .build());

        assertThat(decision.reason()).isEqualTo("episode-tolerance");
    }

    @Test
    @DisplayName("정차 관측이 잠정 후보가 아닌 쪽과 맞으면 그 후보로 교정한다")
    void correctsToTheBetterCandidate() {
        SubwayBindingDecision decision = matcher.match(
                provisional()
                        .withCurrentTrain(train("1001"))
                        .withArrival(train("1001"), "시청", 200, 210)
                        .withArrival(train("2002"), "시청", 0, 10)
                        .build());

        assertThat(decision.kind()).isEqualTo(SubwayBindingDecision.Kind.CORRECTED);
        assertThat(decision.chosen().trainNo()).isEqualTo("2002");
    }

    // ── 재료 ────────────────────────────────────────────────────────────────

    private Instant at(long seconds) {
        return BASE.plusSeconds(seconds);
    }

    private SubwayBindingCandidate candidate(String trainNo, long fromSeconds, long toSeconds) {
        return new SubwayBindingCandidate(trainNo, SERVICE_DATE, LINE, UP,
                at(fromSeconds), at(toSeconds), SubwayTrainEvent.Quality.OBSERVED, null,
                SubwayExpressType.NORMAL, 0);
    }

    private TrainIdentity train(String trainNo) {
        return new TrainIdentity(SERVICE_DATE, LINE, UP, trainNo);
    }

    private InputBuilder pending(List<SubwayBindingCandidate> departures) {
        return new InputBuilder(SubwayBindingStatus.PENDING).withDepartures(departures);
    }

    /** 잠정 판정 뒤의 상태. 후보 둘과 정차 관측이 기본으로 들어 있다. */
    private InputBuilder provisional() {
        return new InputBuilder(SubwayBindingStatus.PROVISIONAL)
                .withKnownCandidates(List.of(candidate("1001", -5, 5), candidate("2002", 115, 125)))
                .withCurrentTrain(train("1001"))
                .withPlannedNextStation("시청")
                .withEpisode(at(0), at(10))
                .withProvisionalDecidedAt(at(-60));
    }

    /** 시험이 읽히게 하려는 대역. 기본값은 "판정이 되는 상태"이고 각 시험이 한 축만 흔든다. */
    private final class InputBuilder {

        private final SubwayBindingStatus status;
        private Instant movementStartedAt = at(0);
        private List<SubwayBindingCandidate> departures = List.of();
        private Set<TrainIdentity> rejected = Set.of();
        private String lineId = LINE;
        private String direction = UP;
        private String boardStation = BOARD_STATION;
        private Instant now = at(30);
        private boolean clockSuspect;
        private Instant episodeFrom;
        private Instant episodeTo;
        private TrainIdentity currentTrain;
        private String plannedNextStation;
        private List<SubwayBindingCandidate> knownCandidates = List.of();
        private final Map<TrainIdentity, List<SubwayTrainEvent>> arrivals = new LinkedHashMap<>();
        private Instant provisionalDecidedAt;

        private InputBuilder(SubwayBindingStatus status) {
            this.status = status;
        }

        InputBuilder withDepartures(List<SubwayBindingCandidate> value) {
            this.departures = value;
            return this;
        }

        InputBuilder withRejected(Set<TrainIdentity> value) {
            this.rejected = value;
            return this;
        }

        InputBuilder withLineId(String value) {
            this.lineId = value;
            return this;
        }

        InputBuilder withDirection(String value) {
            this.direction = value;
            return this;
        }

        InputBuilder withBoardStation(String value) {
            this.boardStation = value;
            return this;
        }

        InputBuilder withMovementStart(Instant value) {
            this.movementStartedAt = value;
            return this;
        }

        InputBuilder withNow(Instant value) {
            this.now = value;
            return this;
        }

        InputBuilder withClockSuspect(boolean value) {
            this.clockSuspect = value;
            return this;
        }

        InputBuilder withEpisode(Instant from, Instant to) {
            this.episodeFrom = from;
            this.episodeTo = to;
            return this;
        }

        InputBuilder withCurrentTrain(TrainIdentity value) {
            this.currentTrain = value;
            return this;
        }

        InputBuilder withPlannedNextStation(String value) {
            this.plannedNextStation = value;
            return this;
        }

        InputBuilder withKnownCandidates(List<SubwayBindingCandidate> value) {
            this.knownCandidates = value;
            return this;
        }

        InputBuilder withProvisionalDecidedAt(Instant value) {
            this.provisionalDecidedAt = value;
            return this;
        }

        InputBuilder withArrival(TrainIdentity identity, String stationName,
                                 long fromSeconds, long toSeconds) {
            arrivals.computeIfAbsent(identity, key -> new java.util.ArrayList<>())
                    .add(new SubwayTrainEvent(SubwayTrainEvent.Type.ARRIVED, "S" + stationName,
                            stationName, at(fromSeconds), at(toSeconds),
                            SubwayTrainEvent.Quality.OBSERVED, null, SubwayExpressType.NORMAL));
            return this;
        }

        SubwayTrainMatcher.MatchInput build() {
            return new SubwayTrainMatcher.MatchInput(status, movementStartedAt, departures,
                    rejected, lineId, direction, boardStation, now, clockSuspect,
                    episodeFrom, episodeTo, currentTrain, plannedNextStation, knownCandidates,
                    arrivals, provisionalDecidedAt);
        }
    }
}
