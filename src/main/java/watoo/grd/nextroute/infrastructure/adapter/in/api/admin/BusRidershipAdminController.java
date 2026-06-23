package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRankingResponse;
import watoo.grd.nextroute.application.bus.service.BusRidershipRankingService;
import watoo.grd.nextroute.application.bus.service.WeeklyRouteRotationService;
import watoo.grd.nextroute.application.bus.service.WeeklyRouteRotationService.RotationResult;

@RestController
@RequestMapping("/api/admin/bus")
@RequiredArgsConstructor
@Tag(name = "[어드민] 버스 관리")
public class BusRidershipAdminController {

	private final BusRidershipRankingService busRidershipRankingService;
	private final WeeklyRouteRotationService weeklyRouteRotationService;

	@PostMapping("/ridership/top-routes")
	@Operation(summary = "버스 노선별 이용량 상위 조회", description = "CardBusTimeNew 전체 데이터를 월 기준으로 수집해 승하차 합계 상위 노선을 반환")
	public ResponseEntity<BusRouteRidershipRankingResponse> getTopRoutes(
			@RequestParam(defaultValue = "202603") String month,
			@RequestParam(defaultValue = "30") int limit,
			@RequestParam(defaultValue = "0") int offset,
			@RequestParam(defaultValue = "1000") int pageSize) {
		return ResponseEntity.ok(busRidershipRankingService.findTopRoutes(month, limit, offset, pageSize));
	}

	@PostMapping("/rotation/run")
	@Operation(summary = "주간 노선 로테이션 수동 실행",
			description = "다음 bucket(30개)으로 weekly_target_route active 집합을 교체. 끝에 도달하면 bucket 0으로 순환")
	public ResponseEntity<RotationResult> runRotation() {
		return ResponseEntity.ok(weeklyRouteRotationService.rotate());
	}
}
