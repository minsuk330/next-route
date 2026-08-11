package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 계측 수집 자체의 계측.
 *
 * <p><b>식별자를 태그로 쓰지 않는다</b>(REQUIREMENTS §6). journeyId·userId·telemetryId 를 태그에
 * 넣으면 시계열 수가 사용자 수만큼 늘어 저장소가 감당하지 못한다. 태그는 값 종류가 적은 것만 쓴다.
 *
 * <p>선택률·alert-to-action 같은 <b>비율은 여기서 계산하지 않는다</b>. 그것은 원본 표를 질의해
 * 구한다 — 카운터로 만들면 나중에 정의를 바꿀 때 지난 데이터를 다시 못 센다.
 */
@Component
@RequiredArgsConstructor
public class JourneyTelemetryMetrics {

    public static final String RECEIVED = "journey.telemetry.received";
    public static final String DUPLICATED = "journey.telemetry.duplicated";
    public static final String REJECTED = "journey.telemetry.rejected";

    /** 서버가 뜻을 아는 이벤트 종류가 아니면 태그를 이 값으로 접는다. */
    static final String UNKNOWN_EVENT_TYPE = "unknown";

    private final MeterRegistry registry;

    /**
     * @param known 서버가 허용 키 목록을 가진 종류인지. 모르는 값을 그대로 태그에 쓰면 앱이
     *              오타를 내는 순간 시계열이 무한히 늘어난다
     */
    public void recordReceived(String eventType, boolean known) {
        registry.counter(RECEIVED, "eventType", known ? eventType : UNKNOWN_EVENT_TYPE).increment();
    }

    /** 재전송이라 버린 수. 앱의 재시도가 잦은지 보는 창이다. */
    public void recordDuplicated() {
        registry.counter(DUPLICATED).increment();
    }

    /** @param reason {@code foreign-alert}·{@code unattributable}·{@code malformed} */
    public void recordRejected(String reason) {
        registry.counter(REJECTED, "reason", reason).increment();
    }
}
