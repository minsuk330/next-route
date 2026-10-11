package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** 하차역 도착 뒤 우리 열차가 하차역을 떠난 시각(#380) — 앱이 120초 추정 대신 탑승을 끝낸다. */
@DisplayName("링 — 하차역 출발")
class SubwayRingTrainLeftReaderTest {

    private static final Instant ARRIVED = Instant.parse("2026-10-11T13:13:17Z");
    private static final String ALIGHT = "1002000214";
    private static final TrainIdentity TRAIN = new TrainIdentity(LocalDate.of(2026, 10, 11), "1002", "외선", "3377");

    private final SubwayTrainLogPort trainLog = mock(SubwayTrainLogPort.class);
    private final SubwayRingTrainLeftReader reader = new SubwayRingTrainLeftReader(trainLog);
    private final SubwayRingChain chain = new SubwayRingChain(List.of(
            new SubwayRingChain.Station(0, "1002000213", "잠실나루"),
            new SubwayRingChain.Station(1, ALIGHT, "강변")));

    @Test
    @DisplayName("하차역 도착 전이면 null")
    void beforeAlightingArrival() {
        assertThat(reader.read(ring(new BigDecimal("0.95")), null, chain, TRAIN, ARRIVED.plusSeconds(60))).isNull();
    }

    @Test
    @DisplayName("하차역에 선 뒤 위치 로그의 하차역 출발 사건 — 도착보다 이른 출발(이전 운행)은 무시")
    void departureEventAfterArrival() {
        given(trainLog.findDepartures(eq(TRAIN.serviceDate()), eq("1002"), eq("외선"), eq(ALIGHT), any(), any()))
                .willReturn(List.of(departure("3377", ARRIVED.minusSeconds(3000)), departure("9999", ARRIVED.plusSeconds(60)),
                        departure("3377", ARRIVED.plusSeconds(76))));

        assertThat(reader.read(ring(BigDecimal.ONE), null, chain, TRAIN, ARRIVED.plusSeconds(120)))
                .isEqualTo(ARRIVED.plusSeconds(76));
    }

    @Test
    @DisplayName("사건이 아직 없으면 지금 위치가 하차역 trainSttus=2 인지 본다")
    void lastSeenDeparted() {
        given(trainLog.lastSeen(TRAIN)).willReturn(Optional.of(new SubwayTrainObservation(ALIGHT, "강변",
                SubwayTrainStatus.DEPARTED, ARRIVED.plusSeconds(70), "x", SubwayExpressType.NORMAL)));

        assertThat(reader.read(ring(BigDecimal.ONE), null, chain, TRAIN, ARRIVED.plusSeconds(100)))
                .isEqualTo(ARRIVED.plusSeconds(70));
    }

    @Test
    @DisplayName("하차역에 서 있으면 null, 한 번 채운 값은 같은 고정 세대에서 이어 쓴다")
    void stillStandingThenSticky() {
        given(trainLog.lastSeen(TRAIN)).willReturn(Optional.of(new SubwayTrainObservation(ALIGHT, "강변",
                SubwayTrainStatus.ARRIVED, ARRIVED, "x", SubwayExpressType.NORMAL)));
        assertThat(reader.read(ring(BigDecimal.ONE), null, chain, TRAIN, ARRIVED.plusSeconds(30))).isNull();

        SubwayRingState previous = ring(BigDecimal.ONE).withTrainLeftAlightingAt(ARRIVED.plusSeconds(76));
        assertThat(reader.read(ring(BigDecimal.ONE), previous, chain, TRAIN, ARRIVED.plusSeconds(200)))
                .isEqualTo(ARRIVED.plusSeconds(76));
    }

    private SubwayRingState ring(BigDecimal progress) {
        return new SubwayRingState(227L, 1, 2, 0, 1, ALIGHT, "강변", "강변", 0, progress, ARRIVED, progress,
                null, ARRIVED.plusSeconds(120), SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, ARRIVED, ARRIVED);
    }

    private SubwayTrainLogPort.TrainDeparture departure(String trainNo, Instant at) {
        return new SubwayTrainLogPort.TrainDeparture(trainNo, new SubwayTrainEvent(SubwayTrainEvent.Type.DEPARTED, ALIGHT,
                "강변", at.minusSeconds(15), at, SubwayTrainEvent.Quality.CORROBORATED, "x", SubwayExpressType.NORMAL));
    }
}
