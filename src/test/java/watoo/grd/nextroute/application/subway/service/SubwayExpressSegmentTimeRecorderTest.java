package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.port.out.SubwayExpressSegmentTimePort;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 급행 구간 실측(#497) — 1호선 경부 A급행 1948 의 2026-10-21 녹화 관측 모양(출발 없음, 지나치는 역 행 없음). */
@DisplayName("급행 구간 실측 표본")
class SubwayExpressSegmentTimeRecorderTest {

    private static final TrainIdentity EXPRESS = new TrainIdentity(LocalDate.of(2026, 10, 21), "1001", "상행", "1948");
    private static final TrainIdentity NORMAL = new TrainIdentity(LocalDate.of(2026, 10, 21), "1001", "상행", "0500");
    private static final String ANYANG = "1001080147";
    private static final String GASAN = "1001080142";
    private static final String GURO = "1001000141";
    private static final Instant ANYANG_ARRIVED = Instant.parse("2026-10-21T12:47:58Z");
    private static final Instant GASAN_ENTERED = Instant.parse("2026-10-21T12:55:05Z");
    private static final Instant GASAN_ARRIVED = Instant.parse("2026-10-21T12:55:32Z");

    private final SubwayExpressSegmentTimePort port = mock(SubwayExpressSegmentTimePort.class);
    private final SubwayTrainKindClassifier classifier = mock(SubwayTrainKindClassifier.class);
    private final SubwayExpressSegmentTimeRecorder recorder = new SubwayExpressSegmentTimeRecorder(port, classifier);

    {
        when(classifier.classifyRaw(eq("1001"), eq("1948"), any(), any())).thenReturn(TrainKind.EXPRESS);
        when(classifier.classifyRaw(eq("1001"), eq("0500"), any(), any())).thenReturn(TrainKind.NORMAL);
    }

    private static SubwayTrainObservation at(String stationId, SubwayTrainStatus status, Instant receivedAt) {
        return new SubwayTrainObservation(stationId, null, status, receivedAt, null, null, "0");
    }

    /** 안양 도착 → (진입) → 가산디지털단지 도착. */
    private void anyangThenGasan(TrainIdentity train) {
        SubwayTrainObservation anyang = at(ANYANG, SubwayTrainStatus.ARRIVED, ANYANG_ARRIVED);
        SubwayTrainObservation entering = at(GASAN, SubwayTrainStatus.ENTERING, GASAN_ENTERED);
        recorder.observe(train, at(ANYANG, SubwayTrainStatus.ENTERING, ANYANG_ARRIVED.minusSeconds(42)), anyang);
        recorder.observe(train, anyang, entering);
        recorder.observe(train, entering, at(GASAN, SubwayTrainStatus.ARRIVED, GASAN_ARRIVED));
    }

    @Test
    @DisplayName("정차역 도착 → 다음 정차역 도착을 잰다(1948 안양 → 가산디지털단지 454초) — 진입이 아니라 도착끼리")
    void recordsArrivalToArrival() {
        anyangThenGasan(EXPRESS);

        verify(port).record("1001", "상행", ANYANG, GASAN, 454);
        verify(port, times(1)).record(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("같은 도착이 수신 시각만 바뀌어 다시 와도 한 번이다 — 다음 구간 시작도 그대로")
    void repeatedArrivalCountsOnce() {
        anyangThenGasan(EXPRESS);
        SubwayTrainObservation gasan = at(GASAN, SubwayTrainStatus.ARRIVED, GASAN_ARRIVED);
        recorder.observe(EXPRESS, gasan, at(GASAN, SubwayTrainStatus.ARRIVED, GASAN_ARRIVED.plusSeconds(20)));
        recorder.observe(EXPRESS, gasan, at(GURO, SubwayTrainStatus.ARRIVED, GASAN_ARRIVED.plusSeconds(278)));

        verify(port).record("1001", "상행", GASAN, GURO, 278);
    }

    @Test
    @DisplayName("일반 열차·범위 밖 시간은 표본이 아니다")
    void skipsNormalAndOutOfRange() {
        anyangThenGasan(NORMAL);
        TrainIdentity late = new TrainIdentity(LocalDate.of(2026, 10, 21), "1001", "상행", "1948");
        recorder.observe(late, null, at(GURO, SubwayTrainStatus.ARRIVED, ANYANG_ARRIVED.plusSeconds(10_000)));
        recorder.observe(late, null, at(ANYANG, SubwayTrainStatus.ARRIVED, ANYANG_ARRIVED.plusSeconds(10_010)));

        verify(port, never()).record(anyString(), anyString(), anyString(), anyString(), anyLong());
    }
}
