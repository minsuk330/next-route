package watoo.grd.nextroute.application.route.routing;

import java.util.Locale;

/**
 * 캐시 키에 붙는 provider·버전 문맥. 같은 OD 라도 provider(ODSAY/OTP)·graph 버전이 다르면
 * 다른 답이므로 키를 갈라야 한다 — ODsay 캐시가 OTP 요청에 섞이거나, graph 재빌드 후 옛 경로가
 * 나가는 것을 막는다. ODsay 는 graphVersion 이 없어 sentinel({@link RoutingResult#ODSAY_NO_GRAPH})을 쓴다.
 *
 * <p>{@code schemaVersion} 은 캐시 값 스키마 버전이다. 올리면 옛 keyspace 와 분리돼 구형 JSON 을
 * 새 DTO 로 읽지 않는다.
 */
public record RoutingCacheContext(RoutingProvider provider, String graphVersion, String schemaVersion) {

    public RoutingCacheContext {
        if (provider == null) {
            throw new IllegalArgumentException("provider required");
        }
        graphVersion = graphVersion == null ? RoutingResult.ODSAY_NO_GRAPH : graphVersion;
        schemaVersion = schemaVersion == null ? "v1" : schemaVersion;
    }

    public static RoutingCacheContext of(RoutingProvider provider, String graphVersion, String schemaVersion) {
        return new RoutingCacheContext(provider, graphVersion, schemaVersion);
    }

    /** 키 앞부분(문맥). 좌표/정류장 앞에 붙는다. */
    public String keyPrefix() {
        return String.format(Locale.ROOT, "%s:%s:%s", schemaVersion,
                provider.name().toLowerCase(Locale.ROOT), graphVersion);
    }
}
