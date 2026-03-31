package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;

import java.util.Map;

@RestController
@RequestMapping("/api/route")
@RequiredArgsConstructor
@Tag(name = "[공용] 경로 찾기")
public class RouteSearchController {

    private final SearchRouteUseCase searchRouteUseCase;

    @GetMapping("/search")
    public ResponseEntity<RouteSearchResult> search(@Valid RouteSearchRequest request) {
        RouteSearchResult result = searchRouteUseCase.search(request);
        return ResponseEntity.ok(result);
    }

    @ExceptionHandler(OdSayApiException.class)
    public ResponseEntity<Map<String, Object>> handleOdSayApiException(OdSayApiException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", true,
                "code", e.getErrorCode(),
                "message", e.getMessage()
        ));
    }
}
