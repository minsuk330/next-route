package watoo.grd.nextroute.application.bus.port.out;

import java.util.Optional;

/**
 * 단독 추적 버스 관찰이 받은 노선 위치를 api 도 읽게 둔다(#265). worker 의 관찰 스케줄러가 쓰고, 스냅샷 버스 안내(PR-5)가
 * <b>외부 호출 없이</b> 읽는다. 조회 실패한 관측은 쓰지 않는다.
 */
public interface BusVehiclePositionCachePort {

    void put(BusPositionPort.Provider provider, String routeId, BusPositionPort.Snapshot snapshot);

    Optional<BusPositionPort.Snapshot> get(BusPositionPort.Provider provider, String routeId);
}
