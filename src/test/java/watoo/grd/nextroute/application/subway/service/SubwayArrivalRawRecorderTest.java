package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.service.ArrivalRawInsertResult;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubwayArrivalRawRecorderTest {

    SubwayDataService subwayDataService = mock(SubwayDataService.class);
    SubwayArrivalRawRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new SubwayArrivalRawRecorder(subwayDataService);
    }

    private SubwayArrivalInfo info(String trainNo, String stationId, String arrivalCode,
                                   String receivedAt, String currentMessage) {
        return new SubwayArrivalInfo(
                stationId, "강남", "1002", "상행",
                "prevStn", "nextStn",
                0, "ordkey1", null, null,
                "일반", 60, trainNo,
                "destId", "성수행",
                currentMessage, arrivalCode, "1002",
                null, receivedAt, "2호선성수행", "0");
    }

    private SubwayArrivalInfo info(String trainNo, String stationId, String arrivalCode) {
        return info(trainNo, stationId, arrivalCode,
                "2026-04-30 10:00:00", "강남 도착");
    }

    @Test
    void 모든_arrivalCode를_저장한다() {
        List<SubwayArrivalInfo> arrivals = List.of(
                info("T1", "S1", "0"),
                info("T2", "S2", "1"),
                info("T3", "S3", "2"),
                info("T4", "S4", "3"),
                info("T5", "S5", "4"),
                info("T6", "S6", "5"),
                info("T7", "S7", "99"));

        when(subwayDataService.insertArrivalRawIgnoreDuplicates(any()))
                .thenReturn(insertResult(7, 7, 1, 1));

        recorder.record(arrivals);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalRaw>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).insertArrivalRawIgnoreDuplicates(captor.capture());
        assertThat(captor.getValue()).hasSize(7);
    }

    @Test
    void 필수_키_null이면_저장하지_않는다() {
        List<SubwayArrivalInfo> arrivals = List.of(
                info(null, "S1", "1"),               // trainNo null
                info("T2", null, "1"),               // stationId null
                info("T3", "S3", null),              // arrivalCode null
                info("T4", "S4", "1", null, "msg"),  // receivedAt null
                info("T5", "S5", "1", "time", null), // currentMessage null
                new SubwayArrivalInfo(                // lineId null
                        "S6", "역", null, "상행", "p", "n",
                        0, "o", null, null, "일반", 0, "T6",
                        "d", "행", "msg", "1", "1002",
                        null, "time", "line", "0"));

        recorder.record(arrivals);

        verify(subwayDataService, never()).insertArrivalRawIgnoreDuplicates(any());
    }

    @Test
    void 필수_키_blank이면_저장하지_않는다() {
        SubwayArrivalInfo blankTrain = info("  ", "S1", "1");

        recorder.record(List.of(blankTrain));

        verify(subwayDataService, never()).insertArrivalRawIgnoreDuplicates(any());
    }

    @Test
    void 빈_리스트면_저장하지_않는다() {
        recorder.record(List.of());

        verify(subwayDataService, never()).insertArrivalRawIgnoreDuplicates(any());
    }

    @Test
    void 동일_trainNo_stationId_라도_receivedAt_arrivalCode_currentMessage_다르면_별도_관측값으로_전달한다() {
        List<SubwayArrivalInfo> arrivals = List.of(
                info("T1", "S1", "1", "2026-04-30 10:00:00", "강남 도착"),
                info("T1", "S1", "1", "2026-04-30 10:00:15", "강남 도착"),  // receivedAt 다름
                info("T1", "S1", "2", "2026-04-30 10:00:00", "강남 도착"),  // arrivalCode 다름
                info("T1", "S1", "1", "2026-04-30 10:00:00", "강남 출발")   // currentMessage 다름
        );

        when(subwayDataService.insertArrivalRawIgnoreDuplicates(any()))
                .thenReturn(insertResult(4, 4, 3, 3));

        recorder.record(arrivals);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalRaw>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).insertArrivalRawIgnoreDuplicates(captor.capture());
        assertThat(captor.getValue()).hasSize(4);
    }

    @Test
    void 전체_필드가_SubwayArrivalRaw에_매핑된다() {
        SubwayArrivalInfo arrived = new SubwayArrivalInfo(
                "S99", "교대", "1003", "하행", "prev1", "next1",
                2, "ord1", "1002,1007", "stn1,stn2", "급행", 30, "T99",
                "dest99", "오금행", "교대 도착", "1", "1003",
                "msg3", "2026-04-30 14:30:00", "3호선오금행", "0");

        when(subwayDataService.insertArrivalRawIgnoreDuplicates(any()))
                .thenReturn(insertResult(1, 1, 1, 1));

        recorder.record(List.of(arrived));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalRaw>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).insertArrivalRawIgnoreDuplicates(captor.capture());
        SubwayArrivalRaw raw = captor.getValue().get(0);

        assertThat(raw.getStationId()).isEqualTo("S99");
        assertThat(raw.getStationName()).isEqualTo("교대");
        assertThat(raw.getLineId()).isEqualTo("1003");
        assertThat(raw.getDirection()).isEqualTo("하행");
        assertThat(raw.getPrevStationId()).isEqualTo("prev1");
        assertThat(raw.getNextStationId()).isEqualTo("next1");
        assertThat(raw.getTransferCount()).isEqualTo(2);
        assertThat(raw.getOrdkey()).isEqualTo("ord1");
        assertThat(raw.getTransferLines()).isEqualTo("1002,1007");
        assertThat(raw.getTransferStations()).isEqualTo("stn1,stn2");
        assertThat(raw.getTrainType()).isEqualTo("급행");
        assertThat(raw.getArrivalSeconds()).isEqualTo(30);
        assertThat(raw.getTrainNo()).isEqualTo("T99");
        assertThat(raw.getDestinationId()).isEqualTo("dest99");
        assertThat(raw.getDestinationName()).isEqualTo("오금행");
        assertThat(raw.getCurrentMessage()).isEqualTo("교대 도착");
        assertThat(raw.getArrivalCode()).isEqualTo("1");
        assertThat(raw.getSubwayId()).isEqualTo("1003");
        assertThat(raw.getArrivalMsg3()).isEqualTo("msg3");
        assertThat(raw.getReceivedAt()).isEqualTo("2026-04-30 14:30:00");
        assertThat(raw.getTrainLineName()).isEqualTo("3호선오금행");
        assertThat(raw.getLastTrainYn()).isEqualTo("0");
        assertThat(raw.getCollectedAt()).isNotNull();
    }

    private ArrivalRawInsertResult insertResult(int attemptedRows, int insertedRows,
                                                int attemptedCode1Rows, int insertedCode1Rows) {
        return new ArrivalRawInsertResult(
                attemptedRows,
                insertedRows,
                attemptedRows - insertedRows,
                attemptedCode1Rows,
                insertedCode1Rows,
                attemptedCode1Rows - insertedCode1Rows
        );
    }
}
