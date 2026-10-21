package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.ResolvedPattern;
import watoo.grd.nextroute.application.subway.service.SubwayTrainKindClassifier;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 묶인 열차의 종류와 급행 정차 패턴(subway-express PR-S4). 링 체인·구간 시간·라벨이 이것을 쓴다.
 *
 * <h2>배포 전 행(종류 null)은 한 번 분류한다</h2>
 *
 * <p>S3 전에는 급행도 바인딩됐다 — null 을 일반으로 보지 않는다. 링 갱신 때 이 열차의 실시간 근거(도착 스냅숏의 모든 행,
 * 직전 위치)로 {@link SubwayTrainKindClassifier#classifyTrain} 을 한 번 돌려 V72 칸에 적는다(조건부 UPDATE, 세대 불변).
 * 급행이면 서비스를 해석하고, 못 하면 UNKNOWN — 링은 계획 체인으로 돈다(예전 동작). 근거가 하나도 없어도 UNKNOWN 이다.
 *
 * <h2>급행 패턴은 바인딩 때의 서비스로 다시 만든다</h2>
 *
 * <p>{@link SubwayStopPatternResolver#resolveService} — 서비스 id 는 바꾸지 않고 열차별 예외 정차만 번호로 지금 계산한다.
 * 못 만들면(서비스가 카탈로그에서 사라짐·각역 구간 모름) 지표 {@code subway.express.unresolved{stage=ring}} 을 올리고 패턴
 * 없이 돌려준다 — 체인 빌더가 빈 체인을 만들어 링을 그리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayBoundTrainResolver {

    static final String UNRESOLVED_METRIC = "subway.express.unresolved";

    private final SubwayLegBindingRepository bindingRepository;
    private final SubwayTrainKindClassifier classifier;
    private final SubwayStopPatternResolver patternResolver;
    private final SubwayRealtimeCachePort realtimeCachePort;
    private final SubwayTrainLogPort trainLogPort;
    private final MeterRegistry meterRegistry;

    /**
     * @param kind    묶인 열차 종류. 식별이 없거나 분류 전이면 null
     * @param pattern 급행이면 해석된 정차 패턴, 못 하면 null
     */
    public record BoundTrain(SubwayBindingTrainKind kind, ResolvedPattern pattern) {

        static final BoundTrain NONE = new BoundTrain(null, null);

        /** 링·push·스냅숏의 {@code trainKind}. 모르면 null. */
        public String kindName() {
            return kind == null ? null : kind.name();
        }

        /** 표시 이름(D8: 급행은 "급행" 하나). 모름은 null. */
        public String label() {
            if (kind == null) {
                return null;
            }
            return switch (kind) {
                case NORMAL -> TrainKind.NORMAL.label();
                case EXPRESS -> TrainKind.EXPRESS.label();
                case UNKNOWN -> null;
            };
        }
    }

    /** @param train 바인딩의 열차 식별. 없으면 종류도 없다 */
    public BoundTrain resolve(SubwayLegBinding binding, TrainIdentity train) {
        if (binding == null || train == null) {
            return BoundTrain.NONE;
        }
        if (binding.getTrainKind() == null) {
            classifyOnce(binding, train);
        }
        SubwayBindingTrainKind kind = binding.getTrainKind();
        if (kind != SubwayBindingTrainKind.EXPRESS) {
            return new BoundTrain(kind, null);
        }
        Optional<ResolvedPattern> pattern;
        try {
            pattern = patternResolver.resolveService(binding.getTrainServiceId(), train.lineId(), train.direction(),
                    train.trainNo(), null);
        } catch (RuntimeException e) {
            log.warn("[SubwayRing] 급행 패턴 해석 실패 journeyId={} service={}: {}", binding.getJourneyId(),
                    binding.getTrainServiceId(), e.getMessage());
            pattern = Optional.empty();
        }
        if (pattern.isEmpty()) {
            unresolved(train.lineId(), "ring");
        }
        return new BoundTrain(kind, pattern.orElse(null));
    }

    /** 배포 전 행을 실시간 근거로 한 번 분류해 적는다. 실패해도 링은 이번 tick 을 미분류(계획 체인)로 돈다. */
    private void classifyOnce(SubwayLegBinding binding, TrainIdentity train) {
        try {
            Evidence evidence = evidenceOf(train);
            TrainKind kind = classifier.classifyTrain(train.lineId(), train.trainNo(), evidence.sightings());
            SubwayBindingTrainKind bound;
            ResolvedPattern pattern = null;
            if (kind == TrainKind.NORMAL) {
                bound = SubwayBindingTrainKind.NORMAL;
            } else if (kind == TrainKind.EXPRESS) {
                pattern = patternResolver.resolve(train.lineId(), train.direction(), train.trainNo(),
                        evidence.destination(), evidence.currentStationId()).orElse(null);
                bound = pattern == null ? SubwayBindingTrainKind.UNKNOWN : SubwayBindingTrainKind.EXPRESS;
                if (pattern == null) {
                    unresolved(train.lineId(), "classify");
                }
            } else {
                // 근거 없음·직통·ITX(옛 행에만 있을 수 있다) — 계획 체인 그대로(예전 동작)
                bound = SubwayBindingTrainKind.UNKNOWN;
            }
            String serviceId = pattern == null ? null : pattern.serviceId();
            String version = pattern == null ? null : pattern.catalogVersion();
            int updated = bindingRepository.classifyIfUnclassified(binding.getId(), binding.getVersion(), bound.name(),
                    serviceId, version);
            if (updated == 1) {
                binding.classifyIfUnclassified(bound, serviceId, version);
                log.info("[SubwayRing] 배포 전 바인딩 분류 journeyId={} train={} kind={} service={} sightings={}",
                        binding.getJourneyId(), train.trainNo(), bound, serviceId, evidence.sightings().size());
            }
        } catch (RuntimeException e) {
            log.warn("[SubwayRing] 바인딩 분류 실패 journeyId={}: {}", binding.getJourneyId(), e.getMessage());
        }
    }

    private record Evidence(List<SubwayTrainKindClassifier.Sighting> sightings, String destination,
                            String currentStationId) {
    }

    /** 이 열차의 도착 스냅숏 행 전부(행마다 trainType 이 다를 수 있다)와 직전 위치. */
    private Evidence evidenceOf(TrainIdentity train) {
        List<SubwayTrainKindClassifier.Sighting> sightings = new ArrayList<>();
        String destination = null;
        Optional<SubwayRealtimeSnapshot> snapshot = realtimeCachePort.readSnapshot();
        if (snapshot.isPresent() && snapshot.get().getTrains() != null) {
            for (SubwayRealtimeTrain row : snapshot.get().getTrains()) {
                if (train.trainNo().equals(row.getTrainNo()) && train.lineId().equals(row.getLineId())
                        && (train.direction() == null || train.direction().equals(row.getDirection()))) {
                    sightings.add(SubwayTrainKindClassifier.Sighting.ofTrainType(row.getTrainType()));
                    if (destination == null) {
                        destination = row.getDestinationName();
                    }
                }
            }
        }
        String current = null;
        Optional<SubwayTrainObservation> seen = trainLogPort.lastSeen(train);
        if (seen.isPresent()) {
            sightings.add(SubwayTrainKindClassifier.Sighting.ofDirectAt(seen.get().express(), seen.get().directAtRaw()));
            current = seen.get().stationId();
            if (destination == null) {
                destination = seen.get().terminalId();
            }
        }
        return new Evidence(sightings, destination, current);
    }

    private void unresolved(String lineId, String stage) {
        meterRegistry.counter(UNRESOLVED_METRIC, "line", lineId == null ? "unknown" : lineId, "stage", stage)
                .increment();
    }
}
