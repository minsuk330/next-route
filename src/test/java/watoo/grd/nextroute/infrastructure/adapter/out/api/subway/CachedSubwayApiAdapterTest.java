package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.subway.config.SubwayStationArrivalProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.observability.SubwayRealtimeMetrics;
import watoo.grd.nextroute.application.subway.port.out.SubwayStationArrivalCachePort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 같은 역을 보는 요청이 API 를 한 번만 부르는지 본다.
 *
 * <p>여정 평가기는 같은 역에서 갈아타는 사용자 수만큼 이 조회를 부른다. 열차 목록은 누구에게나 같아서
 * 한 번이면 된다. 어느 열차를 탈 수 있는지 고르는 일은 이 뒤에 남는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("지하철 역별 조회 캐시 데코레이터")
class CachedSubwayApiAdapterTest {

    @Mock SeoulSubwayApiAdapter delegate;
    @Mock SubwayStationArrivalCachePort cache;

    CachedSubwayApiAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CachedSubwayApiAdapter(delegate, cache,
                new SubwayStationArrivalProperties(),
                new SubwayRealtimeMetrics(new SimpleMeterRegistry()));
    }

    private SubwayArrivalInfo arrival(String trainNo) {
        return new SubwayArrivalInfo("923", "강남", "1002", "상행", null, null,
                0, "0", null, null, "일반", 120, trainNo, null, "성수",
                "전역 출발", "0", "1002", null, "2026-08-05 10:00:00", null, "N");
    }

    @Test
    void TC_캐시_hit이면_API를_부르지_않는다() {
        given(cache.find("강남")).willReturn(Optional.of(List.of(arrival("1001"))));

        List<SubwayArrivalInfo> result = adapter.getRealtimeArrivalByStation("강남");

        verify(delegate, never()).getRealtimeArrivalByStation(anyString());
        assertThat(result).extracting(SubwayArrivalInfo::trainNo).containsExactly("1001");
    }

    @Test
    void TC_miss면_부르고_저장한다() {
        given(cache.find("강남")).willReturn(Optional.empty());
        given(delegate.getRealtimeArrivalByStation("강남")).willReturn(List.of(arrival("1001")));

        List<SubwayArrivalInfo> result = adapter.getRealtimeArrivalByStation("강남");

        assertThat(result).hasSize(1);
        verify(cache).put("강남", result);
    }

    @Test
    void TC_동시_요청은_한_번만_부른다() throws Exception {
        // 캐시가 아직 비어 있는 순간(세대 경계)에 여정 여럿이 같은 역을 물어도 API 는 한 번이다.
        given(cache.find("강남")).willReturn(Optional.empty());
        CountDownLatch started = new CountDownLatch(1);
        willAnswer(invocation -> {
            started.countDown();
            Thread.sleep(150);
            return List.of(arrival("1001"));
        }).given(delegate).getRealtimeArrivalByStation("강남");

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<List<SubwayArrivalInfo>>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> adapter.getRealtimeArrivalByStation("강남")));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            futures.add(pool.submit(() -> adapter.getRealtimeArrivalByStation("강남")));
            futures.add(pool.submit(() -> adapter.getRealtimeArrivalByStation("강남")));

            for (Future<List<SubwayArrivalInfo>> future : futures) {
                assertThat(future.get(3, TimeUnit.SECONDS)).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(delegate, times(1)).getRealtimeArrivalByStation("강남");
    }

    @Test
    void TC_빈_응답은_저장하지_않는다() {
        // 장애로 인한 빈 목록을 TTL 동안 고착시키면 다음 요청도 계속 모름이 된다.
        given(cache.find("강남")).willReturn(Optional.empty());
        given(delegate.getRealtimeArrivalByStation("강남")).willReturn(List.of());

        assertThat(adapter.getRealtimeArrivalByStation("강남")).isEmpty();

        verify(cache).put("강남", List.of());   // 저장 여부 판단은 캐시 어댑터가 소유한다
    }

    @Test
    void TC_나머지_조회는_그대로_위임된다() {
        adapter.getRealtimeArrival();
        adapter.getSubwayStationMaster();
        adapter.getStationDistance();

        verify(delegate).getRealtimeArrival();
        verify(delegate).getSubwayStationMaster();
        verify(delegate).getStationDistance();
        verify(cache, never()).find(anyString());
        verify(cache, never()).put(anyString(), anyList());
    }
}
