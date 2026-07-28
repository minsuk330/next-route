package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 검색 결과 스냅샷 설정.
 *
 * <p>{@code ttlSeconds} 는 검색 후 여정을 시작하기까지 허용하는 상한이자 Redis 상주량을 정하는 값이다.
 * {@code enabled=false} 면 저장을 시도하지 않고 응답에 스냅샷 필드도 붙지 않는다(기존 응답과 동일).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "route.snapshot")
public class RouteSnapshotProperties {

    /** 스냅샷 저장 여부. 끄면 저장을 시도하지 않는다. */
    private boolean enabled = true;

    /** 스냅샷 보관 시간(초). */
    private long ttlSeconds = 600;
}
