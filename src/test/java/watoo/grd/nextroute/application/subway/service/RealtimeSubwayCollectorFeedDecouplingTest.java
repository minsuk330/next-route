package watoo.grd.nextroute.application.subway.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.application.journey.reeval.InterestIndex;
import watoo.grd.nextroute.application.journey.reeval.InterestKey;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationProperties;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTrigger;
import watoo.grd.nextroute.application.journey.reeval.SubwayChangeFeed;
import watoo.grd.nextroute.application.journey.reeval.TriggerResult;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.observability.SubwayRealtimeMetrics;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.port.out.SubwayStationArrivalCachePort;
import watoo.grd.nextroute.common.lock.LeaderLease;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 변화 피드를 Redis 와 떼어 둔다(#451 PR-3). 스냅숏 Redis 저장·역 이름 캐시 채우기가 실패해도 재평가 트리거 요청은
 * 그대로 한 번 나가야 한다 — 트리거는 DB 로 가므로 Redis 장애가 알림 감지까지 끊으면 안 된다.
 *
 * <p>리더 잠금({@code journey.navigation.reeval.leader-lease.enabled})은 꺼진 상태(운영 기본)를 전제한다. 켜져 있으면
 * Redis 장애 때 리더를 놓아 수집 자체를 안 한다 — 다중 인스턴스 한계로 노트 {@code ### 미결} 에 남겼다.
 */
@DisplayName("수집기 — Redis 저장 실패에도 변화 피드는 돈다")
class RealtimeSubwayCollectorFeedDecouplingTest {

    private static final InterestKey.Subway GANGNAM_INNER = new InterestKey.Subway("1002", "S222", "내선");

    private final SubwayApiPort api = mock(SubwayApiPort.class);
    private final SubwayRealtimeCachePort cachePort = mock(SubwayRealtimeCachePort.class);
    private final SubwayStationIdLookup stationIdLookup = mock(SubwayStationIdLookup.class);
    private final ReevaluationTrigger trigger = mock(ReevaluationTrigger.class);
    private final InterestIndex index = mock(InterestIndex.class);
    private final LeaderLease lease = mock(LeaderLease.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RealtimeSubwayCollector collector;

    @BeforeEach
    void setUp() {
        given(lease.isLeader()).willReturn(true);
        given(index.watched(GANGNAM_INNER)).willReturn(true);
        given(index.journeysFor(anyCollection())).willReturn(Map.of(ReevaluationTier.URGENT, Set.of(7L)));
        given(trigger.request(any(), any())).willReturn(new TriggerResult(Set.of(7L), Set.of()));

        ReevaluationProperties reeval = new ReevaluationProperties();
        reeval.setEnabled(true);
        reeval.setShadow(false);
        reeval.setSubwayTrigger(true);
        SubwayChangeFeed feed = new SubwayChangeFeed(reeval, index, trigger,
                mock(SubwayStationArrivalCachePort.class), lease, new JourneyReevalMetrics(registry));

        collector = new RealtimeSubwayCollector(api, cachePort, mock(SubwaySegmentLookup.class), stationIdLookup,
                mock(SubwayArrivalRawRecorder.class), mock(SubwaySegmentTimeRecorder.class),
                mock(SubwayArrivalDepartureRecorder.class), new SubwayRealtimeMetrics(registry), feed, lease);
        ReflectionTestUtils.setField(collector, "enabled", true);
        // 시작 = 끝이면 쉬는 시간이 없다 — 시계에 매이지 않게
        ReflectionTestUtils.setField(collector, "activeStart", "00:00");
        ReflectionTestUtils.setField(collector, "activeEnd", "00:00");
        ReflectionTestUtils.setField(collector, "snapshotTtlSeconds", 25L);
        ReflectionTestUtils.setField(collector, "persistRawArrivals", false);
    }

    @Test
    @DisplayName("스냅숏 Redis 저장 예외에도 트리거 요청은 1회 — 저장 실패는 지표로 센다")
    void snapshotSaveFailureStillTriggersOnce() {
        given(api.getRealtimeArrival())
                .willReturn(List.of(arrival("T1", "99", "역삼", 120)))
                .willReturn(List.of(arrival("T1", "0", "강남", 30)));
        willThrow(new RuntimeException("Redis down")).given(cachePort).saveSnapshot(any(), anyLong());

        collector.collect(); // 기준선
        collector.collect(); // 열차가 한 역 다가옴 → 변화

        verify(trigger, times(1)).request(eq(Map.of(ReevaluationTier.URGENT, Set.of(7L))), any());
        assertThat(registry.counter(SubwayRealtimeMetrics.COLLECT_STORE_ERRORS,
                "collector", "arrival", "op", "snapshot").count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("역 이름 캐시 채우기 예외에도 트리거 요청은 1회")
    void stationLookupFailureStillTriggersOnce() {
        given(api.getRealtimeArrival())
                .willReturn(List.of(arrival("T1", "99", "역삼", 120)))
                .willReturn(List.of(arrival("T1", "0", "강남", 30)));
        willThrow(new IllegalStateException("boom")).given(stationIdLookup).update(anyList());

        collector.collect();
        collector.collect();

        verify(trigger, times(1)).request(any(), any());
        assertThat(registry.counter(SubwayRealtimeMetrics.COLLECT_STORE_ERRORS,
                "collector", "arrival", "op", "station-lookup").count()).isEqualTo(2.0);
    }

    private static SubwayArrivalInfo arrival(String trainNo, String code, String currentStation, int seconds) {
        return new SubwayArrivalInfo("S222", "강남", "1002", "내선", null, null, null, null, null, null,
                null, seconds, trainNo, null, null, null, code, null, currentStation, null, null, null);
    }
}
