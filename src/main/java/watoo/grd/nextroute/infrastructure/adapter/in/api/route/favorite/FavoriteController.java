package watoo.grd.nextroute.infrastructure.adapter.in.api.route.favorite;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import watoo.grd.nextroute.application.route.dto.FavoriteRequest;
import watoo.grd.nextroute.application.route.dto.FavoriteResponse;
import watoo.grd.nextroute.application.route.port.in.AddFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.DeleteFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.GetFavoriteRoutesUseCase;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/route/fav")
@RequiredArgsConstructor
@Tag(name = "[회원] 즐겨찾는 경로")
public class FavoriteController {

    private final AddFavoriteRouteUseCase addFavoriteRouteUseCase;
    private final GetFavoriteRoutesUseCase getFavoriteRoutesUseCase;
    private final DeleteFavoriteRouteUseCase deleteFavoriteRouteUseCase;

    @PostMapping
    public ResponseEntity<FavoriteResponse> add(
            @RequestHeader("X-Device-Id") String deviceId,
            @Valid @RequestBody FavoriteRequest request) {
        return ResponseEntity.ok(addFavoriteRouteUseCase.add(deviceId, request));
    }

    @GetMapping
    public ResponseEntity<List<FavoriteResponse>> getAll(
            @RequestHeader("X-Device-Id") String deviceId) {
        return ResponseEntity.ok(getFavoriteRoutesUseCase.getAll(deviceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Device-Id") String deviceId,
            @PathVariable Long id) {
        deleteFavoriteRouteUseCase.delete(deviceId, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
