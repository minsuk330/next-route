package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 새로 찾은 후보를 보드에 합친다(#264, reroute-periodic-board). <b>DB·시계·외부 호출이 없다.</b>
 *
 * <p>보드는 1분 안팎마다 다시 찾지만 말풍선은 깜빡이면 안 된다. 그래서 "이번 결과로 갈아끼우기"가
 * 아니라 자리마다 아래 규칙으로 고친다(#264 정리본 v1 §5, 논의 결과 8).
 *
 * <ol>
 *   <li><b>지났거나 곧 닿는 자리</b>(도착까지 {@code leadTime} 미만)는 지운다. 숨김 기록도 함께</li>
 *   <li><b>이번에 평가하지 못한 자리</b>(장애·할당량·예산)는 그대로 둔다. 일시 장애가 말풍선을
 *       지우면 사용자는 "더 빠른 길이 사라졌다"로 읽는다</li>
 *   <li>보이는 자리
 *     <ul>
 *       <li>이득이 문턱 아래 → 바로 숨긴다</li>
 *       <li>같은 경로(지문 같음) → 숫자와 경로를 새 값으로, <b>candidateId 는 유지</b>. 사용자가
 *           보던 것을 그대로 수락할 수 있어야 한다</li>
 *       <li>다른 경로 → {@code replaceMargin} 이상 빠를 때만 교체(새 candidateId). 아니면 기존을
 *           그대로 둔다 — 말풍선 내용과 수락 대상이 어긋나지 않게(결정 1-가)</li>
 *     </ul>
 *   </li>
 *   <li>숨긴 자리 → 문턱 + {@code reshowMargin} 을 넘어야 다시 보인다(새 candidateId)</li>
 *   <li>새 자리 → 문턱을 넘으면 보인다. 못 넘으면 기억하지 않는다 — 처음 뜰 때는 원래 문턱이다</li>
 * </ol>
 *
 * <p><b>입력에 언급되지 않은 기존 자리</b>는 평가하지 못한 자리로 친다(유지). 지운다고 정하는 것은
 * 부르는 쪽이 {@code passed} 로 명시해야 한다 — 기점 목록이 잠깐 비었다고 말풍선이 사라지면 안 된다.
 */
public class RerouteBoardMerger {

    private final Thresholds thresholds;
    private final Supplier<String> candidateIds;

    public RerouteBoardMerger(Thresholds thresholds, Supplier<String> candidateIds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        this.candidateIds = Objects.requireNonNull(candidateIds, "candidateIds");
    }

    public Result merge(List<RerouteBoardSlot> current, Input input, Instant now) {
        Map<String, RerouteBoardSlot> byKey = new LinkedHashMap<>();
        for (RerouteBoardSlot slot : current) {
            byKey.put(slot.slotKey(), slot);
        }
        Map<Transition, Integer> transitions = new EnumMap<>(Transition.class);
        List<RerouteBoardSlot> merged = new ArrayList<>();

        for (RerouteBoardSlot slot : byKey.values()) {
            Evaluated fresh = input.evaluated().get(slot.slotKey());
            Instant alightAt = fresh != null && fresh.alightAt() != null ? fresh.alightAt() : slot.alightAt();
            if (input.passed().contains(slot.slotKey()) || tooClose(alightAt, now)
                    || noLongerOffered(slot, input)) {
                count(transitions, Transition.REMOVED);
                continue;
            }
            if (fresh == null) {
                count(transitions, Transition.KEPT);
                merged.add(slot);
                continue;
            }
            RerouteBoardSlot next = slot.isVisible()
                    ? mergeVisible(slot, fresh, now, transitions)
                    : mergeHidden(slot, fresh, now, transitions);
            merged.add(next);
        }

        for (Evaluated fresh : input.evaluated().values()) {
            if (byKey.containsKey(fresh.slotKey()) || input.passed().contains(fresh.slotKey())
                    || tooClose(fresh.alightAt(), now) || !viable(fresh)
                    || fresh.gainSec() < thresholdOf(fresh)) {
                continue;
            }
            count(transitions, Transition.ADDED);
            merged.add(show(fresh, now));
        }
        return new Result(List.copyOf(merged), Map.copyOf(transitions));
    }

    private RerouteBoardSlot mergeVisible(RerouteBoardSlot slot, Evaluated fresh, Instant now,
                                          Map<Transition, Integer> transitions) {
        if (!viable(fresh) || fresh.gainSec() < thresholdOf(fresh)) {
            count(transitions, Transition.HIDDEN);
            return new RerouteBoardSlot(slot.slotKey(), fresh.originKind(),
                    RerouteBoardSlot.State.HIDDEN, null, null, gainOrNull(fresh),
                    null, now, now, fresh.alightAt());
        }
        if (Objects.equals(slot.routeFingerprint(), fresh.routeFingerprint())) {
            count(transitions, Transition.UPDATED);
            return new RerouteBoardSlot(slot.slotKey(), fresh.originKind(),
                    RerouteBoardSlot.State.VISIBLE,
                    fresh.candidate().withCandidateId(slot.candidate().candidateId()),
                    fresh.routeFingerprint(), fresh.gainSec(), slot.firstShownAt(), null,
                    now, fresh.alightAt());
        }
        int previousGain = slot.lastGainSec() == null ? 0 : slot.lastGainSec();
        if (fresh.gainSec() >= previousGain + thresholds.replaceMarginSec()) {
            count(transitions, Transition.REPLACED);
            return show(fresh, now);
        }
        // 다른 경로인데 충분히 낫지 않다. 기존 경로를 숫자까지 그대로 둔다 — 새 경로의 숫자를 옛
        // 경로에 붙이면 사용자는 B 를 보고 A 를 수락한다.
        count(transitions, Transition.KEPT);
        return slot;
    }

    private RerouteBoardSlot mergeHidden(RerouteBoardSlot slot, Evaluated fresh, Instant now,
                                         Map<Transition, Integer> transitions) {
        if (viable(fresh) && fresh.gainSec() >= thresholdOf(fresh) + thresholds.reshowMarginSec()) {
            count(transitions, Transition.RESHOWN);
            return show(fresh, now);
        }
        count(transitions, Transition.KEPT);
        return new RerouteBoardSlot(slot.slotKey(), fresh.originKind(), RerouteBoardSlot.State.HIDDEN,
                null, null, gainOrNull(fresh), null, slot.hiddenAt(), now, fresh.alightAt());
    }

    private RerouteBoardSlot show(Evaluated fresh, Instant now) {
        return new RerouteBoardSlot(fresh.slotKey(), fresh.originKind(), RerouteBoardSlot.State.VISIBLE,
                fresh.candidate().withCandidateId(candidateIds.get()), fresh.routeFingerprint(),
                fresh.gainSec(), now, null, now, fresh.alightAt());
    }

    /**
     * 이 후보가 말풍선이 되려면 넘어야 하는 이득(초).
     *
     * <p>근거를 모르면 보수적으로 ML 쪽 값을 쓴다. 근거 집계는 PR-3 이 채운다 — 그 전에는 모든
     * 후보가 180초 문턱을 탄다.
     */
    int thresholdOf(Evaluated fresh) {
        int base = fresh.realtimeOnly() ? thresholds.realtimeSec() : thresholds.mixedSec();
        return base + thresholds.perExtraWaitSec() * Math.max(0, fresh.extraWaits());
    }

    /**
     * 단계형 자리(현재 위치·대기 정류장·계획 하차점)가 이번 회차 기점에 없으면 그 단계는 끝났다. 버스 중간
     * 정류장은 기점 상한·실시간 장애로 잠깐 빠질 수 있으니 시간(여유)으로만 지운다.
     */
    private static boolean noLongerOffered(RerouteBoardSlot slot, Input input) {
        return input.offered() != null
                && !input.offered().contains(slot.slotKey())
                && !input.evaluated().containsKey(slot.slotKey())
                && slot.originKind() != RerouteOriginKind.RIDE_INTERIOR;
    }

    private boolean tooClose(Instant alightAt, Instant now) {
        return alightAt != null
                && Duration.between(now, alightAt).compareTo(thresholds.leadTime()) < 0;
    }

    /** 쓸 경로가 없으면 이득이라는 숫자도 없다. 0 으로 적으면 "0초 빠르다"로 읽힌다. */
    private static Integer gainOrNull(Evaluated fresh) {
        return viable(fresh) ? fresh.gainSec() : null;
    }

    private static boolean viable(Evaluated fresh) {
        return fresh.candidate() != null;
    }

    private static void count(Map<Transition, Integer> transitions, Transition transition) {
        transitions.merge(transition, 1, Integer::sum);
    }

    /**
     * 자리 하나를 이번에 평가한 결과.
     *
     * @param candidate        가장 나은 후보. 평가했지만 쓸 경로가 없으면 null(숨김 판정으로 간다)
     * @param gainSec          원래 경로 대비 이득. 후보가 없으면 의미 없다
     * @param realtimeOnly     후보·기준선 체인이 전부 실시간 근거인가
     * @param extraWaits       원래 남은 대기보다 늘어난 수
     * @param routeFingerprint 같은 경로인지 가르는 지문
     * @param alightAt         이 자리에 닿는 시각. 현재 위치형은 null
     */
    public record Evaluated(String slotKey, RerouteOriginKind originKind, RerouteCandidate candidate,
                            int gainSec, boolean realtimeOnly, int extraWaits,
                            String routeFingerprint, Instant alightAt) {
        public Evaluated {
            Objects.requireNonNull(slotKey, "slotKey");
        }
    }

    /**
     * 이번 회차의 입력. 자리는 셋 중 하나다 — 평가함 / 지남 / (언급 없음 = 평가 못 함).
     *
     * @param evaluated 자리 키 → 평가 결과
     * @param passed    지났거나 더는 기점이 아닌 자리(구간 전이로 끝난 현재 위치 자리 포함)
     * @param offered   이번 회차에 연 기점. null 이면 모른다(단계형 자리도 지우지 않는다)
     */
    public record Input(Map<String, Evaluated> evaluated, Set<String> passed, Set<String> offered) {
        public Input {
            evaluated = Map.copyOf(evaluated);
            passed = Set.copyOf(passed);
            offered = offered == null ? null : Set.copyOf(offered);
        }

        public Input(Map<String, Evaluated> evaluated, Set<String> passed) {
            this(evaluated, passed, null);
        }
    }

    public record Result(List<RerouteBoardSlot> slots, Map<Transition, Integer> transitions) {
    }

    /** 자리 전이 종류. 지표 태그로 쓴다(고정 집합). */
    public enum Transition {
        ADDED, UPDATED, REPLACED, HIDDEN, RESHOWN, REMOVED, KEPT
    }

    /** 병합 규칙의 값들. 설정에서 만든다. */
    public record Thresholds(int realtimeSec, int mixedSec, int perExtraWaitSec,
                             int replaceMarginSec, int reshowMarginSec, Duration leadTime) {

        public static Thresholds from(JourneyRerouteProperties.Board board) {
            return new Thresholds(board.getRealtimeThresholdSec(), board.getMixedThresholdSec(),
                    board.getPerExtraWaitSec(), board.getReplaceMarginSec(),
                    board.getReshowMarginSec(), Duration.ofSeconds(board.getLeadTimeSec()));
        }
    }
}
