package watoo.grd.nextroute.infrastructure.adapter.in.api.map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.map.dto.BusStopSnapshotPayload;
import watoo.grd.nextroute.application.map.port.in.GetBusStopSnapshotUseCase;

import java.util.concurrent.TimeUnit;

/**
 * 지도용 정류장 스냅샷 엔드포인트.
 *
 * <p>{@code /api/nearby/bus-stops} 와 용도를 분리한다 — 그쪽은 "가까운 N개"(검색·resolve 용)이고,
 * 지도는 "전부"가 필요하다. 지도 마커를 nearby 로 그리면 팬할 때마다 다른 20개가 와서 끊겨 보인다.
 */
@RestController
@RequestMapping("/api/map/bus-stops")
@RequiredArgsConstructor
@Tag(name = "[공용] 지도 정류장 스냅샷")
public class MapBusStopController {

    /**
     * 정류장 표는 며칠~몇 주 단위로나 변한다. 1시간 재검증은 얻는 것 없이 왕복만 만든다.
     *
     * <p>대가: 공유 캐시(프록시/CDN)는 이 기간 동안 재검증하지 않으므로 데이터가 바뀌면 최대
     * 1주일 낡은 응답을 줄 수 있다. 앱은 이 헤더를 따르지 않고 자기 로컬 저장소를 쓰며 시작마다
     * {@code If-None-Match} 로 직접 재검증하므로 앱 경로는 영향받지 않는다.
     */
    private static final CacheControl CACHE_CONTROL =
            CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic().mustRevalidate();

    private final GetBusStopSnapshotUseCase getBusStopSnapshotUseCase;

    @GetMapping("/snapshot")
    @Operation(summary = "서울 전체 버스정류장 스냅샷 (ETag 재검증, 변경 없으면 304)")
    public ResponseEntity<byte[]> snapshot(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        String version = getBusStopSnapshotUseCase.currentVersion();
        if (matches(ifNoneMatch, version)) {
            // 본문을 만들지 않고 끝낸다. 이게 이 엔드포인트의 존재 이유다.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(quote(version))
                    .cacheControl(CACHE_CONTROL)
                    .build();
        }

        BusStopSnapshotPayload payload = getBusStopSnapshotUseCase.snapshot();
        // ETag 는 프로브 값이 아니라 실제 본문의 버전을 쓴다 — 클라가 다음 요청에 보낼 값이어야 한다.
        return ResponseEntity.ok()
                .eTag(quote(payload.version()))
                .cacheControl(CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload.json());
    }

    /** {@code W/} 프리픽스와 쿼트를 벗겨 비교한다. 콤마로 여러 개 올 수 있고 {@code *} 는 항상 매치. */
    private boolean matches(String ifNoneMatch, String version) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String normalized = normalizeETag(candidate);
            if ("*".equals(normalized) || normalized.equals(version)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeETag(String raw) {
        String value = raw.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String quote(String version) {
        return "\"" + version + "\"";
    }
}
