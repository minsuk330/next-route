package watoo.grd.nextroute.application.journey.reeval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository.InterestRow;
import watoo.grd.nextroute.testsupport.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("관심 키 색인")
class InterestIndexTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    private final JourneySessionRepository repository = mock(JourneySessionRepository.class);
    private final MutableClock clock = new MutableClock(NOW, java.time.ZoneId.of("UTC"));
    private final ReevaluationProperties properties = new ReevaluationProperties();
    private final InterestIndex index = new InterestIndex(repository, properties,
            new JourneyEvaluatorProperties(), new JourneyReevalMetrics(new SimpleMeterRegistry()), clock);

    @Test
    @DisplayName("지하철 대기 구간은 (노선, 역, wayCode 로 정한 방향) 키가 된다 — 평가기가 고르는 단위와 같다")
    void subwayKeyUsesNormalizedDirection() {
        given(repository.findInterestRows()).willReturn(List.of(
                row(1L, "WAIT_SUBWAY", "1002", "S222", null, 0, null, false),
                row(2L, "WAIT_SUBWAY", "1004", "S426", null, 2, null, false),
                row(3L, "WAIT_SUBWAY", "1002", "S222", null, 1, null, false)));

        assertThat(index.watched(new InterestKey.Subway("1002", "S222", "내선"))).isTrue();
        assertThat(index.watched(new InterestKey.Subway("1002", "S222", "외선"))).as("2호선 wayCode 1 = 외선").isTrue();
        assertThat(index.watched(new InterestKey.Subway("1004", "S426", "하행"))).isTrue();
        assertThat(index.watched(new InterestKey.Subway("1004", "S426", "상행"))).isFalse();
    }

    @Test
    @DisplayName("방향을 모르는 지하철 구간은 지켜볼 키가 없다 — 평가기도 모름으로 끝낸다")
    void subwayWithoutDirectionIsSkipped() {
        given(repository.findInterestRows()).willReturn(List.of(
                row(1L, "WAIT_SUBWAY", "1004", "S426", null, null, null, false)));

        assertThat(index.journeysFor(List.of(new InterestKey.Subway("1004", "S426", "하행")))).isEmpty();
    }

    @Test
    @DisplayName("버스 대기 구간은 (정류장, 노선, 순번) 키가 된다")
    void busKey() {
        given(repository.findInterestRows()).willReturn(List.of(
                row(1L, "WAIT_BUS", "BL-370", "ST-A", 5, null, null, false)));

        assertThat(index.watched(new InterestKey.Bus("ST-A", "BL-370", 5))).isTrue();
    }

    @Test
    @DisplayName("등급: 위험이면 긴급, 5분 이내 긴급, 15분 이내 주의, 그 밖·모름은 여유")
    void tiersFromReadyTimeAndRisk() {
        given(repository.findInterestRows()).willReturn(List.of(
                row(1L, "WAIT_BUS", "R", "S", 1, null, NOW.plus(Duration.ofMinutes(30)), true),
                row(2L, "WAIT_BUS", "R", "S", 1, null, NOW.plus(Duration.ofMinutes(4)), false),
                row(3L, "WAIT_BUS", "R", "S", 1, null, NOW.plus(Duration.ofMinutes(10)), false),
                row(4L, "WAIT_BUS", "R", "S", 1, null, NOW.plus(Duration.ofMinutes(30)), false),
                row(5L, "WAIT_BUS", "R", "S", 1, null, null, false)));

        Map<ReevaluationTier, Set<Long>> tiers = index.journeysFor(List.of(new InterestKey.Bus("S", "R", 1)));

        assertThat(tiers.get(ReevaluationTier.URGENT)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(tiers.get(ReevaluationTier.CAUTION)).containsExactly(3L);
        assertThat(tiers.get(ReevaluationTier.RELAXED)).containsExactlyInAnyOrder(4L, 5L);
    }

    @Test
    @DisplayName("버스 키를 노선별로 묶고, 키마다 지켜보는 여정 중 가장 급한 등급을 붙인다")
    void busKeysByRouteTakeMostUrgentSubscriber() {
        given(repository.findInterestRows()).willReturn(List.of(
                row(1L, "WAIT_BUS", "R1", "S1", 1, null, NOW.plus(Duration.ofMinutes(30)), false),
                row(2L, "WAIT_BUS", "R1", "S1", 1, null, NOW.plus(Duration.ofMinutes(3)), false),
                row(3L, "WAIT_BUS", "R1", "S2", 2, null, NOW.plus(Duration.ofMinutes(10)), false),
                row(4L, "WAIT_SUBWAY", "1004", "S426", null, 2, null, false),
                row(5L, "WAIT_BUS", "R2", "S9", null, null, null, false)));

        Map<String, Map<InterestKey.Bus, ReevaluationTier>> byRoute = index.busKeysByRoute();

        assertThat(byRoute).as("순번 없는 키(R2)는 평가기도 묻지 않아 감시하지 않는다").containsOnlyKeys("R1");
        assertThat(byRoute.get("R1")).containsEntry(new InterestKey.Bus("S1", "R1", 1), ReevaluationTier.URGENT)
                .containsEntry(new InterestKey.Bus("S2", "R1", 2), ReevaluationTier.CAUTION);
    }

    @Test
    @DisplayName("경계: 정확히 5분은 긴급, 정확히 15분은 주의 — 간격 정책과 같다(<=)")
    void boundariesMatchIntervalPolicy() {
        given(repository.findInterestRows()).willReturn(List.of(
                row(1L, "WAIT_BUS", "R", "S", 1, null, NOW.plus(Duration.ofSeconds(300)), false),
                row(2L, "WAIT_BUS", "R", "S", 1, null, NOW.plus(Duration.ofSeconds(900)), false)));

        Map<ReevaluationTier, Set<Long>> tiers = index.journeysFor(List.of(new InterestKey.Bus("S", "R", 1)));

        assertThat(tiers.get(ReevaluationTier.URGENT)).containsExactly(1L);
        assertThat(tiers.get(ReevaluationTier.CAUTION)).containsExactly(2L);
    }

    @Test
    @DisplayName("같은 여정도 시간이 지나면 더 급한 등급이 된다 — 등급은 조회 때 다시 계산한다")
    void tierAdvancesWithTime() {
        given(repository.findInterestRows()).willReturn(List.of(
                row(1L, "WAIT_BUS", "R", "S", 1, null, NOW.plus(Duration.ofMinutes(10)), false)));
        InterestKey key = new InterestKey.Bus("S", "R", 1);
        assertThat(index.journeysFor(List.of(key))).containsOnlyKeys(ReevaluationTier.CAUTION);

        clock.advance(Duration.ofSeconds(10));
        properties.setInterestRebuildSeconds(3600);
        clock.advance(Duration.ofMinutes(6));

        assertThat(index.journeysFor(List.of(key))).containsOnlyKeys(ReevaluationTier.URGENT);
    }

    @Test
    @DisplayName("재구성 주기 안에서는 DB 를 다시 읽지 않는다")
    void rebuildsOnlyWhenStale() {
        given(repository.findInterestRows()).willReturn(List.of());
        properties.setInterestRebuildSeconds(20);

        index.watched(new InterestKey.Bus("S", "R", 1));
        clock.advance(Duration.ofSeconds(10));
        index.watched(new InterestKey.Bus("S", "R", 1));
        clock.advance(Duration.ofSeconds(15));
        index.watched(new InterestKey.Bus("S", "R", 1));

        verify(repository, times(2)).findInterestRows();
    }

    private static InterestRow row(Long journeyId, String type, String routeId, String stopId,
                                   Integer seq, Integer wayCode, Instant readyAt, boolean risky) {
        return new InterestRow() {
            public Long getJourneyId() { return journeyId; }
            public String getLegType() { return type; }
            public String getRouteId() { return routeId; }
            public String getStopId() { return stopId; }
            public String getStopName() { return "역"; }
            public Integer getBoardingSeq() { return seq; }
            public Integer getWayCode() { return wayCode; }
            public Instant getNextReadyAt() { return readyAt; }
            public Boolean getRisky() { return risky; }
        };
    }
}
