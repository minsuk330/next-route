package watoo.grd.nextroute.application.journey.reeval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayStationArrivalCachePort;
import watoo.grd.nextroute.common.lock.LeaderLease;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("지하철 스냅숏 변화 → 재평가")
class SubwayChangeFeedTest {

    private static final InterestKey.Subway GANGNAM_INNER = new InterestKey.Subway("1002", "S222", "내선");

    private final InterestIndex index = mock(InterestIndex.class);
    private final ReevaluationTrigger trigger = mock(ReevaluationTrigger.class);
    private final SubwayStationArrivalCachePort cache = mock(SubwayStationArrivalCachePort.class);
    private final LeaderLease lease = mock(LeaderLease.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReevaluationProperties properties = new ReevaluationProperties();
    private SubwayChangeFeed feed;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setShadow(false);
        properties.setSubwayTrigger(true);
        given(lease.isLeader()).willReturn(true);
        given(index.watched(GANGNAM_INNER)).willReturn(true);
        given(index.journeysFor(anyCollection()))
                .willReturn(Map.of(ReevaluationTier.URGENT, Set.of(7L)));
        given(trigger.request(any())).willReturn(new TriggerResult(Set.of(7L), Set.of()));
        feed = new SubwayChangeFeed(properties, index, trigger, cache, lease,
                new JourneyReevalMetrics(registry));
    }

    @Test
    @DisplayName("같은 스냅숏이 다시 오면 아무도 깨우지 않는다")
    void sameSnapshotIsQuiet() {
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));

        verify(trigger, never()).request(any());
    }

    @Test
    @DisplayName("남은 초만 바뀐 것은 변화가 아니다 — 0(미산출)이 잦아 잡음이다")
    void secondsOnlyIsNotAChange() {
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 0)));

        verify(trigger, never()).request(any());
    }

    @Test
    @DisplayName("열차가 한 역 다가오면 그 역 캐시를 먼저 지우고 지켜보는 여정을 깨운다")
    void movedTrainEvictsThenTriggers() {
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));

        InOrder order = inOrder(cache, trigger);
        order.verify(cache).evict("강남");
        order.verify(trigger).request(Map.of(ReevaluationTier.URGENT, Set.of(7L)));
    }

    @Test
    @DisplayName("앞 열차가 떠나 다음 열차가 첫째가 되면 변화다")
    void departedLeaderIsAChange() {
        feed.onSnapshot(List.of(train("T1", "1", "강남", 0), train("T2", "99", "서초", 200)));
        feed.onSnapshot(List.of(train("T1", "2", "강남", 0), train("T2", "99", "서초", 150)));

        verify(trigger).request(any());
    }

    @Test
    @DisplayName("아무도 안 지켜보는 역의 변화는 트리거하지 않는다")
    void unwatchedStationIsIgnored() {
        given(index.watched(GANGNAM_INNER)).willReturn(false);
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));

        verify(trigger, never()).request(any());
        verify(cache, never()).evict(any());
    }

    @Test
    @DisplayName("반영 못 한 여정이 있으면 다음 스냅숏에서 같은 변화를 다시 보낸다")
    void deferredIsResent() {
        given(trigger.request(any()))
                .willReturn(new TriggerResult(Set.of(), Set.of(7L)))
                .willReturn(new TriggerResult(Set.of(7L), Set.of()));
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));

        verify(trigger, org.mockito.Mockito.times(2)).request(any());
    }

    @Test
    @DisplayName("그림자 모드는 캐시를 지우지 않는다")
    void shadowDoesNotEvict() {
        properties.setShadow(true);
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));

        verify(cache, never()).evict(any());
        verify(trigger).request(any());
    }

    @Test
    @DisplayName("리더가 아니면 비교도 하지 않는다")
    void nonLeaderDoesNothing() {
        given(lease.isLeader()).willReturn(false);
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));

        verifyNoInteractions(trigger, cache, index);
    }

    @Test
    @DisplayName("리더 자격이 바뀌면 기준선을 비워 그 직후 관측은 기준선만 쌓는다")
    void epochChangeResetsBaseline() {
        given(lease.epoch()).willReturn(1L);
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        given(lease.epoch()).willReturn(2L);
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));

        verify(trigger, never()).request(any());
    }

    @Test
    @DisplayName("꺼져 있으면 아무것도 하지 않는다")
    void disabledDoesNothing() {
        properties.setSubwayTrigger(false);
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));

        verifyNoInteractions(trigger, cache, index);
    }

    @Test
    @DisplayName("비교한 키와 바뀐 키를 센다")
    void countsComparedAndEvents() {
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));

        assertThat(registry.counter(JourneyReevalMetrics.COMPARED, "source", "subway").count()).isEqualTo(2);
        assertThat(registry.counter(JourneyReevalMetrics.EVENTS, "source", "subway").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("구간이 쓰는 역 이름이 원천과 달라도 둘 다 지운다 — 한쪽만 지우면 낡은 캐시가 남는다")
    void evictsBothLegAndSourceNames() {
        given(index.stopNamesFor(GANGNAM_INNER)).willReturn(Set.of("강남역"));
        feed.onSnapshot(List.of(train("T1", "99", "역삼", 120)));
        feed.onSnapshot(List.of(train("T1", "0", "강남", 30)));

        verify(cache).evict("강남역");
        verify(cache).evict("강남");
    }

    private static SubwayRealtimeTrain train(String trainNo, String code, String currentStation, int seconds) {
        return SubwayRealtimeTrain.builder()
                .trainNo(trainNo).lineId("1002").stationId("S222").direction("내선").stationName("강남")
                .arrivalCode(code).currentStationName(currentStation).arrivalSeconds(seconds)
                .build();
    }
}
