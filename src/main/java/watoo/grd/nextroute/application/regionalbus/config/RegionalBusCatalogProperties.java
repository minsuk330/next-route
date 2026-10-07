package watoo.grd.nextroute.application.regionalbus.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 경기·TAGO 노선 정류장 표 적재(#267).
 *
 * <p>{@code enabled} 는 스케줄러(worker)만 본다. 조회 쪽은 표가 비어 있으면 접두사 표로 폴백하므로
 * 꺼져 있어도 지금과 같은 동작이다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "collector.regional-bus-catalog")
public class RegionalBusCatalogProperties {

    private boolean enabled = false;

    /** 동시 호출 수. 포털 키 동시 세션 30 을 도착 조회(GBIS·TAGO)와 나눠 쓴다. */
    private int concurrency = 3;

    /** 동시 세션 한도(TAGO "99")를 만났을 때 다시 시도하는 횟수. */
    private int maxRetries = 3;

    /** 재시도 간격의 기준(ms). n 번째 재시도는 n 배를 기다린다. */
    private long retryBackoffMs = 1000;

    /** 표가 비어 있을 때 부팅 직후 한 번 적재한다. 매 배포마다 2만 회를 쏘지 않도록 비어 있을 때만. */
    private boolean loadOnEmpty = true;
}
