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
    public static final String REMAINING_SEARCH = "journey.reroute.remaining.search";
    public static final String REMAINING_PATHS = "journey.reroute.remaining.paths";
    public static final String ORIGIN_WINDOW = "journey.reroute.origin.window";

    /** 판정이 내려 준 사유를 그대로 태그에 쓰지 않기 위한 접기 값. */
    static final String UNKNOWN_REASON = "unknown";

    /** 사유 태그로 받아들이는 값. 목록 밖은 {@link #UNKNOWN_REASON} 으로 접는다. */
    private static final Set<String> KNOWN_REJECTION_REASONS = Set.of(
            "disabled", "not-active", "cooldown", "window", "no-gain", "already-active",
            "no-transfer", "past-transfer", "stale-context",
            // stage5 2/2: 원점을 못 풀어 저장을 포기했다. 주소 없는 제안은 만들지 않는다.
            "origin-unresolved");

    /** 후보가 걸러진 사유. 마찬가지로 <b>목록 밖은 접는다</b>. */
    private static final Set<String> KNOWN_FILTER_REASONS = Set.of(
            "no-prediction", "unboardable", "missing-duration", "gain-below-threshold",
            "baseline-unboardable", "budget-exhausted",
            // ③ 공정성 게이트(PR4c): 기준선·후보 중 한쪽이라도 노선별 시간표 소요(OTP)를 못 얻어
            // 실제 제안으로 승격 못 하고 shadow 로 빠진 후보.
            "ride-duration-incomparable",
            // ④(남은 구간 재검색)가 쓰는 사유들. 목록에 없으면 조용히 unknown 으로 접혀
            // "왜 빈손인지"를 못 가른다.
            "same-as-current", "no-direction", "unresolved-route", "more-transfers",
            // overlay 체인(PR-1): 첫 대기가 아니라 <b>그 뒤 대기</b>를 못 풀어 버린 후보.
            // no-prediction 과 묶으면 "체인을 켜서 제외가 늘었다"를 못 읽는다.
            "boarding-unresolved",
            // 원점 창(PR-2): 내려서 곧바로 뛰어 타야 하는 후보. 방금 놓칠 뻔한 환승을 한 번 더
            // 겪게 하지 않으려고 뺀다. gain-below-threshold 와 묶으면 "여유가 없어 뺐다"가 안 보인다.
            "miss-risk");

    /**
     * 원점 창이 정류장 하나를 어떻게 처리했는지(PR-2).
     *
     * <p><b>후보가 아니라 자리의 사유다.</b> {@link #CANDIDATE_FILTERED} 는 "경로를 버렸다"이고
     * 이쪽은 "그 정류장에서는 검색조차 안 했다"인데, 둘을 뭉치면 실시간 커버리지 문제(원점이 안
     * 열린다)와 경로 품질 문제(후보가 안 남는다)를 구분할 수 없다.
     */
    private static final Set<String> KNOWN_ORIGIN_OUTCOMES = Set.of(
            "admitted", "no-progress", "stale-progress", "ambiguous-stop", "position-out-of-range",
            "no-realtime", "below-lead-time", "arrival-blocked");

    /** 경로 검색 한 번의 결말. <b>{@code cache-hit} 비율이 ④의 요금을 결정한다.</b> */
    private static final Set<String> KNOWN_SEARCH_OUTCOMES = Set.of(
            "cache-hit", "single-flight-join", "called", "failed");

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
     *               {@code budget-exhausted} · {@code same-as-current} · {@code no-direction} ·
     *               {@code unresolved-route} · {@code more-transfers} ·
     *               {@code boarding-unresolved}(overlay 체인의 두 번째 이후 대기)
     */
    public void recordCandidateFiltered(String reason) {
        registry.counter(CANDIDATE_FILTERED, "reason",
                KNOWN_FILTER_REASONS.contains(reason) ? reason : UNKNOWN_REASON).increment();
    }

    /**
     * 남은 구간 경로 검색이 어떻게 끝났는지(④).
     *
     * <p><b>{@code cache-hit} 비율이 이 칸의 요금을 결정한다.</b> {@code called} 하나만 유료 호출이고
     * {@code single-flight-join} 은 진행 중인 호출에 합류한 것이라 돈이 들지 않는다 — 둘을 뭉치면
     * 중복 억제가 실제로 먹는지 알 수 없다.
     *
     * @param outcome {@code cache-hit} · {@code single-flight-join} · {@code called} · {@code failed}
     */
    public void recordRemainingSearch(String outcome) {
        registry.counter(REMAINING_SEARCH, "outcome",
                KNOWN_SEARCH_OUTCOMES.contains(outcome) ? outcome : UNKNOWN_REASON).increment();
    }

    /**
     * 검색이 <b>준</b> 경로 수와 그중 <b>평가한</b> 수.
     *
     * <p>둘을 함께 세야 상한(max-paths)이 실제로 무엇을 자르고 있는지 보인다. 평가 수만 두면
     * "경로가 원래 하나였다"와 "다섯 개 중 둘만 봤다"가 같은 숫자가 된다.
     */
    /**
     * 주행 중 하차 후보 자리 하나의 결말.
     *
     * <p>{@code no-realtime} 비율이 이 기능의 커버리지다 — 실시간이 안 잡히는 정류장은 시간표로
     * 폴백하지 않고 그냥 뺀다(사용자 결정). 그 값이 높으면 창이 비어 제안 자체가 안 나간다.
     *
     * @param outcome {@code admitted} · {@code no-progress} · {@code stale-progress} ·
     *                {@code ambiguous-stop} · {@code position-out-of-range} · {@code no-realtime} ·
     *                {@code below-lead-time} · {@code arrival-blocked}
     */
    public void recordOriginWindow(String outcome) {
        registry.counter(ORIGIN_WINDOW, "outcome",
                KNOWN_ORIGIN_OUTCOMES.contains(outcome) ? outcome : UNKNOWN_REASON).increment();
    }

    public void recordRemainingPaths(int given, int evaluated) {
        if (given > 0) {
            registry.counter(REMAINING_PATHS, "stage", "given").increment(given);
        }
        if (evaluated > 0) {
            registry.counter(REMAINING_PATHS, "stage", "evaluated").increment(evaluated);
        }
    }
}
