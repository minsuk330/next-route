package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 환승 도착예측 기능 토글 + 운영 하드닝(PR4) 파라미터.
 * enabled=false면 fan-out 전체 차단 (REALTIME 포함).
 * ML은 ml.predictor.enabled로 별도 제어 가능.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "route.transfer-arrival")
public class TransferArrivalProperties {
    private boolean enabled = false;

    /** 검색 1건 전체 enrich 상한(ms). 초과 시 미처리 버스 승차는 status=ERROR. 0 이하면 무제한. */
    private long deadlineMs = 1500;

    /** 검색용 버스 API Redis 캐시 TTL(초). 같은 stopId/routeId 중복 호출 억제. 0 이하면 캐시 비활성. */
    private long cacheTtlSeconds = 15;
}
