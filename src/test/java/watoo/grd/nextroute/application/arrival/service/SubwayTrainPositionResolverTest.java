package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.PositionSource;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.TrainStatus;
import watoo.grd.nextroute.application.arrival.service.SubwayTrainPositionResolver.Placed;
import watoo.grd.nextroute.application.arrival.service.SubwayTrainPositionResolver.Position;
import watoo.grd.nextroute.application.subway.config.SubwayPositionProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;
import watoo.grd.nextroute.application.subway.service.SubwayLineStationIndex;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher.Candidate;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader.Arrival;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SubwayTrainPositionResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-07T07:00:00Z"); // 16:00 KST
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    private static final String LINE = "1002";
    private static final String GANGNAM = "1002000222";
    private static final String YEOKSAM = "1002000223";
    private static final String SEOLLEUNG = "1002000224";

    private final SubwayTrainLogPort logPort = mock(SubwayTrainLogPort.class);
    private final SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
    private final SubwayLineStationIndex stationIndex = mock(SubwayLineStationIndex.class);
    private final SubwayTrainPositionResolver resolver = new SubwayTrainPositionResolver(
            logPort, lineOrder, stationIndex, new SubwayPositionProperties(), new ServiceDateResolver());
    private final Map<TrainIdentity, SubwayTrainObservation> seen = new HashMap<>();

    {
        given(logPort.lastSeenAll(any())).willReturn(seen);
        given(stationIndex.candidates(LINE)).willReturn(List.of(
                new Candidate(GANGNAM, "강남", 0, 0), new Candidate(YEOKSAM, "역삼", 0, 0),
                new Candidate(SEOLLEUNG, "선릉", 0, 0)));
    }

    @Test
    @DisplayName("위치 API 진입·도착·출발은 그 역 그대로")
    void positionStatusesMapToSameStation() {
        assertThat(placeWithPosition(SubwayTrainStatus.ENTERING).status()).isEqualTo(TrainStatus.APPROACHING);
        assertThat(placeWithPosition(SubwayTrainStatus.ARRIVED).status()).isEqualTo(TrainStatus.ARRIVED);
        Position departed = placeWithPosition(SubwayTrainStatus.DEPARTED);
        assertThat(departed.status()).isEqualTo(TrainStatus.DEPARTED);
        assertThat(departed.stationId()).isEqualTo(YEOKSAM);
        assertThat(departed.source()).isEqualTo(PositionSource.POSITION);
    }

    @Test
    @DisplayName("위치 API 전역출발(3)은 그 역의 바로 전역을 떠난 것 — (전역, DEPARTED)")
    void leftPreviousMeansDepartedFromPreviousStation() {
        given(lineOrder.prevOf(LINE, "내선", YEOKSAM)).willReturn(Optional.of(SEOLLEUNG));

        Position p = placeWithPosition(SubwayTrainStatus.LEFT_PREVIOUS);

        assertThat(p.stationId()).isEqualTo(SEOLLEUNG);
        assertThat(p.stationName()).isEqualTo("선릉");
        assertThat(p.status()).isEqualTo(TrainStatus.DEPARTED);
        assertThat(p.source()).isEqualTo(PositionSource.POSITION);
    }

    @Test
    @DisplayName("전역출발인데 전역을 모르면(갈래 합류·캐시 없음) 도착 행으로 넘어간다")
    void leftPreviousWithoutKnownPreviousFallsBackToArrivalRow() {
        given(lineOrder.prevOf(any(), any(), any())).willReturn(Optional.empty());
        seen.put(id("내선", "2231"), obs(YEOKSAM, "역삼", SubwayTrainStatus.LEFT_PREVIOUS, 30));

        Position p = place(row("2231", "내선", "3").prevStationId(YEOKSAM).prevStationName("역삼").build());

        assertThat(p.source()).isEqualTo(PositionSource.ARRIVAL);
        assertThat(p.stationId()).isEqualTo(YEOKSAM);
        assertThat(p.status()).isEqualTo(TrainStatus.DEPARTED);
    }

    @Test
    @DisplayName("신선도 경계: 300초 이내만 위치 API 를 쓴다")
    void freshnessBoundary() {
        seen.put(id("내선", "2231"), obs(YEOKSAM, "역삼", SubwayTrainStatus.ARRIVED, 300));
        assertThat(place(row("2231", "내선", "99").build()).source()).isEqualTo(PositionSource.POSITION);

        seen.put(id("내선", "2231"), obs(YEOKSAM, "역삼", SubwayTrainStatus.ARRIVED, 301));
        assertThat(place(row("2231", "내선", "99").build())).isNull();
    }

    @Test
    @DisplayName("도착 행 대체: 0·1 은 조회역, 3·4·5 는 직전 역, 99 는 현재 역 이름")
    void arrivalRowFallback() {
        Position entering = place(row("1", "내선", "0").build());
        assertThat(entering).extracting(Position::stationId, Position::status, Position::source)
                .containsExactly(GANGNAM, TrainStatus.APPROACHING, PositionSource.ARRIVAL);
        assertThat(place(row("1", "내선", "1").build()).status()).isEqualTo(TrainStatus.ARRIVED);

        Position prevArrived = place(row("1", "내선", "5").prevStationId(YEOKSAM).build());
        assertThat(prevArrived).extracting(Position::stationId, Position::stationName, Position::status)
                .containsExactly(YEOKSAM, "역삼", TrainStatus.ARRIVED);
        assertThat(place(row("1", "내선", "4").prevStationId(YEOKSAM).build()).status())
                .isEqualTo(TrainStatus.APPROACHING);

        Position enroute = place(row("1", "내선", "99").currentStationName("선릉").build());
        assertThat(enroute).extracting(Position::stationId, Position::stationName, Position::status)
                .containsExactly(SEOLLEUNG, "선릉", null);
        assertThat(enroute.observedAt()).isEqualTo(NOW.minusSeconds(20));
    }

    @Test
    @DisplayName("반대 방향 관측이 더 최근이면 reversed, 이 방향이 더 최근이면 아니다")
    void oppositeDirectionNeedsNewerObservation() {
        seen.put(id("외선", "2231"), obs(YEOKSAM, "역삼", SubwayTrainStatus.DEPARTED, 30));
        assertThat(placed(row("2231", "내선", "99").build()).reversed()).isTrue();

        seen.put(id("내선", "2231"), obs(SEOLLEUNG, "선릉", SubwayTrainStatus.DEPARTED, 10));
        assertThat(placed(row("2231", "내선", "99").build()).reversed()).isFalse();
    }

    @Test
    @DisplayName("반대 방향 관측이 오래됐으면 빼지 않는다")
    void staleOppositeIsIgnored() {
        seen.put(id("외선", "2231"), obs(YEOKSAM, "역삼", SubwayTrainStatus.DEPARTED, 600));
        assertThat(placed(row("2231", "내선", "99").build()).reversed()).isFalse();
    }

    @Test
    @DisplayName("방향 표기가 그 노선의 두 방향이 아니면 반대를 모른다")
    void oppositeOnlyForKnownPair() {
        assertThat(SubwayTrainPositionResolver.opposite("1002", "내선")).isEqualTo("외선");
        assertThat(SubwayTrainPositionResolver.opposite("1001", "하행")).isEqualTo("상행");
        assertThat(SubwayTrainPositionResolver.opposite("1002", "상행")).isNull();
        assertThat(SubwayTrainPositionResolver.opposite("1001", null)).isNull();
    }

    private Position placeWithPosition(SubwayTrainStatus status) {
        seen.put(id("내선", "2231"), obs(YEOKSAM, "역삼", status, 30));
        return place(row("2231", "내선", "99").build());
    }

    private Position place(SubwayRealtimeTrain train) {
        return placed(train).position();
    }

    private Placed placed(SubwayRealtimeTrain train) {
        return resolver.place(LINE, List.of(new Arrival(train, 60)), NOW).get(0);
    }

    private static SubwayRealtimeTrain.SubwayRealtimeTrainBuilder row(String trainNo, String direction, String code) {
        return SubwayRealtimeTrain.builder()
                .trainNo(trainNo).lineId(LINE).direction(direction)
                .stationId(GANGNAM).stationName("강남")
                .arrivalCode(code)
                .receivedAt("2026-10-07 15:59:40"); // NOW - 20s (KST)
    }

    private static TrainIdentity id(String direction, String trainNo) {
        return new TrainIdentity(TODAY, LINE, direction, trainNo);
    }

    private static SubwayTrainObservation obs(String stationId, String name, SubwayTrainStatus status, int secondsAgo) {
        return new SubwayTrainObservation(stationId, name, status, NOW.minusSeconds(secondsAgo), null,
                SubwayExpressType.NORMAL);
    }
}
