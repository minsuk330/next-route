package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.config.SubwayPositionProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionFetchResult;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 위치 전이에서 도착·출발 사건을 만드는 규칙.
 *
 * <p>여기서 지키려는 것은 하나로 요약된다 — <b>잘못된 출발 확정보다 확정 지연이 낫다.</b>
 * 있지도 않은 출발을 만들면 엉뚱한 열차에 고정되고, 사용자는 엉뚱한 역에서 내린다.
 */
class SubwayTrainTransitionTrackerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LINE = "1002";
    private static final String DIRECTION = "내선";
    private static final String TRAIN = "2390";

    private FakeLog log;
    private SubwayStationAdjacency adjacency;
    private SubwayTrainTransitionTracker tracker;

    @BeforeEach
    void setUp() {
        log = new FakeLog();
        adjacency = mock(SubwayStationAdjacency.class);
        // 성수 ↔ 건대입구 만 맞닿아 있다. 그 밖의 이동은 전부 순간이동이다.
        when(adjacency.isAdjacent(LINE, "성수", "건대입구")).thenReturn(true);
        when(adjacency.isAdjacent(LINE, "건대입구", "성수")).thenReturn(true);

        SubwayPositionProperties props = new SubwayPositionProperties();
        props.setSourceLagBudget(Duration.ofSeconds(150));
        props.setFreshnessWindow(Duration.ofMinutes(5));

        tracker = new SubwayTrainTransitionTracker(log, adjacency, new ServiceDateResolver(), props);
    }

    // ── 사라짐·불완전 응답 ────────────────────────────────────────────────

    @Test
    @DisplayName("불완전한 응답으로는 사건을 만들지 않는다")
    void ignoresIncompleteResponses() {
        tracker.observe(SubwayPositionFetchResult.failed(LINE, Instant.now(), "INFO-200"));
        assertThat(log.events).isEmpty();
    }

    @Test
    @DisplayName("첫 관측은 기준점으로만 쓰고 사건을 만들지 않는다")
    void firstObservationIsBaselineOnly() {
        int created = tracker.observe(complete("09:00:00", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));

        assertThat(created).isZero();
        assertThat(log.events).isEmpty();
        assertThat(log.remembered).isNotEmpty();
    }

    @Test
    @DisplayName("목록에서 사라진 것만으로는 출발을 만들지 않는다")
    void disappearanceIsNotDeparture() {
        tracker.observe(complete("09:00:00", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:01:00")); // 열차가 통째로 빠진 응답

        assertThat(log.events).isEmpty();
    }

    // ── 출발 판정 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("그 역에 있었다는 선행 관측이 있으면 하류 관측 없이 출발을 확정한다")
    void departureWithPriorPresenceIsCorroborated() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:01:10", row("성수", SubwayTrainStatus.DEPARTED, "09:01:00")));

        SubwayTrainEvent departure = only(SubwayTrainEvent.Type.DEPARTED);
        assertThat(departure.quality()).isEqualTo(SubwayTrainEvent.Quality.CORROBORATED);
        assertThat(departure.stationName()).isEqualTo("성수");
    }

    @Test
    @DisplayName("S3 directAt 원문(9 = ITX)이 직전 위치·출발·도착 사건까지 따라간다 — enum 은 UNKNOWN 으로 접힌다")
    void rawDirectAtFollowsEvents() {
        tracker.observe(complete("09:00:10", itx(row("성수", SubwayTrainStatus.ARRIVED, "09:00:00"))));
        tracker.observe(complete("09:01:10", itx(row("건대입구", SubwayTrainStatus.ARRIVED, "09:01:00"))));

        assertThat(log.events).isNotEmpty().allSatisfy(event -> {
            assertThat(event.express()).isEqualTo(SubwayExpressType.UNKNOWN);
            assertThat(event.directAtRaw()).isEqualTo("9");
        });
        assertThat(log.remembered.values()).allSatisfy(seen -> assertThat(seen.directAtRaw()).isEqualTo("9"));
    }

    private static SubwayPositionInfo itx(SubwayPositionInfo row) {
        return new SubwayPositionInfo(row.lineId(), row.trainNo(), row.stationId(), row.stationName(),
                row.direction(), row.terminalId(), row.terminalName(), row.status(), SubwayExpressType.UNKNOWN,
                row.receivedAt(), row.lastReceivedDate(), "9");
    }

    @Test
    @DisplayName("선행 관측 없이 나타난 출발 표시는 잠정이다")
    void departureWithoutPriorPresenceStaysObserved() {
        // 직전이 이미 출발 상태였다 — 원천이 같은 값을 계속 내보내는 경우와 구분되지 않는다
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.DEPARTED, "09:00:00")));
        tracker.observe(complete("09:01:10", row("성수", SubwayTrainStatus.DEPARTED, "09:01:00")));

        assertThat(only(SubwayTrainEvent.Type.DEPARTED).quality())
                .isEqualTo(SubwayTrainEvent.Quality.OBSERVED);
    }

    @Test
    @DisplayName("맞닿은 역에서 다시 보이면 이전 역 출발을 확정한다")
    void movingToAdjacentStationConfirmsDeparture() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:02:10", row("건대입구", SubwayTrainStatus.ARRIVED, "09:02:00")));

        SubwayTrainEvent departure = only(SubwayTrainEvent.Type.DEPARTED);
        assertThat(departure.stationName()).isEqualTo("성수");
        assertThat(departure.quality()).isEqualTo(SubwayTrainEvent.Quality.CORROBORATED);
    }

    @Test
    @DisplayName("맞닿지 않은 역으로 건너뛰면 사건을 만들지 않는다 — 회차·차량기지·역행")
    void nonAdjacentJumpProducesNothing() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:02:10", row("잠실", SubwayTrainStatus.ARRIVED, "09:02:00")));

        assertThat(log.events).isEmpty();
    }

    @Test
    @DisplayName("이름 표에 없는 코레일 구간도 노선 순서로 맞닿아 있으면 출발을 확정한다(수원 → 화서)")
    void adjacentByLineOrderConfirmsDeparture() {
        when(adjacency.isAdjacentInOrder(LINE, DIRECTION, "ID-수원", "ID-화서")).thenReturn(true);

        tracker.observe(complete("09:00:10", row("수원", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:02:10", row("화서", SubwayTrainStatus.ARRIVED, "09:02:00")));

        SubwayTrainEvent departure = only(SubwayTrainEvent.Type.DEPARTED);
        assertThat(departure.stationName()).isEqualTo("수원");
        assertThat(departure.stationId()).isEqualTo("ID-수원");
    }

    @Test
    @DisplayName("출발 표시를 놓치면 구간이 두 관측 사이로 넓어진다")
    void missedDepartureWidensTheInterval() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:02:10", row("건대입구", SubwayTrainStatus.ARRIVED, "09:02:00")));
        long wide = only(SubwayTrainEvent.Type.DEPARTED).uncertaintySeconds();

        setUp();
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:01:10", row("성수", SubwayTrainStatus.DEPARTED, "09:01:00")));
        long narrow = only(SubwayTrainEvent.Type.DEPARTED).uncertaintySeconds();

        assertThat(wide).isGreaterThan(narrow);
    }

    @Test
    @DisplayName("구간의 하한이 보고 시각보다 과거다 — 사건은 보고보다 앞서 일어났다")
    void lowerBoundIsInThePast() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:01:10", row("성수", SubwayTrainStatus.DEPARTED, "09:01:00")));

        SubwayTrainEvent departure = only(SubwayTrainEvent.Type.DEPARTED);
        assertThat(departure.from()).isBefore(instant("09:01:00"));
        assertThat(departure.to()).isEqualTo(instant("09:01:00"));
    }

    // ── 도착 판정 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("맞닿은 역에 도착하면 도착 사건도 남긴다 — 다음 역 확정(L4)이 이걸 쓴다")
    void recordsArrivalAtTheNextStation() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:02:10", row("건대입구", SubwayTrainStatus.ARRIVED, "09:02:00")));

        assertThat(only(SubwayTrainEvent.Type.ARRIVED).stationName()).isEqualTo("건대입구");
    }

    @Test
    @DisplayName("진입 다음 바로 출발이 와도 출발을 확정한다 — 도착을 못 보고 지나칠 수 있다")
    void enteringThenDepartedIsAValidCausalChain() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ENTERING, "09:00:00")));
        tracker.observe(complete("09:01:10", row("성수", SubwayTrainStatus.DEPARTED, "09:01:00")));

        assertThat(only(SubwayTrainEvent.Type.DEPARTED).quality())
                .isEqualTo(SubwayTrainEvent.Quality.CORROBORATED);
    }

    // ── 갱신·신선도 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("원천이 아직 갱신되지 않았으면 아무 일도 하지 않는다")
    void ignoresUnchangedSourceTimestamp() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:00:20", row("성수", SubwayTrainStatus.DEPARTED, "09:00:00")));

        assertThat(log.events).isEmpty();
    }

    @Test
    @DisplayName("보고 시각이 역행하는 관측은 무시한다")
    void ignoresBackwardTimestamps() {
        tracker.observe(complete("09:01:10", row("성수", SubwayTrainStatus.ARRIVED, "09:01:00")));
        tracker.observe(complete("09:01:20", row("건대입구", SubwayTrainStatus.ARRIVED, "09:00:00")));

        assertThat(log.events).isEmpty();
    }

    @Test
    @DisplayName("전체 응답이 정상이어도 그 열차만 낡았으면 전이에 쓰지 않는다")
    void skipsStaleRowsEvenInACompleteResponse() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        // 10분 전 값이 그대로 실려 왔다
        tracker.observe(complete("09:10:10", row("건대입구", SubwayTrainStatus.ARRIVED, "09:00:30")));

        assertThat(log.events).isEmpty();
    }

    // ── identity ────────────────────────────────────────────────────────

    @Test
    @DisplayName("보고 시각이 같은데 스냅샷이 다르면 그 열차를 통째로 뺀다 — 순서를 가릴 근거가 없다")
    void isolatesDuplicateIdentityWithinOneResponse() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));
        tracker.observe(complete("09:01:10",
                row("건대입구", SubwayTrainStatus.ARRIVED, "09:01:00"),
                row("잠실", SubwayTrainStatus.ARRIVED, "09:01:00")));

        assertThat(log.events).isEmpty();
    }

    @Test
    @DisplayName("중복이 recptnDt 만 다르면 최신 행으로 전이를 만든다 — 1·3·4호선 실측 10~15%")
    void picksLatestRowWhenDuplicateIsSameStation() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));

        int created = tracker.observe(complete("09:01:10",
                row("성수", SubwayTrainStatus.ARRIVED, "09:00:30"),
                row("성수", SubwayTrainStatus.DEPARTED, "09:00:45")));

        assertThat(created).isEqualTo(1);
        assertThat(log.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SubwayTrainEvent.Type.DEPARTED);
            assertThat(event.stationName()).isEqualTo("성수");
            assertThat(event.quality()).isEqualTo(SubwayTrainEvent.Quality.CORROBORATED);
        });
        // 격리하면 여기가 09:00:00 에 멈춘다 — 다음 관측이 두 역 밖이면 출발이 영영 안 만들어진다.
        assertThat(log.remembered.values()).singleElement()
                .satisfies(seen -> assertThat(seen.receivedAt()).isEqualTo(instant("09:00:45")));
    }

    @Test
    @DisplayName("중복 두 행이 맞닿은 역이면 최신 행을 쓴다 — 앞뒤 스냅샷이다")
    void picksLatestRowWhenDuplicateStationsAreAdjacent() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));

        int created = tracker.observe(complete("09:01:10",
                row("성수", SubwayTrainStatus.DEPARTED, "09:00:30"),
                row("건대입구", SubwayTrainStatus.ARRIVED, "09:00:45")));

        assertThat(created).isEqualTo(2);
        assertThat(log.events).extracting(SubwayTrainEvent::type)
                .containsExactly(SubwayTrainEvent.Type.DEPARTED, SubwayTrainEvent.Type.ARRIVED);
    }

    @Test
    @DisplayName("중복 두 행이 맞닿지도 않은 역이면 격리한다 — 열번이 겹친 다른 열차일 수 있다")
    void isolatesDuplicateWhenStationsAreNotAdjacent() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));

        int created = tracker.observe(complete("09:01:10",
                row("잠실", SubwayTrainStatus.ARRIVED, "09:00:30"),
                row("건대입구", SubwayTrainStatus.ARRIVED, "09:00:45")));

        assertThat(created).isZero();
        assertThat(log.events).isEmpty();
        assertThat(log.remembered.values()).singleElement()
                .satisfies(seen -> assertThat(seen.receivedAt()).isEqualTo(instant("09:00:00")));
    }

    @Test
    @DisplayName("같은 행이 그대로 복제됐으면 한 번만 반영한다")
    void acceptsExactDuplicateRows() {
        tracker.observe(complete("09:00:10", row("성수", SubwayTrainStatus.ARRIVED, "09:00:00")));

        int created = tracker.observe(complete("09:01:10",
                row("성수", SubwayTrainStatus.DEPARTED, "09:00:45"),
                row("성수", SubwayTrainStatus.DEPARTED, "09:00:45")));

        assertThat(created).isEqualTo(1);
        assertThat(log.events).hasSize(1);
    }

    @Test
    @DisplayName("방향을 해석하지 못한 행은 추적하지 않는다")
    void skipsRowsWithoutDirection() {
        SubwayPositionInfo noDirection = new SubwayPositionInfo(LINE, TRAIN, "1002000211", "성수",
                null, "1002000211", "성수종착", SubwayTrainStatus.ARRIVED, SubwayExpressType.NORMAL,
                "2026-08-13 09:00:00", "20260813");

        tracker.observe(new SubwayPositionFetchResult(LINE, List.of(noDirection), 1,
                SubwayPositionFetchResult.Status.COMPLETE, instant("09:00:10"), null));

        assertThat(log.remembered).isEmpty();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────

    private SubwayTrainEvent only(SubwayTrainEvent.Type type) {
        List<SubwayTrainEvent> matched = log.events.stream().filter(e -> e.type() == type).toList();
        assertThat(matched).hasSize(1);
        return matched.get(0);
    }

    private SubwayPositionFetchResult complete(String fetchedAt, SubwayPositionInfo... rows) {
        return new SubwayPositionFetchResult(LINE, List.of(rows), rows.length,
                SubwayPositionFetchResult.Status.COMPLETE, instant(fetchedAt), null);
    }

    private SubwayPositionInfo row(String stationName, SubwayTrainStatus status, String receivedAt) {
        return new SubwayPositionInfo(LINE, TRAIN, "ID-" + stationName, stationName, DIRECTION,
                "1002000211", "성수종착", status, SubwayExpressType.NORMAL,
                "2026-08-13 " + receivedAt, "20260813");
    }

    private static Instant instant(String hms) {
        return LocalDateTime.parse("2026-08-13 " + hms, FMT).atZone(SEOUL).toInstant();
    }

    /** 저장소 대역. 마지막 위치를 들고 있고 사건은 그대로 쌓는다. */
    private static class FakeLog implements SubwayTrainLogPort {
        final Map<TrainIdentity, SubwayTrainObservation> remembered = new HashMap<>();
        final List<SubwayTrainEvent> events = new ArrayList<>();

        @Override
        public Optional<SubwayTrainObservation> lastSeen(TrainIdentity train) {
            return Optional.ofNullable(remembered.get(train));
        }

        @Override
        public void remember(TrainIdentity train, SubwayTrainObservation observation) {
            remembered.put(train, observation);
        }

        @Override
        public void append(TrainIdentity train, SubwayTrainEvent event) {
            events.add(event);
        }

        @Override
        public List<TrainDeparture> findDepartures(LocalDate serviceDate, String lineId, String direction,
                                                   String stationId, Instant from, Instant to) {
            return List.of();
        }

        @Override
        public List<SubwayTrainEvent> findArrivals(TrainIdentity train, Instant from, Instant to) {
            return List.of();
        }
    }
}
