package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.application.journey.exception.JourneyCompileFailedException;
import watoo.grd.nextroute.application.journey.exception.SnapshotOwnerMismatchException;
import watoo.grd.nextroute.application.journey.exception.SnapshotUnavailableException;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetActiveJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.ApplyJourneyEventRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.JourneyEventResponse;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.StartJourneyRequest;

import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.Map;

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
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 탑승·하차·차량 선택·취소 같은 명령을 보낸다.
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

    /** 없는 여정과 남의 여정을 구분하지 않는다. 구분하면 남의 여정 번호를 찍어 볼 수 있다. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("JOURNEY_NOT_FOUND", "여정을 찾을 수 없다"));
    }

    /** 진행 중인 여정을 함께 돌려준다. 앱이 "이어서 보기"를 바로 그릴 수 있게. */
    @ExceptionHandler(ActiveJourneyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleActiveExists(ActiveJourneyExistsException e) {
        Map<String, Object> body = new LinkedHashMap<>(
                error("ACTIVE_JOURNEY_EXISTS", "이미 진행 중인 여정이 있다"));
        body.put("journey", e.getCurrent());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 410 — 스냅샷이 만료됐거나 그 안에 없는 경로다. 앱은 다시 검색해야 한다. */
    @ExceptionHandler(SnapshotUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleSnapshotGone(SnapshotUnavailableException e) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(error("SNAPSHOT_EXPIRED", "검색 결과가 만료됐다. 다시 검색해라"));
    }

    @ExceptionHandler(SnapshotOwnerMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleOwnerMismatch(SnapshotOwnerMismatchException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("SNAPSHOT_OWNER_MISMATCH", "다른 사용자의 검색 결과다"));
    }

    /** 422 — 스냅샷은 살아 있는데 쪼갤 구간이 없다. 만료와 구분해야 원인을 볼 수 있다. */
    @ExceptionHandler(JourneyCompileFailedException.class)
    public ResponseEntity<Map<String, Object>> handleCompileFailed(JourneyCompileFailedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(error("JOURNEY_COMPILE_FAILED", "이 경로로는 여정을 시작할 수 없다"));
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", true, "code", code, "message", message);
    }
}
