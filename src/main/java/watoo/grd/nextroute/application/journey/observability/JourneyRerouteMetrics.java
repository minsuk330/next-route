package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 재탐색 제안의 계측.
 *
 * <p><b>식별자를 태그로 쓰지 않는다</b>(REQUIREMENTS §6). journeyId·proposalId 를 태그에 넣으면
 * 시계열 수가 사용자 수만큼 늘어난다.
 *
 * <p>{@code ttl}(lazy 전이)과 {@code ttl-sweep}(배치)을 가르는 것이 이 클래스의 핵심이다.
 * <b>{@code ttl-sweep} 이 대부분이면 사용자가 제안을 보지 못하고 있다는 뜻</b>이라, 그때 봐야 할
 * 곳은 만료가 아니라 발송(⑥)이나 앱 폴링이다. 하나로 뭉치면 그 신호가 사라진다.
 */
@Component
@RequiredArgsConstructor
public class JourneyRerouteMetrics {

    public static final String PROPOSAL_CREATED = "journey.reroute.proposal.created";
    public static final String PROPOSAL_RESOLVED = "journey.reroute.proposal.resolved";
    public static final String REQUEST_REJECTED = "journey.reroute.request.rejected";
    public static final String REQUEST_EMPTY = "journey.reroute.request.empty";
    public static final String TRIGGER_FIRED = "journey.reroute.trigger.fired";
    public static final String TRIGGER_ADMITTED = "journey.reroute.trigger.admitted";
    public static final String CANDIDATE_EVALUATED = "journey.reroute.candidate.evaluated";
    public static final String CANDIDATE_FILTERED = "journey.reroute.candidate.filtered";

    /** 판정이 내려 준 사유를 그대로 태그에 쓰지 않기 위한 접기 값. */
    static final String UNKNOWN_REASON = "unknown";

    /** 사유 태그로 받아들이는 값. 목록 밖은 {@link #UNKNOWN_REASON} 으로 접는다. */
    private static final Set<String> KNOWN_REJECTION_REASONS = Set.of(
            "disabled", "not-active", "cooldown", "window", "no-gain", "already-active",
            "no-transfer", "past-transfer", "stale-context");

    /** 후보가 걸러진 사유. 마찬가지로 <b>목록 밖은 접는다</b>. */
    private static final Set<String> KNOWN_FILTER_REASONS = Set.of(
            "no-prediction", "unboardable", "missing-duration", "gain-below-threshold",
            "baseline-unboardable", "budget-exhausted");

    private final MeterRegistry registry;

    public void recordCreated(String kind) {
        registry.counter(PROPOSAL_CREATED, "kind", kind).increment();
    }

    /**
     * @param reason {@code ttl} · {@code ttl-sweep} · {@code route-changed} · {@code replaced} ·
     *               {@code journey-ended} · {@code accepted}
     */
    public void recordResolved(String reason, long count) {
        if (count <= 0) {
            return;
        }
        registry.counter(PROPOSAL_RESOLVED, "reason", reason).increment(count);
    }

    /**
     * 적격성 거절. 사유는 ①이 소유하므로 <b>모르는 값을 그대로 태그에 쓰지 않는다</b> — 구현이
     * 사유에 식별자를 섞는 순간 시계열이 무한히 늘어난다.
     */
    public void recordRejected(String reason) {
        registry.counter(REQUEST_REJECTED, "reason",
                KNOWN_REJECTION_REASONS.contains(reason) ? reason : UNKNOWN_REASON).increment();
    }

    /** 적격했지만 후보가 하나도 없었다. 실패가 아니라 "나은 것이 없다"는 정상 결과다. */
    public void recordEmpty() {
        registry.counter(REQUEST_EMPTY).increment();
    }

    /**
     * 방아쇠가 켜졌다. cooldown 을 <b>지나기 전</b>의 수다.
     *
     * @param reason {@code degraded} · {@code target-passed}. enum 에서 오므로 값 종류가 둘이다
     */
    public void recordTriggerFired(String reason) {
        registry.counter(TRIGGER_FIRED, "reason", reason).increment();
    }

    /**
     * 방아쇠가 cooldown 을 통과했다.
     *
     * <p><b>{@link #TRIGGER_FIRED} 와의 차이가 cooldown 이 깎는 양이다.</b> 켜진 수만 두면 ③이
     * 켜졌을 때 외부 검색이 몇 번 나갈지 예측할 수 없고, 통과한 수만 두면 방아쇠 조건이 너무
     * 좁은 것인지 cooldown 이 먹는 것인지 가를 수 없다.
     */
    public void recordTriggerAdmitted(String reason) {
        registry.counter(TRIGGER_ADMITTED, "reason", reason).increment();
    }

    /**
     * 한 요청에서 예측을 <b>몇 번 물었는지</b>(기준선 포함).
     *
     * <p>요금을 추정하는 값이다. 제안 건수만으로는 못 센다 — 후보를 넷 평가하고 전부 걸러 빈손을
     * 낸 요청도 외부 호출은 네 번 나갔다.
     */
    public void recordCandidateEvaluated(int count) {
        if (count <= 0) {
            return;
        }
        registry.counter(CANDIDATE_EVALUATED).increment(count);
    }

    /**
     * 평가는 했지만 후보로 올리지 못한 이유.
     *
     * <p>{@link #REQUEST_EMPTY} 만 보면 "왜 빈손인지"를 못 가른다 — 개선폭이 모자란 것과 예측이
     * 아예 안 온 것은 손댈 곳이 다르다.
     *
     * @param reason {@code no-prediction} · {@code unboardable} · {@code missing-duration} ·
     *               {@code gain-below-threshold} · {@code baseline-unboardable} ·
     *               {@code budget-exhausted}
     */
    public void recordCandidateFiltered(String reason) {
        registry.counter(CANDIDATE_FILTERED, "reason",
                KNOWN_FILTER_REASONS.contains(reason) ? reason : UNKNOWN_REASON).increment();
    }
}
