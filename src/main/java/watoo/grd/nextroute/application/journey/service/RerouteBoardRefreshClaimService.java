package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 보드 갱신의 선점과 마무리(#264 PR-2). 둘 다 <b>짧은 트랜잭션 하나</b>다 — 경로 검색이 도는 동안 여정
 * 행을 잠가 두지 않는다. 선점은 {@code reroute_refresh_claimed_until} 로만 표시한다(V58).
 */
@Service
@RequiredArgsConstructor
public class RerouteBoardRefreshClaimService {

    private final JourneySessionRepository sessionRepository;
    private final JourneyRerouteProperties properties;
    private final Clock clock;

    /** @return 이번에 선점한 여정과 그 lease. 마무리할 때 같은 lease 를 돌려줘야 한다 */
    @Transactional
    public List<Claim> claim() {
        JourneyRerouteProperties.Board.Refresh refresh = properties.getBoard().getRefresh();
        Instant now = Instant.now(clock);
        Instant leaseUntil = now.plusSeconds(refresh.getLeaseSeconds());
        return sessionRepository.claimRerouteRefreshBatch(now, leaseUntil, refresh.getBatchSize())
                .stream()
                .map(id -> new Claim(id, leaseUntil))
                .toList();
    }

    /**
     * 다음 예정을 심고 선점을 푼다. 선점 중 신호가 왔으면 최소 간격 뒤, 아니면 기본 주기 뒤다.
     *
     * @return 내 선점이 아니어서(만료 후 다른 워커가 잡음) 쓰지 못했으면 false
     */
    @Transactional
    public boolean finish(Claim claim) {
        JourneyRerouteProperties.Board.Refresh refresh = properties.getBoard().getRefresh();
        Instant now = Instant.now(clock);
        return sessionRepository.finishRerouteRefresh(claim.journeyId(), claim.leaseUntil(), now,
                now.plusSeconds(refresh.getIntervalSeconds()),
                now.plusSeconds(refresh.getMinGapSeconds())) == 1;
    }

    public record Claim(Long journeyId, Instant leaseUntil) {
    }
}
