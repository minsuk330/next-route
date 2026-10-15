package watoo.grd.nextroute.application.journey.reeval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository.ReevaluationRow;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DB 행을 고쳐 재평가를 요청한다.
 *
 * <p>규칙:
 * <ol>
 *   <li>한 여정이 여러 등급에 있으면 가장 급한 등급 한 번만 친다.</li>
 *   <li>급한 등급부터 상한까지 담고, 넘친 여정은 미룬다(overflow). 상한은 기본이 평가기 처리량으로 계산한 값이다
 *       ({@link ReevaluationWakeCap}, 고정 {@code max-journeys-per-request} 는 {@code wake-cap-mode=fixed}).</li>
 *   <li>평가기가 포화됐으면(예정보다 오래 밀린 여정이 기준 이상) 주의·여유 등급은 미룬다. 이미 일이
 *       밀린 평가기에 더 얹으면 정작 급한 여정이 늦어진다. 긴급은 그래도 보낸다.</li>
 *   <li>등급마다 한 트랜잭션. 한 등급이 실패해도 다른 등급은 반영된다.</li>
 * </ol>
 *
 * <p>미룬 여정은 결과에 담아 돌려준다. 부르는 쪽이 감지 기준선을 갱신하지 않고 다음 주기에 다시
 * 보내면 이벤트를 잃지 않는다.
 *
 * <p>시각은 앱 시계 하나를 쓴다(선점·완료와 같은 시계). DB {@code now()} 와 섞으면 "처리 중인가"
 * 비교가 두 시계 차이만큼 틀어진다.
 */
@Slf4j
@Component
public class JdbcReevaluationTrigger implements ReevaluationTrigger {

    private static final List<ReevaluationTier> URGENT_FIRST =
            List.of(ReevaluationTier.URGENT, ReevaluationTier.CAUTION, ReevaluationTier.RELAXED);

    private final JourneySessionRepository sessionRepository;
    private final ReevaluationProperties properties;
    private final JourneyReevalMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ReevaluationWakeCap wakeCap;

    public JdbcReevaluationTrigger(JourneySessionRepository sessionRepository,
                                   ReevaluationProperties properties,
                                   JourneyReevalMetrics metrics,
                                   PlatformTransactionManager transactionManager,
                                   Clock clock) {
        this.sessionRepository = sessionRepository;
        this.properties = properties;
        this.metrics = metrics;
        // 부르는 쪽 트랜잭션에 합류하지 않는다. 합류하면 등급별 분리가 깨지고, 여기서 잡은 실패가
        // 바깥 트랜잭션을 rollback-only 로 만들어 "미뤘다"고 보고한 뒤에 바깥이 터진다.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.wakeCap = new ReevaluationWakeCap(sessionRepository, properties, metrics, clock);
    }

    @Override
    public TriggerResult request(Map<ReevaluationTier, Set<Long>> journeysByTier) {
        Map<ReevaluationTier, Set<Long>> byTier = dedupeToMostUrgent(journeysByTier);
        Set<Long> all = new HashSet<>();
        byTier.values().forEach(all::addAll);
        if (all.isEmpty()) {
            return TriggerResult.nothing();
        }
        if (!properties.isEnabled()) {
            return new TriggerResult(Set.of(), all);
        }

        Instant now = Instant.now(clock);
        boolean saturated = saturated(now);
        int room = wakeCap.current();

        Set<Long> delivered = new HashSet<>();
        Set<Long> deferred = new HashSet<>();
        for (ReevaluationTier tier : URGENT_FIRST) {
            Set<Long> ids = byTier.getOrDefault(tier, Set.of());
            if (ids.isEmpty()) {
                continue;
            }
            if (saturated && tier != ReevaluationTier.URGENT) {
                deferred.addAll(ids);
                metrics.recordDeferred("shed", ids.size());
                continue;
            }
            List<Long> take = new ArrayList<>(ids);
            if (take.size() > room) {
                // 넘칠 때 늘 작은 id 부터 담으면 넘침이 이어지는 동안 큰 id 는 계속 밀린다. 섞어서 고른다
                // (잠금 순서는 SQL 이 id 순으로 따로 정한다).
                Collections.shuffle(take);
                List<Long> over = take.subList(room, take.size());
                deferred.addAll(over);
                metrics.recordDeferred("overflow", over.size());
                take = new ArrayList<>(take.subList(0, room));
            }
            if (take.isEmpty()) {
                continue;
            }
            room -= take.size();
            if (apply(tier, take, now)) {
                delivered.addAll(take);
            } else {
                deferred.addAll(take);
            }
        }
        return new TriggerResult(delivered, deferred);
    }

    /** @return 반영했으면 true. DB 실패면 false — 그 여정들은 미룬다 */
    private boolean apply(ReevaluationTier tier, List<Long> ids, Instant now) {
        if (properties.isShadow()) {
            metrics.recordShadow(tier, ids.size());
            return true;
        }
        try {
            List<ReevaluationRow> rows = transactionTemplate.execute(status ->
                    sessionRepository.requestReevaluation(ids, now, properties.minGapSeconds(tier)));
            int dirty = 0;
            for (ReevaluationRow row : rows == null ? List.<ReevaluationRow>of() : rows) {
                if (Boolean.TRUE.equals(row.getClaimed())) {
                    dirty++;
                }
            }
            int touched = rows == null ? 0 : rows.size();
            metrics.recordDirtyMarked(tier, dirty);
            metrics.recordPulled(tier, touched - dirty);
            return true;
        } catch (DataAccessException | TransactionException e) {
            // 커밋 단계 실패(연결 끊김 등)는 DataAccessException 이 아니다. 같이 잡아야 앞 등급의
            // 반영 결과를 잃지 않는다.
            metrics.recordDeferred("failed", ids.size());
            log.warn("[Reevaluation] {} 여정 {}건 반영 실패 — 다음 주기에 다시 보낸다: {}",
                    tier, ids.size(), e.getMessage());
            return false;
        }
    }

    private boolean saturated(Instant now) {
        long threshold = properties.getShedBacklogThreshold();
        if (threshold <= 0) {
            return false;
        }
        try {
            return sessionRepository.countOverdueSince(now.minus(properties.shedBacklogAge())) >= threshold;
        } catch (DataAccessException e) {
            // 셀 수 없으면 포화로 보지 않는다. 긴급만 보내다 데이터를 놓치는 것보다 낫다.
            log.warn("[Reevaluation] 적체를 세지 못함: {}", e.getMessage());
            return false;
        }
    }

    private static Map<ReevaluationTier, Set<Long>> dedupeToMostUrgent(Map<ReevaluationTier, Set<Long>> in) {
        Map<ReevaluationTier, Set<Long>> out = new EnumMap<>(ReevaluationTier.class);
        Set<Long> seen = new HashSet<>();
        for (ReevaluationTier tier : URGENT_FIRST) {
            Set<Long> ids = in == null ? null : in.get(tier);
            if (ids == null) {
                continue;
            }
            Set<Long> mine = new LinkedHashSet<>();
            for (Long id : ids) {
                if (id != null && seen.add(id)) {
                    mine.add(id);
                }
            }
            out.put(tier, mine);
        }
        return out;
    }
}
