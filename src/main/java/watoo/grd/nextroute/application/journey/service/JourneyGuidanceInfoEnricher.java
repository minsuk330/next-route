package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegGuidance;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 안내 시트 지점 줄 값 중 <b>저장 데이터 조회가 필요한 것</b>을 채운다(#266). 방면처럼 검색 결과만으로 정해지는 값은
 * {@link JourneyCompiler} 가 이미 넣었다.
 *
 * <p>{@link JourneyTransitIdEnricher} <b>다음</b>에 부른다 — 서울 정류장 번호는 그 단계가 {@code bus_stop} 존재를 확인해
 * 채운 내부 정류장 ID 로 찾는다. 출발 ID 의 접두사 규칙을 여기서 다시 쓰지 않는다.
 *
 * <p>부르는 곳: 여정 시작({@code JourneyStartService}), 재탐색 합성({@code JourneyRevisionComposer}), 단독 추적
 * ({@code TrackJourneyStartService}). 조회가 실패해도 여정 시작을 막지 않는다 — 번호는 덧붙이는 값이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneyGuidanceInfoEnricher {

    private final BusStopRepository busStopRepository;

    public JourneyPlan enrich(JourneyPlan plan) {
        Map<String, String> arsByStopId = seoulStopNumbers(plan.legs());
        if (arsByStopId.isEmpty()) {
            return plan;
        }
        List<JourneyLegDraft> legs = plan.legs().stream()
                .map(leg -> isBus(leg.type())
                        ? leg.withGuidance(leg.guidance().withStopNos(
                                arsByStopId.get(leg.startStopId()), arsByStopId.get(leg.endStopId())))
                        : leg)
                .toList();
        return new JourneyPlan(legs, plan.transfers());
    }

    private Map<String, String> seoulStopNumbers(List<JourneyLegDraft> legs) {
        Set<String> stopIds = new LinkedHashSet<>();
        for (JourneyLegDraft leg : legs) {
            if (isBus(leg.type())) {
                addIfPresent(stopIds, leg.startStopId());
                addIfPresent(stopIds, leg.endStopId());
            }
        }
        if (stopIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        try {
            for (BusStop stop : busStopRepository.findByStopIdIn(stopIds)) {
                String ars = stop.getArsId();
                if (ars != null && !ars.isBlank()) {
                    out.put(stop.getStopId(), ars.trim());
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Journey] 정류장 번호 조회 실패 — 번호 없이 진행 stops={}: {}", stopIds.size(), e.getMessage());
            return Map.of();
        }
        return out;
    }

    private static boolean isBus(JourneyLegType type) {
        return type == JourneyLegType.WAIT_BUS || type == JourneyLegType.RIDE_BUS;
    }

    private static void addIfPresent(Set<String> ids, String id) {
        if (id != null && !id.isBlank()) {
            ids.add(id);
        }
    }
}
