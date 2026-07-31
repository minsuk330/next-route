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
 * 실제 안내에 쓰인 경로 한 판.
 *
 * <p>여정이 시작되면 1번 개정본이 {@code ACTIVE} 로 생기고, 재탐색을 사용자가 수락하면
 * 이전 개정본이 {@code SUPERSEDED} 로 밀리고 다음 번호가 {@code ACTIVE} 가 된다. 여정당
 * {@code ACTIVE} 는 하나다({@code uq_journey_active_revision}).
 *
 * <p>아직 수락되지 않은 재탐색 <b>제안</b>은 여기 넣지 않는다. 제안은 점수·만료·중복 제거가
 * 필요해 성격이 다르고, Phase 6 의 {@code reroute_proposal} 이 따로 맡는다.
 *
 * <p>{@code JourneySession.routeSnapshot} 과 1번 개정본은 시작 시점에 같은 값이다. 세션 쪽은
 * 이후 바뀌지 않는 원본이고, 현재 경로를 물을 때는 항상 {@code ACTIVE} 개정본을 본다.
 */
@Entity
@Table(name = "journey_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JourneyRevision extends BaseEntity {

    @Column(nullable = false)
    private UUID journeyId;

    @Column(nullable = false)
    private int routeRevision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JourneyRevisionStatus status;

    /** 이 개정본이 왜 생겼는지. 어휘는 재탐색을 실제로 만드는 Phase 6 이 정한다. */
    @Column(length = 50)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode routeSnapshot;

    @Column(nullable = false)
    private Instant activatedAt;

    private Instant supersededAt;

    @Builder
    public JourneyRevision(UUID journeyId, int routeRevision, JourneyRevisionStatus status,
                           String reason, JsonNode routeSnapshot, Instant activatedAt) {
        this.journeyId = journeyId;
        this.routeRevision = routeRevision;
        this.status = status;
        this.reason = reason;
        this.routeSnapshot = routeSnapshot;
        this.activatedAt = activatedAt;
    }

    /** 새 경로에 자리를 내준다. */
    public void supersede(Instant at) {
        this.status = JourneyRevisionStatus.SUPERSEDED;
        this.supersededAt = at;
    }
}
