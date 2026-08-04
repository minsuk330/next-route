package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyExpiryProperties;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 앱에서 연락이 끊긴 여정을 종료 상태로 정리한다.
 *
 * <p>서버는 앱이 죽었는지 그냥 조용한지 구분할 수 없다. 그래서 "이 시각까지는 앱이 안내를
 * 맡는다"를 미리 정해 두고(lease 3분), 그 시각을 넘겨도 소식이 없으면 접는다. 다만 lease 하나만
 * 보고 접지는 않는다 — iOS·Android 는 정상 동작 중에도 background callback 이 늦게 도착하므로
 * 유예 2분을 더 기다린다(SC-3 1항). 지하철 보호 시각이 남아 있으면 그때까지도 기다린다(SC-12 4항).
 *
 * <p>한 번에 여러 건을 고르고 바꾸는 일은 SQL 한 문장이 한다({@code claimExpiredBatch}). 골라
 * 놓고 애플리케이션에서 하나씩 바꾸면 그 사이에 다른 인스턴스가 같은 여정을 두 번 접는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyLeaseExpiryService {

    /** 접은 여정 수. Phase 8 점진 배포에서 "얼마나 접히고 있나"를 보는 값이다. */
    public static final String EXPIRED_COUNTER = "journey.lease.expired";

    private final JourneySessionRepository sessionRepository;
    private final JourneyNavigationProperties navigationProperties;
    private final JourneyExpiryProperties expiryProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * 밀린 만큼 이어서 훑는다. 한 tick 의 상한은 설정이 정한다.
     *
     * @return 이번 tick 에 접은 여정 수
     */
    public int sweep() {
        int total = 0;
        for (int sweep = 0; sweep < expiryProperties.getMaxSweepsPerTick(); sweep++) {
            int expired = expireBatch().size();
            total += expired;
            if (expired < expiryProperties.getBatchSize()) {
                // 배치를 채우지 못했으면 더 이상 대상이 없다.
                break;
            }
        }
        return total;
    }

    /**
     * 한 배치를 접는다. 트랜잭션은 이 SQL 한 문장만 감싼다.
     *
     * <p>짧게 유지하는 것이 중요하다. 여기에 외부 호출이나 다른 계산을 끼우면 그 시간만큼 DB
     * 커넥션과 행 잠금을 붙잡고, 만료 하나가 커넥션 풀을 마르게 한다.
     *
     * @return 이번 배치에서 접은 여정 번호. 같은 번호가 두 인스턴스에 동시에 나오지 않는다
     */
    @Transactional
    public List<Long> expireBatch() {
        Instant now = Instant.now(clock);
        Instant leaseDeadline = now.minus(navigationProperties.expirationGrace());

        List<Long> expired = sessionRepository.claimExpiredBatch(
                now, leaseDeadline, expiryProperties.getBatchSize());
        if (expired.isEmpty()) {
            return List.of();
        }

        meterRegistry.counter(EXPIRED_COUNTER, "reason", "client_lease_expired")
                .increment(expired.size());
        log.info("[JourneyLeaseExpiry] {}건 만료", expired.size());
        // 어느 여정이 접혔는지는 문의가 들어왔을 때만 필요하다. 배치가 200건이라 info 로 두면 시끄럽다.
        log.debug("[JourneyLeaseExpiry] 만료 journeyIds={}", expired);
        return expired;
    }
}
