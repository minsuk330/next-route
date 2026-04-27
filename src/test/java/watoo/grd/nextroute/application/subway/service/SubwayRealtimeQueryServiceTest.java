package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.*;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SubwayRealtimeQueryServiceTest {

    SubwayRealtimeCachePort cachePort = mock(SubwayRealtimeCachePort.class);
    SubwayRealtimeQueryService service;

    @BeforeEach
    void setUp() {
        service = new SubwayRealtimeQueryService(cachePort);
    }

    private SubwayRealtimeSnapshot snapshotWith(SubwayRealtimeTrain... trains) {
        String now = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return SubwayRealtimeSnapshot.builder()
                .collectedAt(now)
                .status(SubwayRealtimeStatus.ACTIVE)
                .trains(List.of(trains))
                .build();
    }

    private SubwayRealtimeTrain train(String lineId, String stationName, String direction) {
        return SubwayRealtimeTrain.builder()
                .trainNo("T1").lineId(lineId).direction(direction)
                .stationName(stationName).arrivalSeconds(60).build();
    }

    @Test
    void 스냅샷없으면_COLD_START_반환() {
        given(cachePort.readSnapshot()).willReturn(Optional.empty());
        given(cachePort.readStatus()).willReturn(Optional.empty());

        SubwayRealtimeResponse result = service.query(null, null, null);

        assertThat(result.getStatus()).isEqualTo(SubwayRealtimeStatus.COLD_START);
        assertThat(result.getTrains()).isEmpty();
    }

    @Test
    void 스냅샷없고_상태_COLLECTOR_ERROR면_COLLECTOR_ERROR_반환() {
        given(cachePort.readSnapshot()).willReturn(Optional.empty());
        given(cachePort.readStatus()).willReturn(Optional.of(SubwayRealtimeStatus.COLLECTOR_ERROR));

        SubwayRealtimeResponse result = service.query(null, null, null);

        assertThat(result.getStatus()).isEqualTo(SubwayRealtimeStatus.COLLECTOR_ERROR);
    }

    @Test
    void 필터없으면_전체_반환() {
        given(cachePort.readSnapshot()).willReturn(Optional.of(snapshotWith(
                train("1002", "강남", "상행"),
                train("1003", "교대", "하행")
        )));

        SubwayRealtimeResponse result = service.query(null, null, null);

        assertThat(result.getTrains()).hasSize(2);
        assertThat(result.getStatus()).isEqualTo(SubwayRealtimeStatus.ACTIVE);
    }

    @Test
    void lineId_필터링() {
        given(cachePort.readSnapshot()).willReturn(Optional.of(snapshotWith(
                train("1002", "강남", "상행"),
                train("1003", "교대", "하행")
        )));

        SubwayRealtimeResponse result = service.query("1002", null, null);

        assertThat(result.getTrains()).hasSize(1);
        assertThat(result.getTrains().get(0).getLineId()).isEqualTo("1002");
    }

    @Test
    void stationNames_CSV_필터링() {
        given(cachePort.readSnapshot()).willReturn(Optional.of(snapshotWith(
                train("1002", "강남", "상행"),
                train("1002", "역삼", "상행"),
                train("1002", "선릉", "상행")
        )));

        SubwayRealtimeResponse result = service.query(null, List.of("강남", "역삼"), null);

        assertThat(result.getTrains()).hasSize(2);
    }

    @Test
    void direction_필터링() {
        given(cachePort.readSnapshot()).willReturn(Optional.of(snapshotWith(
                train("1002", "강남", "상행"),
                train("1002", "강남", "하행")
        )));

        SubwayRealtimeResponse result = service.query(null, null, "상행");

        assertThat(result.getTrains()).hasSize(1);
        assertThat(result.getTrains().get(0).getDirection()).isEqualTo("상행");
    }

    @Test
    void 모든_필터_AND_조건() {
        given(cachePort.readSnapshot()).willReturn(Optional.of(snapshotWith(
                train("1002", "강남", "상행"),
                train("1002", "강남", "하행"),
                train("1003", "강남", "상행")
        )));

        SubwayRealtimeResponse result = service.query("1002", List.of("강남"), "상행");

        assertThat(result.getTrains()).hasSize(1);
        assertThat(result.getTrains().get(0).getLineId()).isEqualTo("1002");
        assertThat(result.getTrains().get(0).getDirection()).isEqualTo("상행");
    }
}
