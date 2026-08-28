package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.route.dto.RecentPlaceResponse;
import watoo.grd.nextroute.application.route.port.in.ClearSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.DeleteSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.GetSearchHistoryUseCase;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/route/my/searches")
@RequiredArgsConstructor
@Tag(name = "[회원] 내 검색 이력")
public class RouteSearchHistoryController {

    private final GetSearchHistoryUseCase getSearchHistoryUseCase;
    private final DeleteSearchHistoryUseCase deleteSearchHistoryUseCase;
    private final ClearSearchHistoryUseCase clearSearchHistoryUseCase;

    @GetMapping
    public ResponseEntity<List<RecentPlaceResponse>> getAll(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(getSearchHistoryUseCase.getAll(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        deleteSearchHistoryUseCase.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(
            @AuthenticationPrincipal Long userId) {
        clearSearchHistoryUseCase.clear(userId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
