package watoo.grd.nextroute.domain.journey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

/**
 * 한 구간에서 탈 수 있는 노선 후보 하나.
 *
 * <p>같은 정류장에서 목적지로 가는 버스가 둘 이상일 수 있다. 검색 결과는 이걸 여러 후보로
 * 돌려주며, 첫 후보는 선택 경로이고 나머지는 같은 승·하차 구간의 대안이다.
 * ODsay 첫 lane을 선택 경로로 사용하고 나머지는 실시간 재평가용 대체 후보로 보존한다.
 * 이 선택은 실제 차량이나 운행 회차를 식별한다는 뜻이 아니다.
 *
 * <p>후보는 <b>기다리는 단계</b>({@code WAIT_BUS}/{@code WAIT_SUBWAY})가 소유한다. 짝이 되는
 * 타는 단계는 저장 시 같은 첫 후보를 가리킨다.
 */
@Entity
@Table(name = "journey_leg_route_option")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JourneyLegRouteOption extends BaseEntity {

    @Column(nullable = false)
    private Long journeyLegId;

    /** 이 단계 안에서의 후보 순번. {@code (journey_leg_id, option_index)} 가 유일하다. */
    @Column(nullable = false)
    private int optionIndex;

    /** 검색 응답의 {@code lanes} 배열에서의 원래 순번. 응답 순서를 보존한다. */
    @Column(nullable = false)
    private int laneIndex;

    /** 내부에서 쓰는 노선 식별자. 원천 식별자를 해석하지 못하면 비어 있다. */
    @Column(length = 64)
    private String routeId;

    /** 검색 응답이 준 원천 식별자. 해석에 실패해도 원본은 남긴다. */
    @Column(length = 64)
    private String sourceRouteId;

    @Column(length = 100)
    private String routeName;

    /** 상·하행 명칭이 아니라 실시간 조회 후보를 같은 진행 순서로 묶는 안정 키다. */
    @Column(length = 64)
    private String directionKey;

    /** ODSAY 지하철 방향 코드. 내부 lineId와 함께 실시간 방향 판정에 쓴다. */
    private Integer wayCode;

    /** 내부 버스 노선에서 승차 정류장의 순번. 외부 도착 API·ML의 입력이다. */
    private Integer boardingSeq;

    /** 내부 버스 노선에서 하차 정류장의 순번. 남은 정류장·구간 예측의 입력이다. */
    private Integer alightingSeq;

    /** 검색 lane 의 노선 표시색({@code #RRGGBB}). 묶인 버스 노선마다 다르다(#607). V77 이전 행은 null. */
    @Column(length = 16)
    private String routeDisplayColor;

    /** 이 노선으로 이 구간을 타는 정적 탑승시간(초, 검색 lane rideSeconds). 사용자가 노선을 바꾸면 탑승 단계 계획 소요가 이 값(#614). 모르면 null. */
    private Integer rideSeconds;

    @Builder
    public JourneyLegRouteOption(Long journeyLegId, int optionIndex, int laneIndex,
                                 String routeId, String sourceRouteId, String routeName,
                                 String directionKey, Integer wayCode,
                                 Integer boardingSeq, Integer alightingSeq,
                                 String routeDisplayColor, Integer rideSeconds) {
        this.journeyLegId = journeyLegId;
        this.optionIndex = optionIndex;
        this.laneIndex = laneIndex;
        this.routeId = routeId;
        this.sourceRouteId = sourceRouteId;
        this.routeName = routeName;
        this.directionKey = directionKey;
        this.wayCode = wayCode;
        this.boardingSeq = boardingSeq;
        this.alightingSeq = alightingSeq;
        this.routeDisplayColor = routeDisplayColor;
        this.rideSeconds = rideSeconds;
    }

    /** 저장 후 확정된 단계 id 를 연결한다(단계와 후보의 저장 순서를 코드가 잡을 때 쓴다). */
    public void attachTo(Long journeyLegId) {
        this.journeyLegId = journeyLegId;
    }
}
