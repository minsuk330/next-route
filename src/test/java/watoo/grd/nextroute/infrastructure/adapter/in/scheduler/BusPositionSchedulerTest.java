package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.bus.port.in.CollectBusPositionUseCase;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class BusPositionSchedulerTest {

	private final CollectBusPositionUseCase useCase = () -> {};
	private final BusPositionScheduler scheduler = new BusPositionScheduler(useCase);

	@Test
	void TC_자정을_넘기는_수집시간대_안에서만_active다() {
		ReflectionTestUtils.setField(scheduler, "activeStart", "03:30");
		ReflectionTestUtils.setField(scheduler, "activeEnd", "00:30");

		assertThat(scheduler.isActiveHours(LocalTime.of(3, 29))).isFalse();
		assertThat(scheduler.isActiveHours(LocalTime.of(3, 30))).isTrue();
		assertThat(scheduler.isActiveHours(LocalTime.of(23, 59))).isTrue();
		assertThat(scheduler.isActiveHours(LocalTime.of(0, 29))).isTrue();
		assertThat(scheduler.isActiveHours(LocalTime.of(0, 30))).isFalse();
	}
}
