package watoo.grd.nextroute.infrastructure.adapter.in.api.route.favorite;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import watoo.grd.nextroute.application.route.exception.FavoriteConflictException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody FavoriteRequest request) {
        return ResponseEntity.ok(addFavoriteRouteUseCase.add(userId, request));
    }

    @GetMapping
    public ResponseEntity<List<FavoriteResponse>> getAll(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(getFavoriteRoutesUseCase.getAll(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        deleteFavoriteRouteUseCase.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(FavoriteConflictException.class)
    public ResponseEntity<Void> handleConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
