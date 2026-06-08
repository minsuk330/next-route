package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.bus.port.in.CollectBusArrivalUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiBlockStatusPort;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BusArrivalSchedulerTest {

	private final CollectBusArrivalUseCase useCase = () -> {};
	private final BusApiBlockStatusPort blockStatusPort = Optional::empty;
	private final BusArrivalScheduler scheduler = new BusArrivalScheduler(useCase, blockStatusPort);

	@Test
	void TC_자정을_넘기는_수집시간대_안에서만_active다() {
		ReflectionTestUtils.setField(scheduler, "activeStart", "03:30");
		ReflectionTestUtils.setField(scheduler, "activeEnd", "02:30");

		assertThat(scheduler.isActiveHours(LocalTime.of(3, 29))).isFalse();
		assertThat(scheduler.isActiveHours(LocalTime.of(3, 30))).isTrue();
		assertThat(scheduler.isActiveHours(LocalTime.of(23, 59))).isTrue();
		assertThat(scheduler.isActiveHours(LocalTime.of(2, 29))).isTrue();
		assertThat(scheduler.isActiveHours(LocalTime.of(2, 30))).isFalse();
	}

	@Test
	void TC_API_차단중이면_useCase를_실행하지_않는다() {
		AtomicInteger executions = new AtomicInteger();
		BusArrivalScheduler blockedScheduler = new BusArrivalScheduler(
				executions::incrementAndGet,
				() -> Optional.of(Instant.parse("2026-06-09T00:00:00Z"))
		);
		ReflectionTestUtils.setField(blockedScheduler, "enabled", true);
		ReflectionTestUtils.setField(blockedScheduler, "activeStart", "00:00");
		ReflectionTestUtils.setField(blockedScheduler, "activeEnd", "00:00");

		blockedScheduler.trigger();

		assertThat(executions).hasValue(0);
	}
}
