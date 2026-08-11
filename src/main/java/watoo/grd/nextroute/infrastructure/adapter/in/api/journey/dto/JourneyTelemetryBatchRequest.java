package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import watoo.grd.nextroute.application.journey.dto.JourneyTelemetryCommand;

import java.time.Instant;
import java.util.List;

/**
 * 이용행태 계측 batch 요청.
 *
 * <p>batch 인 이유는 앱이 지하철에서 신호를 잃는 동안 이벤트를 모아 두기 때문이다. 전송이
 * 실패하면 <b>같은 {@code telemetryId} 로 그대로 다시 보낸다</b> — 서버가 중복을 지운다.
 *
 * <p>정밀 좌표를 받는 필드는 없다. {@code detail} 도 이벤트별 허용 키의 스칼라만 저장된다.
 *
 * @param events 이벤트 목록. 상한을 넘으면 400 이다 — 조용히 자르면 앱은 다 보냈다고 믿는다
 */
public record JourneyTelemetryBatchRequest(
        @NotEmpty @Size(max = JourneyTelemetryBatchRequest.MAX_EVENTS) List<Event> events
) {

    /** 요청 하나에 담을 수 있는 이벤트 수. 넘으면 앱이 나눠 보내면 되는 문제다. */
    public static final int MAX_EVENTS = 200;

    /**
     * 이벤트 하나.
     *
     * <p><b>{@code @Valid} 를 걸지 않는다.</b> 이 record 에 필드 검증(길이 제한 등)을 얹으면
     * Bean Validation 이 한 필드만 어긋나도 요청 전체를 400 으로 던진다 — "행 단위 검증, 하나
     * 때문에 batch 를 통째로 버리지 않는다"는 계약과 정면으로 어긋난다({@code JourneyTelemetryService}
     * 참고). 길이·형식 검증은 그쪽이 행 단위로 한다.
     *
     * @param telemetryId  앱이 만든 멱등 키. 재전송이면 같은 값
     * @param eventType    이벤트 종류. <b>서버가 값 목록을 닫지 않는다</b> — 앱이 먼저 배포되는
     *                     것이 정상 순서라, 모르는 값을 400 으로 막으면 batch 가 통째로 버려진다
     * @param occurredAt   기기 시각. 감사용이며 순서·보관의 기준으로 쓰지 않는다
     * @param alertId      어떤 알림에 대한 반응인지. 알림 관련 이벤트에는 필수다
     * @param detail       이벤트별 부가 값. 허용 키의 스칼라만 저장된다
     */
    public record Event(
            String telemetryId,
            String eventType,
            Instant occurredAt,
            Integer routeRevision,
            Long alertId,
            JsonNode detail
    ) {

        public JourneyTelemetryCommand toCommand() {
            return new JourneyTelemetryCommand(telemetryId, eventType, occurredAt,
                    routeRevision, alertId, detail);
        }
    }

    public List<JourneyTelemetryCommand> toCommands() {
        return events.stream().map(Event::toCommand).toList();
    }
}
