package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;
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
 * 채운 내부 정류장 ID 로 찾는다. 경기·지방은 정류장 표({@code regional_bus_route_stop.stop_no}, V64)에서 찾는다.
 *
 * <p>부르는 곳: 여정 시작({@code JourneyStartService}), 재탐색 합성({@code JourneyRevisionComposer}), 단독 추적
 * ({@code TrackJourneyStartService}). 조회가 실패해도 여정 시작을 막지 않는다 — 번호는 덧붙이는 값이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneyGuidanceInfoEnricher {

    private final BusStopRepository busStopRepository;
    private final RegionalBusRouteStopStore regionalStopStore;

    public JourneyPlan enrich(JourneyPlan plan) {
        Map<String, String> arsByStopId = seoulStopNumbers(plan.legs());
        Map<String, String> regionalByStopId = regionalStopNumbers(plan.legs(), arsByStopId);
        if (arsByStopId.isEmpty() && regionalByStopId.isEmpty()) {
            return plan;
        }
        List<JourneyLegDraft> legs = plan.legs().stream()
                .map(leg -> isBus(leg.type())
                        ? leg.withGuidance(leg.guidance().withStopNos(
                                number(leg.startStopId(), leg.sourceStartStopId(), arsByStopId, regionalByStopId),
                                number(leg.endStopId(), leg.sourceEndStopId(), arsByStopId, regionalByStopId)))
                        : leg)
                .toList();
        return new JourneyPlan(legs, plan.transfers());
    }

    /** 서울이 먼저(내부 ID 로 확인된 값), 없으면 경기·지방 표(출발 원천 ID 를 정류장 표 ID 로 맞춘 값). */
    private static String number(String stopId, String sourceStopId, Map<String, String> seoul,
                                 Map<String, String> regional) {
        String ars = stopId == null ? null : seoul.get(stopId);
        if (ars != null) {
            return ars;
        }
        String regionalId = regionalStopId(sourceStopId);
        return regionalId == null ? null : regional.get(regionalId);
    }

    /**
     * 출발 원천 ID → {@code regional_bus_route_stop.stop_id}. 원천 ID 는 두 모양이 섞여 온다 — 검색 결과 변환이 접두사를
     * 뗀 값(경기 GBIS 9자리, TAGO {@code DJB8001783})과, 그게 실패했을 때의 OTP 원형({@code 1:BS_TAGO_GGB202000120}).
     * 서울({@code SEB})은 null — 서울 번호는 {@code bus_stop} 몫이다.
     */
    static String regionalStopId(String sourceStopId) {
        if (sourceStopId == null || sourceStopId.isBlank()) {
            return null;
        }
        String id = sourceStopId.trim();
        int otp = id.indexOf("BS_TAGO_");
        if (otp >= 0) {
            id = id.substring(otp + "BS_TAGO_".length());
        }
        if (id.startsWith("SEB")) {
            return null;
        }
        if (id.startsWith("GGB")) {
            return id.substring(3);
        }
        return id;
    }

    private Map<String, String> regionalStopNumbers(List<JourneyLegDraft> legs, Map<String, String> seoul) {
        Set<String> ids = new LinkedHashSet<>();
        for (JourneyLegDraft leg : legs) {
            if (!isBus(leg.type())) {
                continue;
            }
            if (leg.startStopId() == null || !seoul.containsKey(leg.startStopId())) {
                addIfPresent(ids, regionalStopId(leg.sourceStartStopId()));
            }
            if (leg.endStopId() == null || !seoul.containsKey(leg.endStopId())) {
                addIfPresent(ids, regionalStopId(leg.sourceEndStopId()));
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            return regionalStopStore.stopNumbers(ids);
        } catch (RuntimeException e) {
            log.warn("[Journey] 경기·지방 정류장 번호 조회 실패 — 번호 없이 진행 stops={}: {}", ids.size(), e.getMessage());
            return Map.of();
        }
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
