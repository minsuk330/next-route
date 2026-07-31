package watoo.grd.nextroute.domain.journey.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 진행 중인 여정 하나. 서버가 들고 있는 기준 상태다.
 *
 * <p>검색은 사진이고 여정은 동영상이다. 검색은 응답을 주면 끝이지만 여정은 30분~1시간 굴러가고
 * 앱이 꺼져도 서버가 재시작해도 이어져야 한다. 그래서 앱 메모리가 아니라 이 행이 진실이다.
 *
 * <p>{@code BaseEntity} 를 상속하지 않는다. PK 가 순번 bigint 가 아니라 애플리케이션이 만든
 * UUID 이기 때문이다(D7). 여정 번호는 푸시 본문과 앱 링크에 실려 나가므로 순번이면 남의 여정을
 * 추측할 수 있다. 감사 칼럼은 같은 관례({@code timestamp(6)})로 직접 선언한다.
 *
 * <p>제약은 DB 가 소유한다(V35).
 * <ul>
 *   <li>{@code uq_journey_active_per_user} — 사람당 진행 중인 여정 하나</li>
 *   <li>{@code journey_session_status_check}, {@code journey_end_reason_check} — 값 목록</li>
 * </ul>
 */
@Entity
@Table(name = "journey_session")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JourneySession implements Persistable<UUID> {

    @Id
    private UUID id;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    /** User 연관 대신 id 로 참조한다(도메인 경계 단순화, {@code AuthIdentity} 와 같은 관례). */
    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JourneyStatus status;

    /**
     * 사용자 명령이 반영될 때마다 오르는 번호. 탑승·하차·단계 전이·종료가 올린다.
     *
     * <p>앱이 오래된 화면을 보고 누른 명령을 서버가 걸러 내는 기준이다. 평가 결과 갱신은 이 값을
     * 올리지 않는다 — 올리면 사용자가 가만히 있어도 손에 든 번호가 계속 낡아 버린다.
     */
    @Column(nullable = false)
    private long commandVersion;

    /** ETA·환승 상태를 다시 계산할 때마다 오르는 번호. 평가 결과의 최신성 판단용. */
    @Column(nullable = false)
    private long evaluationVersion;

    /** 경로가 바뀔 때만 오르는 번호. 이전 경로로 돌던 비동기 결과를 버리는 기준. */
    @Column(nullable = false)
    private int routeRevision;

    @Column(nullable = false)
    private int currentLegIndex;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(nullable = false)
    private Instant startedAt;

    /** 앱이 마지막으로 살아 있다고 알린 시각(Phase 2 heartbeat). */
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(nullable = false)
    private Instant lastHeartbeatAt;

    /**
     * 이 시각까지는 앱이 안내를 맡는다. 지나도 갱신이 없으면 서버가 여정을 접는다(Phase 4).
     *
     * <p>앱이 죽었는지 그냥 조용한지 서버는 구분할 수 없다. 만료 시각을 미리 정해 두면 "소식이
     * 없으면 언제 접을지"를 매번 판단하지 않아도 된다.
     */
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(nullable = false)
    private Instant guidanceLeaseUntil;

    /** 다음에 서버가 평가해야 할 시각. 채우는 주체는 Phase 4. */
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant nextEvaluationAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private JourneyEndReason endReason;

    /**
     * 시작 시점의 경로 원본. 절대 고치지 않는다.
     *
     * <p>{@code updatable = false} 이고 변경 메서드도 없다. 나중에 경로가 바뀌면
     * {@link JourneyRevision} 에 새 개정본이 쌓이고, 이 값은 "처음에 무엇을 보고 출발했는가"를
     * 말하는 감사·비교용으로만 남는다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode routeSnapshot;

    /** 목적지 표시용 요약(이름·좌표 등). 푸시 문구에 쓴다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode destinationSummary;

    /**
     * Spring Data 가 이 행을 insert 로 다룰지 판단하는 표시.
     *
     * <p>PK 를 애플리케이션이 만들어 넣기 때문에, 이 표시가 없으면 {@code save()} 가 "이미 있는
     * 행"으로 보고 select 를 한 번 더 친다.
     */
    @Transient
    private boolean isNew = true;

    @Builder
    public JourneySession(Long userId, Instant startedAt, Instant guidanceLeaseUntil,
                          JsonNode routeSnapshot, JsonNode destinationSummary) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.status = JourneyStatus.ACTIVE;
        this.commandVersion = 1L;
        this.evaluationVersion = 0L;
        this.routeRevision = 1;
        this.currentLegIndex = 0;
        this.startedAt = startedAt;
        this.lastHeartbeatAt = startedAt;
        this.guidanceLeaseUntil = guidanceLeaseUntil;
        this.routeSnapshot = routeSnapshot;
        this.destinationSummary = destinationSummary;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    /** 여정을 끝낸다. 상태와 이유를 함께 남겨야 나중에 왜 끝났는지 셀 수 있다. */
    public void end(JourneyStatus status, JourneyEndReason reason, Instant endedAt) {
        this.status = status;
        this.endReason = reason;
        this.endedAt = endedAt;
    }

    public void markDeleted(LocalDateTime now) {
        this.deletedAt = now;
    }
}
