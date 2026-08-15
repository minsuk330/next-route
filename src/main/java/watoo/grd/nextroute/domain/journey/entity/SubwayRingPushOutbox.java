package watoo.grd.nextroute.domain.journey.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.Instant;

/**
 * 진행 링 원격 갱신 한 건.
 *
 * <h2>여정 알림 outbox 와 규칙이 반대다</h2>
 *
 * <p>{@code journey_notification}(V40)은 at-least-once 다 — "환승 3분 전"은 한 번 일어난 사건이라
 * 늦더라도 가야 한다. 이 표는 <b>latest-wins</b> 다 — 링 갱신은 최신 하나만 의미가 있고, 늦게
 * 도착한 옛 상태는 새 화면을 덮어 사용자를 되돌린다.
 *
 * <p>그렇다고 재시도를 통째로 없애지 않는다. commit 은 됐는데 push 가 유실되면 잠금화면이 멈춘
 * 채로 남는다. 규칙은 <b>"최신인 동안만 재시도한다"</b>이다.
 *
 * <h2>쓰기는 대부분 네이티브다</h2>
 *
 * <p>순번 채번·최신성 재확인·상태 전이가 모두 <b>조건부 UPDATE 한 문장</b>이어야 한다. 읽고
 * 고치는 방식으로 하면 그 사이에 새 행이 들어오는 창이 열리고, 그 창이 정확히 이 표가 막으려는
 * 사고다. 그래서 이 엔티티는 주로 <b>읽기</b>에 쓰인다.
 */
@Entity
@Getter
@Table(name = "subway_ring_push_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayRingPushOutbox extends BaseEntity {

    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    @Column(name = "route_revision", nullable = false)
    private int routeRevision;

    @Column(name = "leg_index", nullable = false)
    private int legIndex;

    /**
     * 이 payload 가 어느 고정 세대를 보고 만들어졌나.
     *
     * <p>{@code routeRevision} 만으로는 부족하다. 재탐색 계승({@code SubwayLegBinding.inheritTo})은
     * 새 행을 만들며 {@code version} 을 0 으로 되돌리므로, 같은 개정본 안에서 일어난 리셋을
     * 개정본 비교로는 못 잡는다.
     */
    @Column(name = "binding_version", nullable = false)
    private int bindingVersion;

    /**
     * 순서 키. <b>Activity(=여정) 전체에서 단조 증가한다.</b>
     *
     * <p>ring 키별로 다시 시작하면 개정본이 오르거나 leg 이 바뀔 때 값이 되감겨, 늦게 도착한 옛
     * push 가 새 화면을 덮는다.
     */
    @Column(nullable = false)
    private long sequence;

    /**
     * APNs 가 순서를 가리는 값({@code aps.timestamp}). 단위는 epoch <b>초</b>다.
     *
     * <p>{@code props} 안의 시각들은 <b>밀리초</b>다 — 여기서 1000배 오차가 조용히 난다.
     *
     * <p>{@link #sequence} 와 같은 원천에서 파생한다. 이미 {@code SENDING} 인 것을 취소할 수는
     * 없으므로, 역전을 실제로 막는 <b>최후 방어선</b>이 이 값의 단조성이다.
     */
    @Column(name = "aps_timestamp", nullable = false)
    private long apsTimestamp;

    /** 같은 화면이면 다시 보내지 않는다. 링은 5초마다 도는데 대부분의 tick 은 화면을 안 바꾼다. */
    @Column(nullable = false, length = 64)
    private String signature;

    /** 중요 이벤트만 최신인 동안 재시도한다. */
    @Column(nullable = false)
    private boolean critical;

    /** 왜 이 행이 생겼나. 지표 tag 로 쓴다. */
    @Column(nullable = false, length = 30)
    private String reason;

    @Column(nullable = false, length = 10)
    private String event;

    /**
     * {@code {name, props}} 두 겹 그대로. {@code props} 는 JSON <b>문자열</b>이다.
     *
     * <p>Expo Widgets 의 실제 ContentState 가 봉투 모양이라, 한 겹으로 보내면 앱 디코딩이 실패해
     * 갱신이 <b>조용히 무시</b>된다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubwayRingPushStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** 이 시각을 넘기면 보내지 않는다. 늦게 뜬 진행률은 거짓말이다. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "sending_at")
    private Instant sendingAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_failure_reason", length = 200)
    private String lastFailureReason;

    public SubwayRingPushEvent eventType() {
        return SubwayRingPushEvent.fromWire(event);
    }
}
