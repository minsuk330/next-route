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

import java.time.Instant;
import java.util.UUID;

/**
 * 앱이 보낸 명령 하나와 서버가 그걸 어떻게 처리했는지의 기록.
 *
 * <p>지하철 안에서 "탔어요"를 누르면 응답을 못 받고 앱이 다시 보내는 일이 흔하다. 앱이 매번
 * {@code eventId} 를 붙여 보내고 서버가 그 값에 유일 제약을 걸어 두면, 같은 명령이 두 번
 * 반영되지 않는다({@code uq_journey_event_id}).
 *
 * <p>실제로 이 표를 채우는 것은 Phase 2 의 상태머신이다. 이번 단계는 표와 매핑만 만든다.
 */
@Entity
@Table(name = "journey_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JourneyEvent extends BaseEntity {

    /** 앱이 만든 명령 번호. 재전송을 걸러 내는 기준이라 DB 가 유일성을 소유한다. */
    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private Long journeyId;

    /** 앱이 이 명령을 만들 때 들고 있던 {@code commandVersion}. 낡았으면 충돌로 처리한다. */
    @Column(nullable = false)
    private long baseCommandVersion;

    @Column(nullable = false)
    private int routeRevision;

    /** 명령의 종류. 어휘는 Phase 2 가 정한다(DB CHECK 없음). */
    @Column(nullable = false, length = 40)
    private String type;

    /** 앱 기준 발생 시각. 기기 시계라 믿을 수 없어 판단 기준으로 쓰지 않는다. */
    private Instant clientOccurredAt;

    @Column(nullable = false)
    private Instant serverReceivedAt;

    /** 명령에 딸린 값. 정밀한 사용자 위치 좌표는 넣지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JourneyEventResult result;

    /** 반영 후의 {@code commandVersion}. 재전송이 와도 같은 값을 돌려주기 위해 남긴다. */
    private Long resultingCommandVersion;

    @Column(length = 255)
    private String rejectionReason;

    @Builder
    public JourneyEvent(UUID eventId, Long journeyId, long baseCommandVersion, int routeRevision,
                        String type, Instant clientOccurredAt, Instant serverReceivedAt,
                        JsonNode payload, JourneyEventResult result,
                        Long resultingCommandVersion, String rejectionReason) {
        this.eventId = eventId;
        this.journeyId = journeyId;
        this.baseCommandVersion = baseCommandVersion;
        this.routeRevision = routeRevision;
        this.type = type;
        this.clientOccurredAt = clientOccurredAt;
        this.serverReceivedAt = serverReceivedAt;
        this.payload = payload;
        this.result = result;
        this.resultingCommandVersion = resultingCommandVersion;
        this.rejectionReason = rejectionReason;
    }
}
