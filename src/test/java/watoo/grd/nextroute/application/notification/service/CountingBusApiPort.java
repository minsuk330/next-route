package watoo.grd.nextroute.application.notification.service;

import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.dto.BusRidershipFetchResult;
import watoo.grd.nextroute.application.bus.dto.BusRouteInfo;
import watoo.grd.nextroute.application.bus.dto.BusRoutePathPoint;
import watoo.grd.nextroute.application.bus.dto.BusRouteStopInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 알림 디스패치 IT 공유 fake BusApiPort. getArrInfoByStop 호출 수를 센다(옵션 지연).
 * 반환은 sectionTime1=60 고정 도착 1건.
 */
public class CountingBusApiPort implements BusApiPort {

    private final AtomicInteger calls = new AtomicInteger();
    private final long latencyMs;

    public CountingBusApiPort(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
    }

    @Override
    public List<BusArrivalInfo> getArrInfoByStop(String stopId, String routeId, String ord) {
        calls.incrementAndGet();
        sleep(latencyMs);
        return List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null));
    }

    @Override
    public List<BusRouteInfo> getBusRouteList(String searchKeyword) {
        return Collections.emptyList();
    }

    @Override
    public List<BusRouteInfo> getRouteInfo(String busRouteId) {
        return Collections.emptyList();
    }

    @Override
    public List<BusRouteStopInfo> getStationByRoute(String busRouteId) {
        return Collections.emptyList();
    }

    @Override
    public List<BusRoutePathPoint> getRoutePath(String busRouteId) {
        return Collections.emptyList();
    }

    @Override
    public List<BusArrivalInfo> getArrInfoByRouteAll(String busRouteId) {
        return Collections.emptyList();
    }

    @Override
    public List<BusPositionInfo> getBusPosByRtid(String busRouteId) {
        return Collections.emptyList();
    }

    @Override
    public List<String> getBusRouteIds() {
        return Collections.emptyList();
    }

    @Override
    public BusRidershipFetchResult getBusRidershipByMonth(String month, int pageSize) {
        return new BusRidershipFetchResult(month, 0, Collections.emptyList());
    }

    static void sleep(long latencyMs) {
        if (latencyMs <= 0) return;
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fake latency interrupted", e);
        }
    }
}
