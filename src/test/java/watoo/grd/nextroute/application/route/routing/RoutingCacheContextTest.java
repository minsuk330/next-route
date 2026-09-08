package watoo.grd.nextroute.application.route.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.port.out.RemainingRouteCachePort;

import static org.assertj.core.api.Assertions.assertThat;

/** 캐시 키가 provider·graph 버전으로 갈리는지 — 충돌·stale 방지의 핵심. */
class RoutingCacheContextTest {

    @Test
    @DisplayName("ODsay 는 graphVersion 없이 sentinel 사용")
    void odsaySentinel() {
        RoutingCacheContext ctx = RoutingCacheContext.of(RoutingProvider.ODSAY, null, "v1");
        assertThat(ctx.graphVersion()).isEqualTo(RoutingResult.ODSAY_NO_GRAPH);
        assertThat(ctx.keyPrefix()).isEqualTo("v1:odsay:ODSAY_NO_GRAPH");
    }

    @Test
    @DisplayName("ODsay·OTP·다른 graph 버전은 같은 OD 라도 서로 다른 키")
    void keysDoNotCollide() {
        String od = key(RoutingCacheContext.of(RoutingProvider.ODSAY, null, "v1"));
        String otpA = key(RoutingCacheContext.of(RoutingProvider.OTP, "graph-A", "v1"));
        String otpB = key(RoutingCacheContext.of(RoutingProvider.OTP, "graph-B", "v1"));
        assertThat(od).isNotEqualTo(otpA);      // provider 다름
        assertThat(otpA).isNotEqualTo(otpB);    // graph 버전 다름 → stale 방지
    }

    @Test
    @DisplayName("스키마 버전을 올리면 keyspace 가 분리된다")
    void schemaVersionSeparatesKeyspace() {
        String v1 = key(RoutingCacheContext.of(RoutingProvider.OTP, "g", "v1"));
        String v2 = key(RoutingCacheContext.of(RoutingProvider.OTP, "g", "v2"));
        assertThat(v1).isNotEqualTo(v2);
    }

    private String key(RoutingCacheContext ctx) {
        return RemainingRouteCachePort.key(ctx, "STOP-1", 127.0, 37.5);
    }
}
