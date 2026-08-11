package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneyTelemetryCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyTelemetryResult;
import watoo.grd.nextroute.application.journey.observability.JourneyTelemetryMetrics;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotification;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyTelemetryEventRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 앱이 보낸 이용행태 계측을 받아 쌓는다.
 *
 * <p><b>best-effort 다.</b> 이 경로의 실패가 여정 상태·발송·폴링을 흔들지 않는다(SC-3). 그래서
 * 여정 서비스에 끼워 넣지 않고 별도 진입점으로 둔다.
 *
 * <h2>거르는 순서</h2>
 * <ol>
 *   <li><b>여정 소유권</b> — 경로 자원이므로 남의 것이면 요청 전체를 거절한다. 행 단위로 봐줄
 *       일이 아니다</li>
 *   <li><b>행 단위 검증</b> — 어긋난 행만 버리고 나머지는 저장한다. 하나 때문에 batch 를 통째로
 *       버리면 앱은 그 batch 를 영원히 재시도한다</li>
 *   <li><b>허용 키 필터</b> — 목록 밖 키는 조용히 버린다. 여기서 400 을 내면 앱이 필드를 하나
 *       늘릴 때마다 batch 가 죽는다</li>
 * </ol>
 *
 * <p>중복은 저장소가 {@code ON CONFLICT DO NOTHING} 으로 흡수한다 — 예외로 만들면 재전송이라는
 * <b>정상</b> 동작이 500 이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyTelemetryService {

    /**
     * 알림 참조가 반드시 있어야 하는 이벤트.
     *
     * <p>"눌렀다"·"보였다"는 <b>어떤 알림에 대한</b> 반응인지가 없으면 선택률(O13)에 기여하지
     * 못한다. 저장해 봐야 세지 못하는 행이라 받지 않는다.
     */
    private static final Set<String> ALERT_REQUIRED =
            Set.of("ALERT_OPENED", "ALERT_PRESENTED");

    /**
     * 이벤트별 {@code detail} 허용 키.
     *
     * <p>목록 밖 키는 저장하지 않는다 — 앱이 실수로 좌표를 실어도 표에 남지 않아야 한다
     * (REQUIREMENTS §6). 모르는 이벤트는 목록이 없으므로 {@code detail} 이 통째로 비워진다.
     */
    private static final Map<String, Set<String>> ALLOWED_DETAIL_KEYS = Map.of(
            "ALERT_OPENED", Set.of("surface"),
            "ALERT_PRESENTED", Set.of("surface"),
            "ALERT_SUPPRESSED", Set.of("reason"),
            "SCREEN_ENTERED", Set.of("origin"),
            "FOREGROUND_SPAN", Set.of("seconds"),
            "LOCAL_CHECKPOINT", Set.of("state", "checkpointOccurrenceId", "legIndex",
                    "checkpoint", "surface", "triggeredAt"));

    /** {@code detail} 직렬화 크기 상한. 넘으면 그 행만 버린다. */
    private static final int MAX_DETAIL_BYTES = 4096;

    /** {@code journey_telemetry_event.telemetry_id varchar(64)} 와 맞춘다. */
    private static final int MAX_TELEMETRY_ID_LENGTH = 64;

    /** {@code journey_telemetry_event.event_type varchar(40)} 와 맞춘다. */
    private static final int MAX_EVENT_TYPE_LENGTH = 40;

    private final JourneySessionRepository sessionRepository;
    private final JourneyNotificationRepository notificationRepository;
    private final JourneyTelemetryEventRepository telemetryRepository;
    private final JourneyTelemetryMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @throws BusinessException 여정이 그 사용자 것이 아닐 때. 경로 자원이라 요청 전체가 실패한다
     */
    @Transactional
    public JourneyTelemetryResult record(long userId, long journeyId,
                                         List<JourneyTelemetryCommand> commands) {
        sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));

        Instant receivedAt = Instant.now(clock);
        LocalDateTime auditNow = LocalDateTime.now(clock);
        int accepted = 0;
        int duplicated = 0;
        int rejected = 0;

        for (JourneyTelemetryCommand command : commands) {
            if (!hasValidShape(command)) {
                // 길이·필수값 검증을 DTO 의 Bean Validation 이 아니라 여기서 한다. @Valid 로
                // record 필드에 걸면 이 행 하나가 요청 전체를 400 으로 죽인다 — "행 단위 검증"
                // 계약과 정면으로 어긋난다(JourneyTelemetryBatchRequest.Event 참고).
                reject("malformed");
                rejected++;
                continue;
            }

            JourneyNotification alert = null;
            if (command.alertId() != null) {
                alert = notificationRepository.findById(command.alertId()).orElse(null);
                if (alert == null || !alert.getUserId().equals(userId)
                        || !alert.getJourneyId().equals(journeyId)) {
                    // 남의 알림 id 로 "내가 눌렀다"를 심으면 O13·O15 가 엉뚱한 발송에 붙는다.
                    reject("foreign-alert");
                    rejected++;
                    continue;
                }
            } else if (ALERT_REQUIRED.contains(command.eventType())) {
                reject("unattributable");
                rejected++;
                continue;
            }

            Detail detail = filterDetail(command);
            if (detail.tooLarge()) {
                // 크기 상한을 넘는 것은 "남길 게 없는 것"과 다르다. 조용히 비우면 앱은 보냈다고
                // 믿고, 우리는 왜 값이 없는지 모르게 된다.
                reject("malformed");
                rejected++;
                continue;
            }

            List<Long> created = telemetryRepository.insertIgnoringDuplicate(
                    command.telemetryId(), userId, journeyId, command.routeRevision(),
                    command.eventType(), command.occurredAt(), receivedAt,
                    command.alertId(),
                    alert == null ? null : alert.getCheckpoint().name(),
                    alert == null ? null : alert.getSentAt(),
                    detail.json(), auditNow);

            if (created.isEmpty()) {
                duplicated++;
                metrics.recordDuplicated();
            } else {
                accepted++;
                metrics.recordReceived(command.eventType(), ALLOWED_DETAIL_KEYS.containsKey(
                        command.eventType()));
            }
        }
        return new JourneyTelemetryResult(accepted, duplicated, rejected);
    }

    private void reject(String reason) {
        metrics.recordRejected(reason);
    }

    /**
     * 필수값·길이가 저장 가능한 모양인지.
     *
     * <p>DTO 에서 {@code @Size} 로 걸지 않는 이유가 여기 있다 — 길이 초과는 <b>흔히 있는 일</b>
     * (앱 오타·옛 버전)이라 그 행만 버려야 한다. 길이를 넘긴 값을 그대로 insert 로 보내면
     * {@code varchar(64)} 제약을 DB 가 예외로 거부하고, 그 예외가 트랜잭션 전체를 rollback-only
     * 로 만들어 <b>batch 전체가 죽는다</b> — Bean Validation 을 걷어낸 것과 같은 사고가 저장소
     * 앞에서 다시 일어난다. 그래서 DB 에 보내기 전에 여기서 자른다.
     */
    private boolean hasValidShape(JourneyTelemetryCommand command) {
        return command.telemetryId() != null && !command.telemetryId().isBlank()
                && command.telemetryId().length() <= MAX_TELEMETRY_ID_LENGTH
                && command.eventType() != null && !command.eventType().isBlank()
                && command.eventType().length() <= MAX_EVENT_TYPE_LENGTH
                && command.occurredAt() != null;
    }

    /**
     * 걸러 낸 {@code detail}.
     *
     * <p>"남길 값이 없다"와 "너무 크다"를 <b>구분</b>한다. 둘 다 null 로 뭉뚱그리면 크기를 넘긴
     * 행이 조용히 값 없이 저장되고, 앱은 보냈다고 믿는다.
     *
     * @param json     저장할 JSON. 남길 값이 없으면 null
     * @param tooLarge 상한을 넘겨 이 행을 버려야 하면 true
     */
    private record Detail(String json, boolean tooLarge) {

        static final Detail EMPTY = new Detail(null, false);
        static final Detail TOO_LARGE = new Detail(null, true);
    }

    /**
     * 허용 키의 <b>스칼라만</b> 남긴다.
     *
     * <p>중첩 객체·배열을 버리는 이유는 좌표가 숨어 들어올 자리를 없애기 위해서다. 키 이름만
     * 막으면 {@code {"pos":{"lat":37.5}}} 를 못 걸러낸다.
     *
     * <p>모르는 이벤트는 허용 목록이 없으므로 부가 값 없이 저장된다 — 이벤트가 왔다는 사실은
     * 남기되, 검증하지 못한 값은 표에 넣지 않는다.
     */
    private Detail filterDetail(JourneyTelemetryCommand command) {
        JsonNode source = command.detail();
        if (source == null || !source.isObject()) {
            return Detail.EMPTY;
        }
        Set<String> allowed = ALLOWED_DETAIL_KEYS.get(command.eventType());
        if (allowed == null) {
            return Detail.EMPTY;
        }
        ObjectNode filtered = objectMapper.createObjectNode();
        source.fields().forEachRemaining(entry -> {
            if (allowed.contains(entry.getKey()) && entry.getValue().isValueNode()) {
                filtered.set(entry.getKey(), entry.getValue());
            }
        });
        if (filtered.isEmpty()) {
            return Detail.EMPTY;
        }
        try {
            String json = objectMapper.writeValueAsString(filtered);
            return json.getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_BYTES
                    ? Detail.TOO_LARGE
                    : new Detail(json, false);
        } catch (Exception e) {
            log.warn("[JourneyTelemetry] detail 직렬화 실패 eventType={}: {}",
                    command.eventType(), e.getMessage());
            return Detail.EMPTY;
        }
    }
}
