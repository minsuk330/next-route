package watoo.grd.nextroute.application.arrival.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 정류장 단위 버스 도착(#577). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "bus.stop-arrival")
public class BusStopArrivalProperties {
    /**
     * 정류장 단위 캐시(공급자 하나, 정류장 하나). 앱이 30초마다 부르므로 같은 길이로 둬서, 한 사용자의 연이은 요청이나
     * 같은 정류장을 보는 다른 사용자가 캐시를 탄다. 서울 검색 캐시(15 s)와 따로다.
     */
    private long cacheTtlSeconds = 30;
    /** 요청 하나의 전체 기한. 늦은 정류장은 LIMITED 로 두고 나머지를 돌려준다. */
    private long deadlineMs = 2500;
    /**
     * 요청 안 병렬 조회 실행기(캐시를 놓친 공급자만). 큐 없이 넘치면 거절 → 그 정류장 LIMITED. 요청 하나가 최대 20개
     * (10곳 × 서울·경기)를 내므로 그보다 크게 둔다. 실제 외부 동시 호출은 공급자별 세마포어가 따로 묶는다.
     */
    private int executorCoreSize = 8;
    private int executorMaxSize = 32;
}
