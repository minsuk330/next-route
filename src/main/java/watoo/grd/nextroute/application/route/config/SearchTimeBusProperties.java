package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 검색 경로의 실시간 버스 조회 설정.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "route.search.bus")
public class SearchTimeBusProperties {

    /**
     * 같은 키의 진행 중인 provider 호출에 합류했을 때 기다릴 최대 시간.
     * 버스 조회는 이미 짧은 timeout(~1.5s)을 쓰므로 그보다 조금 길게 잡아 owner 를 기다릴 여지를 준다.
     * 이 시간을 넘기면 조회를 못 한 것으로 처리(LIMITED)하고 기본 경로는 그대로 반환한다.
     */
    private long joinWaitMs = 1800;
}
