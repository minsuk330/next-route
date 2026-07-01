package watoo.grd.nextroute.application.bus.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "collector.bus-arrival")
public class BusCollectorProperties {

	private boolean enabled = true;
	private String cron;
	/** 수집한 도착정보를 실시간 캐시에 프라이밍할지 여부(노선×정류장 쓰기량 많음 — 기본 off). */
	private boolean primeRealtimeCache = false;
	private List<String> targetRouteNames = List.of();
	private int dailyBudget = 60000;
	private int finalizeMissThreshold = 2;
	private int staleThresholdMinutes = 5;
	private List<Integer> includedArrivalOrders = new ArrayList<>(List.of(1));
	private List<String> excludedArrivalMessages = new ArrayList<>(List.of("출발대기"));
}
