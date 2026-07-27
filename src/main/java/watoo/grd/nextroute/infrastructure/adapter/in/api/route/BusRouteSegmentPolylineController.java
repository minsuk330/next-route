package watoo.grd.nextroute.infrastructure.adapter.in.api.route;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineRange;
import watoo.grd.nextroute.application.bus.port.in.GetBusRouteSegmentPolylineUseCase;

@RestController
@RequestMapping("/api/bus/routes")
@RequiredArgsConstructor
@Tag(name = "[공용] 버스 노선 폴리라인")
public class BusRouteSegmentPolylineController {

	private final GetBusRouteSegmentPolylineUseCase useCase;

	@GetMapping("/{routeId}/polyline")
	@Operation(summary = "승차·하차 seq 범위의 연속 버스 노선 Polyline 조회")
	public ResponseEntity<BusRouteSegmentPolylineRange> getPolyline(
			@PathVariable String routeId,
			@RequestParam int fromSeq,
			@RequestParam int toSeq
	) {
		if (fromSeq < 1 || toSeq <= fromSeq) {
			return ResponseEntity.badRequest().build();
		}
		return useCase.getRange(routeId, fromSeq, toSeq)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
