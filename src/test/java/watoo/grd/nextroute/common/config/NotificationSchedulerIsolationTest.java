package watoo.grd.nextroute.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step D 검증: 알림 전용 스케줄러 풀 격리로 공용 풀 포화가 알림 잡을 밀지 않음(head-of-line blocking 제거)을
 * 증명한다. 프로덕션과 동일하게 {@link SchedulingConfig} 로 두 풀을 구성한다.
 *
 * <p>공용 풀(size 3)을 블로킹 작업 3개로 가득 채운 뒤, 같은 순간 공용/알림 각각에 작업을 던진다.
 * 공용 작업은 큐에서 대기(블로킹)하지만, 알림 작업은 전용 풀에서 즉시 실행됨을 확인한다.
 */
class NotificationSchedulerIsolationTest {

    private static final int GENERAL_POOL = 3;
    private static final int NOTI_POOL = 2;

    private ThreadPoolTaskScheduler general;
    private ThreadPoolTaskScheduler notification;
    /** 공용 풀 점유 해제 신호. tearDown 에서 먼저 풀어 실패 시에도 블로킹 태스크가 남지 않게 한다. */
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        SchedulingConfig config = new SchedulingConfig();
        general = config.taskScheduler(GENERAL_POOL);
        notification = config.notificationTaskScheduler(NOTI_POOL);
        general.initialize();
        notification.initialize();
    }

    @AfterEach
    void tearDown() {
        release.countDown();   // assert 실패로 미해제된 블로킹 태스크 정리 후 종료
        general.shutdown();
        notification.shutdown();
    }

    @Test
    void notificationPool_runsWhileGeneralPoolSaturated() throws Exception {
        CountDownLatch generalBusy = new CountDownLatch(GENERAL_POOL);

        // 공용 풀 3스레드 전부 블로킹으로 점유.
        for (int i = 0; i < GENERAL_POOL; i++) {
            general.execute(() -> {
                generalBusy.countDown();
                await(release);
            });
        }
        assertThat(generalBusy.await(2, TimeUnit.SECONDS)).isTrue();

        // 공용에 추가 작업 → 큐에서 대기(포화). 알림에 작업 → 전용 풀서 즉시 실행.
        CountDownLatch generalVictim = new CountDownLatch(1);
        CountDownLatch notiDone = new CountDownLatch(1);
        AtomicReference<String> notiThread = new AtomicReference<>();

        general.execute(generalVictim::countDown);
        notification.execute(() -> {
            notiThread.set(Thread.currentThread().getName());
            notiDone.countDown();
        });

        // 알림 작업은 공용 포화와 무관하게 짧은 시간 내 완료.
        assertThat(notiDone.await(1, TimeUnit.SECONDS)).as("알림 잡은 격리되어 즉시 실행").isTrue();
        assertThat(notiThread.get()).startsWith("noti-sched-");

        // 대조: 공용 작업은 아직 블로킹(밀림) — 격리가 없었다면 알림도 이렇게 밀렸을 것.
        assertThat(generalVictim.await(300, TimeUnit.MILLISECONDS))
                .as("공용 잡은 포화 풀 뒤에서 대기(head-of-line blocking)").isFalse();

        // 공용 풀 해제 후 대기 작업도 결국 실행.
        release.countDown();
        assertThat(generalVictim.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void twoDistinctPools_withExpectedSizes() {
        assertThat(general).isNotSameAs(notification);
        assertThat(general.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(GENERAL_POOL);
        assertThat(notification.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(NOTI_POOL);
    }

    private static void await(CountDownLatch latch) {
        try {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
            while (!latch.await(100, TimeUnit.MILLISECONDS) && Instant.now().isBefore(deadline)) {
                // busy-wait bounded
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
