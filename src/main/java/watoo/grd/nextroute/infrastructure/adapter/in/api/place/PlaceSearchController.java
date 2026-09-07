package watoo.grd.nextroute.infrastructure.adapter.in.api.place;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;
import watoo.grd.nextroute.application.place.port.in.PlaceSearchUseCase;

@Validated
@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
@Tag(name = "[공용] 장소 검색")
public class PlaceSearchController {

    private final PlaceSearchUseCase placeSearchUseCase;

    @GetMapping("/search")
    @Operation(summary = "네이버(주)+카카오(중복제외) 통합 장소·주소 검색")
    public ResponseEntity<PlaceSearchResult> search(
            @RequestParam @NotBlank @Size(max = 80) String query,
            @RequestParam(defaultValue = "15") @Min(1) @Max(15) int limit
    ) {
        return ResponseEntity.ok(placeSearchUseCase.search(query.trim(), limit));
    }
}
