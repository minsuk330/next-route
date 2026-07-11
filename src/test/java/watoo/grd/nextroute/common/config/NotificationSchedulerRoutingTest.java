package watoo.grd.nextroute.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import watoo.grd.nextroute.application.notification.service.RouteAlertCheckScheduler;
import watoo.grd.nextroute.application.notification.service.RouteAlertDispatchScheduler;
import watoo.grd.nextroute.application.notification.service.RouteAlertScheduleSpawner;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.BusAlertOutboxRelayScheduler;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.BusArrivalAlertScheduler;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step D 라우팅 회귀 가드: 알림 @Scheduled 잡이 전용 풀(notificationTaskScheduler)로 라우팅되는지
 * 애너테이션 자체를 검증한다. 누군가 scheduler 속성을 지우면(=공용 풀로 회귀) 이 테스트가 잡는다.
 * 격리 <b>동작</b>은 {@link NotificationSchedulerIsolationTest}, 여기선 <b>배선</b>만 본다.
 */
class NotificationSchedulerRoutingTest {

    private static final String NOTI_SCHEDULER = "notificationTaskScheduler";

    @Test
    void allNotificationScheduledJobs_routeToDedicatedPool() throws Exception {
        assertScheduledOn(RouteAlertCheckScheduler.class, "run");
        assertScheduledOn(RouteAlertDispatchScheduler.class, "run");
        assertScheduledOn(RouteAlertScheduleSpawner.class, "run");
        assertScheduledOn(BusArrivalAlertScheduler.class, "poll");
        assertScheduledOn(BusAlertOutboxRelayScheduler.class, "relay");
    }

    private void assertScheduledOn(Class<?> type, String methodName) throws NoSuchMethodException {
        Method m = type.getDeclaredMethod(methodName);
        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertThat(scheduled)
                .as("%s#%s 에 @Scheduled 존재", type.getSimpleName(), methodName)
                .isNotNull();
        assertThat(scheduled.scheduler())
                .as("%s#%s 는 알림 전용 풀로 라우팅돼야 함", type.getSimpleName(), methodName)
                .isEqualTo(NOTI_SCHEDULER);
    }
}
