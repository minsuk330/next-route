package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.port.in.HeartbeatJourneyUseCase;

/**
 * 앱이 살아 있음을 알리는 heartbeat.
 *
 * <p>사용자 ID 는 JWT 에서만 온다. 남의 여정 lease 를 연장할 수 있으면 만료 판정이 무의미해진다.
 */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정")
public class JourneyHeartbeatController {

    private final HeartbeatJourneyUseCase heartbeatJourneyUseCase;

    /**
     * 진행도를 만들 수 없을 때 lease 만 연장한다. 최대 30초 간격이다(SC-7 5항).
     *
     * <p><b>예측을 얹지 않는다.</b> 예측 합성은 실시간 도착 API 를 부르는데, 이 경로는 GPS 가
     * 어두운 동안 30초마다 들어온다. 그때마다 외부 API 를 부르면 provider 지연이 그대로
     * heartbeat 지연이 되고, 정작 예측의 근거가 될 새 위치도 없다. 같은 이유로
     * {@code PUT /progress} 도 예측을 얹지 않는다. 예측이 필요하면
     * {@code GET /api/journeys/{journeyId}} 나 {@code /active} 를 쓴다.
     *
     * <p>응답은 상태를 맞추는 데 필요한 최신 스냅샷이다 — 상태·번호·현재 단계·lease 시각. 보호
     * 시각 안에 지하철에서 복귀한 앱은 이 응답만으로 화면을 서버 기준으로 되돌릴 수 있다(SC-12 6항).
     */
    @PostMapping("/{journeyId}/heartbeat")
    public JourneySnapshot heartbeat(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId) {
        return heartbeatJourneyUseCase.heartbeat(userId, journeyId);
    }
}
