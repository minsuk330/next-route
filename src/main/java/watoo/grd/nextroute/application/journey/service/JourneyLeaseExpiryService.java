package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.journey.config.JourneyExpiryProperties;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;

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
public class JourneyLeaseExpiryService {

    /** 접은 여정 수. Phase 8 점진 배포에서 "얼마나 접히고 있나"를 보는 값이다. */
    public static final String EXPIRED_COUNTER = "journey.lease.expired";

    private final JourneySessionRepository sessionRepository;
    private final JourneyNotificationRepository notificationRepository;
    private final RerouteProposalRepository rerouteProposalRepository;
    private final JourneyRerouteMetrics rerouteMetrics;
    private final JourneyNavigationProperties navigationProperties;
    private final JourneyExpiryProperties expiryProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /**
     * {@link TransactionTemplate} 을 주입받지 않고 여기서 만든다. 자동 구성이 주는 빈에 기대면
     * 슬라이스 테스트마다 그 빈이 있는지에 의존하게 되는데, 이 서비스는 트랜잭션 경계 자체가
     * 검증 대상이라 그 의존을 만들지 않는다.
     */
    public JourneyLeaseExpiryService(JourneySessionRepository sessionRepository,
                                     JourneyNotificationRepository notificationRepository,
                                     RerouteProposalRepository rerouteProposalRepository,
                                     JourneyRerouteMetrics rerouteMetrics,
                                     JourneyNavigationProperties navigationProperties,
                                     JourneyExpiryProperties expiryProperties,
                                     MeterRegistry meterRegistry,
                                     Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.notificationRepository = notificationRepository;
        this.rerouteProposalRepository = rerouteProposalRepository;
        this.rerouteMetrics = rerouteMetrics;
        this.navigationProperties = navigationProperties;
        this.expiryProperties = expiryProperties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 밀린 만큼 이어서 훑는다. 한 tick 의 상한은 설정이 정한다.
     *
     * <p>배치마다 트랜잭션을 <b>여기서 연다</b>. 같은 클래스의 {@link #expireBatch()} 를 그냥
     * 부르면 프록시를 타지 않아 {@code @Transactional} 이 걸리지 않기 때문이다. 만료 SQL 하나만
     * 있을 때는 자동 커밋으로 우연히 원자적이었지만, 이제 알림 취소가 같이 돌아 문장이 둘이다 —
     * 따로 커밋되면 "만료됐는데 알림은 살아 있는" 상태가 생긴다.
     *
     * @return 이번 tick 에 접은 여정 수
     */
    public int sweep() {
        int total = 0;
        for (int sweep = 0; sweep < expiryProperties.getMaxSweepsPerTick(); sweep++) {
            int expired = transactionTemplate.execute(status -> expireBatch()).size();
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
     * <p>{@code @Transactional} 은 이 메서드를 <b>밖에서</b> 직접 부르는 경로(테스트)를 위해
     * 남긴다. {@link #sweep()} 이 이미 트랜잭션을 열고 부르므로 그때는 참여만 한다.
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

        // 접은 여정의 대기 알림을 같은 트랜잭션에서 취소한다. 200개짜리 IN 은
        // idx_journey_notification_journey_live 로 싸다. 만료 SQL 에 합치지 않는 이유는 부하
        // 시험을 거친 그 쿼리(idx_journey_due, 정렬 단계 없음)의 실행 계획을 흔들지 않기 위해서다.
        notificationRepository.cancelPendingByJourneyIdIn(expired);
        // 제안도 같은 트랜잭션에서 접는다. 밖으로 빼면 "여정은 끝났는데 제안은 살아 있는" 창이 생긴다.
        rerouteMetrics.recordResolved("journey-ended",
                rerouteProposalRepository.resolveActiveByJourneyIdIn(expired, now));

        meterRegistry.counter(EXPIRED_COUNTER, "reason", "client_lease_expired")
                .increment(expired.size());
        log.info("[JourneyLeaseExpiry] {}건 만료", expired.size());
        // 어느 여정이 접혔는지는 문의가 들어왔을 때만 필요하다. 배치가 200건이라 info 로 두면 시끄럽다.
        log.debug("[JourneyLeaseExpiry] 만료 journeyIds={}", expired);
        return expired;
    }
}
