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
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 여정을 쪼갠 한 단계. "걷기 / 기다리기 / 타고 가기" 중 하나다.
 *
 * <p>단계 경계는 앱의 GPS progress engine이 확정하는 행동 경계와 일치시킨다.
 *
 * <p>{@code (journey_id, route_revision, leg_index)} 가 유일하다(V35). 경로가 바뀌면
 * {@code route_revision} 이 오른 새 단계 묶음이 통째로 쌓이고 이전 묶음은 남는다.
 */
@Entity
@Table(name = "journey_leg")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JourneyLeg extends BaseEntity {

    /**
     * 근거의 품질이 "모름"인 상태.
     *
     * <p>정류장·노선 식별자가 없어 실시간 대조를 못 하는 경우에 쓴다. 이때도 단계 자체는
     * {@code ACTIVE/PENDING} 으로 두고 GPS progress engine이 leg 경계를 확정한다.
     * 나머지 어휘는 실시간 근거를 실제로 채우는 Phase 3 이 정한다(DB CHECK 없음).
     */
    public static final String EVIDENCE_QUALITY_UNKNOWN = "UNKNOWN";

    @Column(nullable = false)
    private Long journeyId;

    @Column(nullable = false)
    private int routeRevision;

    @Column(nullable = false)
    private int legIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JourneyLegType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private JourneyLegState state;

    @Column(length = 64)
    private String startStopId;

    @Column(length = 64)
    private String endStopId;

    /** ODSAY가 준 출발 ID. 실시간·ML에는 쓰지 않고, 재해석과 진단에만 남긴다. */
    @Column(length = 64)
    private String sourceStartStopId;

    /** ODSAY가 준 도착 ID. 실시간·ML에는 쓰지 않고, 재해석과 진단에만 남긴다. */
    @Column(length = 64)
    private String sourceEndStopId;

    private String startName;

    private String endName;

    /** 현재 선택된 노선의 내부 식별자. 실제 차량·운행 회차 식별자는 아니다. */
    @Column(length = 64)
    private String routeId;

    /** legacy 호환 칼럼. 1차 내비게이션에서는 실제 차량을 식별하지 않으므로 사용하지 않는다. */
    @Column(length = 64)
    private String vehicleId;

    /** 검색 결과가 말한 소요시간. 실제 소요가 아니라 계획값이다. */
    private Integer plannedDurationSec;

    private Instant predictedEndAt;

    private Integer remainingStops;

    /** 위 예측값들이 어디서 왔는지(Phase 3). */
    @Column(length = 30)
    private String evidenceSource;

    private Instant evidenceObservedAt;

    /** 근거를 얼마나 믿을 수 있는지. 지금 정의된 값은 {@link #EVIDENCE_QUALITY_UNKNOWN} 뿐이다. */
    @Column(length = 20)
    private String evidenceQuality;

    /**
     * 마지막으로 반영한 progress 의 식별자. command 의 {@code eventId} 와 별개다.
     *
     * <p>앱은 응답을 못 받으면 같은 progress 를 다시 보낸다. 그때 같은 값으로 다시 반영하면
     * {@code evaluationVersion} 이 두 번 올라가고, 앱은 상태가 바뀐 줄 알고 화면을 다시 그린다.
     * 그래서 "이미 이 progress 를 봤다"를 여기 남긴다.
     */
    private UUID lastProgressId;

    /** Phase 3에서 앱 progress 요약을 저장할 자리. 정밀 좌표는 저장하지 않는다. */
    private Integer progressGeometrySegmentIndex;

    @Column(precision = 7, scale = 6)
    private BigDecimal progressFraction;

    @Column(precision = 7, scale = 6)
    private BigDecimal progressLegFraction;

    private Instant progressObservedAt;

    @Column(length = 64)
    private String progressSectionId;

    private Integer remainingDistanceMeters;

    private Integer remainingDurationSeconds;

    private Instant activatedAt;

    private Instant completedAt;

    /**
     * 이 단계에서 타기로 확정한 노선 후보.
     *
     * <p>ODsay 첫 lane을 현재 경로로 선택하고 다른 후보는 대안으로 보존한다. 사용자의 실제 차량을
     * 확정하거나 차량 선택 UI를 제공한다는 뜻은 아니다.
     *
     * <p>연관 대신 id 로 참조한다. 단계와 후보가 서로를 가리키는 순환 구조라 한 번의 cascade 로
     * 저장할 수 없고, 저장 순서를 코드가 명시적으로 잡아야 하기 때문이다.
     */
    private Long selectedRouteOptionId;

    @Builder
    public JourneyLeg(Long journeyId, int routeRevision, int legIndex,
                      JourneyLegType type, JourneyLegState state,
                      String startStopId, String endStopId,
                      String sourceStartStopId, String sourceEndStopId,
                      String startName, String endName,
                      Integer plannedDurationSec, String evidenceQuality,
                      Instant activatedAt) {
        this.journeyId = journeyId;
        this.routeRevision = routeRevision;
        this.legIndex = legIndex;
        this.type = type;
        this.state = state;
        this.startStopId = startStopId;
        this.endStopId = endStopId;
        this.sourceStartStopId = sourceStartStopId;
        this.sourceEndStopId = sourceEndStopId;
        this.startName = startName;
        this.endName = endName;
        this.plannedDurationSec = plannedDurationSec;
        this.evidenceQuality = evidenceQuality;
        this.activatedAt = activatedAt;
    }

    /** 현재 경로의 routeOption을 선택한다. 실제 차량을 확정하는 동작은 아니다. */
    public void selectRouteOption(Long journeyLegRouteOptionId, String routeId) {
        this.selectedRouteOptionId = journeyLegRouteOptionId;
        this.routeId = routeId;
    }

    /**
     * 갈아탈 차량 도착 예측을 이 단계에 남긴다. 기다리는 단계에만 의미가 있다.
     *
     * <p>남기는 이유는 조회 요청이 이 값을 읽게 하기 위해서다. 지금은 조회할 때마다 실시간 도착
     * API 를 부르는데, 그러면 provider 가 느린 만큼 여정 조회가 느려진다. 평가기가 미리 계산해
     * 여기 적어 두면 조회는 읽기만 한다.
     *
     * <p>{@code remainingStops}·거리는 채우지 않는다. 기다리는 단계에서 "남은 정류장"은 사용자가
     * 아니라 차량의 이야기이고, 그 값은 판정에 쓰지 않는다.
     */
    public void applyBoardingPrediction(Instant predictedEndAt, String evidenceSource,
                                        String evidenceQuality, Instant evidenceObservedAt) {
        this.predictedEndAt = predictedEndAt;
        this.evidenceSource = evidenceSource;
        this.evidenceQuality = evidenceQuality;
        this.evidenceObservedAt = evidenceObservedAt;
    }

    /**
     * 진행 상태를 바꾼다.
     *
     * <p>어떤 상태로 갈 수 있는지는 여기서 따지지 않는다. 그 판단은 여정 전체를 봐야 할 수 있어
     * ({@code JourneyStateMachine}) 단계 하나가 스스로 결정할 수 없다.
     */
    public void changeState(JourneyLegState state, Instant at) {
        this.state = state;
        if (state == JourneyLegState.ACTIVE && this.activatedAt == null) {
            this.activatedAt = at;
        }
        if (state == JourneyLegState.COMPLETED) {
            this.completedAt = at;
        }
    }
}
