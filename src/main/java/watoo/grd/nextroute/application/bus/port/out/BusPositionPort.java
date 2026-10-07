package watoo.grd.nextroute.application.bus.port.out;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * 노선 하나의 실시간 차량 위치(#265 단독 추적 버스 차량 대조). 원천 셋을 같은 모양으로 맞춘다.
 *
 * <ul>
 *   <li>서울 {@code getBusPosByRtid}: {@code vehId}·{@code plainNo}·{@code sectOrd}·{@code stopFlag}(1=정류소 도착)</li>
 *   <li>경기 {@code getBusLocationListv2}: {@code vehId}·{@code plateNo}·{@code stationSeq}·{@code stateCd}(2=정류소 출발)</li>
 *   <li>TAGO {@code getRouteAcctoBusLcList}: {@code vehicleno}·{@code nodeord}. 상태가 없다 — 순번만</li>
 * </ul>
 *
 * <p>순번은 원천 실시간 순번 공간이다(서울 = {@code bus_route_stop.seq}, 경기·TAGO = #267 표 {@code seq}).
 */
public interface BusPositionPort {

    /**
     * @param routeId  원천 노선 id(서울 9자리, 경기 GBIS 9자리, TAGO 접두사 포함)
     * @param cityCode TAGO 도시코드. 서울·경기는 무시
     */
    Snapshot positions(Provider provider, String routeId, String cityCode);

    enum Provider { SEOUL, GYEONGGI, TAGO }

    enum Outcome { OK, LIMITED, BLOCKED, ERROR }

    /**
     * @param vehicleId 대조·저장할 차량 식별. 서울·경기 = 원천 차량 id, TAGO = 차량번호
     * @param plateNo   표시용 차량번호. 없으면 null
     * @param seq       지금(또는 마지막으로 지난) 정류장 순번
     * @param departed  {@code seq} 정류장을 이미 떠났다(서울 stopFlag 0, 경기 stateCd 2). 모르면 false(TAGO)
     */
    record Vehicle(String vehicleId, String plateNo, int seq, boolean departed) {

        /** 이 차가 {@code boardSeq} 정류장을 지났는지. */
        public boolean isPast(int boardSeq) {
            return seq > boardSeq || (seq == boardSeq && departed);
        }
    }

    /** @param observedAt 서버가 받은 시각(원천 시각은 원천마다 형식이 달라 쓰지 않는다) */
    record Snapshot(Outcome outcome, List<Vehicle> vehicles, Instant observedAt) {

        public Snapshot {
            vehicles = vehicles == null ? List.of() : List.copyOf(vehicles);
        }

        public static Snapshot ok(List<Vehicle> vehicles, Instant at) {
            return new Snapshot(Outcome.OK, vehicles, at);
        }

        public static Snapshot failed(Outcome outcome, Instant at) {
            return new Snapshot(outcome, List.of(), at);
        }

        @JsonIgnore
        public boolean isOk() {
            return outcome == Outcome.OK;
        }
    }
}
