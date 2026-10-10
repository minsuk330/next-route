package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 하차 예정은 다음 역 예정이 없어도 채운다(링 TYPICAL PR-4). 출처는 다음 역이 하차역이고 다음 역 예정을 그대로 썼을 때만 그
 * 출처, 나머지는 TYPICAL.
 */
@DisplayName("지하철 링 하차 예정 추정")
class SubwayRingAlightingEstimatorTest {

    private static final Instant NOW = Instant.parse("2026-10-10T14:00:00Z");
    private static final TrainIdentity TRAIN = new TrainIdentity(LocalDate.of(2026, 10, 10), "1002", "외선", "7399");

    /** 승차역(0) → 역A(1) → 역B(2) → 하차역(3). 구간 운행 90초, 역A→하차역 도착 간격 200초. */
    private final SubwayRingChain chain = new SubwayRingChain(List.of(
            new SubwayRingChain.Station(0, "S0", "승차역"),
            new SubwayRingChain.Station(1, "S1", "역A"),
            new SubwayRingChain.Station(2, "S2", "역B"),
            new SubwayRingChain.Station(3, "S3", "하차역")));

    private final SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
    private final SubwayRingAlightingEstimator estimator =
            new SubwayRingAlightingEstimator(lineOrder, new JourneySubwayRingProperties());

    {
        for (int i = 1; i <= 3; i++) {
            given(lineOrder.segmentTimes("1002", "외선", "S" + (i - 1), "S" + i))
                    .willReturn(Optional.of(new SubwayLineOrder.SegmentTimes(30, 90)));
        }
        given(lineOrder.arrivalGapSeconds("1002", "외선", "S1", "S3")).willReturn(Optional.of(200));
        given(lineOrder.arrivalGapSeconds("1002", "외선", "S2", "S3")).willReturn(Optional.of(100));
    }

    @Test
    @DisplayName("다음 역 예정이 실시간이면 그 예정 + 계획 도착 간격, 남은 구간이 계획값이라 출처 TYPICAL")
    void liveNextStation() {
        SubwayRingState ring = ring(1, NOW.plusSeconds(60), SubwayRingEtaSource.LIVE, SubwayRingStage.RUN);

        SubwayRingAlightingEstimator.Estimate e = estimator.estimate(ring, null, chain, TRAIN, NOW);

        assertThat(e.at()).isEqualTo(NOW.plusSeconds(60 + 200));
        assertThat(e.source()).isEqualTo(SubwayRingEtaSource.TYPICAL);
    }

    @Test
    @DisplayName("다음 역이 하차역이면 그 예정과 출처 그대로")
    void nextIsAlighting() {
        SubwayRingState ring = ring(3, NOW.plusSeconds(40), SubwayRingEtaSource.MEASURED, SubwayRingStage.RUN);

        SubwayRingAlightingEstimator.Estimate e = estimator.estimate(ring, null, chain, TRAIN, NOW);

        assertThat(e.at()).isEqualTo(NOW.plusSeconds(40));
        assertThat(e.source()).isEqualTo(SubwayRingEtaSource.MEASURED);
    }

    @Test
    @DisplayName("다음 역 예정이 없어도 채운다 — 늦음·진입은 지금, 정차 초과는 지금 + 운행×1.2, 그 뒤 계획 간격×1.2, 출처 TYPICAL")
    void fillsWithoutNextEta() {
        SubwayRingAlightingEstimator.Estimate late = estimator.estimate(
                ring(2, null, null, SubwayRingStage.LATE), null, chain, TRAIN, NOW);
        assertThat(late.at()).isEqualTo(NOW.plusSeconds(120)); // 100 × 1.2
        assertThat(late.source()).isEqualTo(SubwayRingEtaSource.TYPICAL);

        SubwayRingAlightingEstimator.Estimate dwell = estimator.estimate(
                ring(2, null, null, SubwayRingStage.DWELL), null, chain, TRAIN, NOW);
        assertThat(dwell.at()).isEqualTo(NOW.plusSeconds(108 + 120));

        SubwayRingAlightingEstimator.Estimate approachAlighting = estimator.estimate(
                ring(3, null, null, SubwayRingStage.APPROACH), null, chain, TRAIN, NOW);
        assertThat(approachAlighting.at()).isEqualTo(NOW);
        assertThat(approachAlighting.source()).isEqualTo(SubwayRingEtaSource.TYPICAL);
    }

    @Test
    @DisplayName("정차 직선이면 예정(정차 끝) + 운행×1.2 가 다음 역 도착")
    void dwellLineAddsRun() {
        SubwayRingState ring = ring(2, NOW.plusSeconds(20), SubwayRingEtaSource.TYPICAL, SubwayRingStage.DWELL);

        SubwayRingAlightingEstimator.Estimate e = estimator.estimate(ring, null, chain, TRAIN, NOW);

        assertThat(e.at()).isEqualTo(NOW.plusSeconds(20 + 108 + 120));
        assertThat(e.source()).isEqualTo(SubwayRingEtaSource.TYPICAL);
    }

    @Test
    @DisplayName("GTFS 를 모르면 지어내지 않는다")
    void unknownGtfsIsNull() {
        SubwayRingState ring = ring(2, null, null, SubwayRingStage.LATE);
        SubwayLineOrder empty = mock(SubwayLineOrder.class);
        given(empty.arrivalGapSeconds("1002", "외선", "S2", "S3")).willReturn(Optional.empty());

        SubwayRingAlightingEstimator.Estimate e = new SubwayRingAlightingEstimator(empty, new JourneySubwayRingProperties())
                .estimate(ring, null, chain, TRAIN, NOW);

        assertThat(e.at()).isNull();
        assertThat(e.source()).isNull();
    }

    @Test
    @DisplayName("'지금' 기준 추정은 직전 TYPICAL 하차 예정과 60초 안이면 직전 값을 지킨다 — 30초마다 push 가 나가지 않게")
    void nowBasedEstimateIsSticky() {
        SubwayRingState late = ring(2, null, null, SubwayRingStage.LATE);
        SubwayRingAlightingEstimator.Estimate first = estimator.estimate(late, null, chain, TRAIN, NOW);
        SubwayRingState previous = late.withEstimatedAlightingAt(first.at(), first.source());

        SubwayRingAlightingEstimator.Estimate later = estimator.estimate(late, previous, chain, TRAIN, NOW.plusSeconds(45));
        assertThat(later.at()).isEqualTo(first.at());

        SubwayRingAlightingEstimator.Estimate muchLater = estimator.estimate(late, previous, chain, TRAIN,
                NOW.plusSeconds(90));
        assertThat(muchLater.at()).as("늦음이 더 커지면 따라간다").isEqualTo(NOW.plusSeconds(90 + 120));
    }

    private SubwayRingState ring(int next, Instant eta, SubwayRingEtaSource source, SubwayRingStage stage) {
        return new SubwayRingState(1L, 1, 4, 0, next, "S" + next, "역" + next, "하차역", 3 - next + 1,
                new BigDecimal("0.30"), NOW.minusSeconds(10), new BigDecimal("0.90"), eta, NOW.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, NOW, NOW, 0L, null, null, null,
                source, null, stage, null, null);
    }
}
