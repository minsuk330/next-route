package watoo.grd.nextroute.application.journey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 아무도 지나가지 않는 제안을 접는다.
 *
 * <p>lazy 전이(조회·생성·수락)가 1차이고 <b>이것은 그물이다.</b> 초안은 lazy 만으로 끝냈는데,
 * 그 판단은 "활성 제안이 있는 여정은 사용자가 화면을 보고 있다"는 가정에 기대고 있었다. 앱이
 * 죽거나 백그라운드로 들어가면 세 진입점이 <b>모두</b> 오지 않는다. 그러면 TTL 이 지난 행이
 * {@code ACTIVE} 로 남고, 사용자가 돌아왔을 때 그 자리 때문에 새 제안이 안 만들어진다.
 *
 * <p>그래서 둘 다 있어야 한다. 하나만 두면 각각 "느린 화면"과 "막힌 자리"가 된다.
 *
 * <p>모양은 {@link JourneyLeaseExpiryService} 를 그대로 본뜬다 — 다른 모양으로 만들면 두 만료
 * 경로를 각각 배워야 한다.
 */
@Slf4j
@Service
public class RerouteProposalExpiryService {

    private final RerouteProposalRepository proposalRepository;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /**
     * {@link TransactionTemplate} 을 주입받지 않고 여기서 만든다({@link JourneyLeaseExpiryService}
     * 와 같은 이유) — 이 서비스는 트랜잭션 경계 자체가 검증 대상이라 자동 구성 빈에 기대지 않는다.
     */
    public RerouteProposalExpiryService(RerouteProposalRepository proposalRepository,
                                        JourneyRerouteProperties properties,
                                        JourneyRerouteMetrics metrics,
                                        Clock clock,
                                        PlatformTransactionManager transactionManager) {
        this.proposalRepository = proposalRepository;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 밀린 만큼 이어서 훑되 한 tick 의 상한을 지킨다.
     *
     * <p>배치마다 트랜잭션을 <b>여기서 연다</b>. 같은 클래스의 {@link #expireBatch()} 를 그냥
     * 부르면 프록시를 타지 않아 {@code @Transactional} 이 걸리지 않는다.
     *
     * @return 이번 tick 에 접은 제안 수
     */
    public int sweep() {
        int total = 0;
        int batchSize = properties.getExpiry().getBatchSize();
        for (int sweep = 0; sweep < properties.getExpiry().getMaxSweepsPerTick(); sweep++) {
            int expired = transactionTemplate.execute(status -> expireBatch());
            total += expired;
            if (expired < batchSize) {
                // 배치를 채우지 못했으면 더 이상 대상이 없다.
                break;
            }
        }
        if (total > 0) {
            log.info("[JourneyRerouteExpiry] 제안 {}건 만료", total);
        }
        return total;
    }

    /**
     * 한 배치를 접는다. 트랜잭션은 이 SQL 한 문장만 감싼다 — 길게 잡으면 만료 하나가 커넥션
     * 풀을 마르게 한다.
     *
     * <p>{@code @Transactional} 은 이 메서드를 <b>밖에서</b> 직접 부르는 경로(테스트)를 위해
     * 남긴다. {@link #sweep()} 이 이미 트랜잭션을 열고 부르므로 그때는 참여만 한다.
     */
    @Transactional
    public int expireBatch() {
        Instant now = Instant.now(clock);
        int expired = proposalRepository.expireDueBatch(
                now, properties.getExpiry().getBatchSize(), LocalDateTime.now(clock));
        // lazy 전이(ttl)와 사유를 가른다. 이쪽이 대부분이면 사용자가 제안을 보지 못하고 있다는 뜻이다.
        metrics.recordResolved("ttl-sweep", expired);
        return expired;
    }
}
