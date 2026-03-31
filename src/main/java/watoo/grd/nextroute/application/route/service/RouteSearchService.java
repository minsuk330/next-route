package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchLog;
import watoo.grd.nextroute.domain.route.log.service.RouteDataService;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteSearchService implements SearchRouteUseCase {

    private final OdSayApiPort odSayApiPort;
    private final RouteDataService routeDataService;
    private final ObjectMapper objectMapper;

    @Override
    public RouteSearchResult search(RouteSearchRequest request) {
        RouteSearchResult result = odSayApiPort.searchPath(
                request.startX(), request.startY(),
                request.endX(), request.endY()
        );

        try {
            String responseJson = objectMapper.writeValueAsString(result);
            RouteSearchLog searchLog = RouteSearchLog.builder()
                    .startX(request.startX())
                    .startY(request.startY())
                    .endX(request.endX())
                    .endY(request.endY())
                    .startName(request.startName())
                    .endName(request.endName())
                    .pathCount(result.paths() != null ? result.paths().size() : 0)
                    .searchType(result.searchType())
                    .responseJson(responseJson)
                    .searchedAt(LocalDateTime.now())
                    .build();
            routeDataService.save(searchLog);
        } catch (Exception e) {
            log.warn("[Route] Failed to save search log: {}", e.getMessage());
        }

        return result;
    }
}
