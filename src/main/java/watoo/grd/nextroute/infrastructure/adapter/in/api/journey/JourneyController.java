package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyProgressUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetActiveJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneySnapshotEtaComposer;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.ApplyJourneyEventRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.ApplyJourneyProgressRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.JourneyProgressResponse;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.EndJourneyRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.JourneyEventResponse;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.StartJourneyRequest;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;


/**
 * 여정 시작·복원.
 *
 * <p>사용자 ID 는 JWT 에서만 온다. 본문이나 쿼리로 받으면 남의 여정을 시작할 수 있다.
 */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정")
public class JourneyController {

    private final StartJourneyUseCase startJourneyUseCase;
    private final GetActiveJourneyUseCase getActiveJourneyUseCase;
    private final ApplyJourneyEventUseCase applyJourneyEventUseCase;
    private final GetJourneyUseCase getJourneyUseCase;
    private final ApplyJourneyProgressUseCase applyJourneyProgressUseCase;
    private final JourneySnapshotEtaComposer etaComposer;

    /**
     * 검색 결과 하나로 여정을 시작한다.
     *
     * <p>진행 중인 여정이 있으면 자동으로 끝내지 않고 409 로 거절한다. 갈아타려면
     * {@code ?replace=true} 를 명시해야 한다.
     */
    @PostMapping
    public ResponseEntity<JourneySnapshot> start(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "false") boolean replace,
            @Valid @RequestBody StartJourneyRequest request) {
        JourneySnapshot snapshot = startJourneyUseCase.start(userId, request.toCommand(replace));
        return ResponseEntity.status(HttpStatus.CREATED).body(snapshot);
    }

    /** 진행 중인 여정. 없으면 204 — 없는 것은 오류가 아니다. */
    @GetMapping("/active")
    public ResponseEntity<JourneySnapshot> active(@AuthenticationPrincipal Long userId) {
        return getActiveJourneyUseCase.getActive(userId)
                .map(snapshot -> etaComposer.compose(userId, snapshot))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 여정 하나를 번호로 조회한다. 끝난 여정도 볼 수 있다.
     *
     * <p>{@code afterCommandVersion}·{@code afterEvaluationVersion} 을 함께 보내면, 그 이후로
     * 바뀐 것이 없을 때 <b>204</b> 를 돌려준다. 앱이 30초마다 물어도 대부분은 바뀐 것이 없고,
     * 그때마다 전체 단계 목록을 실어 보내면 지하철에서 쓸데없이 데이터를 먹는다.
     */
    @GetMapping("/{journeyId}")
    public ResponseEntity<JourneySnapshot> detail(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @RequestParam(required = false) Long afterCommandVersion,
            @RequestParam(required = false) Long afterEvaluationVersion) {
        JourneySnapshot journey = getJourneyUseCase.get(userId, journeyId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));

        if (unchanged(journey, afterCommandVersion, afterEvaluationVersion)) {
            // 바뀐 것이 없으면 예측도 계산하지 않는다. 실시간 조회는 값이 쓰일 때만 한다.
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(etaComposer.compose(userId, journey));
    }

    /** 앱이 들고 있는 번호가 서버와 같으면 새로 보낼 것이 없다. 한쪽만 보내도 그 값만 본다. */
    private static boolean unchanged(JourneySnapshot journey,
                                     Long afterCommandVersion, Long afterEvaluationVersion) {
        if (afterCommandVersion == null && afterEvaluationVersion == null) {
            return false;
        }
        boolean commandSame = afterCommandVersion == null
                || journey.commandVersion() <= afterCommandVersion;
        boolean evaluationSame = afterEvaluationVersion == null
                || journey.evaluationVersion() <= afterEvaluationVersion;
        return commandSame && evaluationSame;
    }

    /** 목적지에 도착했다. 취소와 끝나는 이유가 달라 따로 둔다. */
    @PostMapping("/{journeyId}/complete")
    public ResponseEntity<JourneyEventResponse> complete(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody EndJourneyRequest request) {
        return end(userId, journeyId, request, JourneyEventType.JOURNEY_COMPLETED);
    }

    /** 여정을 접는다. 사용자가 명시적으로 그만두는 경로다. */
    @DeleteMapping("/{journeyId}")
    public ResponseEntity<JourneyEventResponse> cancel(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody EndJourneyRequest request) {
        return end(userId, journeyId, request, JourneyEventType.JOURNEY_CANCELED);
    }

    /**
     * 종료 계열 요청을 명령으로 바꿔 보낸다.
     *
     * <p>종료도 앱의 로컬 outbox와 같은 멱등성·version 계약을 사용한다. 충돌하면 최신 snapshot을
     * 적용한 뒤 새 eventId로 다시 시도한다.
     */
    private ResponseEntity<JourneyEventResponse> end(Long userId, Long journeyId,
                                                     EndJourneyRequest request,
                                                     JourneyEventType type) {
        JourneyEventOutcome outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                request.toCommand(type));
        return ResponseEntity.status(statusOf(outcome.result()))
                .body(JourneyEventResponse.from(outcome));
    }

    /**
     * 네이티브 GPS progress engine이 확정한 leg 경계 전이를 보낸다.
     *
     * <p>같은 {@code eventId} 로 다시 오면 처음 판정을 그대로 돌려준다. 지하철에서 응답을 못 받은
     * 앱이 재전송하는 것이 정상 동작이기 때문이다.
     *
     * <p>앱이 낡은 상태를 보고 눌렀으면 409 다. 규칙상 지금 할 수 없는 명령이면 422 다. 둘 다
     * 최신 여정 상태를 함께 싣는다.
     */
    @PostMapping("/{journeyId}/events")
    public ResponseEntity<JourneyEventResponse> applyEvent(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody ApplyJourneyEventRequest request) {
        JourneyEventOutcome outcome =
                applyJourneyEventUseCase.apply(userId, journeyId, request.toCommand());
        return ResponseEntity.status(statusOf(outcome.result()))
                .body(JourneyEventResponse.from(outcome));
    }

    /**
     * 앱이 확정한 진행 요약을 올린다. 명령이 아니라 관측이다.
     *
     * <p>{@code PUT} 인 이유는 이 호출이 leg 의 <b>최신 진행 한 벌</b>을 대체하기 때문이다.
     * 이력을 쌓지 않으므로 여러 번 보내도 결과가 같다.
     *
     * <p>재전송·역순 요청도 200 이다. 앱은 응답의 결과 값과 함께 실린 최신 스냅샷을 보고
     * 화면을 맞추면 되고, 왜 반영되지 않았는지 따로 물어볼 필요가 없다. 같은 관측 시각에 다른
     * 내용이 온 경우만 409 다.
     */
    @PutMapping("/{journeyId}/progress")
    public JourneyProgressResponse applyProgress(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @Valid @RequestBody ApplyJourneyProgressRequest request) {
        return JourneyProgressResponse.from(
                applyJourneyProgressUseCase.apply(userId, journeyId, request.toCommand()));
    }

    /**
     * 결과를 HTTP 상태로 옮긴다.
     *
     * <p>재전송(DUPLICATE)은 성공과 같은 200 이다. 앱이 보기에 "그 명령은 반영됐다"는 사실은
     * 처음이든 두 번째든 같다.
     */
    private static HttpStatus statusOf(JourneyEventResult result) {
        return switch (result) {
            case APPLIED, DUPLICATE -> HttpStatus.OK;
            case CONFLICT -> HttpStatus.CONFLICT;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }


}
