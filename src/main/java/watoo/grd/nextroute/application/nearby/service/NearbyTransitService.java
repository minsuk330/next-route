package watoo.grd.nextroute.application.nearby.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.nearby.dto.NearbyBusStopResult;
import watoo.grd.nextroute.application.nearby.dto.NearbySubwayStationResult;
import watoo.grd.nextroute.application.nearby.port.in.GetNearbyBusStopsUseCase;
import watoo.grd.nextroute.application.nearby.port.in.GetNearbySubwayStationsUseCase;
import watoo.grd.nextroute.domain.bus.service.BusDataService;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.List;
import java.util.function.BiFunction;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NearbyTransitService implements GetNearbyBusStopsUseCase, GetNearbySubwayStationsUseCase {

    static final double INITIAL_RADIUS = 500.0;
    static final double RADIUS_STEP = 500.0;
    static final double MAX_RADIUS = 3000.0;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final BusDataService busDataService;
    private final SubwayDataService subwayDataService;

    @Override
    public List<NearbyBusStopResult> getNearbyBusStops(double lat, double lng, int limit) {
        validateCoordinates(lat, lng);
        int lim = clampLimit(limit);
        return searchWithExpanding(
                (radius, l) -> busDataService.findNearbyStops(lat, lng, radius, l).stream()
                        .map(p -> new NearbyBusStopResult(
                                p.getStopId(), p.getStopName(), p.getArsId(),
                                p.getLatitude(), p.getLongitude(),
                                (int) Math.round(p.getDistMeters())))
                        .toList(),
                lim);
    }

    @Override
    public List<NearbySubwayStationResult> getNearbySubwayStations(double lat, double lng, int limit) {
        validateCoordinates(lat, lng);
        int lim = clampLimit(limit);
        return searchWithExpanding(
                (radius, l) -> subwayDataService.findNearbyStations(lat, lng, radius, l).stream()
                        .map(p -> new NearbySubwayStationResult(
                                p.getStationId(), p.getStationName(), p.getLineId(), p.getLineName(),
                                p.getLatitude(), p.getLongitude(),
                                (int) Math.round(p.getDistMeters())))
                        .toList(),
                lim);
    }

    private <T> List<T> searchWithExpanding(BiFunction<Double, Integer, List<T>> searcher, int lim) {
        for (double r = INITIAL_RADIUS; r <= MAX_RADIUS; r += RADIUS_STEP) {
            List<T> results = searcher.apply(r, lim);
            if (!results.isEmpty()) return results;
        }
        return List.of();
    }

    private void validateCoordinates(double lat, double lng) {
        if (lat < -90 || lat > 90) throw new IllegalArgumentException("lat must be between -90 and 90");
        if (lng < -180 || lng > 180) throw new IllegalArgumentException("lng must be between -180 and 180");
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }
}
