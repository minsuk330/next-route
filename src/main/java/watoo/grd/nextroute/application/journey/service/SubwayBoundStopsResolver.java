package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.ResolvedPattern;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 여정 경로 모양(geometry)의 지하철 역 목록을 <b>묶인 열차 기준</b>으로 바꾸는 데 필요한 것(subway-express PR-S4).
 *
 * <ul>
 *   <li>{@link #bindingsOf} — 지하철 타는 단계의 묶인(열차가 정해진) 고정</li>
 *   <li>{@link #patternOf} — 급행 고정의 정차 패턴(바인딩 때 해석한 서비스 그대로, 링과 같은 계산)</li>
 *   <li>{@link #versionToken} — geometry 버전 키의 바인딩 판</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayBoundStopsResolver {

    private final SubwayLegBindingRepository bindingRepository;
    private final SubwayStopPatternResolver patternResolver;

    /** 지하철 타는 단계 id → 열차가 정해진 고정. 고정이 없거나 열차를 아직 못 골랐으면 빠진다. */
    public Map<Long, SubwayLegBinding> bindingsOf(List<JourneyLeg> legs) {
        List<Long> rideIds = legs.stream()
                .filter(leg -> leg.getType() == JourneyLegType.RIDE_SUBWAY && leg.getId() != null)
                .map(JourneyLeg::getId)
                .toList();
        if (rideIds.isEmpty()) {
            return Map.of();
        }
        try {
            return bindingRepository.findByJourneyLegIdIn(rideIds).stream()
                    .filter(binding -> binding.getStatus() != null && binding.getStatus().isBound()
                            && binding.getTrainNo() != null)
                    .collect(Collectors.toMap(SubwayLegBinding::getJourneyLegId, b -> b, (a, b) -> a,
                            LinkedHashMap::new));
        } catch (RuntimeException e) {
            // geometry 는 바인딩 없이도 그릴 수 있다(계획 정차열) — 실패를 응답 실패로 번지지 않게 한다
            log.warn("[Geometry] 지하철 고정 조회 실패: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 급행 고정의 정차 패턴. 급행이 아니거나 못 만들면 empty. */
    public Optional<ResolvedPattern> patternOf(SubwayLegBinding binding) {
        if (binding == null || binding.getTrainKind() != SubwayBindingTrainKind.EXPRESS) {
            return Optional.empty();
        }
        try {
            return patternResolver.resolveService(binding.getTrainServiceId(), binding.getTrainLineId(),
                    binding.getTrainDirection(), binding.getTrainNo(), null);
        } catch (RuntimeException e) {
            log.warn("[Geometry] 급행 패턴 해석 실패 service={}: {}", binding.getTrainServiceId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * geometry 버전 키의 바인딩 판. 단계 순서대로 {@code {legIndex}.{version}{종류 머리글자}} 를 {@code -} 로 잇는다
     * (예 {@code 1.0E}, {@code 1.2N-4.0E}). 종류가 배포 전 행이라 아직 없으면 머리글자를 비운다 — 링이 분류하면 키가 바뀌어
     * 앱이 다시 받는다. 고정이 없으면 null(키에 붙이지 않는다 — S2 형식 그대로).
     */
    public static String versionToken(Collection<SubwayLegBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return null;
        }
        return bindings.stream()
                .sorted(Comparator.comparingInt(SubwayLegBinding::getLegIndex))
                .map(b -> b.getLegIndex() + "." + b.getVersion()
                        + (b.getTrainKind() == null ? "" : b.getTrainKind().name().substring(0, 1)))
                .collect(Collectors.joining("-"));
    }
}
