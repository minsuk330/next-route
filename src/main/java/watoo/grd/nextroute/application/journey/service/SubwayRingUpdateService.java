package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingContext;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.observability.SubwayRingMetrics;
import watoo.grd.nextroute.application.journey.port.out.SubwayRingPort;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.time.Clock;
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
    private final SubwayRingPort ringPort;
    private final SubwayRingMetrics metrics;
    private final JourneySubwayRingProperties properties;
    private final Clock clock;

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
        SubwayRingContext context = new SubwayRingContext(binding.getJourneyId(),
                binding.getRouteRevision(), binding.getLegIndex(), binding.getVersion(), chain);

        SubwayRingState previous = ringPort.find(binding.getJourneyId(),
                binding.getRouteRevision(), binding.getLegIndex(), binding.getVersion())
                .orElse(null);
        int target = calculator.targetStationSequence(previous, context);
        if (target < 1) {
            return;
        }

        TrainIdentity train = identityOf(binding);
        SubwayRingObservation observation = train == null
                ? null
                : signalReader.read(train, chain, target);

        SubwayRingState state = calculator.next(previous, context, observation, clock.instant());
        if (state == null) {
            return;
        }
        ringPort.save(state);
        metrics.recordUpdated(state, observation);

        // 원격 갱신은 링 저장과 규칙이 다르다 — 링은 latest-wins 로 덮어써도 되지만 push 는
        // 화면이 실제로 바뀔 때만, 그리고 최신인 동안만 재시도한다. 트랜잭션도 따로 연다.
        pushEnqueueService.enqueueIfChanged(previous, state, ride);
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
