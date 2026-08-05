package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 다음 평가까지 얼마나 기다릴지 정한다.
 *
 * <p>급한 여정은 자주, 한가한 여정은 뜸하게 본다. 모두에게 같은 주기를 쓰면 환승 2분 남은 사용자는
 * 버스가 늦어진 것을 늦게 알고, 40분 뒤에 갈아탈 사용자에게는 아무것도 안 변하는데 외부 API 를 쓴다.
 *
 * <h2>규칙 (위에서부터 먼저 걸리는 것이 이긴다)</h2>
 *
 * <ol>
 *   <li>provider 가 막혔다 → 기본 간격 × 4</li>
 *   <li>남은 환승에 위험·놓침이 있다 → 최소 간격</li>
 *   <li>가장 가까운 환승이 임박 기준 안이다 → 최소 간격</li>
 *   <li>한가함 기준 안이다 → 기본 간격</li>
 *   <li>그보다 멀거나 남은 환승이 없다 → 한가할 때의 간격</li>
 *   <li>위 값이 정해진 뒤, 직전 평가와 아무것도 안 바뀌었다 → 직전 간격 × 배수</li>
 * </ol>
 *
 * <p>마지막에 최소·최대 사이로 자른다.
 *
 * <p><b>2·3 에 걸린 급한 상태에서는 6 을 적용하지 않는다.</b> 값이 그대로라는 것과 상황이 급하지
 * 않다는 것은 다른 말이다. 위험한 상태에서 뜸해지면 알림이 늦는다.
 *
 * <p>DB 도 시계도 외부 호출도 쓰지 않는다. 규칙 표를 표 그대로 시험할 수 있어야 하기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class JourneyEvaluationIntervalPolicy {

    /** 막혔을 때 다음 평가를 몇 배로 늦출지. 막힌 문을 30초마다 두드리지 않는다. */
    private static final int BLOCKED_BACKOFF_MULTIPLIER = 4;

    private final JourneyEvaluatorProperties properties;

    /**
     * @param assessments      이번 평가가 낸 판정. 비어 있으면 환승 없는 여정이다
     * @param currentLegIndex  지금 진행 중인 단계. 이미 지나온 환승을 빼는 데 쓴다
     * @param previousInterval 직전 평가와 이번 평가 사이의 간격. 모르면 null
     * @param changed          앱에 보일 값이 달라졌는지
     */
    public Decision decide(List<TransferRiskAssessment> assessments, int currentLegIndex,
                           Instant now, Duration previousInterval,
                           boolean changed, boolean upstreamBlocked) {
        if (upstreamBlocked) {
            return new Decision(
                    clamp(properties.interval().multipliedBy(BLOCKED_BACKOFF_MULTIPLIER)),
                    Reason.BLOCKED);
        }

        List<TransferRiskAssessment> upcoming = assessments.stream()
                .filter(assessment -> assessment.isUpcoming(currentLegIndex))
                .toList();

        Decision base = baseDecision(upcoming, now);
        if (base.urgent() || changed) {
            return base;
        }
        Duration grown = backoff(base.interval(), previousInterval);
        // 실제로 늘어났을 때만 근거를 BACKOFF 로 남긴다. 안 늘었는데 그렇게 적으면 지표가
        // "값이 안 바뀌어서 뜸해지고 있다"고 잘못 말한다.
        return grown.compareTo(base.interval()) > 0 ? new Decision(grown, Reason.BACKOFF) : base;
    }

    /** 급한 정도만 보고 정한 간격. 변화 여부는 아직 안 본다. */
    private Decision baseDecision(List<TransferRiskAssessment> upcoming, Instant now) {
        if (upcoming.stream().anyMatch(this::risky)) {
            return new Decision(clamp(properties.minInterval()), Reason.AT_RISK);
        }

        Duration nearest = nearestTransferIn(upcoming, now);
        if (nearest == null) {
            // 남은 환승이 없거나 준비 시각을 모른다. 서둘러 볼 이유가 없다.
            return new Decision(clamp(properties.idleInterval()), Reason.IDLE);
        }
        if (nearest.compareTo(properties.nearTransfer()) <= 0) {
            return new Decision(clamp(properties.minInterval()), Reason.NEAR);
        }
        if (nearest.compareTo(properties.farTransfer()) <= 0) {
            return new Decision(clamp(properties.interval()), Reason.NORMAL);
        }
        return new Decision(clamp(properties.idleInterval()), Reason.IDLE);
    }

    /**
     * 값이 그대로일 때 간격을 늘린다.
     *
     * <p>직전 간격을 모르면(첫 평가·환승 없는 여정) 규칙이 정한 값을 그대로 쓴다. 알더라도 먼저
     * 최대치로 자른 뒤 곱한다 — 워커가 오래 멈췄다 돌아오면 직전 간격이 몇 분씩 잡히는데, 그것을
     * 그대로 곱하면 한 번에 상한까지 튄다.
     */
    private Duration backoff(Duration base, Duration previousInterval) {
        if (previousInterval == null || previousInterval.isNegative() || previousInterval.isZero()) {
            return base;
        }
        Duration grown = clamp(previousInterval)
                .multipliedBy(properties.getUnchangedBackoffMultiplier());
        return clamp(grown.compareTo(base) < 0 ? base : grown);
    }

    /** 아직 남은 환승 중 가장 가까운 것까지 남은 시간. 없으면 null. */
    private Duration nearestTransferIn(List<TransferRiskAssessment> upcoming, Instant now) {
        return upcoming.stream()
                .map(TransferRiskAssessment::userReadyAt)
                .filter(java.util.Objects::nonNull)
                .map(readyAt -> Duration.between(now, readyAt))
                .min(Duration::compareTo)
                .orElse(null);
    }

    private boolean risky(TransferRiskAssessment assessment) {
        return assessment.status() == TransferStatus.AT_RISK
                || assessment.status() == TransferStatus.MISSED;
    }

    private Duration clamp(Duration interval) {
        if (interval.compareTo(properties.minInterval()) < 0) {
            return properties.minInterval();
        }
        if (interval.compareTo(properties.maxInterval()) > 0) {
            return properties.maxInterval();
        }
        return interval;
    }

    /**
     * 정해진 간격과 그 근거.
     *
     * @param reason 지표 tag 로 쓴다. 고정 집합이라 시계열이 늘어나지 않는다
     */
    public record Decision(Duration interval, Reason reason) {

        /** 급해서 정한 간격인지. 이 경우 값이 그대로여도 간격을 늘리지 않는다. */
        boolean urgent() {
            return reason == Reason.AT_RISK || reason == Reason.NEAR;
        }
    }

    public enum Reason {
        /** provider 가 막혀 물어볼 수 없었다. */
        BLOCKED,
        /** 놓칠 위험이 있는 환승이 있다. */
        AT_RISK,
        /** 환승이 곧이다. */
        NEAR,
        /** 환승까지 적당히 남았다. */
        NORMAL,
        /** 환승이 멀거나 남은 환승이 없다. */
        IDLE,
        /** 확인해도 값이 그대로라 간격을 늘렸다. */
        BACKOFF
    }
}
