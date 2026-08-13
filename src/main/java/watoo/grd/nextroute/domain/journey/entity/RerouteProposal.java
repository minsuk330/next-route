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

/**
 * 아직 수락되지 않은 재탐색 대안 하나.
 *
 * <p>안내에 쓰이는 경로는 {@link JourneyRevision} 이 갖는다. 제안을 그쪽에 섞지 않는 이유는
 * 성격이 다르기 때문이다 — 제안에는 만료·점수·중복 제거가 있고, 섞으면 "지금 안내 중인 경로"를
 * 묻는 모든 질문이 status 필터를 달고 다녀야 한다.
 *
 * <h2>후보가 두 겹인 이유</h2>
 * {@code candidates} 의 후보마다 <b>적용 재료</b>({@code apply})와 <b>화면 요약</b>
 * ({@code display})이 함께 들어간다. 화면 요약만 저장하면 사용자가 다른 경로를 골랐을 때 새 leg 를
 * 만들 재료가 없어, 수락 시점에 다시 검색해야 한다 — 그러면 <b>사용자가 고른 것과 다른 경로</b>로
 * 여정이 바뀔 수 있다.
 *
 * <p>{@code apply} 는 API 밖으로 나가지 않는다. 후보 하나에 경로 원본이 통째로 들어가 후보 3개면
 * 수십 KB 이고, 앱은 그 값을 쓰지 않는다.
 *
 * <h2>만료돼도 지우지 않는다</h2>
 * 지우면 늦게 온 수락 요청이 "그런 제안 없음"인지 "만료됨"인지 구별되지 않는다. 부분 unique 가
 * {@code status = 'ACTIVE'} 조건부라 남겨 둬도 새 제안을 막지 않는다.
 */
@Entity
@Getter
@Table(name = "reroute_proposal")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RerouteProposal extends BaseEntity {

    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    /** 조회마다 {@code journey_session} 을 조인하지 않으려고 소유자를 여기서도 든다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 이 제안이 유효한 개정본. 수락 재검증의 기준이기도 하다.
     *
     * <p>{@code base_route_revision} 을 따로 두지 않는다 — 두 값은 언제나 같고, 둘로 나누면
     * 어긋난 상태가 생길 수 있는 데다 어느 쪽이 진짜인지 읽는 사람이 헷갈린다.
     */
    @Column(name = "route_revision", nullable = false)
    private int routeRevision;

    @Column(name = "transfer_index", nullable = false)
    private int transferIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private RerouteProposalKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RerouteProposalStatus status;

    /** 만든 시점의 명령 번호. 사용자가 누르기까지 최대 2분이 있어 그 사이 여정이 바뀔 수 있다. */
    @Column(name = "base_command_version", nullable = false)
    private long baseCommandVersion;

    /** {@code {"version":1,"candidates":[...]}} 봉투. 배열을 바로 넣으면 메타를 붙일 자리가 없다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidates", nullable = false, columnDefinition = "jsonb")
    private JsonNode candidates;

    /** 후보 평가에 쓴 실시간 스냅샷 시각. 신선도 표시와 사후 검증의 기준. */
    @Column(name = "snapshot_observed_at", nullable = false)
    private Instant snapshotObservedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * 왜 끝났는지. {@code ttl}·{@code ttl-sweep}·{@code route-changed}·{@code replaced}·
     * {@code journey-ended}·{@code accepted}.
     *
     * <p>{@code ttl}(lazy)과 {@code ttl-sweep}(배치)을 가르는 것이 중요하다 — 후자가 대부분이면
     * 사용자가 제안을 보지 못하고 있다는 뜻이라 발송이나 앱 폴링 쪽을 봐야 한다.
     */
    @Column(name = "resolved_reason", length = 40)
    private String resolvedReason;

    /**
     * 수락된 후보(V43). 수락되지 않은 제안에는 null 이다.
     *
     * <p>없으면 늦게 온 수락 요청을 가릴 수 없다 — 같은 후보의 재전송과 다른 후보를 고른 요청이
     * 둘 다 "이미 ACCEPTED" 로만 보인다.
     */
    @Column(name = "accepted_candidate_id", length = 36)
    private String acceptedCandidateId;

    @Builder
    public RerouteProposal(Long journeyId, Long userId, int routeRevision, int transferIndex,
                           RerouteProposalKind kind, long baseCommandVersion, JsonNode candidates,
                           Instant snapshotObservedAt, Instant expiresAt) {
        this.journeyId = journeyId;
        this.userId = userId;
        this.routeRevision = routeRevision;
        this.transferIndex = transferIndex;
        this.kind = kind;
        this.baseCommandVersion = baseCommandVersion;
        this.candidates = candidates;
        this.snapshotObservedAt = snapshotObservedAt;
        this.expiresAt = expiresAt;
        this.status = RerouteProposalStatus.ACTIVE;
    }

    /** TTL 이 지났다. */
    public void expire(Instant now, String reason) {
        resolve(RerouteProposalStatus.EXPIRED, now, reason);
    }

    /** 새 제안이나 개정본 변경이 자리를 가져갔다. */
    public void supersede(Instant now, String reason) {
        resolve(RerouteProposalStatus.SUPERSEDED, now, reason);
    }

    /** 사용자가 골랐다(⑤). <b>무엇을 골랐는지</b>도 함께 남긴다. */
    public void accept(Instant now, String candidateId) {
        this.acceptedCandidateId = candidateId;
        resolve(RerouteProposalStatus.ACCEPTED, now, "accepted");
    }

    /** 상태와 함께 "언제 왜"를 반드시 채운다. 셋을 따로 두면 이유 없는 종료 행이 생긴다. */
    private void resolve(RerouteProposalStatus newStatus, Instant now, String reason) {
        this.status = newStatus;
        this.resolvedAt = now;
        this.resolvedReason = reason;
    }

    public boolean isActive() {
        return status == RerouteProposalStatus.ACTIVE;
    }
}
