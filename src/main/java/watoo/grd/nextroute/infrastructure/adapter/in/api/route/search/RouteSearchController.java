package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    /**
     * 실패의 성격을 상태코드로 구분한다(Step 2).
     * 이전에는 전부 400 이라 "요청이 잘못됐다"와 "상류가 응답하지 않는다"가 섞였다.
     */
    @ExceptionHandler(OdSayApiException.class)
    public ResponseEntity<Map<String, Object>> handleOdSayApiException(OdSayApiException e) {
        HttpStatus status = switch (e.getKind()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;   // 504 — 시간 예산 안에 경로를 못 만듦
            case UPSTREAM -> HttpStatus.BAD_GATEWAY;      // 502 — 상류 장애
            case BUSINESS -> HttpStatus.BAD_REQUEST;      // 400 — 요청 자체의 문제(-98 등)
        };
        return ResponseEntity.status(status).body(Map.of(
                "error", true,
                "code", e.getErrorCode(),
                "message", e.getMessage()
        ));
    }
}
