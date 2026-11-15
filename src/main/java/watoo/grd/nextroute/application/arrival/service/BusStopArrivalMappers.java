package watoo.grd.nextroute.application.arrival.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiStopArrival;
import watoo.grd.nextroute.application.arrival.dto.SeoulStationArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoStopArrival;

/**
 * 정류장 단위 원본 → 노선 한 줄(#577). 칸의 뜻은 노선 단위 API 와 같게 둔다: 서울은 {@link SeoulArrivalRules},
 * 경기는 {@link GyeonggiArrivalMapper}, TAGO 는 {@link TagoArrivalMapper} 를 그대로 쓴다.
 */
final class BusStopArrivalMappers {

    /** 시간이 있는 줄을 먼저(남은 초 순), 없는 줄(운행종료, 출발대기, 정보 없음)은 뒤에 노선 이름 순. */
    static final Comparator<BusStopArrivalResponse> ORDER = Comparator
            .comparing(BusStopArrivalResponse::predictTime1, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(BusStopArrivalResponse::routeName, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(BusStopArrivalResponse::routeId, Comparator.nullsLast(Comparator.naturalOrder()));

    private BusStopArrivalMappers() {
    }

    /**
     * 서울. 정류소 번호(arsId) 하나에 정류장 id(stId)가 여럿일 수 있어 요청 stId 의 행만 쓴다. 순환 노선이 같은 노선 id 로
     * 두 행이면 첫 버스가 빠른 쪽.
     */
    static List<BusStopArrivalResponse> seoul(List<SeoulStationArrival> rows, String stId) {
        Map<String, BusStopArrivalResponse> byRoute = new LinkedHashMap<>();
        for (SeoulStationArrival row : rows) {
            if (row.busRouteId() == null || !Objects.equals(stId, row.stId())) {
                continue;
            }
            BusStopArrivalResponse mapped = seoul(row);
            byRoute.merge(row.busRouteId(), mapped, (a, b) -> ORDER.compare(a, b) <= 0 ? a : b);
        }
        return List.copyOf(byRoute.values());
    }

    static BusStopArrivalResponse seoul(SeoulStationArrival row) {
        String vehicle1 = SeoulArrivalRules.vehicleId(row.vehId1());
        String vehicle2 = SeoulArrivalRules.vehicleId(row.vehId2());
        return BusStopArrivalResponse.builder()
                .routeId(row.busRouteId())
                .routeName(row.routeName())
                .routeType(row.routeType())
                .routeTypeSource(BusRouteTypeNames.SOURCE_SEOUL)
                .routeTypeName(BusRouteTypeNames.seoul(row.routeType()))
                .direction(blankToNull(row.adirection()))
                .arrivalMsg1(row.arrivalMsg1())
                .predictTime1(SeoulArrivalRules.predictSeconds(row.traTime1(), vehicle1))
                .congestionNum1(row.congestion1())
                .stopsAway1(SeoulArrivalRules.stopsAway(row.staOrd(), row.sectOrd1(), vehicle1))
                .vehicleId1(vehicle1)
                .arrivalMsg2(row.arrivalMsg2())
                .predictTime2(SeoulArrivalRules.predictSeconds(row.traTime2(), vehicle2))
                .congestionNum2(row.congestion2())
                .stopsAway2(SeoulArrivalRules.stopsAway(row.staOrd(), row.sectOrd2(), vehicle2))
                .vehicleId2(vehicle2)
                .build();
    }

    static List<BusStopArrivalResponse> gyeonggi(List<GyeonggiStopArrival> rows) {
        List<BusStopArrivalResponse> out = new ArrayList<>();
        for (GyeonggiStopArrival row : rows) {
            if (row.arrival() == null || row.arrival().routeId() == null) {
                continue;
            }
            BusArrivalResponse base = GyeonggiArrivalMapper.toResponse(row.arrival());
            out.add(wrap(base, row.routeName(), row.routeTypeCd(), BusRouteTypeNames.SOURCE_GYEONGGI,
                    BusRouteTypeNames.gyeonggi(row.routeTypeCd()), row.routeDestName()));
        }
        return List.copyOf(out);
    }

    /** TAGO 는 버스 한 대가 한 줄이라 노선으로 묶는다. 다가오는 버스가 없는 노선은 원본에 없다(운행종료 표시 불가). */
    static List<BusStopArrivalResponse> tago(List<TagoStopArrival> rows) {
        Map<String, List<TagoBusArrival>> buses = new LinkedHashMap<>();
        Map<String, TagoStopArrival> firstRow = new LinkedHashMap<>();
        for (TagoStopArrival row : rows) {
            if (row.bus() == null || row.bus().routeId() == null) {
                continue;
            }
            buses.computeIfAbsent(row.bus().routeId(), k -> new ArrayList<>()).add(row.bus());
            firstRow.putIfAbsent(row.bus().routeId(), row);
        }
        List<BusStopArrivalResponse> out = new ArrayList<>();
        buses.forEach((routeId, list) -> {
            Optional<BusArrivalResponse> base = TagoArrivalMapper.toResponse(routeId, list);
            TagoStopArrival meta = firstRow.get(routeId);
            base.ifPresent(b -> out.add(wrap(b, meta.routeNo(), null, BusRouteTypeNames.SOURCE_TAGO,
                    blankToNull(meta.routeTp()), null)));
        });
        return List.copyOf(out);
    }

    /**
     * 공급자 여럿의 줄을 합친다. 같은 노선 id 는 한 줄 — 서울 정류장 단위 응답에도 경기 노선이 경기 노선 id 그대로 섞여
     * 오므로(2026-11-16 실응답, {@code 222000073} 1700남양주) 공급자는 보지 않고 노선 id 로만 묶는다. 둘 중 첫 버스가 더
     * 빨리 오는 줄(시간이 있는 줄)을 남긴다. 노선 번호는 지역 사이에 겹치므로(경기 시내 "1") 보지 않는다.
     */
    static List<BusStopArrivalResponse> mergeSorted(List<List<BusStopArrivalResponse>> lists) {
        Map<String, BusStopArrivalResponse> byRoute = new LinkedHashMap<>();
        for (List<BusStopArrivalResponse> list : lists) {
            for (BusStopArrivalResponse row : list) {
                byRoute.merge(row.routeId(), row, (a, b) -> ORDER.compare(a, b) <= 0 ? a : b);
            }
        }
        List<BusStopArrivalResponse> merged = new ArrayList<>(byRoute.values());
        merged.sort(ORDER);
        return List.copyOf(merged);
    }

    private static BusStopArrivalResponse wrap(BusArrivalResponse b, String routeName, Integer routeType,
                                               String source, String typeName, String direction) {
        return BusStopArrivalResponse.builder()
                .routeId(b.getRouteId())
                .routeName(routeName)
                .routeType(routeType)
                .routeTypeSource(source)
                .routeTypeName(typeName)
                .direction(blankToNull(direction))
                .arrivalMsg1(b.getArrivalMsg1())
                .predictTime1(b.getPredictTime1())
                .congestionNum1(b.getCongestionNum1())
                .stopsAway1(b.getStopsAway1())
                .vehicleId1(b.getVehicleId1())
                .arrivalMsg2(b.getArrivalMsg2())
                .predictTime2(b.getPredictTime2())
                .congestionNum2(b.getCongestionNum2())
                .stopsAway2(b.getStopsAway2())
                .vehicleId2(b.getVehicleId2())
                .build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
