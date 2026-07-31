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

/**
 * 여정을 쪼갠 한 단계. "걷기 / 기다리기 / 타고 가기" 중 하나다.
 *
 * <p>단계 경계는 사용자가 버튼을 누르는 지점과 일치시킨다. 버스 한 구간을 하나로 두면
 * "탔어요"를 누른 뒤에도 같은 단계에 머물러 상태가 애매해진다.
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
     * {@code ACTIVE/PENDING} 으로 둬서 사용자가 손으로 진행할 수 있게 한다.
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

    /** 탄 차량의 식별자. 실시간 대조로 확정되면 채워진다(Phase 3). */
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
     * 이 단계에서 타기로 확정한 노선 후보.
     *
     * <p>후보가 하나뿐일 때만 채운다. 여러 개면 사용자가 고를 때까지 비워 둔다 — 서버가 임의로
     * 첫 후보를 고르면 사용자가 실제로 탄 노선과 어긋난다.
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
                      Integer plannedDurationSec, String evidenceQuality) {
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
    }

    /** 탈 노선을 확정한다. 후보가 하나뿐인 구간에서 시작 시점에 호출한다. */
    public void selectRouteOption(Long journeyLegRouteOptionId) {
        this.selectedRouteOptionId = journeyLegRouteOptionId;
    }

    /**
     * 진행 상태를 바꾼다.
     *
     * <p>어떤 상태로 갈 수 있는지는 여기서 따지지 않는다. 그 판단은 여정 전체를 봐야 할 수 있어
     * ({@code JourneyStateMachine}) 단계 하나가 스스로 결정할 수 없다.
     */
    public void changeState(JourneyLegState state) {
        this.state = state;
    }
}
