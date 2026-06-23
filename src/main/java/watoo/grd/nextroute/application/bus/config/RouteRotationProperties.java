package watoo.grd.nextroute.application.bus.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 주간 노선 로테이션 설정. {@code ridershipMonth} 의 ridership 랭킹에서
 * bucket 단위(30개)로 수집 대상을 매주 교체한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "collector.route-rotation")
public class RouteRotationProperties {

	private boolean enabled = false;
	private String cron;
	/** 랭킹 조회 기준월(yyyyMM). ridership 데이터가 확정된 월이어야 한다. */
	private String ridershipMonth = "202603";
	private int limit = 30;
	private int pageSize = 1000;
}
