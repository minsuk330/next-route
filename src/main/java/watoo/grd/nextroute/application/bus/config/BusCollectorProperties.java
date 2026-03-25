package watoo.grd.nextroute.application.bus.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "collector.bus-arrival")
public class BusCollectorProperties {

	private boolean enabled = true;
	private String cron;
	private List<String> targetRouteNames = List.of();
	private int dailyBudget = 1000;
}
