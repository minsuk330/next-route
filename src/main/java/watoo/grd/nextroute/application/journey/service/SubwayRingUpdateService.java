package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingContext;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.observability.SubwayRingMetrics;
import watoo.grd.nextroute.application.journey.port.out.SubwayRingPort;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;

import java.time.Clock;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import java.time.Instant;
import java.util.List;

/**
 * 진행 링 한 tick.
 *
 * <h2>확정된 고정만 본다</h2>
 *
 * <p>{@code PENDING}·{@code PROVISIONAL} 은 아직 어느 열차인지 모른다. 그 상태에서 다음 역과
 * 진행률을 만들면 앱은 그것을 실시간으로 읽는다 — 미확정 구간의 화면은 "탑승 열차 확인 중"이지
 * 0% 링이 아니다.
 *
 * <h2>한 건의 실패는 그 건만 죽인다</h2>
 *
 * <p>tick 하나가 통째로 죽으면 그 순간 진행 중인 모든 여정의 링이 함께 멈춘다. 실패한 건은
 * 행이 그대로 남아 다음 tick 에 다시 잡힌다.
 *
 * <h2>Redis 가 비어도 안전하다</h2>
 *
 * <p>직전 링이 없으면 그 구간을 0 에서 다시 시작한다. DB 의 확정 고정과 실시간 도착정보만으로
 * 재구성되므로 캐시 유실이 <b>틀린 열차</b>로 이어지지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayRingUpdateService {

    private final SubwayLegBindingRepository bindingRepository;
    private final JourneyLegRepository legRepository;
    private final JourneySubwayStationChainReader stationChainReader;
    private final SubwayRingStationResolver stationResolver;
    private final SubwayRingSignalReader signalReader;
    private final SubwayRingCalculator calculator;
    private final SubwayRingPushEnqueueService pushEnqueueService;
    private final SubwayRingPushDecider pushDecider;
    private final SubwayRingPort ringPort;
    private final SubwayRingMetrics metrics;
    private final JourneySubwayRingProperties properties;
    private final Clock clock;
    private final RerouteRefreshSignal rerouteRefreshSignal;
    private final SubwayLineOrder lineOrder;
    private final JourneySessionRepository sessionRepository;
    private final JourneyNavigationProperties navigationProperties;

    /** @return 훑은 건수 */
    public int runOnce() {
        List<SubwayLegBinding> batch = bindingRepository.findRingTargets(properties.getBatchSize());
        metrics.recordScanned(batch.size());
        for (SubwayLegBinding binding : batch) {
            try {
                processOne(binding);
            } catch (Exception e) {
                metrics.recordFailure("transient");
                log.warn("[SubwayRing] 링 갱신 실패 — journeyLegId={}: {}",
                        binding.getJourneyLegId(), e.getMessage(), e);
            }
        }
        return batch.size();
    }

    private void processOne(SubwayLegBinding binding) {
        JourneyLeg ride = legRepository.findById(binding.getJourneyLegId()).orElse(null);
        if (ride == null) {
            return;
        }
        SubwayRingChain chain = chainOf(binding, ride);
        if (!chain.isUsable()) {
            // 역 체인을 못 읽으면 남은 역 수도 다음 역도 만들 수 없다. 계획값으로 지어내지 않는다.
            return;
        }
        // 역에서 고르고 아직 안 움직였으면 열차가 승차역에 오기 전일 수 있다 — 승차역부터 본다(#304).
        int start = binding.isSelectedBeforeMovement() ? 0 : 1;
        SubwayRingContext context = new SubwayRingContext(binding.getJourneyId(),
                binding.getRouteRevision(), binding.getLegIndex(), binding.getVersion(), chain, start);

        SubwayRingState previous = ringPort.find(binding.getJourneyId(),
                binding.getRouteRevision(), binding.getLegIndex(), binding.getVersion())
                .orElse(null);
        int target = calculator.targetStationSequence(previous, context);
        if (target < 0) {
            // 체인을 쓸 수 없다. 0 은 승차역이다(#304) — 열차를 기다리는 링이라 거르지 않는다.
            return;
        }

        TrainIdentity train = identityOf(binding);
        Instant now = clock.instant();
        SubwayRingObservation observation = train == null
                ? null
                : seen(previous, signalReader.read(train, chain, target), now);
        if (train != null) {
            java.util.Map<Integer, SubwayRingContext.Typical> memo = new java.util.HashMap<>();
            context = context.withTypical(seq -> memo.computeIfAbsent(seq, k -> typicalOf(chain, train, k)));
        }

        SubwayRingState state = calculator.next(previous, context, observation, now);
        if (state == null) {
            return;
        }
        state = withAlighting(state, chain, train);
        state = state.withGuidanceVersion(guidanceVersionOf(previous, state, now));
        ringPort.save(state);
        metrics.recordUpdated(state, observation, binding.getStatus());
        extendProtection(binding, ride, state);
        // 고정 열차가 역을 하나 지났다 — 재탐색 보드를 바로 다시 찾게 한다(#264 PR-2).
        if (state.advancedFrom(previous)) {
            rerouteRefreshSignal.requestNow(binding.getJourneyId());
        }

        // 원격 갱신은 링 저장과 규칙이 다르다 — 링은 latest-wins 로 덮어써도 되지만 push 는
        // 화면이 실제로 바뀔 때만, 그리고 최신인 동안만 재시도한다. 트랜잭션도 따로 연다.
        pushEnqueueService.enqueueIfChanged(previous, state, ride);
    }

    /**
     * 하차 도착 예정 + 여유로 오프라인 보호를 민다(상한 = 탑승 시작 + cap, {@link SubwayOfflineProtectionPolicy} 와 같은 식).
     * 고르면 즉시 탑승이라 계획 소요시간 기준 보호가 승강장에서 기다린 만큼 모자란다. 실패해도 링 갱신은 계속한다.
     */
    private void extendProtection(SubwayLegBinding binding, JourneyLeg ride, SubwayRingState state) {
        Instant alightingAt = state.estimatedAlightingAt();
        if (alightingAt == null || ride.getActivatedAt() == null) {
            return;
        }
        Instant until = alightingAt.plus(navigationProperties.subwayProtectionBuffer());
        Instant cap = ride.getActivatedAt().plus(navigationProperties.subwayProtectionCap());
        try {
            sessionRepository.extendSubwayProtection(binding.getJourneyId(), binding.getRouteRevision(),
                    binding.getLegIndex(), until.isBefore(cap) ? until : cap);
        } catch (RuntimeException e) {
            log.warn("[SubwayRing] 오프라인 보호 연장 실패 — journeyId={}: {}", binding.getJourneyId(), e.getMessage());
        }
    }

    /**
     * 화면이 바뀌었을 때만 버전을 올린다(#307). 앱은 이 값으로 조건부 조회(204)를 가르고, 늦게 온 옛 스냅샷을 버린다.
     *
     * <p>"바뀜"은 push 와 같은 기준이다({@link SubwayRingPushDecider}) — 다음 역·국면·품질·도착·앵커 지문. 매 tick
     * 올리면 204 가 무의미해진다. 값은 시각이라 고정 세대가 바뀌어 키가 새로 생겨도 단조다. 같은 millis 면 +1.
     */
    private long guidanceVersionOf(SubwayRingState previous, SubwayRingState state, Instant now) {
        long prior = previous == null ? 0L : previous.guidanceVersion();
        if (prior > 0 && pushDecider.decide(previous, state).isEmpty()) {
            return prior;
        }
        return Math.max(now.toEpochMilli(), prior + 1);
    }

    /**
     * 서버가 이 근거를 처음 본 시각을 붙인다. 원천 시각이 직전 링의 근거와 같으면 그때 본 시각을 이어 쓴다 — 매 tick 지금으로
     * 찍으면 원천이 굳어 있어도 늘 신선해 보인다(수집 시각 {@code collectedAt} 은 매 회차 새로 찍히므로 이 용도로 못 쓴다).
     */
    private static SubwayRingObservation seen(SubwayRingState previous, SubwayRingObservation observation, Instant now) {
        if (observation == null || observation.observedAt() == null) {
            return observation;
        }
        boolean same = previous != null && previous.sourceSeenAt() != null
                && observation.observedAt().equals(previous.sourceObservedAt());
        return observation.withSeenAt(same ? previous.sourceSeenAt() : now);
    }

    /** {@code target} 역으로 들어가는 구간의 GTFS 계획 시간. 승차역 대기 구간·모르는 구간은 null — 계산기가 기존대로 둔다. */
    private SubwayRingContext.Typical typicalOf(SubwayRingChain chain, TrainIdentity train, int target) {
        SubwayRingChain.Station from = chain.at(target - 1);
        SubwayRingChain.Station to = chain.at(target);
        if (from == null || to == null) {
            return null;
        }
        return lineOrder.segmentTimes(train.lineId(), train.direction(), from.canonicalKey(), to.canonicalKey())
                .map(t -> new SubwayRingContext.Typical(t.dwellSeconds(), t.runSeconds()))
                .orElse(null);
    }

    /**
     * 하차 예정과 출처. 다음 역이 하차역이면 다음 역 예정의 출처 그대로, 아니면 남은 구간이 GTFS 계획이라 TYPICAL 이다 —
     * 다음 역 예정이 실시간이어도 하차까지 전부를 실시간이라 말하면 과장이다.
     */
    private SubwayRingState withAlighting(SubwayRingState state, SubwayRingChain chain, TrainIdentity train) {
        Instant alightingAt = alightingAtOf(state, chain, train);
        boolean nextIsAlighting = state.nextStationSequence() == chain.alightingSequence();
        return state.withEstimatedAlightingAt(alightingAt,
                nextIsAlighting ? state.etaSource() : SubwayRingEtaSource.TYPICAL);
    }

    /**
     * 하차역 도착 예정(#310) = 다음 역 도착 예정 + GTFS 계획 초(다음 역 도착 → 하차역 도착). 사용자 결정(2026-10-10): 남은
     * 구간은 시간표만 쓴다 — 지연은 다음 역 예정에만 반영된다. 다음 역이 하차역이면 그 예정 그대로.
     *
     * <p>다음 역 예정이 없거나(멈춘 링) 역·계획을 모르면 null — 지어내지 않는다.
     */
    private Instant alightingAtOf(SubwayRingState state, SubwayRingChain chain, TrainIdentity train) {
        Instant next = state.estimatedNextStationAt();
        if (next == null || train == null) {
            return null;
        }
        SubwayRingChain.Station nextStation = chain.at(state.nextStationSequence());
        SubwayRingChain.Station alighting = chain.at(chain.alightingSequence());
        if (nextStation == null || alighting == null) {
            return null;
        }
        if (state.stage() == SubwayRingStage.DWELL && state.etaSource() == SubwayRingEtaSource.TYPICAL) {
            // 정차 직선의 예정은 정차 끝이다 — 다음 역 도착은 그 뒤 운행 시간만큼 더 걸린다(리뷰 R3).
            SubwayRingContext.Typical times = typicalOf(chain, train, state.nextStationSequence());
            if (times == null || times.runSeconds() == null) {
                return null;
            }
            next = next.plusMillis(java.math.BigDecimal.valueOf(times.runSeconds() * 1000L)
                    .multiply(properties.getTypicalFactor()).longValue());
        }
        if (nextStation.sequence() == alighting.sequence()) {
            return next;
        }
        return lineOrder.arrivalGapSeconds(train.lineId(), train.direction(),
                        nextStation.canonicalKey(), alighting.canonicalKey())
                .map(next::plusSeconds)
                .orElse(null);
    }

    /**
     * 계획 역 이름을 {@code statnId} 체인으로 바꾼다.
     *
     * <p>이름 해석은 여기서 한 번만 한다. 그 뒤의 판정은 전부 {@code statnId} 로만 하므로 역명
     * 표기가 흔들려도({@code 성수}/{@code 성수종착}) 링이 리셋되지 않는다.
     */
    private SubwayRingChain chainOf(SubwayLegBinding binding, JourneyLeg ride) {
        List<String> names = stationChainReader.stationNames(binding.getJourneyId(),
                binding.getRouteRevision(), ride.getLegIndex());
        return stationResolver.chainOf(ride.getRouteId(), names);
    }

    /** 열차 identity 는 네 값이 함께 있어야 한다. 하나만 비어도 어느 열차도 가리키지 못한다. */
    private TrainIdentity identityOf(SubwayLegBinding binding) {
        if (binding.getTrainNo() == null || binding.getTrainServiceDate() == null
                || binding.getTrainLineId() == null || binding.getTrainDirection() == null) {
            return null;
        }
        return new TrainIdentity(binding.getTrainServiceDate(), binding.getTrainLineId(),
                binding.getTrainDirection(), binding.getTrainNo());
    }
}
