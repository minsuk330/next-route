package watoo.grd.nextroute.domain.journey.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.Instant;

/**
 * 앱이 보낸 이용행태 계측 한 건.
 *
 * <p><b>best-effort 신호다.</b> 여정 상태 전이의 근거로 쓰지 않는다(SC-3). 여기 쓰기가 실패해도
 * 여정·발송·폴링은 흔들리지 않아야 한다.
 *
 * <p>원본을 그대로 쌓는 이유는 alert-to-action(O15)이 <b>두 사건의 시각 차이</b>이기 때문이다.
 * 카운터만 올리면 "몇 번 눌렸나"는 알아도 "누르고 얼마 뒤에 움직였나"를 영영 못 구한다.
 *
 * <h2>시각이 둘인 이유</h2>
 * {@code occurredAt} 은 기기 시각이라 <b>믿지 않는다.</b> 시계가 틀어진 기기가 섞이므로 순서의
 * 근거로도, 보관 삭제의 기준으로도 쓰지 않는다. 그 둘은 {@code receivedAt}(서버 시각)이 맡는다.
 *
 * <h2>알림 메타를 복사해 두는 이유</h2>
 * {@code alertId} 에 FK 를 걸지 않는다 — 알림 행의 보관 정책이 이 표의 쓰기를 막으면 안 된다.
 * 대신 {@code alertCheckpoint}·{@code alertSentAt} 을 <b>수집 시점에 서버가 복사</b>한다. 알림
 * 행이 나중에 사라져도 "어떤 알림에 대한 반응이었나"가 남는다.
 */
@Entity
@Getter
@Table(name = "journey_telemetry_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JourneyTelemetryEvent extends BaseEntity {

    /** 앱이 만든 멱등 키. 유일성은 사용자별이다(기기를 물려받아도 남의 키가 삼키지 않게). */
    @Column(name = "telemetry_id", nullable = false, length = 64)
    private String telemetryId;

    /** 토큰에서 읽은 사용자. 본문 값은 쓰지 않는다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 경로에서 온 여정. 소유권은 경로 자원으로 한 번에 확인된다. */
    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    @Column(name = "route_revision")
    private Integer routeRevision;

    /**
     * 이벤트 종류. <b>enum 이 아니라 문자열이다.</b>
     *
     * <p>enum 으로 닫으면 앱이 값을 하나 늘릴 때마다 서버 배포가 앞서야 한다. 모르는 값도
     * 저장하고 지표에서만 {@code unknown} 으로 센다.
     */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    /** 기기 시각. 믿지 않는다. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** 서버 시각. 지연 계산과 보관의 기준이다. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "alert_id")
    private Long alertId;

    /** 수집 시점 스냅샷. 앱이 보낸 값이 아니다. */
    @Column(name = "alert_checkpoint", length = 40)
    private String alertCheckpoint;

    /** 수집 시점 스냅샷. 앱이 보낸 값이 아니다. */
    @Column(name = "alert_sent_at")
    private Instant alertSentAt;

    /** 허용 키 목록을 통과한 스칼라만 담긴다. 좌표는 들어오지 못한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    private JsonNode detail;
}
