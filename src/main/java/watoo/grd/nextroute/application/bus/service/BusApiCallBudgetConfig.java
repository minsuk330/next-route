package watoo.grd.nextroute.application.bus.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 도착/위치 수집기는 일일 호출 예산을 독립적으로 소진한다.
 * 단일 공유 예산이면 한쪽이 다른 쪽 예산까지 먹어 저녁 수집이 통째로 끊겼다.
 */
@Configuration
public class BusApiCallBudgetConfig {

	@Bean
	public BusApiCallBudget arrivalApiCallBudget(Clock clock) {
		return new BusApiCallBudget(clock);
	}

	@Bean
	public BusApiCallBudget positionApiCallBudget(Clock clock) {
		return new BusApiCallBudget(clock);
	}
}
