package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.JourneyTelemetryResult;
import watoo.grd.nextroute.application.journey.service.JourneyTelemetryService;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.JourneyTelemetryBatchRequest;

/**
 * 앱 이용행태 계측 수집.
 *
 * <p>사용자 ID 는 JWT 에서만 온다. 본문에 있어도 쓰지 않는다 — 남의 여정에 기록을 심을 수 있으면
 * 지표가 아니라 구멍이다.
 *
 * <p>여정이 경로에 있는 이유는 소유권 확인을 <b>한 자리</b>에서 끝내기 위해서다. 본문 값으로
 * 받으면 이벤트마다 확인이 흩어진다.
 */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정")
public class JourneyTelemetryController {

    private final JourneyTelemetryService telemetryService;

    /**
     * 계측 batch 수집. <b>best-effort 다</b> — 이 호출의 실패가 앱 동작을 멈추면 안 된다.
     *
     * <p>{@code 202} 인 이유. 서버는 "받았다"까지만 약속한다. 중복이라 저장되지 않은 것도 정상
     * 결과이고, 앱은 응답의 세 수를 보고 재전송 여부를 정한다.
     */
    @PostMapping("/{journeyId}/telemetry/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JourneyTelemetryResult collect(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody JourneyTelemetryBatchRequest request) {
        return telemetryService.record(userId, journeyId, request.toCommands());
    }
}
