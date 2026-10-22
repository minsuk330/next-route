package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingContext;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * 하차역 도착 예정(#310)과 그 출처. <b>링에 다음 역 예정이 없어도 채운다</b>(지하철 링 TYPICAL PR-4) — 늦음·진입·도착·정차 초과·
 * 멈춤에서 앱 Dynamic Island 오른쪽 시각이 비면 사용자는 언제 내리는지 모른다.
 *
 * <ul>
 *   <li>다음 역 예정이 있으면 그 예정(정차 직선이면 정차 끝 + 운행 × 배수) + GTFS 도착 간격(다음 역 → 하차역)</li>
 *   <li>없으면 다음 역 도착을 추정한다 — 늦음·진입·도착이면 지금, 전역 정차 중이면 지금 + 운행 × 배수, 그 밖(운행 중 멈춤)은 지금 +
 *       남은 운행 몫. 거기에 GTFS 도착 간격 × 배수. 출처는 TYPICAL</li>
 *   <li>출처: 다음 역이 하차역이고 다음 역 예정이 그대로 쓰였으면 그 출처, 아니면 TYPICAL(남은 구간은 계획값이다)</li>
 * </ul>
 *
 * <p>GTFS 를 모르면 null — 지어내지 않는다.
 *
 * <p>예정 없이 "지금" 기준으로 만든 추정은 시간이 흐르면 같이 흐른다. 그대로 두면 30초 눈금을 넘을 때마다 push 가 나가므로,
 * 직전 TYPICAL 하차 예정과 {@link #STICKY} 안이면 직전 값을 지킨다.
 */
@Component
@RequiredArgsConstructor
public class SubwayRingAlightingEstimator {

    private final SubwayLineOrder lineOrder;
    private final JourneySubwayRingProperties properties;

    /** "지금" 기준 추정이 직전 값과 이만큼 안이면 직전 값을 지킨다. */
    static final java.time.Duration STICKY = java.time.Duration.ofSeconds(60);

    public record Estimate(Instant at, SubwayRingEtaSource source) {
        static final Estimate NONE = new Estimate(null, null);
    }

    /**
     * @param previous 직전 링(같은 고정 세대가 아니어도 된다 — 하차 예정 값만 본다). 없으면 null
     */
    public Estimate estimate(SubwayRingState state, SubwayRingState previous, SubwayRingChain chain,
                             TrainIdentity train, Instant now) {
        return estimate(state, previous, chain, train, now, null);
    }

    /**
     * @param segmentTimes 링 계산기와 같은 구간 시간(순번 → 정차·운행). 급행 체인이면 남은 구간도 이것으로 더한다 — 급행 실측(#497)이
     *                들어 있다. 없거나 한 구간이라도 모르면 GTFS 도착 간격
     */
    public Estimate estimate(SubwayRingState state, SubwayRingState previous, SubwayRingChain chain,
                             TrainIdentity train, Instant now, IntFunction<SubwayRingContext.Typical> segmentTimes) {
        if (state == null || chain == null || train == null) {
            return Estimate.NONE;
        }
        SubwayRingChain.Station nextStation = chain.at(state.nextStationSequence());
        SubwayRingChain.Station alighting = chain.at(chain.alightingSequence());
        if (nextStation == null || alighting == null) {
            return Estimate.NONE;
        }
        if (nextStation.sequence() == alighting.sequence() && state.arrivedAtNextStation()) {
            // 하차역에 이미 섰다 — 예정은 끝났다. 직전 값을 지키고, 없으면 도착 시각. "지금" 기준 추정을 계속 돌리면 하차 뒤에도
            // 60초마다 값이 바뀌어 링 버전·push 가 났다(journey 227, #380).
            if (previous != null && previous.estimatedAlightingAt() != null) {
                return new Estimate(previous.estimatedAlightingAt(), previous.alightingEtaSource());
            }
            return new Estimate(state.progressBaseAt(), SubwayRingEtaSource.TYPICAL);
        }
        // 진입 직선(#483)의 예정은 그리기용(지금 + 몇 초)이다 — 다음 역 도착은 "지금"으로 본다(아래 nextArrivalWithoutEta).
        Instant next = state.stage() == SubwayRingStage.APPROACH ? null : state.estimatedNextStationAt();
        IntFunction<SubwayRingContext.Typical> times = chain.hasPassing() ? segmentTimes : null;
        boolean typical = false;
        if (next != null && state.stage() == SubwayRingStage.DWELL
                && state.etaSource() == SubwayRingEtaSource.TYPICAL) {
            // 정차 직선의 예정은 정차 끝이다 — 다음 역 도착은 그 뒤 운행 시간만큼 더 걸린다.
            Optional<Integer> run = runSeconds(chain, train, state.nextStationSequence(), times);
            if (run.isEmpty()) {
                return Estimate.NONE;
            }
            next = next.plusMillis(scaledMillis(run.get()));
            typical = true;
        }
        boolean fromNow = false;
        if (next == null) {
            next = nextArrivalWithoutEta(state, chain, train, now, times);
            if (next == null) {
                return Estimate.NONE;
            }
            typical = true;
            fromNow = true;
        }
        if (nextStation.sequence() == alighting.sequence()) {
            return sticky(new Estimate(next, typical ? SubwayRingEtaSource.TYPICAL : state.etaSource()), previous,
                    fromNow);
        }
        // 급행이면 정차열이 정확히 같은 OTP 급행 패턴의 계획만(subway-express PR-S4). 없으면 모름 — 완행 합으로 지어내지 않는다.
        Optional<Integer> gap = gapOf(chain, nextStation.sequence(), alighting.sequence(), times);
        if (gap.isEmpty()) {
            gap = chain.pattern() == null
                    ? lineOrder.arrivalGapSeconds(train.lineId(), train.direction(), nextStation.canonicalKey(),
                            alighting.canonicalKey())
                    : lineOrder.arrivalGapSeconds(train.lineId(), train.direction(), nextStation.canonicalKey(),
                            alighting.canonicalKey(), chain.pattern());
        }
        if (gap.isEmpty()) {
            return Estimate.NONE;
        }
        Instant at = typical ? next.plusMillis(scaledMillis(gap.get())) : next.plusSeconds(gap.get());
        return sticky(new Estimate(at, SubwayRingEtaSource.TYPICAL), previous, fromNow);
    }

    private static Estimate sticky(Estimate estimate, SubwayRingState previous, boolean fromNow) {
        if (!fromNow || previous == null || previous.estimatedAlightingAt() == null
                || previous.alightingEtaSource() != SubwayRingEtaSource.TYPICAL) {
            return estimate;
        }
        java.time.Duration drift = java.time.Duration.between(previous.estimatedAlightingAt(), estimate.at()).abs();
        return drift.compareTo(STICKY) <= 0
                ? new Estimate(previous.estimatedAlightingAt(), SubwayRingEtaSource.TYPICAL)
                : estimate;
    }

    /** 다음 역 예정이 없을 때의 다음 역 도착 추정. 모르면 null. */
    private Instant nextArrivalWithoutEta(SubwayRingState state, SubwayRingChain chain, TrainIdentity train,
                                          Instant now, IntFunction<SubwayRingContext.Typical> times) {
        SubwayRingStage stage = state.stage();
        if (stage == SubwayRingStage.LATE || stage == SubwayRingStage.APPROACH || stage == SubwayRingStage.ARRIVED
                || state.arrivedAtNextStation()) {
            return now;
        }
        Optional<Integer> run = runSeconds(chain, train, state.nextStationSequence(), times);
        if (run.isEmpty()) {
            return null;
        }
        if (stage == SubwayRingStage.DWELL) {
            return now.plusMillis(scaledMillis(run.get()));
        }
        // 운행 중인데 예정이 없다(멈춤) — 남은 몫만큼.
        BigDecimal cap = state.progressTarget() == null || state.progressTarget().signum() == 0
                ? properties.getProgressTarget() : state.progressTarget();
        BigDecimal shown = state.shownProgressAt(now);
        BigDecimal left = cap.subtract(shown).max(BigDecimal.ZERO).divide(cap, 4, java.math.RoundingMode.HALF_UP);
        return now.plusMillis(BigDecimal.valueOf(scaledMillis(run.get())).multiply(left).longValue());
    }

    /** 다음 역 → 하차역 도착 간격 = 그 뒤 구간마다 정차 + 운행. 한 구간이라도 모르면 empty. */
    private Optional<Integer> gapOf(SubwayRingChain chain, int next, int alighting,
                                    IntFunction<SubwayRingContext.Typical> times) {
        if (times == null) {
            return Optional.empty();
        }
        int sum = 0;
        for (int sequence = next + 1; sequence <= alighting; sequence++) {
            SubwayRingContext.Typical t = times.apply(sequence);
            if (t == null || t.runSeconds() == null) {
                return Optional.empty();
            }
            sum += (t.dwellSeconds() != null ? t.dwellSeconds() : (int) properties.getDefaultDwell().toSeconds())
                    + t.runSeconds();
        }
        return Optional.of(sum);
    }

    private Optional<Integer> runSeconds(SubwayRingChain chain, TrainIdentity train, int sequence,
                                         IntFunction<SubwayRingContext.Typical> times) {
        SubwayRingContext.Typical t = times == null ? null : times.apply(sequence);
        if (t != null && t.runSeconds() != null) {
            return Optional.of(t.runSeconds());
        }
        SubwayRingChain.Station from = chain.at(sequence - 1);
        SubwayRingChain.Station to = chain.at(sequence);
        if (from == null || to == null) {
            return Optional.empty();
        }
        return (chain.pattern() == null
                ? lineOrder.segmentTimes(train.lineId(), train.direction(), from.canonicalKey(), to.canonicalKey())
                : lineOrder.segmentTimes(train.lineId(), train.direction(), from.canonicalKey(), to.canonicalKey(),
                        chain.pattern()))
                .map(SubwayLineOrder.SegmentTimes::runSeconds);
    }

    private long scaledMillis(int seconds) {
        return BigDecimal.valueOf(seconds * 1000L).multiply(properties.getTypicalFactor()).longValue();
    }
}
