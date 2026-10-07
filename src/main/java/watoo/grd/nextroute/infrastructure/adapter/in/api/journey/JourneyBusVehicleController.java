package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.service.TrackBusVehicleResetService;

/** 버스 차량 고정 보정(#265 PR-4). 고정은 단독 추적 버스에만 생긴다. */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정")
public class JourneyBusVehicleController {

    private final TrackBusVehicleResetService resetService;

    /**
     * "이 버스 아님". 지금 타는 버스 단계의 고정 차량을 지운다. 다시 찾지 않는다(이미 타 있다). 버스를 타는 단계가 아니면
     * 409 {@code NOT_RIDING_BUS}.
     */
    @PostMapping("/{journeyId}/bus/vehicle/reset")
    public ResponseEntity<Void> reset(@AuthenticationPrincipal Long userId, @PathVariable Long journeyId) {
        resetService.reset(userId, journeyId);
        return ResponseEntity.noContent().build();
    }
}
