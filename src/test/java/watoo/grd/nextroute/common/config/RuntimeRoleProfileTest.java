package watoo.grd.nextroute.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import watoo.grd.nextroute.application.bus.port.in.CollectBusArrivalUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiBlockStatusPort;
import watoo.grd.nextroute.application.notification.service.RouteAlertCheckScheduler;
import watoo.grd.nextroute.application.notification.service.RouteAlertDispatchScheduler;
import watoo.grd.nextroute.application.notification.service.RouteAlertScheduleSpawner;
import watoo.grd.nextroute.application.route.service.PredictionSupportRefreshScheduler;
import watoo.grd.nextroute.application.route.service.PredictionSupportService;
import watoo.grd.nextroute.application.route.service.WeatherSnapshotRefreshScheduler;
import watoo.grd.nextroute.application.route.service.WeatherSnapshotService;
import watoo.grd.nextroute.application.subway.service.RealtimeSubwayCollector;
import watoo.grd.nextroute.application.subway.service.SubwayArrivalEventBatchScheduler;
import watoo.grd.nextroute.application.subway.service.SubwaySegmentReloadScheduler;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.BusArrivalLabelBatchScheduler;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.BusArrivalScheduler;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.BusPositionScheduler;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.OdsayRoutePolylineCollectionScheduler;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.StaticDataScheduler;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.WeeklyRouteRotationScheduler;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.notifination.BusAlertRelayLoop;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.notifination.BusArrivalAlertScheduler;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeRoleProfileTest {

    private static final List<Class<?>> WORKER_COMPONENTS = List.of(
            BusArrivalScheduler.class,
            BusPositionScheduler.class,
            RealtimeSubwayCollector.class,
            StaticDataScheduler.class,
            OdsayRoutePolylineCollectionScheduler.class,
            WeeklyRouteRotationScheduler.class,
            BusArrivalLabelBatchScheduler.class,
            SubwayArrivalEventBatchScheduler.class,
            SubwaySegmentReloadScheduler.class,
            RouteAlertCheckScheduler.class,
            RouteAlertDispatchScheduler.class,
            RouteAlertScheduleSpawner.class,
            BusArrivalAlertScheduler.class,
            BusAlertRelayLoop.class
    );

    @Test
    void allGlobalBackgroundComponentsAreWorkerOnly() {
        for (Class<?> type : WORKER_COMPONENTS) {
            Profile profile = AnnotatedElementUtils.findMergedAnnotation(type, Profile.class);
            assertThat(profile)
                    .as("%s must declare @Profile", type.getSimpleName())
                    .isNotNull();
            assertThat(profile.value())
                    .as("%s must be worker-only", type.getSimpleName())
                    .containsExactly("worker");
        }
    }

    @Test
    void routeSearchLocalRefreshComponentsRunInBothRoles() {
        assertProfile(WeatherSnapshotRefreshScheduler.class, "api", "worker");
        assertProfile(PredictionSupportRefreshScheduler.class, "api", "worker");
    }

    @Test
    void profileConditionActuallySelectsTheRoleSpecificComponent() {
        try (AnnotationConfigApplicationContext api = context("api")) {
            assertThat(api.getBeansOfType(WeatherSnapshotRefreshScheduler.class)).hasSize(1);
            assertThat(api.getBeansOfType(BusArrivalScheduler.class)).isEmpty();
        }

        try (AnnotationConfigApplicationContext worker = context("worker")) {
            assertThat(worker.getBeansOfType(WeatherSnapshotRefreshScheduler.class)).hasSize(1);
            assertThat(worker.getBeansOfType(BusArrivalScheduler.class)).hasSize(1);
        }
    }

    @Test
    void cacheServicesDoNotOwnScheduledTriggers() {
        assertThat(scheduledMethods(WeatherSnapshotService.class)).isEmpty();
        assertThat(scheduledMethods(PredictionSupportService.class)).isEmpty();
    }

    private static AnnotationConfigApplicationContext context(String profile) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.registerBean(WeatherSnapshotService.class, () -> mock(WeatherSnapshotService.class));
        context.registerBean(CollectBusArrivalUseCase.class, () -> mock(CollectBusArrivalUseCase.class));
        context.registerBean(BusApiBlockStatusPort.class, () -> mock(BusApiBlockStatusPort.class));
        context.register(WeatherSnapshotRefreshScheduler.class, BusArrivalScheduler.class);
        context.refresh();
        return context;
    }

    private static List<Method> scheduledMethods(Class<?> type) {
        return List.of(type.getDeclaredMethods()).stream()
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .toList();
    }

    private static void assertProfile(Class<?> type, String... expected) {
        Profile profile = AnnotatedElementUtils.findMergedAnnotation(type, Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly(expected);
    }
}
