package watoo.grd.nextroute.infrastructure.adapter.out.cache.walk;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;

@Entity
@Table(name = "tmap_walk_cache")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TmapWalkCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cache_key", nullable = false, unique = true, length = 255)
    private String cacheKey;

    @Column(name = "start_x", nullable = false)
    private Double startX;

    @Column(name = "start_y", nullable = false)
    private Double startY;

    @Column(name = "end_x", nullable = false)
    private Double endX;

    @Column(name = "end_y", nullable = false)
    private Double endY;

    @Column(name = "search_option", nullable = false, columnDefinition = "smallint")
    private Short searchOption;

    @Column(name = "walk_segment", columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String walkSegment;

    @Column(name = "total_distance")
    private Integer totalDistance;

    @Column(name = "total_time")
    private Integer totalTime;

    @Builder.Default
    @Column(name = "hit_count", nullable = false)
    private Integer hitCount = 0;

    @Builder.Default
    @Column(name = "is_negative", nullable = false)
    private Boolean isNegative = false;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    public void overwrite(String walkSegmentJson, Integer totalDistance, Integer totalTime,
                          boolean negative, OffsetDateTime now, OffsetDateTime expiresAt) {
        this.walkSegment = walkSegmentJson;
        this.totalDistance = totalDistance;
        this.totalTime = totalTime;
        this.isNegative = negative;
        this.fetchedAt = now;
        this.expiresAt = expiresAt;
        this.hitCount = 0;
    }

    public void incrementHit() {
        this.hitCount = this.hitCount + 1;
    }

    public boolean isExpired(OffsetDateTime now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
