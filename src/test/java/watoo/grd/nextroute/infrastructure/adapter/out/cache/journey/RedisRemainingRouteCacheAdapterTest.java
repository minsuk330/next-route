package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

import watoo.grd.nextroute.application.route.routing.RoutingCacheContext;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 남은 구간 경로 캐시.
 *
 * <p><b>키 모양과 TTL 이 계약이다.</b> 키에 여정·사용자가 없어야 다른 사람의 같은 질문이 이 캐시를
 * 맞히고, 좌표 표기가 흔들리면 같은 지점이 다른 키가 되어 캐시가 영원히 빗나간다.
 *
 * <p>그리고 <b>실패하면 없는 것처럼 동작해야 한다</b>(fail-open). 같은 패키지의 cooldown 은 정반대다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("남은 구간 경로 캐시")
class RedisRemainingRouteCacheAdapterTest {

    private static final RoutingCacheContext CTX =
            RoutingCacheContext.of(RoutingProvider.ODSAY, null, "v1");

    private static final String STOP_ID = "ST-C";
    private static final double DEST_X = 127.123456;
    private static final double DEST_Y = 37.5;
    private static final String KEY = "journey:reroute:remaining:v1:odsay:ODSAY_NO_GRAPH:ST-C:127.12346:37.50000";

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private RedisRemainingRouteCacheAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RedisRemainingRouteCacheAdapter(redisTemplate, objectMapper, properties);
    }

    @Test
    @DisplayName("키에 여정·사용자가 없고 좌표는 소수점 5자리로 고정된다")
    void keyIsSharedAndCoordinatesAreFixedWidth() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(List.of(path())));

        assertThat(adapter.find(CTX, STOP_ID, DEST_X, DEST_Y)).isPresent();
    }

    @Test
    @DisplayName("저장은 설정한 TTL 로 한 문장에 끝낸다")
    void putUsesConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        adapter.put(CTX, STOP_ID, DEST_X, DEST_Y, List.of(path()));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(KEY), json.capture(), eq(Duration.ofMinutes(10)));
        assertThat(json.getValue()).contains("subPaths");
    }

    @Test
    @DisplayName("저장한 값을 그대로 읽어 온다")
    void roundTrip() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(List.of(path())));

        Optional<List<PathResult>> found = adapter.find(CTX, STOP_ID, DEST_X, DEST_Y);

        assertThat(found).isPresent();
        assertThat(found.get()).hasSize(1);
        assertThat(found.get().get(0).subPaths()).hasSize(1);
    }

    @Test
    @DisplayName("빈 목록은 저장하지 않는다 — 빈 답을 10분 들고 있으면 그 구간이 10분 내내 빈손이다")
    void doesNotCacheEmptyResult() {
        adapter.put(CTX, STOP_ID, DEST_X, DEST_Y, List.of());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Redis 조회가 터져도 예외 대신 빈 결과다 — 캐시는 fail-open 이다")
    void findFailureIsOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThat(adapter.find(CTX, STOP_ID, DEST_X, DEST_Y)).isEmpty();
    }

    @Test
    @DisplayName("Redis 저장이 터져도 검색 결과는 그대로 쓴다")
    void putFailureIsSwallowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.BDDMockito.willThrow(new IllegalStateException("redis down"))
                .given(valueOps).set(anyString(), anyString(), any(Duration.class));

        adapter.put(CTX, STOP_ID, DEST_X, DEST_Y, List.of(path()));
    }

    @Test
    @DisplayName("깨진 값이 들어 있으면 없는 것으로 본다")
    void brokenValueIsMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn("{ 이건 JSON 이 아니다");

        assertThat(adapter.find(CTX, STOP_ID, DEST_X, DEST_Y)).isEmpty();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    private PathResult path() {
        SubPathResult sub = new SubPathResult(2, 15, null, null, null,
                "정류장C", "정류장D", 127.3, 37.3, 127.4, 37.4,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                "ST-C", "ST-D", null, null, null, null, null);
        return new PathResult(1, null, List.of(sub), List.of());
    }
}
