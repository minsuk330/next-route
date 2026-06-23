package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

/**
 * 주간 노선 로테이션의 수집 대상 1건.
 *
 * <p>{@code routeName} 은 {@link BusRoute#getRouteName()}(=표시 노선번호, ridership RTE_NM)과
 * 매칭되는 수집 키다. {@code active=true} 인 행이 현재 주차의 수집 대상이다.
 */
@Entity
@Table(name = "weekly_target_route",
        indexes = {
                @Index(name = "idx_weekly_target_route_active", columnList = "active")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WeeklyTargetRoute extends BaseEntity {

    @Column(name = "route_no")
    private String routeNo;

    @Column(name = "route_name", nullable = false)
    private String routeName;

    @Column(name = "rank_position", nullable = false)
    private Integer rankPosition;

    @Column(name = "bucket", nullable = false)
    private Integer bucket;

    @Column(name = "ridership_month")
    private String ridershipMonth;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Builder
    public WeeklyTargetRoute(String routeNo, String routeName, Integer rankPosition,
                             Integer bucket, String ridershipMonth, boolean active) {
        this.routeNo = routeNo;
        this.routeName = routeName;
        this.rankPosition = rankPosition;
        this.bucket = bucket;
        this.ridershipMonth = ridershipMonth;
        this.active = active;
    }

    public void deactivate() {
        this.active = false;
    }
}
