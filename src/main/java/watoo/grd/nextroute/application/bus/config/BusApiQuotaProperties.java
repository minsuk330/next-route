package watoo.grd.nextroute.application.bus.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 검색 몫 provider quota(일일, KST). collector 몫(collector.bus-*.daily-budget)과 합이
 * provider 실제 일일 한도를 넘지 않도록 설정한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "bus.quota.search")
public class BusApiQuotaProperties {
    /** 검색 도착정보(getArrInfoByStId) 일일 호출 상한. */
    private int arrival = 5000;
    /** 검색 위치정보(getBusPosByRtid) 일일 호출 상한. */
    private int position = 5000;
}
