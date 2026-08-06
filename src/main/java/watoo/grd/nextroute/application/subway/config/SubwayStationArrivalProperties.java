package watoo.grd.nextroute.application.subway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 역별 지하철 실시간 조회의 캐시·합류 설정.
 *
 * <p>전체 수집(collector)이 아니라 <b>역 하나를 직접 묻는 경로</b>에만 걸린다. 여정 평가가 같은 역을
 * 보는 사용자 수만큼 이 API 를 부르던 것을 한 번으로 합치는 것이 목적이다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "realtime.subway.station-arrival")
public class SubwayStationArrivalProperties {

    /**
     * 응답 캐시 TTL. 0 이하면 캐시를 완전히 우회한다.
     *
     * <p>짧게 잡는 이유는 안전 때문이 아니다 — 남은 시간은 응답에 담긴 {@code receivedAt} 기준으로
     * 보정되므로 캐시가 시간을 부풀리지 않는다. 다만 열차 편성이 바뀌는 것을 오래 못 보면 곤란하다.
     */
    private long cacheTtlSeconds = 10;

    /** 진행 중인 같은 역 조회에 합류해 기다리는 최대 시간. */
    private long joinWaitMs = 1000;
}
