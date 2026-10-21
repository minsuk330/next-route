package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingApproach;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.StationsAheadCalculator.Stop;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 승차역 전 열차의 현재 역(#368). 신분당선 강남(승차) ← 양재 ← 양재시민의숲 ← 청계산입구. */
@DisplayName("다가오는 열차 위치")
class SubwayRingApproachReaderTest {

    private static final Instant NOW = Instant.parse("2026-10-11T09:30:00Z");
    private static final String LINE = "1077";
    private static final String DIR = "상행";
    private static final String GANGNAM = "1077006801";
    private static final TrainIdentity TRAIN = new TrainIdentity(LocalDate.parse("2026-10-11"), LINE, DIR, "4021");

    private final SubwayTrainLogPort trainLog = mock(SubwayTrainLogPort.class);
    private final SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
    private final SubwayRingApproachReader reader =
            new SubwayRingApproachReader(trainLog, lineOrder, new JourneySubwayRingProperties());
    private final SubwayRingChain chain = new SubwayRingChain(List.of(
            new SubwayRingChain.Station(0, GANGNAM, "강남"),
            new SubwayRingChain.Station(1, "1077006800", "신논현"),
            new SubwayRingChain.Station(2, "1077006799", "신사")));

    {
        // 먼 역 → 가까운 역(마지막이 승차역 바로 앞).
        when(lineOrder.behind(eq(LINE), eq(DIR), eq(GANGNAM), anyInt())).thenReturn(List.of(
                new Stop("k", "1077006804", "청계산입구", null, null),
                new Stop("k", "1077006803", "양재시민의숲", null, null),
                new Stop("k", "1077006802", "양재", null, null)));
    }

    @Test
    @DisplayName("2역 전 양재시민의숲: 이름·남은 역 수·지나올 역(열차 쪽부터)")
    void twoStationsAway() {
        seenAt("1077006803", NOW.minusSeconds(20));

        SubwayRingApproach approach = reader.read(waiting(), null, chain, TRAIN, NOW);

        assertThat(approach.stationName()).isEqualTo("양재시민의숲");
        assertThat(approach.stationsUntilBoarding()).isEqualTo(2);
        assertThat(approach.stations()).containsExactly("양재시민의숲", "양재");
    }

    @Test
    @DisplayName("승차역 바로 앞 역이면 1")
    void oneStationAway() {
        seenAt("1077006802", NOW.minusSeconds(20));

        assertThat(reader.read(waiting(), null, chain, TRAIN, NOW).stationsUntilBoarding()).isEqualTo(1);
    }

    @Test
    @DisplayName("열차가 승차역에 오면 null — 기존 링이 맡는다")
    void atBoardingIsNull() {
        seenAt(GANGNAM, NOW.minusSeconds(20));

        assertThat(reader.read(waiting(), null, chain, TRAIN, NOW)).isNull();
    }

    @Test
    @DisplayName("대기 구간이 아니거나(승차 뒤) 링이 STALE 이면 null")
    void outsideWaitIsNull() {
        seenAt("1077006803", NOW.minusSeconds(20));

        assertThat(reader.read(ring(1, SubwayRingQuality.NORMAL), null, chain, TRAIN, NOW)).isNull();
        assertThat(reader.read(ring(0, SubwayRingQuality.STALE), null, chain, TRAIN, NOW)).isNull();
        assertThat(reader.read(waiting(), null, chain, null, NOW)).isNull();
    }

    @Test
    @DisplayName("앞쪽 목록에 없는 역(더 멀거나 다른 갈래)은 모름 — 지어내지 않는다")
    void unknownStationIsNull() {
        seenAt("1077009999", NOW.minusSeconds(20));

        assertThat(reader.read(waiting(), null, chain, TRAIN, NOW)).isNull();
    }

    @Test
    @DisplayName("관측이 낡거나 없으면 같은 대기 구간의 직전 값을 잇고, 직전 값이 없으면 null")
    void staleObservationCarriesPrevious() {
        seenAt("1077006804", NOW.minusSeconds(600));
        SubwayRingApproach before = new SubwayRingApproach("양재", 1, List.of("양재"));

        assertThat(reader.read(waiting(), waiting().withApproach(before), chain, TRAIN, NOW)).isEqualTo(before);
        assertThat(reader.read(waiting(), null, chain, TRAIN, NOW)).isNull();
        when(trainLog.lastSeen(TRAIN)).thenReturn(Optional.empty());
        assertThat(reader.read(waiting(), waiting().withApproach(before), chain, TRAIN, NOW)).isEqualTo(before);
    }

    @Test
    @DisplayName("급행(subway-express PR-S4): 남은 역은 열차가 서는 역만 센다 — 지금 있는 역은 지나치는 역이어도 위치로 남긴다")
    void expressCountsOnlyStops() {
        // 가상의 급행: 청계산입구·양재 정차, 양재시민의숲 통과
        var service = new watoo.grd.nextroute.application.subway.service.SubwayExpressCatalog.ExpressService(
                "1077-test", LINE, "급행", DIR, "t", null, List.of(), false);
        var pattern = new watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.ResolvedPattern(
                service, DIR, List.of("1077006804", "1077006802", GANGNAM), null);
        SubwayRingChain express = new SubwayRingChain(chain.stations(), pattern);

        seenAt("1077006804", NOW.minusSeconds(20));
        SubwayRingApproach fromStop = reader.read(waiting(), null, express, TRAIN, NOW);
        assertThat(fromStop.stations()).containsExactly("청계산입구", "양재");
        assertThat(fromStop.stationsUntilBoarding()).isEqualTo(2);

        seenAt("1077006803", NOW.minusSeconds(20));
        SubwayRingApproach fromPassing = reader.read(waiting(), null, express, TRAIN, NOW);
        assertThat(fromPassing.stationName()).isEqualTo("양재시민의숲");
        assertThat(fromPassing.stations()).containsExactly("양재시민의숲", "양재");
    }

    private void seenAt(String stationId, Instant at) {
        when(trainLog.lastSeen(TRAIN)).thenReturn(Optional.of(
                new SubwayTrainObservation(stationId, null, SubwayTrainStatus.DEPARTED, at, null, null)));
    }

    private static SubwayRingState waiting() {
        return ring(0, SubwayRingQuality.NORMAL);
    }

    private static SubwayRingState ring(int sequence, SubwayRingQuality quality) {
        return new SubwayRingState(1L, 1, 1, 0,
                sequence, sequence == 0 ? GANGNAM : "1077006800", sequence == 0 ? "강남" : "신논현", "신사", 2,
                BigDecimal.ZERO, NOW, BigDecimal.ZERO, null, NOW.plusSeconds(60),
                SubwayRingPhase.RIDE_SUBWAY, quality, NOW, NOW, 1L);
    }
}
