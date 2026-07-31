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
 * 돌려주는데, 서버가 임의로 첫 번째를 정답으로 굳히면 사용자가 실제로 탄 노선과 어긋난다.
 * 그래서 전부 저장하고 선택은 미룬다(D19).
 *
 * <p>후보는 <b>기다리는 단계</b>({@code WAIT_BUS}/{@code WAIT_SUBWAY})가 소유한다. 짝이 되는
 * 타는 단계는 사용자가 고른 뒤 같은 후보를 가리킨다.
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

    @Builder
    public JourneyLegRouteOption(Long journeyLegId, int optionIndex, int laneIndex,
                                 String routeId, String sourceRouteId, String routeName) {
        this.journeyLegId = journeyLegId;
        this.optionIndex = optionIndex;
        this.laneIndex = laneIndex;
        this.routeId = routeId;
        this.sourceRouteId = sourceRouteId;
        this.routeName = routeName;
    }

    /** 저장 후 확정된 단계 id 를 연결한다(단계와 후보의 저장 순서를 코드가 잡을 때 쓴다). */
    public void attachTo(Long journeyLegId) {
        this.journeyLegId = journeyLegId;
    }
}
