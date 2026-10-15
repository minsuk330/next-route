package watoo.grd.nextroute.application.journey.reeval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;

/**
 * 수집 한 번에 깨울 여정 수 상한(#427 처방 2차).
 *
 * <p><b>평가기가 다음 수집 전까지 처리할 수 있는 만큼만 깨운다.</b> 고정 500 은 평가기가 남아도 500 에서 멈추고,
 * 평가기가 모자라도 500 을 얹는다. 대신
 * <pre>상한 = clamp(최근 처리량(건/초) × 다음 수집까지(초) × 여유 배수, 바닥, 천장)</pre>
 *
 * <h2>처리량은 인스턴스 전체</h2>
 * 평가기는 모든 worker 인스턴스에서 돌고({@code SKIP LOCKED} 로 같은 행을 나눠 가짐), 감시자는 리더 한 대에서
 * 돈다. 리더 자기 워커만 세면 인스턴스가 늘어도 상한이 그대로다. 그래서 공유 상태인 DB 에서 센다 — 최근 창 안에
 * {@code last_evaluated_at} 을 쓴 진행 중 여정 수 ÷ 창. 몇 초 캐시하므로 수집·tick 마다 세지 않는다.
 *
 * <h2>왜 배수가 1 보다 큰가</h2>
 * 잰 값은 "할 수 있는 만큼"이 아니라 "실제로 한 만큼"이다. 일을 적게 주면 적게 하고, 그걸 그대로 상한으로 쓰면
 * 상한이 내려갈 일만 남는다. 1.5 배를 얹어 평가기 쪽으로 한 칸씩 더듬어 올라가고, 넘치면 기존 포화 차단(shed —
 * 오래 밀린 여정이 기준 이상이면 주의·여유를 미룸)이 막는다. 넘침(overflow)·차단(shed)·실패 모두 기준선을
 * 남겨 다음 수집에 다시 나오므로 이벤트는 잃지 않는다.
 *
 * <p>셀 수 없으면(DB 실패) 바닥값 — 예전 고정값과 같은 동작으로 내려간다.
 *
 * <p>상태(캐시)는 이 인스턴스 메모리에만 있다. 리더가 바뀌면 새 리더가 처음 한 번 다시 센다 — 값의 원천이 DB 라
 * 잃는 것이 없다.
 */
@Slf4j
public class ReevaluationWakeCap {

    private final JourneySessionRepository sessionRepository;
    private final ReevaluationProperties properties;
    private final JourneyReevalMetrics metrics;
    private final Clock clock;

    private Instant measuredAt;
    private double cachedRate = -1;

    public ReevaluationWakeCap(JourneySessionRepository sessionRepository, ReevaluationProperties properties,
                               JourneyReevalMetrics metrics, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** 이번 요청의 상한. */
    public synchronized int current() {
        if (properties.getWakeCapMode() == ReevaluationProperties.WakeCapMode.FIXED) {
            int fixed = Math.max(0, properties.getMaxJourneysPerRequest());
            metrics.recordWakeCap(fixed, -1);
            return fixed;
        }
        double rate = rate();
        int cap = compute(rate, properties.getWakeHorizonSeconds(), properties.getWakeHeadroom(),
                properties.getWakeCapMin(), properties.getWakeCapMax());
        metrics.recordWakeCap(cap, rate);
        return cap;
    }

    /**
     * 처리량 → 상한. 순수 함수(계약 시험 대상).
     *
     * @param ratePerSecond 최근 처리량. 음수 = 모름(셀 수 없음) → 바닥
     * @return {@code [min, max]} 안의 값. min &gt; max 로 잘못 넣으면 max 가 이긴다(천장이 안전 장치라서)
     */
    static int compute(double ratePerSecond, long horizonSeconds, double headroom, int min, int max) {
        int floor = Math.max(0, min);
        int ceiling = Math.max(0, max);
        if (ratePerSecond < 0 || Double.isNaN(ratePerSecond) || horizonSeconds <= 0) {
            return Math.min(floor, ceiling);
        }
        double raw = ratePerSecond * horizonSeconds * Math.max(headroom, 0);
        long wanted = (long) Math.ceil(Math.min(raw, Integer.MAX_VALUE));
        return (int) Math.min(ceiling, Math.max(floor, wanted));
    }

    /** @return 최근 처리량(건/초), 셀 수 없으면 -1 */
    private double rate() {
        Instant now = Instant.now(clock);
        if (measuredAt != null && cachedRate >= 0
                && now.isBefore(measuredAt.plusMillis(Math.max(0, properties.getWakeRateCacheMs())))) {
            return cachedRate;
        }
        long window = Math.max(1, properties.getWakeRateWindowSeconds());
        try {
            long done = sessionRepository.countEvaluatedSince(now.minusSeconds(window));
            cachedRate = (double) done / window;
            measuredAt = now;
            return cachedRate;
        } catch (DataAccessException e) {
            log.warn("[Reevaluation] 평가기 처리량을 세지 못함 — 깨우기 상한을 바닥값으로: {}", e.getMessage());
            measuredAt = null;
            return -1;
        }
    }
}
