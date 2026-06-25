package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 주간 노선 로테이션 상태(단일 행). {@code currentBucket} 으로 다음 offset 을 계산한다.
 * (offset = bucket * 30)
 */
@Entity
@Table(name = "route_rotation_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteRotationState extends BaseEntity {

    @Column(name = "current_bucket", nullable = false)
    private Integer currentBucket;

    @Column(name = "ridership_month", nullable = false)
    private String ridershipMonth;

    @Column(name = "total_buckets")
    private Integer totalBuckets;

    @Column(name = "rotated_at")
    private LocalDateTime rotatedAt;

    @Builder
    public RouteRotationState(Integer currentBucket, String ridershipMonth,
                              Integer totalBuckets, LocalDateTime rotatedAt) {
        this.currentBucket = currentBucket;
        this.ridershipMonth = ridershipMonth;
        this.totalBuckets = totalBuckets;
        this.rotatedAt = rotatedAt;
    }

    public void advanceTo(int bucket, String ridershipMonth, Integer totalBuckets, LocalDateTime rotatedAt) {
        this.currentBucket = bucket;
        this.ridershipMonth = ridershipMonth;
        this.totalBuckets = totalBuckets;
        this.rotatedAt = rotatedAt;
    }
}
