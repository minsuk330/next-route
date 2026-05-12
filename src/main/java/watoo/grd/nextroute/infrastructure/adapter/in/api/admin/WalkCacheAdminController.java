package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;

@RestController
@RequestMapping("/api/admin/walk-cache")
@RequiredArgsConstructor
@Tag(name = "[어드민] TMAP 보행자 캐시 관리")
public class WalkCacheAdminController {

    private final WalkSegmentCachePort cache;

    @DeleteMapping
    @Operation(summary = "TMAP 보행자 캐시 무효화",
            description = "prefix가 없으면 전체 삭제, 있으면 해당 prefix로 시작하는 키만 삭제")
    public ResponseEntity<InvalidateResult> invalidate(
            @RequestParam(required = false) String prefix) {
        int deleted = (prefix == null || prefix.isBlank())
                ? cache.invalidateAll()
                : cache.invalidateByPrefix(prefix);
        return ResponseEntity.ok(new InvalidateResult(deleted, prefix));
    }

    public record InvalidateResult(int deleted, String prefix) {}
}
