package watoo.grd.nextroute.domain.journey.entity;

import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.DynamicUpdate;
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
 * 진행 중인 여정 하나. 서버가 들고 있는 기준 상태다.
 *
 * <p>검색은 사진이고 여정은 동영상이다. 검색은 응답을 주면 끝이지만 여정은 30분~1시간 굴러가고
 * 앱이 꺼져도 서버가 재시작해도 이어져야 한다. 그래서 앱 메모리가 아니라 이 행이 진실이다.
 *
 * <p>제약은 DB 가 소유한다(V35).
 * <ul>
 *   <li>{@code uq_journey_active_per_user} — 사람당 진행 중인 여정 하나</li>
 *   <li>{@code journey_session_status_check}, {@code journey_end_reason_check} — 값 목록</li>
 * </ul>
 *
 * <p>{@code id} 는 다른 표와 같은 순번 bigint 다. 여정 번호는 푸시 본문과 앱 링크에 실려 나가
 * 이웃 번호를 찍어 볼 수 있으므로, 조회는 반드시 소유자를 <b>쿼리 조건에</b> 넣는다
 * ({@code findByIdAndUserIdAndDeletedAtIsNull}). 꺼낸 뒤에 비교하는 방식은 언젠가 빠뜨린다.
 *
 * <p><b>{@code @DynamicUpdate}</b>: 바뀐 칸만 쓴다. 이 행은 평가기·재평가 트리거가 SQL 로 직접 고치는
 * 칸(다음 평가 시각·선점·dirty)과 엔티티로 고치는 칸이 섞여 있다. 잠금 없이 읽은 엔티티를 저장하는
 * 경로(재탐색 제안 닫기 등)가 통째로 쓰면, 그사이 SQL 이 바꾼 다음 평가 시각을 옛 값으로 되돌린다.
 */
@Entity
@Table(name = "journey_session")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JourneySession extends BaseEntity {

    /** User 연관 대신 id 로 참조한다(도메인 경계 단순화, {@code AuthIdentity} 와 같은 관례). */
    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JourneyStatus status;

    /**
     * 일반 여정인가 단독 추적인가(V59). 시작 때 정해지고 바뀌지 않는다.
     *
     * <p>단독 추적은 재탐색에서 빠진다 — 거르는 곳은 보드 선점·예약 SQL 이다({@link JourneyKind}).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private JourneyKind kind;

    /**
     * 여정을 시작한 검색의 급행 끄기 선호(V73, subway-express S5). 재탐색이 같은 급행 정책으로 찾는 기준이다.
     *
     * <p><b>원래 요청값</b>이다 — 그 검색이 일반 경로 0건이라 필터를 풀었어도(D10) 사용자는 급행을 끈 채다. 시작 때 정해지고
     * 바뀌지 않는다. 단독 추적·배포 전 여정은 false(급행 포함 = 옛 동작).
     */
    @Column(nullable = false, updatable = false)
    private boolean excludeExpress;

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

    @Column(nullable = false)
    private Instant startedAt;

    /** 앱이 마지막으로 살아 있다고 알린 시각(Phase 2 heartbeat). */
    @Column(nullable = false)
    private Instant lastHeartbeatAt;

    /**
     * 이 시각까지는 앱이 안내를 맡는다. 지나도 갱신이 없으면 서버가 여정을 접는다(Phase 4).
     *
     * <p>앱이 죽었는지 그냥 조용한지 서버는 구분할 수 없다. 만료 시각을 미리 정해 두면 "소식이
     * 없으면 언제 접을지"를 매번 판단하지 않아도 된다.
     */
    @Column(nullable = false)
    private Instant guidanceLeaseUntil;

    /** 지하철 터널의 GPS·callback 공백 동안 일반 lease 만료를 유예하는 상한 시각. */
    private Instant subwayOfflineProtectionUntil;

    /** 다음에 서버가 평가해야 할 시각. 채우는 주체는 Phase 4. */
    private Instant nextEvaluationAt;

    /**
     * 다음 선점에서 먼저 잡을지. 평가기가 밀릴 때 환승이 위험하거나 임박한 여정이 예정 시각만 보고
     * 먼 여정 뒤에 줄 서지 않게 한다. 평가기가 매번 다시 정한다.
     */
    private boolean evaluationUrgent;

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
     * 이 여정의 재탐색 알림이 끝났다(V53).
     *
     * <p>수락은 한 번뿐이고(고른 길로 바꿨는데 또 바꾸라고 하면 피로가 이득을 넘는다), 거절하면서
     * "계속 받기"를 켜지 않은 것도 같은 뜻이다. 새 경로가 나중에 또 위험해져도 알리지 않는다 —
     * 의도한 엣지케이스다.
     */
    @Column(nullable = false)
    private boolean rerouteTerminated;

    /** 거절하면서 "계속 알림 받기"를 켰다. 무응답 상한을 넘겨도 계속 알린다. */
    @Column(nullable = false)
    private boolean rerouteAlertOptIn;

    /**
     * 알렸는데 답이 없었던 횟수(TTL 만료 + 명시적 무시).
     *
     * <p><b>Redis 가 아니라 여기 있는 이유</b>: 상한("여정당 2회")은 TTL 로 사라지면 뜻을 잃는다.
     * cooldown 처럼 "잠깐 참기"는 캐시가 맞지만 이 값은 여정의 상태다.
     */
    @Column(nullable = false)
    private int rerouteIgnoredCount;

    /**
     * 여정을 시작한 검색의 스냅샷 id(V80, route_search_event.search_session_id). 사용 퍼널에서 검색과 여정을 잇는다.
     * 단독 추적(TRACK)은 검색을 거치지 않아 null.
     */
    @Column(name = "search_session_id", length = 64, updatable = false)
    private String searchSessionId;

    /**
     * @param kind 비우면 {@link JourneyKind#NAVIGATION}. 기본값을 빌더가 아니라 여기서 정한다 —
     *             {@code @Builder.Default} 없이 필드 초기화로 두면 빌더가 조용히 {@code null} 을 넣는다
     * @param excludeExpress 시작 검색의 급행 끄기(원래 요청값). 빌더에서 빼면 false
     * @param searchSessionId 시작 검색의 스냅샷 id. 빌더에서 빼면 null
     */
    @Builder
    public JourneySession(Long userId, JourneyKind kind, Instant startedAt, Instant guidanceLeaseUntil,
                          JsonNode routeSnapshot, JsonNode destinationSummary, boolean excludeExpress,
                          String searchSessionId) {
        this.userId = userId;
        this.searchSessionId = searchSessionId;
        this.kind = kind == null ? JourneyKind.NAVIGATION : kind;
        this.excludeExpress = excludeExpress;
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

    /**
     * 사용자 명령이 반영됐다.
     *
     * <p>이 번호가 오르는 순간, 앱이 손에 들고 있던 이전 번호로 온 명령은 전부 낡은 것이 된다.
     * 그래서 상태를 실제로 바꾼 명령만 이 메서드를 부른다.
     */
    public void bumpCommandVersion() {
        this.commandVersion++;
    }

    /**
     * 안내 경로가 바뀌었다(재탐색 수락).
     *
     * <p>이 번호가 오르면 앱이 들고 있던 geometry·단계가 통째로 낡은 것이 된다. 그래서 개정본을
     * 올리는 쪽은 <b>같은 트랜잭션에서</b> 새 개정본의 단계까지 전부 만들어 둬야 한다.
     */
    public void bumpRouteRevision() {
        this.routeRevision++;
    }

    /** 진행 중인 단계를 옮긴다. 뒤로 가지 않는 것은 부르는 쪽이 보장한다. */
    public void moveToLeg(int legIndex) {
        this.currentLegIndex = legIndex;
    }

    /**
     * 다음에 서버가 평가할 시각을 정한다.
     *
     * <p>여정을 시작할 때와 단계가 바뀔 때 지금 시각으로 심는다 — 두 순간은 상황이 크게 달라져
     * 다음 주기를 기다릴 이유가 없다. 그 뒤로는 평가기가 스스로 다음 시각을 기록한다.
     *
     * <p>같은 이유로 급한 줄에 세운다. 평가기가 밀려 있어도 방금 버스를 탄 사람의 판단이 예정 시각만
     * 보고 뒤로 가면 안 된다.
     */
    public void scheduleEvaluationAt(Instant evaluateAt) {
        this.nextEvaluationAt = evaluateAt;
        this.evaluationUrgent = true;
    }

    public void protectSubwayUntil(Instant protectionUntil) {
        this.subwayOfflineProtectionUntil = protectionUntil;
    }

    public void clearSubwayProtection() {
        this.subwayOfflineProtectionUntil = null;
    }

    /**
     * 여정을 끝낸다. 상태와 이유를 함께 남겨야 나중에 왜 끝났는지 셀 수 있다.
     *
     * <p>다음 평가 시각도 함께 비운다. 끝난 여정이 평가 대상에 남아 있으면 이미 끝난 여정 때문에
     * 외부 API 를 쓴다. 만료 배치는 같은 일을 SQL 한 문장에서 한다.
     */
    public void end(JourneyStatus status, JourneyEndReason reason, Instant endedAt) {
        this.status = status;
        this.endReason = reason;
        this.endedAt = endedAt;
        this.subwayOfflineProtectionUntil = null;
        this.nextEvaluationAt = null;
    }

    /**
     * 이 여정에서는 더 이상 재탐색을 알리지 않는다(수락 · 거절+계속받기 미체크).
     *
     * <p>{@code optIn} 을 함께 끄지 <b>않는다</b> — 켠 적이 없으면 어차피 false 이고, 켠 적이
     * 있다면 그 뜻은 "알림을 계속 받고 싶다"라서 종료와 함께 지울 이유가 없다. 실제 판단은
     * {@link #isRerouteAlertAllowed(int)} 가 종료를 먼저 보는 것으로 끝난다.
     */
    public void terminateReroute() {
        this.rerouteTerminated = true;
    }

    /** 거절했지만 다음 제안은 계속 받겠다. 종료 플래그는 서지 않는다. */
    public void optInRerouteAlert() {
        this.rerouteAlertOptIn = true;
        this.rerouteTerminated = false;
    }

    /** 알렸는데 답이 없었다(만료 · 명시적 무시). 상한은 부르는 쪽이 본다. */
    public void recordRerouteIgnored() {
        this.rerouteIgnoredCount++;
    }

    /**
     * 지금 재탐색을 <b>먼저 알려도 되는가</b>.
     *
     * <p>순서가 계약이다 — 종료가 가장 세고, 그다음이 "계속 받기"(상한을 무시한다), 마지막이
     * 무응답 상한이다. 사용자 요청 경로({@code RerouteProposalService.request})는 이 판단을 쓰지
     * 않는다: 사용자가 직접 누른 것은 알림이 아니다.
     */
    public boolean isRerouteAlertAllowed(int maxIgnored) {
        if (rerouteTerminated) {
            return false;
        }
        return rerouteAlertOptIn || rerouteIgnoredCount < maxIgnored;
    }
}
