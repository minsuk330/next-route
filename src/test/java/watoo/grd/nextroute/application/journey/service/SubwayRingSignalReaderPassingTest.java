package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 급행이 지나치는 역의 실시간 신호(subway-express PR-S4, codex 검토). 지나치는 역 관측은 구간 <b>안</b>의 진행(PASSING)이다 —
 * 정차·도착·구간 밖(PASSED)이 아니다.
 */
@DisplayName("링 신호 읽기 — 급행이 지나치는 역")
class SubwayRingSignalReaderPassingTest {

    private static final String LINE = "1009";
    private static final String DIR = "상행";
    private static final String GAYANG = "1009000907";
    private static final String JEUNGMI = "1009000908";
    private static final String DEUNGCHON = "1009000909";
    private static final String YEOMCHANG = "1009000910";
    private static final String SINMOKDONG = "1009000911";
    private static final String SEONYUDO = "1009000912";
    private static final String DANGSAN = "1009000913";
    /** 2026-10-21 21:40:11 KST */
    private static final Instant RECEIVED = Instant.parse("2026-10-21T12:40:11Z");

    private final SubwayRealtimeCachePort cache = mock(SubwayRealtimeCachePort.class);
    private final SubwayTrainLogPort trainLog = mock(SubwayTrainLogPort.class);
    private final SubwaySegmentTimePort segments = mock(SubwaySegmentTimePort.class);
    private final SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
    private final SubwayRingSignalReader reader = new SubwayRingSignalReader(cache, trainLog, segments, lineOrder);

    private final TrainIdentity train = new TrainIdentity(LocalDate.parse("2026-10-21"), LINE, DIR, "9712");
    /** 가양(0) →[증미·등촌]→ 염창(1) →[신목동·선유도]→ 당산(2). */
    private final SubwayRingChain chain = new SubwayRingChain(List.of(
            new SubwayRingChain.Station(0, GAYANG, "가양"),
            new SubwayRingChain.Station(1, YEOMCHANG, "염창", List.of(JEUNGMI, DEUNGCHON)),
            new SubwayRingChain.Station(2, DANGSAN, "당산", List.of(SINMOKDONG, SEONYUDO))));

    @BeforeEach
    void allStopTimes() {
        // 완행 운행: 가양→증미 60, 증미→등촌 90, 등촌→염창 90 (누적 비율 0.25·0.625)
        given(lineOrder.segmentTimes(LINE, DIR, GAYANG, JEUNGMI))
                .willReturn(Optional.of(new SubwayLineOrder.SegmentTimes(30, 60)));
        given(lineOrder.segmentTimes(LINE, DIR, JEUNGMI, DEUNGCHON))
                .willReturn(Optional.of(new SubwayLineOrder.SegmentTimes(30, 90)));
        given(lineOrder.segmentTimes(LINE, DIR, DEUNGCHON, YEOMCHANG))
                .willReturn(Optional.of(new SubwayLineOrder.SegmentTimes(30, 90)));
        given(trainLog.findArrivals(eq(train), any(), any())).willReturn(List.of());
    }

    @Test
    @DisplayName("도착 행 없음 + 위치가 지나치는 역 → PASSING(구간 안), PASSED(하차역 도착)로 뛰지 않는다")
    void passingStationIsInsideSegment() {
        given(cache.readSnapshot()).willReturn(Optional.empty());
        position(DEUNGCHON, SubwayTrainStatus.ARRIVED, RECEIVED);
        // 이력에 구간 역(가양) 도착이 있어도 — 예전엔 등촌이 구간 밖이라 PASSED 였다
        given(trainLog.findArrivals(eq(train), any(), any())).willReturn(List.of(
                arrived(GAYANG, RECEIVED.minusSeconds(200)), arrived(DEUNGCHON, RECEIVED.minusSeconds(20))));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.source()).isEqualTo(SubwayRingObservation.Source.PASSING);
        assertThat(observation.observedStationSequence()).isEqualTo(1);
        assertThat(observation.passingFraction()).isEqualByComparingTo("0.625");
        assertThat(observation.passedAt()).as("사건 시각 = 그 역에 처음 보인 때").isEqualTo(RECEIVED.minusSeconds(20));
        assertThat(observation.observedAt()).isEqualTo(RECEIVED);
    }

    @Test
    @DisplayName("구간 시간을 모르면 역 수로 고르게 나눈다")
    void uniformFractionWithoutTimes() {
        given(cache.readSnapshot()).willReturn(Optional.empty());
        position(SINMOKDONG, SubwayTrainStatus.DEPARTED, RECEIVED);

        SubwayRingObservation observation = reader.read(train, chain, 2);

        assertThat(observation.source()).isEqualTo(SubwayRingObservation.Source.PASSING);
        assertThat(observation.observedStationSequence()).isEqualTo(2);
        assertThat(observation.passingFraction()).isEqualByComparingTo("0.3333");
    }

    @Test
    @DisplayName("목표 역 도착 행 4·5(전역 진입·도착) + 지나치는 역이 앞에 → PASSING(마지막 지나친 역), 정차가 아니다")
    void prevArrivedBecomesPassing() {
        snapshot(row(YEOMCHANG, "5", 60));
        given(trainLog.lastSeen(train)).willReturn(Optional.empty());

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.source()).isEqualTo(SubwayRingObservation.Source.PASSING);
        assertThat(observation.passingFraction()).isEqualByComparingTo("0.625");
        assertThat(observation.code()).isNull();
    }

    @Test
    @DisplayName("일반 체인(지나치는 역 없음)의 4·5 는 예전대로 전역 정차")
    void prevArrivedOnNormalChainUnchanged() {
        SubwayRingChain normal = new SubwayRingChain(List.of(
                new SubwayRingChain.Station(0, DEUNGCHON, "등촌"),
                new SubwayRingChain.Station(1, YEOMCHANG, "염창")));
        snapshot(row(YEOMCHANG, "5", 60));
        given(trainLog.lastSeen(train)).willReturn(Optional.empty());

        SubwayRingObservation observation = reader.read(train, normal, 1);

        assertThat(observation.source()).isEqualTo(SubwayRingObservation.Source.ARRIVAL);
        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.PREV_ARRIVED);
    }

    @Test
    @DisplayName("목표 역 3(전역 출발)은 실측 그대로 — 표본(완행 전역=등촌 → 염창)과 관측 구간이 같아 쓴다, 진행 하한은 등촌 지점")
    void prevDepartedMeasuredWithFloor() {
        snapshot(row(YEOMCHANG, "3", 140));
        given(trainLog.lastSeen(train)).willReturn(Optional.empty());
        given(segments.recentSeconds(LINE, DIR, YEOMCHANG)).willReturn(List.of(80L));
        given(segments.departedAt(LINE, DIR, YEOMCHANG, "9712")).willReturn(Optional.of(RECEIVED.minusSeconds(10)));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.isMeasured()).isTrue();
        assertThat(observation.estimatedArrivalAt()).isEqualTo(RECEIVED.minusSeconds(10).plusSeconds(80));
        assertThat(observation.passingFraction()).isEqualByComparingTo("0.625");
    }

    @Test
    @DisplayName("목표 역 3 인데 표본이 없으면 예정 없는 전역 출발이 아니라 PASSING — 구간 전체를 새로 세지 않게")
    void prevDepartedWithoutSampleIsPassing() {
        snapshot(row(YEOMCHANG, "3", 140));
        given(trainLog.lastSeen(train)).willReturn(Optional.empty());
        given(segments.recentSeconds(LINE, DIR, YEOMCHANG)).willReturn(List.of());

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.source()).isEqualTo(SubwayRingObservation.Source.PASSING);
        assertThat(observation.passingFraction()).isEqualByComparingTo("0.625");
    }

    @Test
    @DisplayName("위치 trainSttus=3(전역출발) at 목표 역 — 떠난 역(fromKey)은 마지막 지나친 역(등촌)이다")
    void leftPreviousFromKeyIsLastPassing() {
        snapshot(row(YEOMCHANG, "99", 200));
        position(YEOMCHANG, SubwayTrainStatus.LEFT_PREVIOUS, RECEIVED.plusSeconds(5));
        given(trainLog.findDepartures(any(), eq(LINE), eq(DIR), anyString(), any(), any())).willReturn(List.of());
        given(segments.recentSeconds(LINE, DIR, YEOMCHANG)).willReturn(List.of(80L));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        verify(trainLog).findDepartures(any(), eq(LINE), eq(DIR), eq(DEUNGCHON), any(), any());
        verify(trainLog, never()).findDepartures(any(), eq(LINE), eq(DIR), eq(GAYANG), any(), any());
        assertThat(observation.isMeasured()).isTrue();
        assertThat(observation.passingFraction()).isEqualByComparingTo("0.625");
    }

    @Test
    @DisplayName("앞 정차역(가양) 출발 — 관측 구간(가양→염창)이 표본 구간(등촌→염창)과 달라 실측을 쓰지 않는다")
    void departedFromPreviousStopIgnoresMismatchedSamples() {
        snapshot(row(YEOMCHANG, "99", 200));
        position(GAYANG, SubwayTrainStatus.DEPARTED, RECEIVED.plusSeconds(5));
        given(trainLog.findDepartures(any(), any(), any(), any(), any(), any())).willReturn(List.of());
        given(segments.recentSeconds(LINE, DIR, YEOMCHANG)).willReturn(List.of(80L));

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.code()).isEqualTo(SubwayArrivalCode.PREV_DEPARTED);
        assertThat(observation.isMeasured()).isFalse();
        verify(segments, never()).recentSeconds(LINE, DIR, YEOMCHANG);
    }

    @Test
    @DisplayName("도착 행(99)보다 새로운 지나치는 역 위치가 있으면 위치를 쓴다, 도착 행이 목표 역 진입(0)이면 도착 행")
    void positionAheadOfArrival() {
        snapshot(row(YEOMCHANG, "99", 200));
        position(DEUNGCHON, SubwayTrainStatus.ARRIVED, RECEIVED.plusSeconds(10));
        assertThat(reader.read(train, chain, 1).source()).isEqualTo(SubwayRingObservation.Source.PASSING);

        snapshot(row(YEOMCHANG, "0", 0));
        assertThat(reader.read(train, chain, 1).source()).isEqualTo(SubwayRingObservation.Source.ARRIVAL);
    }

    @Test
    @DisplayName("뒤 구간의 지나치는 역 위치는 도착 행을 이기지 못한다(되돌리지 않는다)")
    void behindPassingIgnored() {
        snapshot(row(DANGSAN, "99", 200));
        position(DEUNGCHON, SubwayTrainStatus.ARRIVED, RECEIVED.plusSeconds(10));

        assertThat(reader.read(train, chain, 2).source()).isEqualTo(SubwayRingObservation.Source.ARRIVAL);
    }

    @Test
    @DisplayName("다음 정차역 trainSttus=3 이 목표보다 앞이면(정차역 도착을 놓침) 그 구간의 마지막 지나친 역 진행")
    void leftPreviousAheadBecomesPassing() {
        given(cache.readSnapshot()).willReturn(Optional.empty());
        position(DANGSAN, SubwayTrainStatus.LEFT_PREVIOUS, RECEIVED);

        SubwayRingObservation observation = reader.read(train, chain, 1);

        assertThat(observation.source()).isEqualTo(SubwayRingObservation.Source.PASSING);
        assertThat(observation.observedStationSequence()).isEqualTo(2);
        assertThat(observation.passingFraction()).isEqualByComparingTo(new BigDecimal("0.6667"));
    }

    private void position(String stationId, SubwayTrainStatus status, Instant at) {
        given(trainLog.lastSeen(train)).willReturn(Optional.of(new SubwayTrainObservation(stationId, "역", status, at,
                "1009000938", SubwayExpressType.EXPRESS, "1")));
    }

    private SubwayTrainEvent arrived(String stationId, Instant at) {
        return new SubwayTrainEvent(SubwayTrainEvent.Type.ARRIVED, stationId, "역", at.minusSeconds(15), at,
                SubwayTrainEvent.Quality.CORROBORATED, "1009000938", SubwayExpressType.EXPRESS);
    }

    private void snapshot(SubwayRealtimeTrain row) {
        given(cache.readSnapshot()).willReturn(Optional.of(SubwayRealtimeSnapshot.builder().trains(List.of(row)).build()));
    }

    private SubwayRealtimeTrain row(String stationId, String code, int seconds) {
        return SubwayRealtimeTrain.builder().lineId(LINE).direction(DIR).stationId(stationId).trainNo("9712")
                .arrivalCode(code).arrivalSeconds(seconds).receivedAt("2026-10-21 21:40:11").build();
    }
}
