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
    private final JourneySnapshotEtaAttacher etaAttacher;

    /**
     * 진행도를 만들 수 없을 때 lease 만 연장한다. 최대 30초 간격이다(SC-7 5항).
     *
     * <p>응답은 상태를 맞추는 데 필요한 최신 스냅샷이다 — 상태·번호·현재 단계·lease 시각. 보호
     * 시각 안에 지하철에서 복귀한 앱은 이 응답만으로 화면을 서버 기준으로 되돌릴 수 있다(SC-12 6항).
     *
     * <p><b>조회와 같은 예측을 얹는다(#470).</b> 앱은 화면에 돌아오면 이 응답으로 스냅샷을 갈아끼우고, 이어서
     * 조건부 조회를 하지만 번호가 같아 204 를 받는다. 여기서 예측을 빼면 도착 예정이 다음 번호가 오를 때까지
     * 사라진다. 평가기가 켜져 있으면(기본) 합성은 저장된 판정을 읽을 뿐 외부 API 를 부르지 않고, 이 경로는
     * 많아야 30초에 한 번이라 10초마다 묻는 조회보다 드물다. 진행 보고({@code PUT /progress})는 얹지 않는다 —
     * 이유는 {@link JourneySnapshotEtaAttacher}.
     */
    @PostMapping("/{journeyId}/heartbeat")
    public JourneySnapshot heartbeat(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId) {
        return etaAttacher.forWrite(userId, heartbeatJourneyUseCase.heartbeat(userId, journeyId));
    }
}
