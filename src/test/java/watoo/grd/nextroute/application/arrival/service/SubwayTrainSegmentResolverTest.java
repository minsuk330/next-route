package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.PositionSource;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Segment;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.SegmentSource;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.TrainStatus;
import watoo.grd.nextroute.application.arrival.service.SubwayTrainPositionResolver.Position;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;
import watoo.grd.nextroute.application.subway.service.SubwayLineStationIndex;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher.Candidate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SubwayTrainSegmentResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-07T07:00:00Z");
    private static final String LINE = "1002";
    private static final String DIR = "내선";
    // 내선 진행: 선릉(S) → 역삼(Y) → 강남(G) → 교대(J) → 서초(C) → 방배(B)
    private static final String S = "S", Y = "Y", G = "G", J = "J", C = "C", B = "B";

    private final SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
    private final SubwaySegmentTimePort segmentTimePort = mock(SubwaySegmentTimePort.class);
    private final SubwayLineStationIndex stationIndex = mock(SubwayLineStationIndex.class);
    private final SubwayTrainSegmentResolver resolver =
            new SubwayTrainSegmentResolver(lineOrder, segmentTimePort, stationIndex);

    {
        List<String> order = List.of(S, Y, G, J, C, B);
        given(lineOrder.nextOf(any(), any(), any())).willAnswer(inv -> {
            int i = order.indexOf(inv.<String>getArgument(2));
            return i >= 0 && i + 1 < order.size() ? Optional.of(order.get(i + 1)) : Optional.empty();
        });
        given(lineOrder.prevOf(any(), any(), any())).willAnswer(inv -> {
            int i = order.indexOf(inv.<String>getArgument(2));
            return i > 0 ? Optional.of(order.get(i - 1)) : Optional.empty();
        });
        given(stationIndex.candidates(LINE)).willReturn(List.of(new Candidate(G, "강남", 0, 0),
                new Candidate(Y, "역삼", 0, 0)));
    }

    @Test
    @DisplayName("실측 표본이 있어도 도착 예정은 GTFS 계획 초, SCHEDULED — 실측은 진입 기준이라 짧다(2026-10-10)")
    void scheduledEvenWithMeasuredSamples() {
        given(segmentTimePort.departedAt(LINE, DIR, G, "2231")).willReturn(Optional.of(NOW.minusSeconds(30)));
        given(segmentTimePort.recentSeconds(LINE, DIR, G)).willReturn(List.of(16L, 21L, 25L));
        given(lineOrder.runSeconds(LINE, DIR, Y, G)).willReturn(Optional.of(90));

        Segment seg = resolver.segment(LINE, DIR, "2231", departedFrom(Y, PositionSource.POSITION));

        assertThat(seg.fromStationId()).isEqualTo(Y);
        assertThat(seg.toStationId()).isEqualTo(G);
        assertThat(seg.toStationName()).isEqualTo("강남");
        assertThat(seg.departedAt().toInstant()).isEqualTo(NOW.minusSeconds(30));
        assertThat(seg.expectedAt().toInstant()).isEqualTo(NOW.plusSeconds(60));
        assertThat(seg.source()).isEqualTo(SegmentSource.SCHEDULED);
    }

    @Test
    @DisplayName("GTFS 계획 초, SCHEDULED. 출발 기록이 없으면 위치 API 상태가 바뀐 시각")
    void scheduledFallback() {
        given(segmentTimePort.departedAt(any(), any(), any(), any())).willReturn(Optional.empty());
        given(lineOrder.runSeconds(LINE, DIR, Y, G)).willReturn(Optional.of(90));

        Segment seg = resolver.segment(LINE, DIR, "2231", departedFrom(Y, PositionSource.POSITION));

        assertThat(seg.departedAt().toInstant()).isEqualTo(NOW.minusSeconds(40)); // observedAt
        assertThat(seg.expectedAt().toInstant()).isEqualTo(NOW.plusSeconds(50));
        assertThat(seg.source()).isEqualTo(SegmentSource.SCHEDULED);
    }

    @Test
    @DisplayName("도착 API 원천은 출발 기록 없이는 구간을 만들지 않는다 — 확인 시각은 출발 시각이 아니다")
    void arrivalSourceNeedsMeasuredDeparture() {
        given(segmentTimePort.departedAt(any(), any(), any(), any())).willReturn(Optional.empty());

        assertThat(resolver.segment(LINE, DIR, "2231", departedFrom(Y, PositionSource.ARRIVAL))).isNull();
    }

    @Test
    @DisplayName("출발 시각은 아는데 예정을 모르면 expectedAt·source 가 null")
    void unknownDuration() {
        given(segmentTimePort.departedAt(any(), any(), any(), any())).willReturn(Optional.of(NOW.minusSeconds(10)));
        given(lineOrder.runSeconds(any(), any(), any(), any())).willReturn(Optional.empty());

        Segment seg = resolver.segment(LINE, DIR, "2231", departedFrom(Y, PositionSource.ARRIVAL));

        assertThat(seg.expectedAt()).isNull();
        assertThat(seg.source()).isNull();
    }

    @Test
    @DisplayName("정차·진입 중이거나 다음 역을 모르면 구간 없음, Redis 실패도 구간 없음")
    void noSegment() {
        Position arrived = new Position(Y, "역삼", TrainStatus.ARRIVED, NOW, PositionSource.POSITION);
        assertThat(resolver.segment(LINE, DIR, "2231", arrived)).isNull();
        assertThat(resolver.segment(LINE, DIR, "2231", departedFrom(B, PositionSource.POSITION))).isNull();

        given(segmentTimePort.departedAt(any(), any(), any(), any())).willThrow(new IllegalStateException("down"));
        assertThat(resolver.segment(LINE, DIR, "2231", departedFrom(Y, PositionSource.ARRIVAL))).isNull();
    }

    @Test
    @DisplayName("지난 열차: 하류 3역 안이면 지났다, 상류·조회역·더 먼 하류는 아니다")
    void passedByOrder() {
        assertThat(resolver.hasPassed(LINE, DIR, G, at(J), null)).isTrue();
        assertThat(resolver.hasPassed(LINE, DIR, G, at(B), null)).isTrue();
        assertThat(resolver.hasPassed(LINE, DIR, G, at(Y), null)).isFalse();
        assertThat(resolver.hasPassed(LINE, DIR, G, at(G), null)).isFalse();
        assertThat(resolver.hasPassed(LINE, DIR, G, null, null)).isFalse();
    }

    @Test
    @DisplayName("순환선처럼 하류이면서 상류이기도 하면 지나지 않은 것으로 본다")
    void loopOverlapIsNotPassed() {
        given(lineOrder.prevOf(LINE, DIR, G)).willReturn(Optional.of(J)); // 고리: 교대가 강남 앞이기도 하다

        assertThat(resolver.hasPassed(LINE, DIR, G, at(J), null)).isFalse();
    }

    @Test
    @DisplayName("위치 API 가 이 역 출발이라 했고 도착 행보다 최신이면 지났다, 더 오래됐으면 아니다")
    void positionDepartedFromBoard() {
        Position left = new Position(G, "강남", TrainStatus.DEPARTED, NOW, PositionSource.POSITION);

        assertThat(resolver.hasPassed(LINE, DIR, G, left, NOW.minusSeconds(30))).isTrue();
        assertThat(resolver.hasPassed(LINE, DIR, G, left, NOW.plusSeconds(5))).isFalse();
    }

    @Test
    @DisplayName("종착: 조회역 상류에서 열차 위치보다 먼저 종착역이 나오면 이 역에 오지 않는다(수원 상행 병점행)")
    void terminatesBeforeBoard() {
        given(stationIndex.candidates(LINE)).willReturn(List.of(new Candidate(S, "선릉", 0, 0),
                new Candidate(Y, "역삼", 0, 0), new Candidate(G, "강남", 0, 0), new Candidate(J, "교대", 0, 0)));

        // 교대(J) 조회, 열차는 선릉(S), 역삼행 — 역삼에서 끝난다
        assertThat(resolver.terminatesBefore(LINE, DIR, J, "역삼", at(S))).isTrue();
        // 열차가 이미 종착역보다 가까이(강남) 있으면 종착역은 지난 뒤다 — 순환선의 한 바퀴 열차
        assertThat(resolver.terminatesBefore(LINE, DIR, J, "역삼", at(G))).isFalse();
        // 이 역 종착은 온다
        assertThat(resolver.terminatesBefore(LINE, DIR, J, "교대", at(S))).isFalse();
        // 하류 종착·모르는 이름·위치 모름은 끝내지 않는다
        assertThat(resolver.terminatesBefore(LINE, DIR, J, "서초", at(S))).isFalse();
        assertThat(resolver.terminatesBefore(LINE, DIR, J, "병점", at(S))).isFalse();
        assertThat(resolver.terminatesBefore(LINE, DIR, J, "역삼", null)).isFalse();
        assertThat(resolver.terminatesBefore(LINE, DIR, J, null, at(S))).isFalse();
    }

    private static Position departedFrom(String station, PositionSource source) {
        return new Position(station, null, TrainStatus.DEPARTED, NOW.minusSeconds(40), source);
    }

    private static Position at(String station) {
        return new Position(station, null, TrainStatus.ARRIVED, NOW, PositionSource.POSITION);
    }
}
