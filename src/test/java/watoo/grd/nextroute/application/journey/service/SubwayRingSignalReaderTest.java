package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    private final SubwayRingSignalReader reader = new SubwayRingSignalReader(cache, trainLog, segments);

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

    private void snapshot(SubwayRealtimeTrain row) {
        given(cache.readSnapshot()).willReturn(Optional.of(SubwayRealtimeSnapshot.builder().trains(List.of(row)).build()));
    }

    private SubwayRealtimeTrain row(String code, int seconds) {
        return SubwayRealtimeTrain.builder().lineId(LINE).direction(DIR).stationId(NEXT).trainNo("7462")
                .arrivalCode(code).arrivalSeconds(seconds).receivedAt("2026-10-07 22:10:11").build();
    }
}
