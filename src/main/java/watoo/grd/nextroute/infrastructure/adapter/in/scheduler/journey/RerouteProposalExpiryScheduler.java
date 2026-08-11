package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.service.RerouteProposalExpiryService;

/**
 * 만료된 재탐색 제안을 주기적으로 접는다.
 *
 * <p>worker 프로필에서만 돈다({@link JourneyLeaseExpiryScheduler} 와 같은 이유) — api 컨테이너
 * 에서도 돌면 사용자 요청을 처리해야 할 스레드와 DB 커넥션을 배치가 나눠 쓴다.
 *
 * <p><b>기능 플래그를 걸지 않는다.</b> 재탐색을 끈 뒤에도 그전에 만들어진 행은 접혀야 한다.
 * 대상이 없으면 SQL 한 문장이 0행을 돌려주고 끝이라 값이 싸다.
 *
 * <p>여러 인스턴스가 동시에 돌아도 된다. 같은 행을 두 번 접지 않는 책임은 스케줄러가 아니라
 * {@code expireDueBatch} 의 {@code SKIP LOCKED} 가 진다.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class RerouteProposalExpiryScheduler {

    private final RerouteProposalExpiryService expiryService;

    /**
     * 기본 60초. 제안 TTL 이 120초라 그 절반이면 자리를 오래 막지 않는다.
     *
     * <p>lease 만료(30초)보다 느슨해도 되는 이유는 여기서 늦는 대가가 "새 제안이 조금 늦게
     * 생긴다"뿐이기 때문이다.
     *
     * <p>예외를 삼킨다. 던지면 로그만 쌓이고, 만료는 다음 tick 에 다시 시도하면 되는 일이다.
     */
    @Scheduled(fixedDelayString = "${journey.reroute.expiry.interval-ms:60000}")
    public void run() {
        try {
            expiryService.sweep();
        } catch (Exception e) {
            log.error("[JourneyRerouteExpiry] 제안 만료 처리 실패: {}", e.getMessage(), e);
        }
    }
}
