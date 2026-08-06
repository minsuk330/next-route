package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

/**
 * 환승 여유(slack)를 다섯 단계 상태로 옮긴다.
 *
 * <pre>
 * transferSlack = nextBusArrivalAt - userReadyAt
 * </pre>
 *
 * <p>여유가 음수면 이미 놓친 것이고, 클수록 편안하다. 필수 값이 없으면 그럴듯한 상태를 만들지
 * 않고 {@code UNKNOWN} 이다.
 *
 * <h2>떨림 방지</h2>
 * <p>임계값 하나만 쓰면 여유가 그 근처에서 흔들릴 때 상태가 오르내리고, 상태가 바뀔 때마다 알림이
 * 나가므로 사용자는 같은 이야기를 반복해 듣는다. 그래서 <b>나빠질 때는 곧바로, 좋아질 때는 더
 * 큰 여유를 요구</b>한다. 안전한 방향으로는 빠르게, 안심시키는 방향으로는 느리게 움직인다.
 *
 * <p>저장소를 보지 않고 아무것도 저장하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TransferRiskPolicy {

    private final JourneyTransferRiskProperties properties;

    /**
     * @param slackSeconds   환승 여유(초). null 이면 계산할 근거가 없다는 뜻이다
     * @param previousStatus 직전 상태. 없으면 null
     */
    public TransferStatus evaluate(Long slackSeconds, TransferStatus previousStatus) {
        if (slackSeconds == null) {
            return TransferStatus.UNKNOWN;
        }
        TransferStatus raw = classify(slackSeconds);
        if (previousStatus == null || previousStatus == TransferStatus.UNKNOWN) {
            return raw;
        }
        if (severity(raw) >= severity(previousStatus)) {
            // 같거나 더 나쁜 쪽으로는 유예 없이 바로 옮긴다. 늦게 알리면 사용자가 놓친다.
            return raw;
        }
        // 좋아지는 방향은 임계를 넘긴 것만으로 부족하다. 여유를 더 확보해야 인정한다.
        return slackSeconds >= lowerBound(raw) + properties.getHysteresisSeconds()
                ? raw
                : previousStatus;
    }

    /** 여유만 보고 매기는 상태. 떨림 방지를 적용하지 않은 값이다. */
    public TransferStatus classify(long slackSeconds) {
        if (slackSeconds < 0) {
            return TransferStatus.MISSED;
        }
        if (slackSeconds < properties.getCatchableSeconds()) {
            return TransferStatus.AT_RISK;
        }
        if (slackSeconds < properties.getComfortableSeconds()) {
            return TransferStatus.CATCHABLE;
        }
        return TransferStatus.COMFORTABLE;
    }

    /** 그 상태로 인정받는 최소 여유. */
    private long lowerBound(TransferStatus status) {
        return switch (status) {
            case COMFORTABLE -> properties.getComfortableSeconds();
            case CATCHABLE -> properties.getCatchableSeconds();
            case AT_RISK -> 0L;
            case MISSED, UNKNOWN -> Long.MIN_VALUE;
        };
    }

    /**
     * 나쁜 정도. 표는 {@link TransferStatus#severity()} 가 소유한다 — 알림 승격 판단(Phase 5)이
     * 같은 순위를 봐야 하는데, 표가 두 벌이면 한쪽만 고쳐도 아무 데서도 안 터진다.
     */
    private int severity(TransferStatus status) {
        return status.severity();
    }
}
