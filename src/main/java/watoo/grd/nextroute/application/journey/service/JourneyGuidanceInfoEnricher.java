package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;
import watoo.grd.nextroute.application.subway.service.SubwayEntranceIndex;
import watoo.grd.nextroute.application.subway.service.SubwayDoorSideIndex;
import watoo.grd.nextroute.application.subway.service.SubwayFastAlightIndex;
import watoo.grd.nextroute.application.subway.service.SubwayLineEnds;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final SubwayEntranceIndex entranceIndex;
    private final SubwayFastAlightIndex fastAlightIndex;
    private final SubwayDoorSideIndex doorSideIndex;
    private final SubwayLineEnds lineEnds;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public JourneyPlan enrich(JourneyPlan plan) {
        return withDoorSide(withFastAlight(withSubwayExits(withBusStopNumbers(plan))));
    }

    /**
     * 내리는 문 방향(#335). 지하철 탑승 단계마다 노선(대기 단계 후보의 내부 line_id 가 하나로 정해질 때) + 하차역으로
     * 찾는다. 하차역이 노선 끝이거나 끝인지 모르면 null — 종착역은 도착 선로가 번갈아 바뀌어 문이 뒤집힌다.
     */
    private JourneyPlan withDoorSide(JourneyPlan plan) {
        if (plan.legs().stream().noneMatch(leg -> leg.type() == JourneyLegType.RIDE_SUBWAY)) {
            return plan;
        }
        Map<Integer, JourneyLegDraft> byIndex = new HashMap<>();
        plan.legs().forEach(leg -> byIndex.put(leg.legIndex(), leg));
        try {
            List<JourneyLegDraft> legs = plan.legs().stream()
                    .map(leg -> leg.type() == JourneyLegType.RIDE_SUBWAY ? withDoorSide(leg, byIndex) : leg)
                    .toList();
            return new JourneyPlan(legs, plan.transfers());
        } catch (RuntimeException e) {
            log.warn("[Journey] 내리는 문 방향 계산 실패 — 없이 진행: {}", e.getMessage());
            return plan;
        }
    }

    private JourneyLegDraft withDoorSide(JourneyLegDraft ride, Map<Integer, JourneyLegDraft> byIndex) {
        JourneyLegDraft wait = ride.pairedLegIndex() == null ? null : byIndex.get(ride.pairedLegIndex());
        if (wait == null) {
            return ride;
        }
        List<String> lineIds = wait.routeOptions().stream()
                .map(option -> option.routeId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (lineIds.size() != 1) {
            return ride;
        }
        String lineId = lineIds.get(0);
        if (!lineEnds.isLineEnd(lineId, ride.endName()).equals(Optional.of(false))) {
            return ride;
        }
        return doorSideIndex.lookup(lineId, ride.endName())
                .map(side -> ride.withGuidance(ride.guidance().withAlightDoorSide(side.name())))
                .orElse(ride);
    }

    /**
     * 빠른 하차 칸·문(#266 PR-4). 지하철 탑승 단계마다 호선(대기 단계 후보의 내부 line_id, 1~8호선만) + 하차역 + 하차
     * 직전 역으로 찾는다. 서울교통공사 구간 밖이거나 방향을 못 정하면 null.
     */
    private JourneyPlan withFastAlight(JourneyPlan plan) {
        if (plan.legs().stream().noneMatch(leg -> leg.type() == JourneyLegType.RIDE_SUBWAY)) {
            return plan;
        }
        Map<Integer, JourneyLegDraft> byIndex = new HashMap<>();
        plan.legs().forEach(leg -> byIndex.put(leg.legIndex(), leg));
        try {
            List<JourneyLegDraft> legs = plan.legs().stream()
                    .map(leg -> leg.type() == JourneyLegType.RIDE_SUBWAY ? withFastAlight(leg, byIndex) : leg)
                    .toList();
            return new JourneyPlan(legs, plan.transfers());
        } catch (RuntimeException e) {
            log.warn("[Journey] 빠른 하차 계산 실패 — 없이 진행: {}", e.getMessage());
            return plan;
        }
    }

    private JourneyLegDraft withFastAlight(JourneyLegDraft ride, Map<Integer, JourneyLegDraft> byIndex) {
        JourneyLegDraft wait = ride.pairedLegIndex() == null ? null : byIndex.get(ride.pairedLegIndex());
        Integer line = wait == null ? null : wait.routeOptions().stream()
                .map(option -> SubwayFastAlightIndex.lineNoOfLineId(option.routeId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .reduce((a, b) -> -1)
                .filter(n -> n > 0)
                .orElse(null);
        if (line == null) {
            return ride;
        }
        List<SubwayFastAlightIndex.FastAlight> found =
                fastAlightIndex.lookup(line, ride.endName(), ride.alightPrevName());
        if (found.isEmpty()) {
            return ride;
        }
        try {
            return ride.withGuidance(ride.guidance().withFastAlightJson(JSON.writeValueAsString(found)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ride;
        }
    }

    private JourneyPlan withBusStopNumbers(JourneyPlan plan) {
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

    /**
     * 지하철 출구 번호(#266 PR-3). 앞뒤 <b>단계 종류</b>로 정한다.
     * <ul>
     *   <li>하차 출구(탑승 단계 {@code endExitNo}): 바로 다음이 걷기이고 그 다음이 지하철 대기가 아닐 때, 하차역 출입구 중
     *       그 걷기의 끝에 가장 가까운 것. 다음이 바로 대기(같은 정류장 환승)거나 걷기 뒤 지하철 대기(지하철 환승)면 null</li>
     *   <li>승차 출구(대기·탑승 단계 {@code startExitNo}): 바로 앞이 걷기이고 그 앞이 지하철 탑승이 아닐 때, 승차역 출입구
     *       중 그 걷기의 시작에 가장 가까운 것</li>
     * </ul>
     * 좌표는 draft 의 x=경도, y=위도. 출구를 못 고르면 null 이다.
     */
    private JourneyPlan withSubwayExits(JourneyPlan plan) {
        List<JourneyLegDraft> legs = plan.legs();
        if (legs.stream().noneMatch(leg -> leg.type() == JourneyLegType.WAIT_SUBWAY)) {
            return plan;
        }
        List<JourneyLegDraft> out = new java.util.ArrayList<>(legs);
        try {
            for (int i = 0; i < legs.size(); i++) {
                JourneyLegDraft leg = legs.get(i);
                if (leg.type() == JourneyLegType.WAIT_SUBWAY) {
                    String startExit = boardingExit(legs, i);
                    out.set(i, withExits(out.get(i), startExit, out.get(i).guidance().endExitNo()));
                    Integer ride = leg.pairedLegIndex();
                    if (ride != null && ride == i + 1 && i + 1 < legs.size()) {
                        JourneyLegDraft rideLeg = out.get(i + 1);
                        out.set(i + 1, withExits(rideLeg, startExit, alightingExit(legs, i + 1)));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Journey] 지하철 출구 계산 실패 — 출구 없이 진행: {}", e.getMessage());
            return plan;
        }
        return new JourneyPlan(out, plan.transfers());
    }

    private String boardingExit(List<JourneyLegDraft> legs, int waitIndex) {
        JourneyLegDraft wait = legs.get(waitIndex);
        JourneyLegDraft walk = waitIndex >= 1 ? legs.get(waitIndex - 1) : null;
        if (walk == null || !isWalk(walk.type())) {
            return null;
        }
        JourneyLegDraft before = waitIndex >= 2 ? legs.get(waitIndex - 2) : null;
        if (before != null && before.type() == JourneyLegType.RIDE_SUBWAY) {
            return null;
        }
        return entranceIndex.exitToward(wait.startName(), wait.startY(), wait.startX(), walk.startY(), walk.startX())
                .orElse(null);
    }

    private String alightingExit(List<JourneyLegDraft> legs, int rideIndex) {
        JourneyLegDraft ride = legs.get(rideIndex);
        JourneyLegDraft walk = rideIndex + 1 < legs.size() ? legs.get(rideIndex + 1) : null;
        if (walk == null || !isWalk(walk.type())) {
            return null;
        }
        JourneyLegDraft after = rideIndex + 2 < legs.size() ? legs.get(rideIndex + 2) : null;
        if (after != null && after.type() == JourneyLegType.WAIT_SUBWAY) {
            return null;
        }
        return entranceIndex.exitToward(ride.endName(), ride.endY(), ride.endX(), walk.endY(), walk.endX())
                .orElse(null);
    }

    private static JourneyLegDraft withExits(JourneyLegDraft leg, String startExit, String endExit) {
        return leg.withGuidance(leg.guidance().withExits(startExit, endExit));
    }

    private static boolean isWalk(JourneyLegType type) {
        return type == JourneyLegType.WALK || type == JourneyLegType.TRANSFER_WALK;
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
