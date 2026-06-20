package watoo.grd.nextroute.application.stopselection.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.route.service.PredictionSupportService;
import watoo.grd.nextroute.application.stopselection.dto.RouteStopsResult;
import watoo.grd.nextroute.application.stopselection.dto.SearchSuggestResult;
import watoo.grd.nextroute.application.stopselection.dto.StopRouteResult;
import watoo.grd.nextroute.application.stopselection.port.in.GetRouteStopsUseCase;
import watoo.grd.nextroute.application.stopselection.port.in.GetStopRoutesUseCase;
import watoo.grd.nextroute.application.stopselection.port.in.SearchSuggestUseCase;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StopSelectionService
        implements GetStopRoutesUseCase, GetRouteStopsUseCase, SearchSuggestUseCase {

    /** 자동완성 keyword 최대 길이. 초과 시 빈 결과. */
    static final int MAX_KEYWORD_LENGTH = 20;

    private final BusDataService busDataService;
    private final PredictionSupportService predictionSupportService;

    @Override
    public List<StopRouteResult> getStopRoutes(String stopId) {
        return busDataService.findRoutesByStopId(stopId).stream()
                .map(p -> new StopRouteResult(
                        p.getRouteId(), p.getRouteName(), p.getDirection(),
                        p.getRouteType(), p.getStartStation(), p.getEndStation(),
                        predictionSupportService.isSupported(p.getRouteId())))
                .toList();
    }

    @Override
    public RouteStopsResult getRouteStops(String routeId) {
        List<RouteStopsResult.RouteStop> stops = busDataService.findStopsByRouteId(routeId).stream()
                .map(p -> new RouteStopsResult.RouteStop(
                        p.getSeq(), p.getStopId(), p.getStopName(),
                        p.getLatitude(), p.getLongitude(), p.getDirection()))
                .toList();
        return new RouteStopsResult(routeId, predictionSupportService.isSupported(routeId), stops);
    }

    @Override
    public SearchSuggestResult suggest(String keyword) {
        if (keyword == null) {
            return SearchSuggestResult.empty();
        }
        String kw = keyword.trim();
        if (kw.isEmpty() || kw.length() > MAX_KEYWORD_LENGTH) {
            return SearchSuggestResult.empty();
        }

        List<SearchSuggestResult.SuggestRoute> routes = busDataService.searchRoutesByNamePrefix(kw).stream()
                .map(r -> new SearchSuggestResult.SuggestRoute(
                        r.getRouteId(), r.getRouteName(), r.getRouteType(),
                        r.getStartStation(), r.getEndStation(),
                        predictionSupportService.isSupported(r.getRouteId())))
                .toList();

        List<SearchSuggestResult.SuggestStop> stops = busDataService.searchStopsByNamePrefix(kw).stream()
                .map(s -> new SearchSuggestResult.SuggestStop(
                        s.getStopId(), s.getStopName(), s.getArsId(),
                        s.getLatitude(), s.getLongitude()))
                .toList();

        return new SearchSuggestResult(routes, stops);
    }
}
