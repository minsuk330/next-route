package watoo.grd.nextroute.infrastructure.adapter.in.api.search;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.stopselection.dto.SearchSuggestResult;
import watoo.grd.nextroute.application.stopselection.port.in.SearchSuggestUseCase;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Tag(name = "[공용] 정류장 선택 UI")
public class SearchSuggestController {

    private final SearchSuggestUseCase searchSuggestUseCase;

    @GetMapping("/suggest")
    @Operation(summary = "버스번호 + 정류장명 통합 자동완성 (prefix)")
    public ResponseEntity<SearchSuggestResult> suggest(
            @RequestParam(required = false, defaultValue = "") String keyword) {
        return ResponseEntity.ok(searchSuggestUseCase.suggest(keyword));
    }
}
