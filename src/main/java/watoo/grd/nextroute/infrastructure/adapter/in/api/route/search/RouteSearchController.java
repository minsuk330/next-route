package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.exception.InvalidDepartureTimeException;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.port.in.RecordSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.service.RouteSearchSnapshotRecorder;

import java.util.Map;

@RestController
@RequestMapping("/api/route")
@RequiredArgsConstructor
@Tag(name = "[공용] 경로 찾기")
public class RouteSearchController {

    private final SearchRouteUseCase searchRouteUseCase;
    private final RecordSearchHistoryUseCase recordSearchHistoryUseCase;
    private final RouteSearchSnapshotRecorder snapshotRecorder;

    /**
     * 공용 엔드포인트 유지 + optional auth. JWT 가 있으면 {@code userId} 가 채워지고(로그인),
     * 익명 요청은 null 이다. 로그인 사용자에 한해 검색 이력을 비동기로 기록한다(응답 지연 없음).
     *
     * <p>스냅샷 부착은 {@code RouteSearchService} 내부가 아니라 여기서 한다. {@code withMeta} 와 같은 자리라
     * 내부 중간 결과나 다른 호출부(알림 소요시간 추정 등)를 오염시키지 않는다.
     */
    @GetMapping("/search")
    public ResponseEntity<RouteSearchResult> search(
            @AuthenticationPrincipal Long userId,
            @Valid RouteSearchRequest request) {
        RouteSearchResult result = searchRouteUseCase.search(request);
        // 급행 끄기는 요청값으로 남긴다(D10 해제와 무관) — 여정 재탐색이 같은 선호로 찾는다(subway-express S5).
        result = snapshotRecorder.attach(userId, result, request.excludesExpress());
        if (userId != null) {
            recordSearchHistoryUseCase.record(userId, request, result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 실패의 성격을 상태코드로 구분한다(Step 2).
     * 이전에는 전부 400 이라 "요청이 잘못됐다"와 "상류가 응답하지 않는다"가 섞였다.
     */
    /** departureAt 형식/​offset 오류는 요청 자체의 문제 → 400(무경로/timeout 과 구분). */
    @ExceptionHandler(InvalidDepartureTimeException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidDepartureTime(InvalidDepartureTimeException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", true,
                "code", "INVALID_DEPARTURE_TIME",
                "message", e.getMessage()
        ));
    }

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
