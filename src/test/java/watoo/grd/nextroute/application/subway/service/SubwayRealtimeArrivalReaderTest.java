package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 방향 필터와 <b>"지나간 열차" 판정</b>의 회귀 계약.
 *
 * <p>방향: {@code toDirection} 이 엄격해졌다({@code wayCode} 의 모르는 값이 {@code null} 이 된다).
 * 그 변화가 <b>대기 지하철 ETA 경로</b>까지 새는지 여기서 고정한다 — 그쪽은 방향이 {@code null}
 * 이면 필터를 건너뛰어 전 방향을 그대로 보여 주는 것이 원래 동작이고, 그게 바뀌면 도착 정보가
 * 통째로 사라진다. 조용히 비는 종류의 회귀라 시험 없이는 안 드러난다.
 *
 * <p>지나간 열차: 거르는 근거가 "보정한 초가 음수"에서 {@code arvlCd=2} 로 바뀌었다. 두 방향
 * 모두 조용히 비는 회귀다 — 느슨해지면 이미 떠난 열차를 안내하고, 엄격해지면 오고 있는 열차가
 * 사라진다. 양쪽을 다 고정한다.
 */
class SubwayRealtimeArrivalReaderTest {

    private static final String LINE = "1001";
    private static final String STATION = "1001000133";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RECEIVED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SubwayApiPort apiPort = mock(SubwayApiPort.class);
    private final SubwayRealtimeCachePort cachePort = mock(SubwayRealtimeCachePort.class);
    private final SubwayRealtimeArrivalReader reader = new SubwayRealtimeArrivalReader(cachePort, apiPort);

    // ── 방향 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("방향을 알면 그 방향만 남긴다")
    void filtersByKnownDirection() {
        givenBothDirections();

        List<SubwayRealtimeArrivalReader.Arrival> arrivals =
                reader.arrivalsByStation(LINE, STATION, "서울역", 1);

        assertThat(arrivals).hasSize(1);
        assertThat(arrivals.get(0).train().getDirection()).isEqualTo("상행");
    }

    @Test
    @DisplayName("방향을 모르면 전 방향을 그대로 본다 — 필터를 건너뛰는 것이 원래 동작이다")
    void keepsEveryDirectionWhenWayCodeIsUnknown() {
        givenBothDirections();

        assertThat(reader.arrivalsByStation(LINE, STATION, "서울역", 99)).hasSize(2);
        assertThat(reader.arrivalsByStation(LINE, STATION, "서울역", null)).hasSize(2);
    }

    // ── 지나간 열차 판정 ───────────────────────────────────────────────────

    @Test
    @DisplayName("남은 초가 미산출인 열차를 버리지 않는다 — 전역 도착은 아직 오는 중이다")
    void keepsTrainWhoseEtaIsNotComputed() {
        // 실측: barvlDt=0, arvlCd=5(전역 도착). 0 에서 경과시간을 빼면 음수라 예전에는 사라졌다.
        when(apiPort.getRealtimeArrivalByStation(anyString()))
                .thenReturn(List.of(train("6135", 0, "5", "전역 도착", secondsAgo(30))));

        List<SubwayRealtimeArrivalReader.Arrival> arrivals =
                reader.arrivalsByStation(LINE, STATION, "압구정로데오", null);

        assertThat(arrivals).hasSize(1);
        assertThat(arrivals.get(0).arrivalSeconds()).isNull();
        assertThat(arrivals.get(0).hasSeconds()).isFalse();
        assertThat(arrivals.get(0).train().getCurrentMessage()).isEqualTo("전역 도착");
    }

    @Test
    @DisplayName("조회역에 도착한 열차도 남는다 — 초가 0 인 것은 미산출이지 지나간 것이 아니다")
    void keepsTrainArrivedAtQueriedStation() {
        when(apiPort.getRealtimeArrivalByStation(anyString()))
                .thenReturn(List.of(train("6542", 0, "1", "압구정로데오 도착", secondsAgo(45))));

        assertThat(reader.arrivalsByStation(LINE, STATION, "압구정로데오", null)).hasSize(1);
    }

    @Test
    @DisplayName("조회역을 출발한 열차는 버린다 — 지나간 것은 arvlCd=2 가 말한다")
    void dropsTrainThatAlreadyDepartedQueriedStation() {
        when(apiPort.getRealtimeArrivalByStation(anyString()))
                .thenReturn(List.of(train("6542", 0, "2", "압구정로데오 출발", secondsAgo(10))));

        assertThat(reader.arrivalsByStation(LINE, STATION, "압구정로데오", null)).isEmpty();
    }

    @Test
    @DisplayName("예정 시각이 있었는데 그마저 넘긴 열차는 버린다")
    void dropsTrainWhoseRealEtaHasElapsed() {
        // 60초 남았다고 했는데 관측이 5분 전이다. 그 열차는 이미 왔다 갔다.
        when(apiPort.getRealtimeArrivalByStation(anyString()))
                .thenReturn(List.of(train("6135", 60, "99", "1분 0초 후", secondsAgo(300))));

        assertThat(reader.arrivalsByStation(LINE, STATION, "압구정로데오", null)).isEmpty();
    }

    @Test
    @DisplayName("남은 초가 있으면 경과시간만큼 당겨서 돌려주고, 초가 있는 열차를 앞에 둔다")
    void adjustsRemainingSecondsAndSortsKnownEtaFirst() {
        when(apiPort.getRealtimeArrivalByStation(anyString())).thenReturn(List.of(
                train("6135", 0, "5", "전역 도착", secondsAgo(30)),
                train("6542", 180, "99", "3분 0초 후", secondsAgo(30))));

        List<SubwayRealtimeArrivalReader.Arrival> arrivals =
                reader.arrivalsByStation(LINE, STATION, "압구정로데오", null);

        assertThat(arrivals).hasSize(2);
        assertThat(arrivals.get(0).train().getTrainNo()).isEqualTo("6542");
        assertThat(arrivals.get(0).arrivalSeconds()).isBetween(145, 155);
        assertThat(arrivals.get(1).arrivalSeconds()).isNull();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private void givenBothDirections() {
        when(apiPort.getRealtimeArrivalByStation(anyString()))
                .thenReturn(List.of(arrival("1234", "상행", 120), arrival("5678", "하행", 200)));
    }

    private String secondsAgo(int seconds) {
        return LocalDateTime.now(SEOUL).minusSeconds(seconds).format(RECEIVED_AT);
    }

    @Test
    @DisplayName("#317 직전 역 ID(statnFid)·현재 역 이름(arvlMsg3)을 옮긴다")
    void carriesPrevStationIdAndCurrentStationName() {
        when(apiPort.getRealtimeArrivalByStation("서울역")).thenReturn(List.of(new SubwayArrivalInfo(
                STATION, "서울역", LINE, "상행", "1001000134", null, null, null, null, null, "0", 60, "K1",
                null, null, null, "3", null, "남영", null, null, null)));

        SubwayRealtimeTrain train = reader.arrivalsByStation(LINE, STATION, "서울역", null).get(0).train();

        assertThat(train.getPrevStationId()).isEqualTo("1001000134");
        assertThat(train.getCurrentStationName()).isEqualTo("남영");
    }

    @Test
    @DisplayName("막 떠난 열차: 캐시에서 이 역·방향의 arvlCd=2 행만 — arrivals 가 빼는 바로 그 행들")
    void justDepartedTrainNos() {
        when(cachePort.readSnapshot()).thenReturn(java.util.Optional.of(
                watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot.builder().trains(List.of(
                        cached("K1", STATION, "상행", "2"),
                        cached("K2", STATION, "상행", "1"),
                        cached("K3", STATION, "하행", "2"),
                        cached("K4", "1001000134", "상행", "2"))).build()));

        assertThat(reader.justDepartedTrainNos(LINE, STATION, 1)).containsExactly("K1");
        assertThat(reader.arrivals(LINE, STATION, 1)).extracting(a -> a.train().getTrainNo())
                .containsExactly("K2");
    }

    private static SubwayRealtimeTrain cached(String trainNo, String stationId, String direction, String code) {
        return SubwayRealtimeTrain.builder().trainNo(trainNo).lineId(LINE).stationId(stationId)
                .direction(direction).arrivalCode(code).arrivalSeconds(60).build();
    }

    private SubwayArrivalInfo arrival(String trainNo, String direction, int seconds) {
        return new SubwayArrivalInfo(STATION, "서울역", LINE, direction, null, null, null,
                null, null, null, "0", seconds, trainNo, null, null, null, null, null, null,
                null, null, null);
    }

    private SubwayArrivalInfo train(String trainNo, int seconds, String arrivalCode,
                                    String currentMessage, String receivedAt) {
        return new SubwayArrivalInfo(STATION, "압구정로데오", LINE, "하행", null, null, null,
                null, null, null, "일반", seconds, trainNo, null, "고색", currentMessage,
                arrivalCode, LINE, null, receivedAt, null, "0");
    }
}
