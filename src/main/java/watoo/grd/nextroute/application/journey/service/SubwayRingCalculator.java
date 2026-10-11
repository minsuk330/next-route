package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingContext;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingHoldReason;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * 진행 링 한 칸을 옮기는 <b>순수 함수</b>. 이 PR 의 본체다.
 *
 * <p>입출력만 받는다. 시험이 쉬워야 관문 숫자를 실측 뒤에 조일 수 있고, DB·Redis·시계가 섞이면
 * 그 숫자를 고칠 때마다 통합 시험을 돌려야 한다.
 *
 * <h2>서버는 앵커만 만든다</h2>
 *
 * <p>{@code progressBaseAt} 시점의 확정 진행률과 다음 역 도착 예정 시각을 내면 앱이 그 사이를
 * 보간한다. 그래서 여기서 하는 일은 "지금 몇 %인가"가 아니라 <b>"어느 직선을 그리게 할까"</b>다.
 *
 * <h2>코드가 초보다 먼저다</h2>
 *
 * <p>{@code barvlDt=0} 은 실측 표본의 절반인데 뜻이 {@code arvlCd} 에 따라 정반대다 —
 * {@code arvlCd=1} 이면 "다 왔다"이고 {@code 99} 면 "아직 3정거장 전"이다. 초를 먼저 보면
 * <b>멀리 있는 열차를 90% 로 표시</b>한다.
 *
 * <h2>모르면 느리게라도 움직이되, 추정이라고 밝힌다(2026-10-10 결정)</h2>
 *
 * <p>예전 원칙은 "남은 초를 못 얻으면 시간표로 채우지 않고 멈춘다"였다. 실측(journey 202)에서 전역 정차 내내 0 에 얼고
 * 구간마다 회색 "확인 중"이 떠 제품 결정으로 뒤집었다: 실시간·실측 예정이 없으면 GTFS 계획 시간 × 여유 배수로 직선을 그리고
 * {@link SubwayRingEtaSource#TYPICAL} 로 밝힌다(앱은 도착 시각을 숨긴다). 지키는 것은 둘 — 되감지 않는다(#357), 진입·도착
 * 근거 없이 상한을 넘지 않는다. {@link SubwayRingQuality#STALE} 은 서버가 새 관측을 못 받을 때만이다.
 *
 * <h2>승차역 구간은 0 에 묶는다</h2>
 *
 * <p>사용자가 역에서 고른 열차는 아직 승차역에 안 왔을 수 있다(#304). 그때 다음 역은 <b>승차역</b>이고
 * 진행률은 0 에 고정한다 — 열차가 다가오는 것을 링으로 그리면 사용자는 이미 타고 가는 중이라고 읽는다.
 * 도착 예정 시각은 싣는다(앱이 "곧 도착"을 쓴다). 열차가 승차역에 서거나 떠나면 다음 구간 0 으로 넘어간다.
 */
@Component
@RequiredArgsConstructor
public class SubwayRingCalculator {

    private static final MathContext RATIO_CONTEXT = new MathContext(6, RoundingMode.HALF_UP);
    private static final int PROGRESS_SCALE = 4;
    private static final int BOARDING_SEQUENCE = 0;

    private final JourneySubwayRingProperties properties;

    /**
     * 새 링 상태.
     *
     * @param previous    직전 상태. 없거나 다른 고정 세대면 처음부터 시작한다
     * @param observation 이번 tick 의 관측. <b>{@code null} 이면 freeze</b> 다
     * @return 체인이 쓸 수 없으면 {@code null} — 부르는 쪽이 "안내 없음"으로 다룬다
     */
    public SubwayRingState next(SubwayRingState previous, SubwayRingContext context,
                                SubwayRingObservation observation, Instant now) {
        SubwayRingChain chain = context.chain();
        if (chain == null || !chain.isUsable()) {
            return null;
        }
        SubwayRingState prior = validPrevious(previous, context);
        int target = targetSequence(prior, chain, context.startSequence());

        if (prior != null && prior.arrivedAtNextStation()
                && prior.nextStationSequence() >= chain.alightingSequence()) {
            // 하차역 도착까지 끝났다. 더 옮길 구간이 없으므로 마지막 상태를 그대로 둔다.
            return prior;
        }
        if (observation == null || isStale(observation, now)) {
            return stamped(prior, notBehindScreen(prior, freeze(prior, context, target, now), now), null);
        }
        SubwayRingState state = switch (observation.source()) {
            case ARRIVAL -> fromArrival(prior, context, target, observation, now);
            case POSITION -> fromPosition(prior, context, target, observation, now);
            // 열차가 이미 하차역을 지났다(#311). 도착은 사실이라 NORMAL — STALE 이면 앱이 끝까지 "확인 중"으로 남는다.
            case PASSED -> anchored(context, context.chain().alightingSequence(), BigDecimal.ONE,
                    observation.observedAt(), null, SubwayRingQuality.NORMAL, observation.observedAt(), now)
                    .withSource(null, SubwayRingStage.ARRIVED);
        };
        return stamped(prior, notBehindScreen(prior, state, now), observation);
    }

    /**
     * 근거 관측의 원천 시각·처음 본 시각을 싣고, 예정이 없으면 출처를 비운다.
     *
     * <p>쓸 관측이 없었으면(멈춤) 직전 링의 근거를 이어 간다 — 그래야 "서버가 마지막으로 새 근거를 본 때"가 남는다.
     */
    private SubwayRingState stamped(SubwayRingState prior, SubwayRingState state, SubwayRingObservation used) {
        if (state == null || state == prior) {
            return state;
        }
        SubwayRingState out = state.estimatedNextStationAt() == null && state.etaSource() != null
                ? state.withSource(null, state.stage())
                : state;
        if (used != null) {
            return out.withSourceTimes(used.observedAt(), used.seenAt() == null ? used.observedAt() : used.seenAt());
        }
        return prior == null ? out : out.withSourceTimes(prior.sourceObservedAt(), prior.sourceSeenAt());
    }

    /**
     * 같은 구간에서 새 링이 <b>지금 화면</b>보다 뒤면 지금 화면 값에서 다시 시작한다(#357).
     *
     * <p>앱은 {@code progressBaseAt → estimatedNextStationAt} 사이를 보간해 그리므로 화면 값은 저장된 base 보다 앞서 있다.
     * 멈춤(freeze·hold)이 base 로 돌아가거나, 원천 지연만큼 과거 시각({@code observedAt})으로 재앵커하면 링이 뒤로 감긴다.
     * 앱도 같은 규칙을 갖고 있지만 실기기 push 는 앱을 거치지 않고 바로 그려진다 — 그래서 저장값부터 단조로 만든다.
     */
    private SubwayRingState notBehindScreen(SubwayRingState prior, SubwayRingState state, Instant now) {
        if (prior == null || state == null || state == prior
                || state.nextStationSequence() != prior.nextStationSequence()) {
            return state;
        }
        BigDecimal shown = prior.shownProgressAt(now);
        if (state.shownProgressAt(now).compareTo(shown) >= 0) {
            return state;
        }
        return state.withAnchor(scaled(shown), now);
    }

    /**
     * 이번 tick 에 <b>어느 역을 조회해야 하는지</b>.
     *
     * <p>갱신기가 도착정보를 물어보기 전에 알아야 하는 값이라 밖으로 연다. 여기서 정하지 않고
     * 갱신기가 따로 세면 두 곳이 서로 다른 역을 가리키는 순간 링이 영영 안 움직인다.
     *
     * @return 체인이 쓸 수 없으면 {@code -1}
     */
    public int targetStationSequence(SubwayRingState previous, SubwayRingContext context) {
        SubwayRingChain chain = context.chain();
        if (chain == null || !chain.isUsable()) {
            return -1;
        }
        return targetSequence(validPrevious(previous, context), chain, context.startSequence());
    }

    // ── 도착 API ────────────────────────────────────────────────────────────

    /**
     * 우리는 <b>언제나 바로 다음 역만</b> 조회한다. 그래서 그 역 기준 코드가 곧 우리 구간의
     * 진행이다.
     *
     * <table>
     *   <tr><td>{@code 3/4/5} 전역</td><td>구간 첫 관측은 0 앵커, 이후는 이어 그림(초 없으면 freeze)</td></tr>
     *   <tr><td>{@code 99} + 초</td><td>0 ~ 상한 보간</td></tr>
     *   <tr><td>{@code 99} + 초 없음</td><td>{@code [N]번째 전역} — freeze</td></tr>
     *   <tr><td>{@code 0} 진입</td><td>0.95</td></tr>
     *   <tr><td>{@code 1} 도착</td><td>1.00 — 다음 tick 이 구간을 넘긴다</td></tr>
     *   <tr><td>{@code 2} 출발</td><td>그 역을 이미 지났다 — 구간을 넘긴다</td></tr>
     * </table>
     */
    private SubwayRingState fromArrival(SubwayRingState prior, SubwayRingContext context,
                                        int target, SubwayRingObservation observation, Instant now) {
        if (observation.observedStationSequence() != target) {
            // 조회한 역이 지금 목표와 다르다. 낡은 관측이므로 진행에 쓰지 않는다.
            return keep(prior, context, target, observation, now);
        }
        SubwayArrivalCode code = observation.code() == null
                ? SubwayArrivalCode.UNKNOWN
                : observation.code();
        Instant observedAt = observation.observedAt();

        return switch (code) {
            case ARRIVED -> arrivedAt(target, anchored(context, target, BigDecimal.ONE,
                    observedAt, null, SubwayRingQuality.NORMAL, observedAt, now));
            case ENTERING -> anchored(context, target, properties.getApproachProgress(),
                    observedAt, null, SubwayRingQuality.NORMAL, observedAt, now)
                    .withSource(null, SubwayRingStage.APPROACH);
            case DEPARTED -> advanced(context, target + 1, observedAt, seenOf(observation), true, now);
            case PREV_DEPARTED, PREV_ENTERING, PREV_ARRIVED -> fromPreviousStation(prior, context,
                    target, observation, now);
            case ENROUTE -> observation.estimatedArrivalAt() == null
                    // "[N]번째 전역" — 아직 멀다. 초가 없으니 그리던 직선을 그대로 둔다.
                    ? keep(prior, context, target, observation, now)
                    : reanchored(prior, context, target, observation, now);
            case UNKNOWN -> keep(prior, context, target, observation, now);
        };
    }

    /**
     * 열차가 우리 다음 역의 <b>전역</b>에 있다({@code 3/4/5}).
     *
     * <ul>
     *   <li>{@code 4/5} 전역 진입·도착 — 아직 출발 전이다. 진행을 그 자리에 세운다(예정 없음, NORMAL)</li>
     *   <li>{@code 3} 전역 출발 + 실측 예정 — 출발 시각에서 예정까지 직선. 같은 예정이면 링을 그대로 둔다</li>
     *   <li>{@code 3} 예정 없음(실측 표본 없음) — 첫 관측은 0 STALE, 같은 구간이면 멈춘다</li>
     * </ul>
     *
     * <p>같은 코드는 구간 내내 반복된다. 볼 때마다 0 으로 재앵커하면 앱 링이 15~30초마다 되감긴다(journey 158).
     */
    private SubwayRingState fromPreviousStation(SubwayRingState prior, SubwayRingContext context,
                                                int target, SubwayRingObservation observation,
                                                Instant now) {
        SubwayArrivalCode code = observation.code();
        if (code == SubwayArrivalCode.PREV_ENTERING || code == SubwayArrivalCode.PREV_ARRIVED) {
            return atPreviousStation(prior, context, target, observation, now);
        }
        if (observation.isMeasured()) {
            return fromMeasured(prior, context, target, observation, now);
        }
        if (sameSegment(prior, target) && (prior.stage() == SubwayRingStage.LATE
                || prior.etaSource() == SubwayRingEtaSource.TYPICAL && prior.stage() == SubwayRingStage.RUN)) {
            // 같은 구간 같은 직선 — 다시 그리지 않는다(예정이 흔들리면 push 가 tick 마다 나간다).
            return keep(prior, context, target, observation, now);
        }
        if (sameSegment(prior, target) && observation.estimatedArrivalAt() != null) {
            return reanchored(prior, context, target, observation, now);
        }
        // 실측 표본이 없다 — 출발을 처음 본 시각부터 GTFS 운행 시간으로 그린다(TYPICAL).
        SubwayRingState typical = typicalLine(context, target, baseProgressOf(prior, target), seenOf(observation),
                false, observation.observedAt(), now);
        if (typical != null) {
            return typical;
        }
        if (sameSegment(prior, target)) {
            return keep(prior, context, target, observation, now);
        }
        Instant observedAt = observation.observedAt();
        return anchored(context, target, BigDecimal.ZERO, observedAt,
                observation.estimatedArrivalAt(),
                observation.estimatedArrivalAt() == null
                        ? SubwayRingQuality.STALE : SubwayRingQuality.NORMAL,
                observedAt, now)
                .withSource(observation.estimatedArrivalAt() == null ? null : SubwayRingEtaSource.LIVE,
                        SubwayRingStage.RUN);
    }

    /**
     * 전역 출발 + 앞 열차 실측 예정(2026-10-07 결정).
     *
     * <p>예정은 "출발을 처음 본 시각 + 실측 중앙값"이라 반복 관측에서 바뀌지 않는다 — 같으면 직전 링을 그대로 두어 push·
     * 버전이 흔들리지 않는다. 예정이 지났는데 도착이 안 보이면 상한에서 멈춘다(늦는 중). 이미 다른 직선 위에서 움직이던
     * 링이면 지금 화면 값에서 새 예정으로 이어 그린다.
     *
     * <p>유효기간은 예정 + 신선도 창까지 늘린다. 앵커 시각이 원천 지연만큼 과거라 앵커 + 창이면 예정 전에 앱이 시간 초과로
     * 본다.
     */
    private SubwayRingState fromMeasured(SubwayRingState prior, SubwayRingContext context, int target,
                                         SubwayRingObservation observation, Instant now) {
        if (observation.seenAt() != null && sameSegment(prior, target)
                && (prior.stage() == SubwayRingStage.LATE || prior.etaSource() == SubwayRingEtaSource.MEASURED)) {
            // 이미 이 구간 실측 직선을 그리는 중이다. 서버 시계 앵커는 한 번만 정한다(리뷰 R1).
            return keep(prior, context, target, observation, now);
        }
        // 앵커 = 서버가 전역 출발을 처음 본 시각. 모르면(옛 경로) 원천 기준 출발 시각 그대로.
        Instant start = observation.seenAt() != null ? observation.seenAt() : observation.segmentStartAt();
        Instant eta = start.plus(observation.measuredDuration());
        BigDecimal base = baseProgressOf(prior, target);
        if (!now.isBefore(lateAt(eta, observation))) {
            return late(context, target, base, observation.observedAt(), now);
        }
        if (sameSegment(prior, target) && eta.equals(prior.estimatedNextStationAt())
                && prior.quality() == SubwayRingQuality.NORMAL) {
            return prior.refreshed(observation.observedAt(), now);
        }
        SubwayRingState next = (base.signum() > 0 && prior != null && prior.estimatedNextStationAt() != null
                ? reanchored(prior, context, target, observation, now)
                : anchored(context, target, base, start, eta,
                        SubwayRingQuality.NORMAL, observation.observedAt(), now))
                .withSource(SubwayRingEtaSource.MEASURED, SubwayRingStage.RUN);
        Instant validUntil = eta.plus(properties.getFreshnessWindow());
        return next.progressValidUntil() == null || next.progressValidUntil().isBefore(validUntil)
                ? next.withProgressValidUntil(validUntil)
                : next;
    }

    /**
     * 링을 그 자리에 세운다 — 예정 없음, 보간 상한 = 지금 값. 정상 상황이라 NORMAL 이다("확인 중"이 아니다).
     *
     * <p>부르는 쪽이 {@link SubwayRingState#withHold} 로 이유를 붙인다. 이유 없이 멈춘 링은 앱이 고장과 구분하지 못한다.
     */
    private SubwayRingState hold(SubwayRingContext context, int target, BigDecimal progress,
                                 Instant observedAt, Instant now) {
        SubwayRingState held = anchored(context, target, progress, observedAt, null,
                SubwayRingQuality.NORMAL, observedAt, now);
        return held.withProgressTarget(held.segmentProgress());
    }

    /**
     * 기존 앵커로 <b>화면 진행률</b>을 계산하고 그 값을 새 base 로 삼는다.
     *
     * <p>새 도착 예정만 갈아 끼우면 앱이 그리던 직선이 뒤로 감긴다. 반대로 base 를 그대로 두고
     * 예정 시각만 늘리면 링이 멈춘 것처럼 보인다. 지금 값을 확정해 두고 거기서 새 직선을 그리는
     * 것이 둘을 함께 지키는 유일한 방법이다.
     *
     * <p><b>경계를 먼저 친다.</b> 분모가 0 이면 정의되지 않고, 음수면 시간비의 의미가 뒤집힌다.
     */
    private SubwayRingState reanchored(SubwayRingState prior, SubwayRingContext context,
                                       int target, SubwayRingObservation observation, Instant now) {
        BigDecimal cap = capOf(target);
        BigDecimal base = baseProgressOf(prior, target);
        Instant observedAt = observation.observedAt();

        BigDecimal current;
        if (!sameSegment(prior, target) || prior.progressBaseAt() == null
                || prior.estimatedNextStationAt() == null) {
            current = base;
        } else if (!prior.estimatedNextStationAt().isAfter(prior.progressBaseAt())) {
            // 분모가 0 또는 음수다. 비율을 만들지 않는다.
            current = cap;
        } else if (!observedAt.isBefore(prior.estimatedNextStationAt())) {
            // 예정 시각을 이미 지났다.
            current = cap;
        } else {
            BigDecimal elapsed = seconds(prior.progressBaseAt(), observedAt);
            BigDecimal span = seconds(prior.progressBaseAt(), prior.estimatedNextStationAt());
            BigDecimal ratio = clamp01(elapsed.divide(span, RATIO_CONTEXT));
            current = base.add(cap.subtract(base).multiply(ratio));
        }

        // 서버 앵커는 뒤로 가지 않는다. 지금 화면보다 뒤인지는 next() 가 마지막에 본다(#357) — 새 예정 시각이 짧으면
        // 앱이 보간하던 직선이 갈아타면서 앞으로 튈 수 있다.
        BigDecimal progress = max(base, current);
        return anchored(context, target, progress, observedAt,
                observation.estimatedArrivalAt(), SubwayRingQuality.NORMAL, observedAt, now)
                .withSource(SubwayRingEtaSource.LIVE, SubwayRingStage.RUN);
    }

    // ── 위치 API (폴백) ─────────────────────────────────────────────────────

    /**
     * 도착 목록에 우리 열차가 없을 때. <b>구간만은 잃지 않는다.</b>
     *
     * <p>초가 없으므로 링은 앵커에 멈추고 {@code STALE} 이다. 그래도 역이 바뀌면 다음 구간
     * 0 으로 정상 진행하므로, 그 여정이 <b>내내</b> 죽지는 않는다.
     *
     * <p>관측이 우리 목표보다 <b>앞선 역</b>을 가리키면 그 사이 역들을 놓친 것이다. 목표를 그
     * 역까지 건너뛴다 — 안 뛰면 남은 역 수가 영영 줄지 않는다.
     */
    private SubwayRingState fromPosition(SubwayRingState prior, SubwayRingContext context,
                                         int target, SubwayRingObservation observation, Instant now) {
        int seen = observation.observedStationSequence();
        if (seen < target) {
            // 아직 우리 구간의 다음 역에 못 왔다. 새 정보가 없다 — 그리던 직선을 그대로 둔다.
            return keep(prior, context, target, observation, now);
        }
        SubwayTrainStatus status = observation.positionStatus() == null
                ? SubwayTrainStatus.UNKNOWN
                : observation.positionStatus();
        Instant observedAt = observation.observedAt();

        return switch (status) {
            // 도착·진입은 사실이다. 남은 초가 없을 뿐이라 NORMAL — 회색 "확인 중"은 관측이 끊겼을 때만이다.
            case ARRIVED -> arrivedAt(seen, anchored(context, seen, BigDecimal.ONE,
                    observedAt, null, SubwayRingQuality.NORMAL, observedAt, now));
            case ENTERING -> anchored(context, seen, properties.getApproachProgress(),
                    observedAt, null, SubwayRingQuality.NORMAL, observedAt, now)
                    .withSource(null, SubwayRingStage.APPROACH);
            case DEPARTED -> advanced(context, seen + 1, observedAt, seenOf(observation), true, now);
            // 전역출발(3)은 그 역의 바로 전역을 출발했다는 뜻이다(2026-10-09 확정) — 우리 목표보다 뒤 역이면 그 역 구간으로
            // 넘긴다. 목표 역의 3 은 리더가 전역 출발 관측(PREV_DEPARTED)으로 바꿔 넘긴다(#371). 모름은 근거로 쓰지 않는다.
            case LEFT_PREVIOUS, UNKNOWN -> seen > target
                    ? advanced(context, seen, observedAt, seenOf(observation), true, now)
                    : keep(prior, context, target, observation, now);
        };
    }

    // ── 상태 만들기 ─────────────────────────────────────────────────────────

    /**
     * 다음 역 도착 표기. 승차역(0) 도착은 {@link #anchored} 가 곧바로 다음 구간 시작으로 넘기므로, 그때는 구간 시작
     * 표기(RUN)를 그대로 둔다 — 새 구간에 ARRIVED 가 붙으면 그 구간의 이후 단계가 전부 "앞 단계"로 보인다.
     */
    private SubwayRingState arrivedAt(int target, SubwayRingState state) {
        return state.nextStationSequence() == target ? state.withSource(null, SubwayRingStage.ARRIVED) : state;
    }

    /** 구간을 넘긴다. 새 구간은 0 에서 시작하고 아직 남은 초가 없다. */
    private SubwayRingState advanced(SubwayRingContext context, int nextTarget, Instant observedAt,
                                     Instant startedAt, boolean departed, Instant now) {
        int bounded = Math.min(nextTarget, context.chain().alightingSequence());
        // 정상 구간 전환은 STALE 이 아니다. 전형 시간을 알면 바로 직선을 그린다 — 출발했으면 운행, 역에 섰으면 정차부터.
        SubwayRingState typical = departed
                ? typicalLine(context, bounded, BigDecimal.ZERO, startedAt, false, observedAt, now)
                : dwellLine(context, bounded, BigDecimal.ZERO, startedAt, observedAt, now);
        if (typical != null) {
            return typical;
        }
        return anchored(context, bounded, BigDecimal.ZERO, observedAt, null,
                SubwayRingQuality.STALE, observedAt, now)
                .withSource(null, SubwayRingStage.RUN);
    }

    /**
     * 열차가 전역에 서 있다(4/5). 정차를 같은 구간 직선의 앞부분으로 그린다.
     *
     * <ul>
     *   <li>같은 구간에서 이미 그 뒤 단계(운행·늦음·진입·도착)를 봤으면 되돌아가지 않는다 — 위치로 출발을 본 뒤 도착 API 가 늦게
     *       전역 도착을 줘도 정차로 돌아가 멈추지 않는다(리뷰 R4)</li>
     *   <li>정차 직선을 그리는 중이면 그대로 둔다(같은 4/5 반복에 정차 시작을 다시 잡지 않는다 — journey 158). 정차 예정이 지났으면
     *       정차 지점에서 멈춘다(사유 유지)</li>
     *   <li>처음이면 정차 직선: 0(또는 지금 화면) → 정차 지점, 정차 × 배수. 정차 지점 = 상한 × 정차/(정차+운행) — 정시면 운행
     *       직선과 기울기가 같아 꺾이지 않는다</li>
     * </ul>
     *
     * <p>전형 시간을 모르면 예전처럼 그 자리에 세운다.
     */
    private SubwayRingState atPreviousStation(SubwayRingState prior, SubwayRingContext context, int target,
                                              SubwayRingObservation observation, Instant now) {
        String stationName = nameOf(context, target - 1);
        if (sameSegment(prior, target) && prior.stage() != null
                && prior.stage().compareTo(SubwayRingStage.DWELL) > 0) {
            return keep(prior, context, target, observation, now);
        }
        if (sameSegment(prior, target) && prior.stage() == SubwayRingStage.DWELL
                && (prior.etaSource() == SubwayRingEtaSource.TYPICAL || prior.estimatedNextStationAt() == null
                && prior.holdReason() == SubwayRingHoldReason.PREV_STATION_STOPPED
                && prior.progressTarget() != null && prior.progressTarget().signum() > 0
                && prior.progressTarget().compareTo(capOf(target)) < 0)) {
            Instant eta = prior.estimatedNextStationAt();
            if (eta != null && !now.isBefore(eta)) {
                return hold(context, target, prior.progressTarget(), observation.observedAt(), now)
                        .withHold(SubwayRingHoldReason.PREV_STATION_STOPPED, stationName)
                        .withSource(null, SubwayRingStage.DWELL);
            }
            return prior.refreshed(observation.observedAt(), now);
        }
        SubwayRingState line = dwellLine(context, target, baseProgressOf(prior, target),
                dwellStartOf(prior, target, observation), observation.observedAt(), now);
        if (line != null) {
            return line;
        }
        return hold(context, target, baseProgressOf(prior, target), observation.observedAt(), now)
                .withHold(SubwayRingHoldReason.PREV_STATION_STOPPED, stationName)
                .withSource(null, SubwayRingStage.DWELL);
    }

    /** 정차 시작 = 직전 링이 바로 그 역 도착이면 그 도착을 처음 본 시각(한 tick 늦지 않게), 아니면 이 관측을 처음 본 시각. */
    private static Instant dwellStartOf(SubwayRingState prior, int target, SubwayRingObservation observation) {
        if (prior != null && prior.arrivedAtNextStation() && prior.nextStationSequence() == target - 1
                && prior.sourceSeenAt() != null) {
            return prior.sourceSeenAt();
        }
        return seenOf(observation);
    }

    /**
     * 정차 직선. 상한 대신 정차 지점까지만 그리고 예정 = 정차 끝. 이미 정차 지점을 넘었거나 정차 예정이 지났으면 정차 지점에 세운다.
     *
     * @return 승차역 구간이거나 전형 시간을 모르면 null
     */
    private SubwayRingState dwellLine(SubwayRingContext context, int target, BigDecimal base, Instant startedAt,
                                      Instant observedAt, Instant now) {
        if (target <= BOARDING_SEQUENCE || target > context.chain().alightingSequence() || startedAt == null) {
            return null;
        }
        SubwayRingContext.Typical times = context.typicalFor(target);
        if (times == null || times.runSeconds() == null) {
            return null;
        }
        long dwell = dwellSecondsOf(times);
        BigDecimal ceiling = scaled(capOf(target).multiply(BigDecimal.valueOf(dwell))
                .divide(BigDecimal.valueOf(dwell + times.runSeconds()), RATIO_CONTEXT));
        Instant eta = startedAt.plusMillis(BigDecimal.valueOf(dwell * 1000L)
                .multiply(properties.getTypicalFactor()).longValue());
        String stationName = nameOf(context, target - 1);
        if (base.compareTo(ceiling) >= 0 || !now.isBefore(eta)) {
            return hold(context, target, max(base, ceiling), observedAt, now)
                    .withHold(SubwayRingHoldReason.PREV_STATION_STOPPED, stationName)
                    .withSource(null, SubwayRingStage.DWELL);
        }
        SubwayRingState line = anchored(context, target, base, startedAt, eta, SubwayRingQuality.NORMAL, observedAt, now)
                .withProgressTarget(ceiling)
                .withHold(SubwayRingHoldReason.PREV_STATION_STOPPED, stationName)
                .withSource(SubwayRingEtaSource.TYPICAL, SubwayRingStage.DWELL);
        return longValidity(line, now);
    }

    /**
     * TYPICAL 직선 — 실시간·실측 예정이 없을 때 GTFS 계획 시간 × 여유 배수로 그린다. 출처를 TYPICAL 로 밝혀 앱이 시각을 숨긴다.
     *
     * @param startedAt    서버가 그 사건(출발·도착)을 처음 본 시각. 원천 시각(1~2분 늦음)에 앵커하면 그리자마자 예정이 지나 점프한다
     * @param includeDwell 직전 역에 서 있는 동안 시작하면 정차 시간도 더한다(정차+운행 한 직선)
     * @return 승차역 구간이거나 전형 시간을 모르면 null — 부르는 쪽이 기존대로 둔다
     */
    private SubwayRingState typicalLine(SubwayRingContext context, int target, BigDecimal base, Instant startedAt,
                                        boolean includeDwell, Instant observedAt, Instant now) {
        if (target <= BOARDING_SEQUENCE || target > context.chain().alightingSequence() || startedAt == null) {
            return null;
        }
        SubwayRingContext.Typical times = context.typicalFor(target);
        if (times == null || times.runSeconds() == null) {
            return null;
        }
        long seconds = times.runSeconds() + (includeDwell ? dwellSecondsOf(times) : 0);
        Instant eta = startedAt.plusMillis(BigDecimal.valueOf(seconds * 1000L)
                .multiply(properties.getTypicalFactor()).longValue());
        if (!now.isBefore(eta.plus(properties.getLateGrace()))) {
            return late(context, target, base, observedAt, now);
        }
        SubwayRingState line = anchored(context, target, base, startedAt, eta, SubwayRingQuality.NORMAL, observedAt, now)
                .withSource(SubwayRingEtaSource.TYPICAL, includeDwell ? SubwayRingStage.DWELL : SubwayRingStage.RUN);
        return longValidity(line, now);
    }

    private long dwellSecondsOf(SubwayRingContext.Typical times) {
        return times.dwellSeconds() != null ? times.dwellSeconds() : properties.getDefaultDwell().toSeconds();
    }

    /** 보간 유효기간을 예정·지금 뒤까지 늘린다 — 앵커 + 창이면 긴 직선은 예정 전에 앱 보간이 끊긴다. */
    private SubwayRingState longValidity(SubwayRingState line, Instant now) {
        Instant window = line.estimatedNextStationAt() == null ? now : line.estimatedNextStationAt();
        Instant until = (window.isAfter(now) ? window : now).plus(properties.getFreshnessWindow());
        return line.progressValidUntil() == null || line.progressValidUntil().isBefore(until)
                ? line.withProgressValidUntil(until)
                : line;
    }

    /** 서버 시각을 아는 관측이면 유예를 더한다. 모르면(옛 경로) 예정 그대로. */
    private Instant lateAt(Instant eta, SubwayRingObservation observation) {
        return observation.seenAt() != null ? eta.plus(properties.getLateGrace()) : eta;
    }

    /** 예정이 지났는데 진입·도착이 안 보인다 — 상한에서 멈추고 LATE 라 말한다. */
    private SubwayRingState late(SubwayRingContext context, int target, BigDecimal base, Instant observedAt,
                                 Instant now) {
        return hold(context, target, max(base, capOf(target)), observedAt, now)
                .withHold(SubwayRingHoldReason.LATE, null)
                .withSource(null, SubwayRingStage.LATE);
    }

    /**
     * 수집은 살아 있는데 새 근거가 없다 — 그리던 직선을 그대로 둔다(STALE 이 아니다). 그 직선의 예정이 유예까지 지났으면 LATE.
     * 아직 한 번도 못 본 구간이면 기존처럼 0 에서 멈춘다.
     */
    private SubwayRingState keep(SubwayRingState prior, SubwayRingContext context, int target,
                                 SubwayRingObservation observation, Instant now) {
        if (!sameSegment(prior, target)) {
            return freeze(prior, context, target, now);
        }
        Instant eta = prior.estimatedNextStationAt();
        boolean lineWithGrace = prior.etaSource() == SubwayRingEtaSource.TYPICAL
                || prior.etaSource() == SubwayRingEtaSource.MEASURED && observation.seenAt() != null;
        if (eta != null && lineWithGrace && !now.isBefore(eta.plus(properties.getLateGrace()))) {
            return late(context, target, prior.segmentProgress(), observation.observedAt(), now)
                    .withHold(SubwayRingHoldReason.LATE, null);
        }
        SubwayRingState kept = prior.refreshed(observation.observedAt(), now);
        return kept.quality() == SubwayRingQuality.STALE ? kept.withQuality(SubwayRingQuality.NORMAL) : kept;
    }

    private static Instant seenOf(SubwayRingObservation observation) {
        return observation.seenAt() != null ? observation.seenAt() : observation.observedAt();
    }

    /**
     * 관측이 없거나 낡았다. 마지막 값에 <b>멈춘다</b>.
     *
     * <p>{@code estimatedNextStationAt} 을 비우는 것이 핵심이다 — 앱은 그 값이 없으면 보간을
     * 멈춘다. 남겨 두면 죽은 화면이 계속 살아 있는 척한다.
     */
    private SubwayRingState freeze(SubwayRingState prior, SubwayRingContext context,
                                   int target, Instant now) {
        if (sameSegment(prior, target)) {
            return new SubwayRingState(context.journeyId(), context.routeRevision(),
                    context.legIndex(), context.bindingVersion(),
                    target, keyOf(context, target), nameOf(context, target),
                    context.chain().alightingName(), remainingOf(context, target),
                    prior.segmentProgress(), prior.progressBaseAt(), prior.progressTarget(),
                    null, prior.progressValidUntil(),
                    phaseOf(context, target), SubwayRingQuality.STALE,
                    prior.observedAt(), now)
                    .withSource(null, prior.stage());
        }
        // 이 구간을 아직 한 번도 못 봤다. 계획값으로 채우지 않고 0 에서 멈춰 둔다.
        return new SubwayRingState(context.journeyId(), context.routeRevision(),
                context.legIndex(), context.bindingVersion(),
                target, keyOf(context, target), nameOf(context, target),
                context.chain().alightingName(), remainingOf(context, target),
                scaled(BigDecimal.ZERO), now, capOf(target),
                null, now,
                phaseOf(context, target), SubwayRingQuality.STALE,
                prior == null ? null : prior.observedAt(), now);
    }

    private SubwayRingState anchored(SubwayRingContext context, int target, BigDecimal progress,
                                     Instant baseAt, Instant estimatedAt,
                                     SubwayRingQuality quality, Instant observedAt, Instant now) {
        int bounded = Math.min(Math.max(target, 0), context.chain().alightingSequence());
        if (bounded == BOARDING_SEQUENCE) {
            if (progress.compareTo(BigDecimal.ONE) >= 0) {
                // 열차가 승차역에 섰다. 승차역 구간은 끝났고 다음 역을 향해 0 에서 시작한다(정차+운행).
                return advanced(context, BOARDING_SEQUENCE + 1, observedAt, now, false, now);
            }
            progress = BigDecimal.ZERO;
        }
        BigDecimal value = scaled(progress);
        return new SubwayRingState(context.journeyId(), context.routeRevision(),
                context.legIndex(), context.bindingVersion(),
                bounded, keyOf(context, bounded), nameOf(context, bounded),
                context.chain().alightingName(), remainingOf(context, bounded),
                value, baseAt, max(capOf(bounded), value),
                estimatedAt, baseAt.plus(properties.getFreshnessWindow()),
                phaseOf(context, bounded), quality, observedAt, now);
    }

    // ── 잔가지 ──────────────────────────────────────────────────────────────

    private SubwayRingState validPrevious(SubwayRingState previous, SubwayRingContext context) {
        if (previous == null || !previous.matches(context.routeRevision(), context.legIndex(),
                context.bindingVersion())) {
            return null;
        }
        return previous;
    }

    /** 지금 향하는 역. 직전 구간을 도착까지 마쳤으면 다음 칸으로 넘어간다. */
    private int targetSequence(SubwayRingState prior, SubwayRingChain chain, int startSequence) {
        if (prior == null) {
            return Math.min(Math.max(startSequence, 0), chain.alightingSequence());
        }
        int next = prior.arrivedAtNextStation()
                ? prior.nextStationSequence() + 1
                : prior.nextStationSequence();
        return Math.min(Math.max(next, 0), chain.alightingSequence());
    }

    private boolean isStale(SubwayRingObservation observation, Instant now) {
        if (observation.source() == SubwayRingObservation.Source.PASSED) {
            // 구간을 지났다는 것은 시간이 지나도 바뀌지 않는다. 위치 원천 지연(최대 150초)으로 창을 넘겨도 마무리한다.
            return false;
        }
        if (observation.observedAt() == null) {
            return true;
        }
        if (observation.presentAt() != null) {
            // 서버 시계 기준: 우리 열차 행을 마지막으로 받아 본 지 staleAfter 가 지났거나, 원천이 너무 묵은 행을 계속 주거나.
            // recptnDt 는 1~2분 뒤처져 있어 그것만으로 재면 대부분이 STALE 이 된다.
            return Duration.between(observation.presentAt(), now).compareTo(properties.getStaleAfter()) > 0
                    || Duration.between(observation.observedAt(), now)
                    .compareTo(properties.getStaleSourceMaxAge()) > 0;
        }
        return Duration.between(observation.observedAt(), now)
                .compareTo(properties.getFreshnessWindow()) > 0;
    }

    private boolean sameSegment(SubwayRingState prior, int target) {
        return prior != null && prior.nextStationSequence() == target
                && prior.segmentProgress() != null;
    }

    private BigDecimal baseProgressOf(SubwayRingState prior, int target) {
        return sameSegment(prior, target) ? prior.segmentProgress() : BigDecimal.ZERO;
    }

    /**
     * 하차역을 <b>포함</b>해 센다. 다음 역이 하차역이면 1 이다.
     *
     * <p>승차역을 향할 때는 승차역을 세지 않는다 — 탈 정거장 수, 곧 승차 다음 역부터 하차역까지다.
     */
    private Integer remainingOf(SubwayRingContext context, int target) {
        return context.chain().alightingSequence() - Math.max(target, 1) + 1;
    }

    /** 앱 보간의 상한. 승차역 구간은 0 이다 — 열차를 기다리는 동안 링이 차오르지 않는다. */
    private BigDecimal capOf(int target) {
        return target == BOARDING_SEQUENCE ? BigDecimal.ZERO : properties.getProgressTarget();
    }

    /**
     * 하차 국면 전환 조건은 <b>다음 역이 하차역</b> 하나다.
     *
     * <p>남은 시간으로 전환하지 않는다. 실시간이 늦으면 안내가 통째로 늦어지고, 빠르면 아직
     * 두 정거장 남았는데 내리라고 말한다.
     */
    private SubwayRingPhase phaseOf(SubwayRingContext context, int target) {
        return target >= context.chain().alightingSequence()
                ? SubwayRingPhase.SUBWAY_ALIGHT
                : SubwayRingPhase.RIDE_SUBWAY;
    }

    private String keyOf(SubwayRingContext context, int target) {
        SubwayRingChain.Station station = context.chain().at(target);
        return station == null ? null : station.canonicalKey();
    }

    private String nameOf(SubwayRingContext context, int target) {
        SubwayRingChain.Station station = context.chain().at(target);
        return station == null ? null : station.name();
    }

    private BigDecimal seconds(Instant from, Instant to) {
        return BigDecimal.valueOf(Duration.between(from, to).toMillis(), 3);
    }

    private BigDecimal clamp01(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : value;
    }

    private BigDecimal max(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private BigDecimal scaled(BigDecimal value) {
        return value.setScale(PROGRESS_SCALE, RoundingMode.HALF_UP);
    }
}
