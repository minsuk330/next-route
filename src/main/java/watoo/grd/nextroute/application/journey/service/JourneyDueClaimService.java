package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 평가할 때가 된 여정을 선점한다.
 *
 * <p>선점은 다음 평가 시각을 미래로 미는 것이다. 그 순간부터 다른 워커에게는 "아직 때가 아닌
 * 여정"으로 보인다. 별도의 "처리 중" 칼럼을 두지 않는 이유는 워커가 죽었을 때의 회수까지 같은
 * 규칙으로 처리되기 때문이다 — 밀어 둔 시각이 지나면 자연히 다시 잡힌다.
 *
 * <p>선점만 하고 계산은 하지 않는다. 트랜잭션 안에서 외부 API 를 기다리면 DB 커넥션을 그만큼
 * 붙잡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyDueClaimService {

    private final JourneySessionRepository sessionRepository;
    private final JourneyEvaluatorProperties properties;
    private final Clock clock;

    /**
     * 한 배치를 선점한다.
     *
     * <p>돌려주는 {@code leaseUntil} 은 여정 행에 실제로 심은 값이다. 평가를 마친 워커가 저장 전에
     * 그 값으로 소유권을 확인하므로, 여기서 계산한 값과 저장된 값이 <b>정확히 같아야</b> 한다.
     */
    @Transactional
    public List<JourneyDueClaim> claimDue() {
        Instant now = Instant.now(clock);
        Instant leaseUntil = now.plus(properties.lease());

        List<JourneySessionRepository.DueClaimRow> claimed = sessionRepository.claimDueBatch(
                now, leaseUntil, properties.getBatchSize(),
                properties.getClaimOrder() == JourneyEvaluatorProperties.ClaimOrder.URGENT_FIRST,
                now.minus(properties.starvationGuard()));
        if (claimed.isEmpty()) {
            return List.of();
        }
        log.debug("[JourneyDueEvaluator] {}건 선점 — leaseUntil={}", claimed.size(), leaseUntil);
        return claimed.stream()
                .map(row -> new JourneyDueClaim(row.getId(), leaseUntil, row.getDueAt()))
                .toList();
    }
}
