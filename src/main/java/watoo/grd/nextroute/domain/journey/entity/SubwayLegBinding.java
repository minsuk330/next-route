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
     * 사용자 보정 세대. 자동 판정기(2026-10 제거) 시절 "다른 열차를 탔다" 보정이 올렸다. 지금은 쓰는 경로가 없고
     * 기존 행·재탐색 계승을 위해 칸만 남긴다.
     */
    @Column(name = "reset_generation", nullable = false)
    private int resetGeneration;

    /**
     * 이동 시작 관측의 마지막 순번. 모션 증거 수신(2026-10 제거)만 채웠다 — 새 행은 늘 null 이고 기존 행 호환으로 남긴다.
     */
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
     * 이미 있는 행(앞서 고른 행, 자동 판정기 시절 행)에 사용자가 고른 열차를 얹는다. <b>사용자 결정이라
     * 확정도 덮는다.</b>
     *
     * <p>이동 순번은 건드리지 않는다. 이미 움직이는 중이면 링이 바로 이어서 돈다.
     */
    public void selectByUser(String trainNo, LocalDate serviceDate, String lineId, String direction) {
        selectByUser(trainNo, serviceDate, lineId, direction, null);
    }

    /**
     * @param aboardAt 이미 탄 열차를 골랐으면(떠난 열차·탑승 중 재선택) 그 시각. 링이 승차역(0)이 아니라 다음 역부터 센다 —
     *                 이동 관측 통로가 없어진 뒤(Dynamic Island 정상화, 선택만으로 연결)에는 이것이 유일한 "탔다" 근거다.
     *                 null 이면 아직 승강장이다(이동 관측이 없을 때만 지운다)
     */
    public void selectByUser(String trainNo, LocalDate serviceDate, String lineId, String direction, Instant aboardAt) {
        this.status = SubwayBindingStatus.CONFIRMED;
        this.source = SubwayBindingSource.USER_SELECTED;
        this.confidence = null;
        assignTrain(trainNo, serviceDate, lineId, direction);
        if (aboardAt != null) {
            this.boardedDetectedAt = aboardAt;
        } else if (this.lastMovementSeq == null) {
            this.boardedDetectedAt = null;
        }
        this.version++;
    }

    /** 사용자가 고른 고정에 이동 시작 관측이 처음 왔다. 감지 시각만 채운다(상태는 그대로). */
    public void markBoardedDetected(Instant detectedAt) {
        if (this.boardedDetectedAt == null && detectedAt != null) {
            this.boardedDetectedAt = detectedAt;
        }
    }

    /**
     * 사용자가 골랐지만 아직 이동 시작 관측도, 이미 탔다는 선택도 없다 — 승강장에서 기다리는 중일 수 있다. 링은 승차역부터
     * 센다(#304).
     */
    public boolean isSelectedBeforeMovement() {
        return this.source == SubwayBindingSource.USER_SELECTED && this.lastMovementSeq == null
                && this.boardedDetectedAt == null;
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
     * <p>{@link #resetGeneration} 과 관측 순번은 <b>그대로 가져온다</b> — 자동 판정기 시절 행의 기록을 잃지 않는다.
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

    private void assignTrain(String trainNo, LocalDate serviceDate, String lineId, String direction) {
        this.trainNo = trainNo;
        this.trainServiceDate = serviceDate;
        this.trainLineId = lineId;
        this.trainDirection = direction;
    }
}
