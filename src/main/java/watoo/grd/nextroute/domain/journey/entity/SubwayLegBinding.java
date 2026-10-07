package watoo.grd.nextroute.domain.journey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 지하철 단계 하나가 어느 열차에 묶였는지.
 *
 * <h2>왜 {@link JourneyLeg} 의 칼럼이 아닌가</h2>
 *
 * <p>여정 단계는 재탐색마다 통째로 복제된다. 거기에 열차번호를 얹으면 <b>복제가 기본</b>이 되어,
 * 경로가 바뀌었는데 옛 열차가 살아남는다. 사용자는 엉뚱한 역에서 내린다.
 *
 * <p>표를 나누면 반대가 된다 — 새 개정본의 단계는 이 행이 없으므로 "고정 없음"이 기본이고,
 * 이어받는 것은 명시적으로 만들 때만 일어난다. <b>빠뜨렸을 때 안전한 쪽</b>으로 기운다.
 *
 * <h2>행을 지우지 않는다</h2>
 *
 * <p>지우고 새로 만들면 {@link #version} 과 {@link #resetGeneration} 이 0 으로 돌아가, 늦게
 * 도착한 갱신이 되살아난다. 사용자가 "다른 열차를 탔다"고 알려도 행은 남기고 상태만 되돌린다.
 *
 * <h2>고정은 타는 단계가 소유한다</h2>
 *
 * <p>노선 후보({@link JourneyLegRouteOption})는 기다리는 단계가 소유한다. 축을 나눠 두면
 * 한 개념이 두 행에 흩어지지 않는다. 대기 단계로 들어온 요청은 짝 {@code RIDE_SUBWAY} 로 옮겨 적는다.
 */
@Entity
@Getter
@Table(name = "subway_leg_binding")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayLegBinding extends BaseEntity {

    @Column(name = "journey_leg_id", nullable = false)
    private Long journeyLegId;

    /** 여정 단위 정리와 개정본 판별을 단계 조인 없이 하기 위해 함께 든다. */
    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    @Column(name = "route_revision", nullable = false)
    private int routeRevision;

    @Column(name = "leg_index", nullable = false)
    private int legIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubwayBindingStatus status;

    /** 열차번호. 아래 세 값과 함께 있거나 함께 없다 — 하나만 있으면 어느 열차도 못 가리킨다. */
    @Column(name = "train_no", length = 32)
    private String trainNo;

    @Column(name = "train_service_date")
    private LocalDate trainServiceDate;

    @Column(name = "train_line_id", length = 16)
    private String trainLineId;

    @Column(name = "train_direction", length = 10)
    private String trainDirection;

    /** 센서가 이동 시작을 감지한 시각. <b>실제 승차 시각이 아니다.</b> */
    @Column(name = "boarded_detected_at")
    private Instant boardedDetectedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SubwayBindingSource source;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private SubwayBindingConfidence confidence;

    /** 낙관적 잠금. 늦게 온 갱신이 이미 교정된 결과를 되돌리지 못하게 한다. */
    @Column(nullable = false)
    private int version;

    /**
     * 사용자 보정 세대.
     *
     * <p>사용자가 "다른 열차를 탔다"고 알리면 올라간다. 이 값이 없으면 판정기가 같은 관측을
     * 다시 읽어 <b>방금 거부한 열차를 그대로 다시 고른다.</b>
     */
    @Column(name = "reset_generation", nullable = false)
    private int resetGeneration;

    /** 이동 시작 관측의 마지막 순번. 정차 순번과 칸을 나눠 든다 — 각각 1 부터 세기 때문이다. */
    @Column(name = "last_movement_seq")
    private Integer lastMovementSeq;

    @Column(name = "last_episode_seq")
    private Integer lastEpisodeSeq;

    @Builder
    private SubwayLegBinding(Long journeyLegId, Long journeyId, int routeRevision, int legIndex,
                             SubwayBindingStatus status, Instant boardedDetectedAt,
                             SubwayBindingSource source, SubwayBindingConfidence confidence) {
        this.journeyLegId = journeyLegId;
        this.journeyId = journeyId;
        this.routeRevision = routeRevision;
        this.legIndex = legIndex;
        this.status = status;
        this.boardedDetectedAt = boardedDetectedAt;
        this.source = source;
        this.confidence = confidence;
        this.version = 0;
        this.resetGeneration = 0;
    }

    /** 이동 시작을 받았지만 아직 열차를 고르지 못한 상태로 연다. */
    public static SubwayLegBinding pending(Long journeyLegId, Long journeyId, int routeRevision,
                                           int legIndex, Instant detectedAt,
                                           SubwayBindingSource source,
                                           SubwayBindingConfidence confidence) {
        return SubwayLegBinding.builder()
                .journeyLegId(journeyLegId)
                .journeyId(journeyId)
                .routeRevision(routeRevision)
                .legIndex(legIndex)
                .status(SubwayBindingStatus.PENDING)
                .boardedDetectedAt(detectedAt)
                .source(source)
                .confidence(confidence)
                .build();
    }

    /**
     * 역에서 사용자가 고른 열차로 연다(#265). 곧바로 {@code CONFIRMED} 다.
     *
     * <p>열차 식별 4칸을 <b>모두</b> 채운다 — {@code subway_leg_binding_train_identity_check}(V44)가 함께 요구하고,
     * 링도 하나라도 비면 식별을 못 만든다. 이동 순번은 비워 둔다 — 이동 시작 관측이 와야 링이 켜진다.
     */
    public static SubwayLegBinding selected(Long journeyLegId, Long journeyId, int routeRevision, int legIndex,
                                            String trainNo, LocalDate serviceDate, String lineId,
                                            String direction) {
        SubwayLegBinding created = SubwayLegBinding.builder()
                .journeyLegId(journeyLegId)
                .journeyId(journeyId)
                .routeRevision(routeRevision)
                .legIndex(legIndex)
                .status(SubwayBindingStatus.CONFIRMED)
                .source(SubwayBindingSource.USER_SELECTED)
                .build();
        created.assignTrain(trainNo, serviceDate, lineId, direction);
        return created;
    }

    /**
     * 이미 있는 행(관측이 먼저 왔거나, 앞서 고르거나 되돌린 행)에 사용자가 고른 열차를 얹는다. <b>사용자 결정이라
     * 확정도 덮는다</b> — 보정({@link #resetByUser})과 같은 자격이다.
     *
     * <p>이동 순번은 건드리지 않는다. 이미 움직이는 중이면 링이 바로 이어서 돈다.
     */
    public void selectByUser(String trainNo, LocalDate serviceDate, String lineId, String direction) {
        this.status = SubwayBindingStatus.CONFIRMED;
        this.source = SubwayBindingSource.USER_SELECTED;
        this.confidence = null;
        assignTrain(trainNo, serviceDate, lineId, direction);
        this.version++;
    }

    /** 사용자가 고른 고정에 이동 시작 관측이 처음 왔다. 감지 시각만 채운다(상태는 그대로). */
    public void markBoardedDetected(Instant detectedAt) {
        if (this.boardedDetectedAt == null && detectedAt != null) {
            this.boardedDetectedAt = detectedAt;
        }
    }

    /** 사용자가 골랐지만 아직 이동 시작 관측이 없다 — 승강장에서 기다리는 중. 링을 켜지 않는다. */
    public boolean isSelectedBeforeMovement() {
        return this.source == SubwayBindingSource.USER_SELECTED && this.lastMovementSeq == null;
    }

    /**
     * 재탐색으로 개정본이 올랐지만 지하철 구간이 <b>완전히 같아</b> 그대로 이어받는다.
     *
     * <p>옛 행을 옮기지 않고 <b>새 행을 만든다.</b> 이 표는 단계와 1:1 이고 새 개정본의 단계는
     * 새 행이라, 옮기면 옛 개정본의 감사 기록이 어느 단계를 가리키는지 알 수 없게 된다.
     *
     * <p>{@link #version} 은 0 에서 다시 시작한다. 늦게 온 갱신이 되살아나는 문제가 없는 것은
     * 그 갱신이 <b>옛 단계 번호</b>를 조건으로 걸기 때문이다 — 새 행에는 닿지 않는다.
     *
     * <p>{@link #resetGeneration} 과 관측 순번은 <b>그대로 가져온다</b>. 근거 감사행도 함께
     * 재발행되는데, 세대가 어긋나면 판정기가 그 근거를 하나도 못 읽어 확정 경로가 통째로 죽는다.
     *
     * @param newLeg 새 개정본의 짝 단계
     */
    public SubwayLegBinding inheritTo(JourneyLeg newLeg) {
        SubwayLegBinding copy = SubwayLegBinding.builder()
                .journeyLegId(newLeg.getId())
                .journeyId(this.journeyId)
                .routeRevision(newLeg.getRouteRevision())
                .legIndex(newLeg.getLegIndex())
                .status(this.status)
                .boardedDetectedAt(this.boardedDetectedAt)
                // 사용자가 고른 고정은 출처를 그대로 둔다(#265) — 오판율 지표가 출처로 사용자 선택을 가른다.
                .source(this.source == SubwayBindingSource.USER_SELECTED
                        ? SubwayBindingSource.USER_SELECTED : SubwayBindingSource.INHERITED_ON_REROUTE)
                .confidence(this.confidence)
                .build();
        copy.trainNo = this.trainNo;
        copy.trainServiceDate = this.trainServiceDate;
        copy.trainLineId = this.trainLineId;
        copy.trainDirection = this.trainDirection;
        copy.resetGeneration = this.resetGeneration;
        copy.lastMovementSeq = this.lastMovementSeq;
        copy.lastEpisodeSeq = this.lastEpisodeSeq;
        return copy;
    }

    /**
     * 사용자가 다른 열차를 탔다고 알렸다.
     *
     * <p>행을 지우지 않고 되돌린다. 지우면 {@link #version} 이 0 으로 돌아가 늦게 온 갱신이
     * 되살아난다. {@link #resetGeneration} 을 올려 이전 세대의 후보를 무효로 만든다.
     *
     * <p>관측 순번도 비운다 — 그래야 새 관측이 오기 전까지 판정기가 다시 고르지 않는다.
     */
    public void resetByUser() {
        this.status = SubwayBindingStatus.PENDING;
        clearTrain();
        this.source = SubwayBindingSource.USER_MANUAL;
        this.confidence = null;
        this.boardedDetectedAt = null;
        this.lastMovementSeq = null;
        this.lastEpisodeSeq = null;
        this.resetGeneration++;
        this.version++;
    }

    /** 사용자가 타지 않았다고 알렸거나, 판정 시간이 지났다. */
    public void abandon() {
        this.status = SubwayBindingStatus.ABANDONED;
        clearTrain();
        this.version++;
    }

    /**
     * 관측 순번을 앞으로만 옮긴다.
     *
     * @return 실제로 옮겼으면 true. 이미 본 순번이면 false
     */
    public boolean advanceMovementSeq(Integer sequence) {
        if (sequence == null || (lastMovementSeq != null && sequence <= lastMovementSeq)) {
            return false;
        }
        this.lastMovementSeq = sequence;
        return true;
    }

    public boolean advanceEpisodeSeq(Integer sequence) {
        if (sequence == null || (lastEpisodeSeq != null && sequence <= lastEpisodeSeq)) {
            return false;
        }
        this.lastEpisodeSeq = sequence;
        return true;
    }

    private void assignTrain(String trainNo, LocalDate serviceDate, String lineId, String direction) {
        this.trainNo = trainNo;
        this.trainServiceDate = serviceDate;
        this.trainLineId = lineId;
        this.trainDirection = direction;
    }

    private void clearTrain() {
        this.trainNo = null;
        this.trainServiceDate = null;
        this.trainLineId = null;
        this.trainDirection = null;
    }
}
