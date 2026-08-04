package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.service.JourneyLeaseExpiryService;

/**
 * 만료 처리 배치를 주기적으로 깨운다.
 *
 * <p>worker 프로필에서만 돈다. api 컨테이너에서도 돌면 사용자 요청을 처리해야 할 스레드와 DB
 * 커넥션을 배치가 나눠 쓰게 된다(VPS 는 api·worker 두 컨테이너로 뜬다).
 *
 * <p>{@code journey.navigation.expiry.enabled=false} 로 끌 수 있다. 만료가 정상 사용자의 여정을
 * 접는 상황이라면 배포를 기다리지 않고 멈춰야 한다.
 *
 * <p>여러 인스턴스가 동시에 돌아도 된다. 같은 여정을 두 번 접지 않는 책임은 스케줄러가 아니라
 * {@code claimExpiredBatch} 의 {@code SKIP LOCKED} 가 진다.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.navigation.expiry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class JourneyLeaseExpiryScheduler {

    private final JourneyLeaseExpiryService expiryService;

    /**
     * 기본 30초. lease 3분 + 유예 2분이므로 만료 판정이 30초 늦어지는 것은 계약 안이다.
     *
     * <p>예외를 삼킨다. 던지면 {@code @Scheduled} 가 다음 tick 을 계속 잡되 로그만 쌓이고, 만료는
     * 다음 tick 에 다시 시도하면 되는 일이다.
     */
    @Scheduled(fixedDelayString = "${journey.navigation.expiry.interval-ms:30000}")
    public void run() {
        try {
            expiryService.sweep();
        } catch (Exception e) {
            log.error("[JourneyLeaseExpiry] 만료 처리 실패: {}", e.getMessage(), e);
        }
    }
}
