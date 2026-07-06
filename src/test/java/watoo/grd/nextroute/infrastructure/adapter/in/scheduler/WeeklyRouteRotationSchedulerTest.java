package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.bus.config.RouteRotationProperties;
import watoo.grd.nextroute.application.bus.service.WeeklyRouteRotationService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WeeklyRouteRotationSchedulerTest {

	private WeeklyRouteRotationService rotationService;
	private RouteRotationProperties properties;
	private WeeklyRouteRotationScheduler scheduler;

	@BeforeEach
	void setUp() {
		rotationService = mock(WeeklyRouteRotationService.class);
		properties = new RouteRotationProperties();
		properties.setCron("0 0 4 * * MON");
		properties.setRidershipMonth("202603");
		properties.setLimit(30);
		scheduler = new WeeklyRouteRotationScheduler(rotationService, properties);
	}

	@Test
	void TC_비활성화되어_있으면_로테이션을_실행하지_않는다() {
		properties.setEnabled(false);

		scheduler.trigger();

		verify(rotationService, never()).rotate();
	}

	@Test
	void TC_활성화되어_있으면_로테이션을_실행한다() {
		properties.setEnabled(true);

		scheduler.trigger();

		verify(rotationService).rotate();
	}

	@Test
	void TC_로테이션_실패는_스케줄러_밖으로_전파하지_않는다() {
		properties.setEnabled(true);
		doThrow(new IllegalStateException("boom")).when(rotationService).rotate();

		assertDoesNotThrow(() -> scheduler.trigger());

		verify(rotationService).rotate();
	}
}
