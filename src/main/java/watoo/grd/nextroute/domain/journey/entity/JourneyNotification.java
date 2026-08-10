package watoo.grd.nextroute.domain.journey.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import watoo.grd.nextroute.common.entity.BaseEntity;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Instant;

/**
 * 보낼 여정 알림 한 건(outbox 행).
 *
 * <p>평가기는 "이 체크포인트가 발생했다"만 여기 적고 실제 발송은 relay 가 한다. 평가는 최소
 * 10초마다 다시 돌고 DB 트랜잭션 안에서 도는데, 그 안에서 발송 API 를 부르면 응답을 기다리는
 * 동안 커넥션을 붙잡기 때문이다.
 *
 * <p>멱등 키가 두 겹이다.
 * <ul>
 *   <li>{@code logicalCheckpointKey} — {@code journeyId:routeRevision:scopeType:scopeId:checkpoint}.
 *       "사용자에게 두 번 보이지 않게"</li>
 *   <li>{@code deliveryKey} — 위 키 + {@code :channel}. "한 채널로 두 번 보내지 않게".
 *       {@code ON CONFLICT} 의 중재자는 이쪽이다 — logical 로 걸면 두 번째 채널이 조용히 사라진다</li>
 * </ul>
 *
 * <p><b>이 표의 행은 지우지 않는다</b> — soft delete 도 물리 삭제도. {@code deliveryKey} 의
 * unique 가 부분 인덱스가 아니라서, 지우는 순간 키가 다시 열려 같은 알림이 재발송된다.
 *
 * <p>발송 보장은 <b>최소 한 번</b>이다. 발송이 트랜잭션 밖이라 provider 에 넘긴 직후 죽으면
 * 회수 후 다시 간다.
 */
@Entity
@Table(name = "journey_notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JourneyNotification extends BaseEntity {

    @Column(nullable = false)
    private Long journeyId;

    /**
     * 수신자. relay 가 {@code journey_session} 을 다시 읽지 않게 비정규화한다.
     * enqueue SQL 이 여정 행에서 그대로 집어 오므로 두 값이 어긋날 수 없다.
     */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int routeRevision;

    /**
     * 만들 당시의 명령 번호. PR5 가 발송 직전 재검증에 쓰고 PR6 이 payload 에 싣는다.
     * 두 PR 다 마이그레이션이 없어 자리를 지금 만들어 둔다.
     */
    @Column(nullable = false)
    private long commandVersion;

    /** 만들 당시의 평가 번호. 용도는 {@link #commandVersion} 과 같다. */
    @Column(nullable = false)
    private long evaluationVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JourneyNotificationScope scopeType;

    /** 뜻이 {@link #scopeType} 마다 다르다. TRANSFER 면 {@code transferIndex}. */
    @Column(nullable = false)
    private int scopeId;

    /**
     * 어휘를 Java 가 소유한다(DB CHECK 없음). 그래서 enum 이 아니라 문자열로 읽어도 되지만,
     * 쓰는 쪽이 하나뿐이라 enum 으로 둔다 — 모르는 값이 들어오면 로드에서 터지는 편이 낫다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private JourneyCheckpoint checkpoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false, length = 255)
    private String logicalCheckpointKey;

    @Column(nullable = false, length = 255)
    private String deliveryKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JourneyNotificationStatus status;

    @Column(nullable = false)
    private Instant scheduledAt;

    /**
     * 이 시각을 넘기면 보내지 않고 CANCELED. 늦게 도착한 환승 알림은 사용자를 엉뚱한 때에
     * 움직인다.
     */
    @Column(nullable = false)
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode payload;

    /** claim SQL 이 올린다. 상태 전이 메서드는 이 값을 건드리지 않는다(버스와 동일). */
    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private int requeueCount;

    private Instant lastRequeuedAt;

    private Instant processingStartedAt;

    private Instant sentAt;

    @Column(length = 500)
    private String lastFailureReason;

    /**
     * 이 생성자로 만드는 경로는 테스트뿐이다. 운영의 삽입은
     * {@code JourneyNotificationRepository#enqueue} 의 native SQL 이 한다 — 여정 재확인 CTE 와
     * {@code ON CONFLICT} 를 한 문장으로 묶어야 하기 때문이다.
     */
    @Builder
    public JourneyNotification(Long journeyId, Long userId, int routeRevision,
                               long commandVersion, long evaluationVersion,
                               JourneyNotificationScope scopeType, int scopeId,
                               JourneyCheckpoint checkpoint, NotificationChannel channel,
                               String logicalCheckpointKey, String deliveryKey,
                               Instant scheduledAt, Instant expiresAt, JsonNode payload) {
        this.journeyId = journeyId;
        this.userId = userId;
        this.routeRevision = routeRevision;
        this.commandVersion = commandVersion;
        this.evaluationVersion = evaluationVersion;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.checkpoint = checkpoint;
        this.channel = channel;
        this.logicalCheckpointKey = logicalCheckpointKey;
        this.deliveryKey = deliveryKey;
        this.scheduledAt = scheduledAt;
        this.expiresAt = expiresAt;
        this.payload = payload;
        this.status = JourneyNotificationStatus.PENDING;
        this.attemptCount = 0;
        this.requeueCount = 0;
    }

    public void markCanceled(String reason) {
        this.status = JourneyNotificationStatus.CANCELED;
        this.lastFailureReason = reason;
    }

    public void markSent(Instant now) {
        this.status = JourneyNotificationStatus.SENT;
        this.sentAt = now;
    }

    /** permanent 실패. 재시도하지 않는다. */
    public void markFailed(String reason) {
        this.status = JourneyNotificationStatus.FAILED;
        this.lastFailureReason = reason;
    }

    /** transient 재시도 소진. 사람이 봐야 한다. */
    public void markDead(String reason) {
        this.status = JourneyNotificationStatus.DEAD;
        this.lastFailureReason = reason;
    }

    /**
     * transient 실패 → 재시도 위해 PENDING 복귀. {@code scheduledAt} 을 백오프 시각으로 미룬다.
     *
     * <p>{@code attemptCount} 도 {@code requeueCount} 도 건드리지 않는다. 시도 횟수는 claim SQL
     * 이 올리고({@code attempt_count}), {@code requeueCount} 는 V28 대칭으로 둔 자리라 이 PR 의
     * 경로에서는 쓰지 않는다.
     */
    public void releaseToPending(String reason, Instant nextAttemptAt) {
        this.status = JourneyNotificationStatus.PENDING;
        this.lastFailureReason = reason;
        this.scheduledAt = nextAttemptAt;
    }
}
