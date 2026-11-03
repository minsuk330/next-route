package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.bus.port.in.CollectBusPositionUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiBlockStatusPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.common.lock.FixedLeaderLease;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BusPositionSchedulerTest {

	private final CollectBusPositionUseCase useCase = () -> {};
	private final BusApiBlockStatusPort blockStatusPort = endpoint -> Optional.empty();
	private final BusPositionScheduler scheduler = new BusPositionScheduler(useCase, blockStatusPort, FixedLeaderLease.leader());

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
		BusPositionScheduler blockedScheduler = new BusPositionScheduler(
				executions::incrementAndGet,
				endpoint -> endpoint == BusApiQuotaPort.Endpoint.POSITION
						? Optional.of(Instant.parse("2026-06-09T00:00:00Z"))
						: Optional.empty(),
				FixedLeaderLease.leader()
		);
		ReflectionTestUtils.setField(blockedScheduler, "enabled", true);
		ReflectionTestUtils.setField(blockedScheduler, "activeStart", "00:00");
		ReflectionTestUtils.setField(blockedScheduler, "activeEnd", "00:00");

		blockedScheduler.trigger();

		assertThat(executions).hasValue(0);
	}

	@Test
	void TC_리더가_아니면_useCase를_실행하지_않는다() {
		AtomicInteger executions = new AtomicInteger();
		BusPositionScheduler follower = new BusPositionScheduler(
				executions::incrementAndGet, endpoint -> Optional.empty(), FixedLeaderLease.notLeader());
		ReflectionTestUtils.setField(follower, "enabled", true);
		ReflectionTestUtils.setField(follower, "activeStart", "00:00");
		ReflectionTestUtils.setField(follower, "activeEnd", "00:00");

		follower.trigger();

		assertThat(executions).hasValue(0);
	}

	@Test
	void TC_리더면_useCase를_실행한다() {
		AtomicInteger executions = new AtomicInteger();
		BusPositionScheduler leader = new BusPositionScheduler(
				executions::incrementAndGet, endpoint -> Optional.empty(), FixedLeaderLease.leader());
		ReflectionTestUtils.setField(leader, "enabled", true);
		ReflectionTestUtils.setField(leader, "activeStart", "00:00");
		ReflectionTestUtils.setField(leader, "activeEnd", "00:00");

		leader.trigger();

		assertThat(executions).hasValue(1);
	}
}
