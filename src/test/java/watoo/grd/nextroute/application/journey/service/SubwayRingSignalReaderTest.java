package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 전역 출발(3) 구간의 도착 예정은 앞 열차 실측으로 만든다 — API 초는 쓰지 않는다(2026-10-07 결정).
 */
@DisplayName("링 신호 읽기 — 실측 예정")
class SubwayRingSignalReaderTest {

    private static final String LINE = "1002";
    private static final String DIR = "내선";
    private static final String NEXT = "1002000234";
    /** 2026-10-07 22:10:11 KST */
    private static final Instant RECEIVED = Instant.parse("2026-10-07T13:10:11Z");

    private final SubwayRealtimeCachePort cache = mock(SubwayRealtimeCachePort.class);
    private final SubwayTrainLogPort trainLog = mock(SubwayTrainLogPort.class);
    private final SubwaySegmentTimePort segments = mock(SubwaySegmentTimePort.class);
    private final watoo.grd.nextroute.application.subway.service.SubwayLineOrder lineOrder =
            mock(watoo.grd.nextroute.application.subway.service.SubwayLineOrder.class);
    private final SubwayRingSignalReader reader = new SubwayRingSignalReader(cache, trainLog, segments, lineOrder);

    private final TrainIdentity train = new TrainIdentity(LocalDate.parse("2026-10-07"), LINE, DIR, "7462");
    private final SubwayRingChain chain = new SubwayRingChain(List.of(
            new SubwayRingChain.Station(0, "1002000233", "대림"),
            new SubwayRingChain.Station(1, NEXT, "신도림")));

    @Test
    @DisplayName("전역 출발 + 표본 → 예정 = 출발을 처음 본 시각 + 표본 중앙값, API 초(140)는 무시")
    void departedUsesMeasuredMedian() {
        snapshot(row("3", 140));
        given(segments.recentSeconds(LINE, DIR, NEXT)).willReturn(List.of(60L, 40L, 50L));
        given(segments.departedAt(LINE, DIR, NEXT, "7462")).willReturn(Optional.of(RECEIVED.minusSeconds(32)));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.isMeasured()).isTrue();
        assertThat(observation.segmentStartAt()).isEqualTo(RECEIVED.minusSeconds(32));
        assertThat(observation.estimatedArrivalAt()).isEqualTo(RECEIVED.minusSeconds(32).plusSeconds(50));
        assertThat(observation.observedAt()).isEqualTo(RECEIVED);
    }

    @Test
    @DisplayName("출발 기록이 없으면 그 행의 수신 시각을 출발로 본다")
    void missingDepartureFallsBackToRow() {
        snapshot(row("3", 140));
        given(segments.recentSeconds(LINE, DIR, NEXT)).willReturn(List.of(45L));
        given(segments.departedAt(LINE, DIR, NEXT, "7462")).willReturn(Optional.empty());

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.segmentStartAt()).isEqualTo(RECEIVED);
        assertThat(observation.estimatedArrivalAt()).isEqualTo(RECEIVED.plusSeconds(45));
    }

    @Test
    @DisplayName("표본이 없으면 예정 없음 — API 초로 채우지 않는다")
    void noSampleNoEta() {
        snapshot(row("3", 140));
        given(segments.recentSeconds(LINE, DIR, NEXT)).willReturn(List.of());

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.PREV_DEPARTED);
        assertThat(observation.estimatedArrivalAt()).isNull();
        assertThat(observation.isMeasured()).isFalse();
    }

    @Test
    @DisplayName("전역 정차(5)는 예정 없음, 2역 이상 전(99)은 기존대로 API 초")
    void dwellNoEtaEnrouteKeepsApi() {
        snapshot(row("5", 60));
        assertThat(reader.read(train, chain, 1).estimatedArrivalAt()).isNull();

        snapshot(row("99", 300));
        assertThat(reader.read(train, chain, 1).estimatedArrivalAt()).isEqualTo(RECEIVED.plusSeconds(300));
    }

    // ── 이미 구간을 지난 열차 (#311) ─────────────────────────────────────────

    @Test
    @DisplayName("도착 행이 없고 위치가 구간 밖인데, 이력에 구간 역 도착이 있으면 → 하차역 도착(PASSED)")
    void passedChainFinishes() {
        given(cache.readSnapshot()).willReturn(Optional.empty());
        given(trainLog.lastSeen(train)).willReturn(Optional.of(observation("1002000235", RECEIVED)));
        given(trainLog.findArrivals(org.mockito.ArgumentMatchers.eq(train), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).willReturn(List.of(arrivedAt(NEXT, RECEIVED.minusSeconds(240))));

        SubwayRingObservation observation = reader.read(train, chain, 0);

        assertThat(observation.source()).isEqualTo(SubwayRingObservation.Source.PASSED);
        assertThat(observation.observedStationSequence()).isEqualTo(1);
        assertThat(observation.observedAt()).isEqualTo(RECEIVED);
    }

    @Test
    @DisplayName("구간 밖인데 구간 역 도착 이력이 없으면(아직 승차역 전) 판단하지 않는다")
    void upstreamTrainIsUnknown() {
        given(cache.readSnapshot()).willReturn(Optional.empty());
        given(trainLog.lastSeen(train)).willReturn(Optional.of(observation("1002000230", RECEIVED)));
        given(trainLog.findArrivals(org.mockito.ArgumentMatchers.eq(train), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).willReturn(List.of(arrivedAt("1002000229", RECEIVED.minusSeconds(60))));

        assertThat(reader.read(train, chain, 0)).isNull();
    }

    // ── 두 원천 중 앞선 쪽 (#371) ───────────────────────────────────────────

    private static final String PREV = "1002000233";

    @Test
    @DisplayName("도착 행은 아직 99인데 위치가 전역 도착을 말하면 → 전역 정차(PREV_ARRIVED), 출처 위치")
    void positionPrevArrivedBeatsEnroute() {
        snapshot(row("99", 120));
        position(PREV, SubwayTrainStatus.ARRIVED, RECEIVED.plusSeconds(5));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.source()).as("계산기 전역 처리를 타야 한다").isEqualTo(SubwayRingObservation.Source.ARRIVAL);
        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.PREV_ARRIVED);
        assertThat(observation.observedStationSequence()).isEqualTo(1);
        assertThat(observation.observedAt()).isEqualTo(RECEIVED.plusSeconds(5));
        assertThat(observation.effectiveOrigin()).isEqualTo(SubwayRingObservation.Origin.POSITION_API);
    }

    @Test
    @DisplayName("도착 행은 전역 정차(5)인데 위치가 전역 출발(2) → 실측 직선, 키 = target 역, 출발 = 위치 로그 출발 사건")
    void positionPrevDepartedStartsMeasuredLine() {
        snapshot(row("5", 60));
        position(PREV, SubwayTrainStatus.DEPARTED, RECEIVED.plusSeconds(30));
        given(trainLog.findDepartures(eq(train.serviceDate()), eq(LINE), eq(DIR), eq(PREV), any(), any()))
                .willReturn(List.of(new SubwayTrainLogPort.TrainDeparture("9999", departedEvent(PREV, RECEIVED)),
                        new SubwayTrainLogPort.TrainDeparture("7462", departedEvent(PREV, RECEIVED.plusSeconds(20)))));
        given(segments.recentSeconds(LINE, DIR, NEXT)).willReturn(List.of(50L));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.PREV_DEPARTED);
        assertThat(observation.isMeasured()).isTrue();
        assertThat(observation.segmentStartAt()).isEqualTo(RECEIVED.plusSeconds(20));
        assertThat(observation.estimatedArrivalAt()).isEqualTo(RECEIVED.plusSeconds(70));
        assertThat(observation.effectiveOrigin()).isEqualTo(SubwayRingObservation.Origin.POSITION_API);
    }

    @Test
    @DisplayName("위치 trainSttus=3 은 그 역의 전역 출발 — target 역의 3 이면 전역 출발 실측, 출발 사건 없으면 관측 시각")
    void leftPreviousAtTargetIsPrevDeparted() {
        snapshot(row("5", 60));
        position(NEXT, SubwayTrainStatus.LEFT_PREVIOUS, RECEIVED.plusSeconds(30));
        given(segments.recentSeconds(LINE, DIR, NEXT)).willReturn(List.of(50L));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.PREV_DEPARTED);
        assertThat(observation.segmentStartAt()).isEqualTo(RECEIVED.plusSeconds(30));
    }

    @Test
    @DisplayName("도착 행이 99(남은 초)면 순위로 못 견준다 — 위치가 더 오래됐으면 도착 행(예정 유지)")
    void enrouteBeatsOlderPosition() {
        snapshot(row("99", 110));
        position(PREV, SubwayTrainStatus.ARRIVED, RECEIVED.minusSeconds(28));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.ENROUTE);
        assertThat(observation.estimatedArrivalAt()).isEqualTo(RECEIVED.plusSeconds(110));
    }

    @Test
    @DisplayName("진행이 같으면 도착 행 — 수신 시각이 더 새로워도 위치를 쓰지 않는다")
    void sameProgressKeepsArrival() {
        snapshot(row("3", 140));
        given(segments.recentSeconds(LINE, DIR, NEXT)).willReturn(List.of(50L));
        position(PREV, SubwayTrainStatus.DEPARTED, RECEIVED.plusSeconds(40));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.effectiveOrigin()).isEqualTo(SubwayRingObservation.Origin.ARRIVAL_API);
        assertThat(observation.observedAt()).isEqualTo(RECEIVED);
    }

    @Test
    @DisplayName("위치가 뒤처지면 도착 행 — 도착(1) vs 위치 진입(0)")
    void behindPositionIgnored() {
        snapshot(row("1", 0));
        position(NEXT, SubwayTrainStatus.ENTERING, RECEIVED.plusSeconds(40));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.ARRIVED);
        assertThat(observation.effectiveOrigin()).isEqualTo(SubwayRingObservation.Origin.ARRIVAL_API);
    }

    @Test
    @DisplayName("target 역 사건이 위치에서 앞서면 위치 관측 그대로 — 진입(0) vs 위치 도착(1)")
    void positionAtTargetAhead() {
        snapshot(row("0", 0));
        position(NEXT, SubwayTrainStatus.ARRIVED, RECEIVED.plusSeconds(10));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.source()).isEqualTo(SubwayRingObservation.Source.POSITION);
        assertThat(observation.positionStatus()).isEqualTo(SubwayTrainStatus.ARRIVED);
        assertThat(observation.observedStationSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("위치가 도착 행보다 2분 넘게 묵었으면 앞서 보여도 쓰지 않는다")
    void stalePositionIgnored() {
        snapshot(row("99", 120));
        position(PREV, SubwayTrainStatus.ARRIVED, RECEIVED.minusSeconds(121));

        assertThat(reader.read(train, chain, 1).effectiveOrigin())
                .isEqualTo(SubwayRingObservation.Origin.ARRIVAL_API);
    }

    // ── 승차 전 대기: 승차역 앞 역 (#379) ─────────────────────────────────────

    private static final String BEFORE_BOARDING = "1002000232";

    private void boardingPrevIs(String stationId) {
        given(lineOrder.behind(LINE, DIR, PREV, 1)).willReturn(List.of(
                new watoo.grd.nextroute.application.subway.service.StationsAheadCalculator.Stop(
                        "k", stationId, "구로디지털단지", null, null)));
    }

    private SubwayRealtimeTrain boardingRow(String code, int seconds) {
        return SubwayRealtimeTrain.builder().lineId(LINE).direction(DIR).stationId(PREV).trainNo("7462")
                .arrivalCode(code).arrivalSeconds(seconds).receivedAt("2026-10-07 22:10:11").build();
    }

    @Test
    @DisplayName("대기 링: 위치가 승차역 앞 역 출발을 먼저 보면 → 승차역 기준 전역 출발 실측, 출발 = 그 역 출발 사건")
    void boardingWaitUsesPositionBeforeBoarding() {
        boardingPrevIs(BEFORE_BOARDING);
        given(cache.readSnapshot()).willReturn(Optional.of(SubwayRealtimeSnapshot.builder()
                .trains(List.of(boardingRow("99", 150))).build()));
        position(BEFORE_BOARDING, SubwayTrainStatus.DEPARTED, RECEIVED.plusSeconds(20));
        given(trainLog.findDepartures(eq(train.serviceDate()), eq(LINE), eq(DIR), eq(BEFORE_BOARDING), any(), any()))
                .willReturn(List.of(new SubwayTrainLogPort.TrainDeparture("7462",
                        departedEvent(BEFORE_BOARDING, RECEIVED.plusSeconds(15)))));
        given(segments.recentSeconds(LINE, DIR, PREV)).willReturn(List.of(90L));

        SubwayRingObservation observation = reader.read(train, chain, 0);

        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.PREV_DEPARTED);
        assertThat(observation.observedStationSequence()).isEqualTo(0);
        assertThat(observation.segmentStartAt()).isEqualTo(RECEIVED.plusSeconds(15));
        assertThat(observation.estimatedArrivalAt()).isEqualTo(RECEIVED.plusSeconds(105));
        assertThat(observation.effectiveOrigin()).isEqualTo(SubwayRingObservation.Origin.POSITION_API);
    }

    @Test
    @DisplayName("대기 링: 승차역 앞 역이 아닌 구간 밖 역이면 도착 행")
    void boardingWaitIgnoresOtherOutsideStation() {
        boardingPrevIs(BEFORE_BOARDING);
        given(cache.readSnapshot()).willReturn(Optional.of(SubwayRealtimeSnapshot.builder()
                .trains(List.of(boardingRow("99", 150))).build()));
        position("1002000231", SubwayTrainStatus.DEPARTED, RECEIVED.plusSeconds(20));

        assertThat(reader.read(train, chain, 0).effectiveOrigin())
                .isEqualTo(SubwayRingObservation.Origin.ARRIVAL_API);
    }

    @Test
    @DisplayName("대기 링: 승차역의 trainSttus=3(전역 출발)은 승차역 앞 역 출발 — 전역 정차(5) 행보다 앞선다")
    void boardingLeftPreviousBeatsPrevArrived() {
        given(cache.readSnapshot()).willReturn(Optional.of(SubwayRealtimeSnapshot.builder()
                .trains(List.of(boardingRow("5", 60))).build()));
        position(PREV, SubwayTrainStatus.LEFT_PREVIOUS, RECEIVED.plusSeconds(20));

        SubwayRingObservation observation = reader.read(train, chain, 0);

        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.PREV_DEPARTED);
        assertThat(observation.effectiveOrigin()).isEqualTo(SubwayRingObservation.Origin.POSITION_API);
    }

    private void position(String stationId, SubwayTrainStatus status, Instant at) {
        given(trainLog.lastSeen(train)).willReturn(Optional.of(new SubwayTrainObservation(stationId, "역", status, at,
                "1002000211", SubwayExpressType.NORMAL)));
    }

    private SubwayTrainEvent departedEvent(String stationId, Instant at) {
        return new SubwayTrainEvent(SubwayTrainEvent.Type.DEPARTED, stationId, "역", at.minusSeconds(15), at,
                SubwayTrainEvent.Quality.CORROBORATED, "1002000211", SubwayExpressType.NORMAL);
    }

    private watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation observation(String stationId, Instant at) {
        return new watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation(stationId, "역",
                watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus.ARRIVED, at, "1002000211",
                watoo.grd.nextroute.application.subway.dto.SubwayExpressType.NORMAL);
    }

    private watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent arrivedAt(String stationId, Instant at) {
        return new watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent(
                watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent.Type.ARRIVED, stationId, "역",
                at.minusSeconds(60), at, watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent.Quality.CORROBORATED,
                "1002000211", watoo.grd.nextroute.application.subway.dto.SubwayExpressType.NORMAL);
    }

    private void snapshot(SubwayRealtimeTrain row) {
        given(cache.readSnapshot()).willReturn(Optional.of(SubwayRealtimeSnapshot.builder().trains(List.of(row)).build()));
    }

    private SubwayRealtimeTrain row(String code, int seconds) {
        return SubwayRealtimeTrain.builder().lineId(LINE).direction(DIR).stationId(NEXT).trainNo("7462")
                .arrivalCode(code).arrivalSeconds(seconds).receivedAt("2026-10-07 22:10:11").build();
    }
}
