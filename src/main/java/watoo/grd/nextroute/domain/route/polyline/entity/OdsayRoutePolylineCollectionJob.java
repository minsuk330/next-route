package watoo.grd.nextroute.domain.route.polyline.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "odsay_route_polyline_collection_job")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OdsayRoutePolylineCollectionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String odsayRouteId;

    @Column(nullable = false)
    private Integer laneClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CollectionJobStatus status;

    @Column(nullable = false)
    private Integer requestedCount;

    private String lastMapObjFragment;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false)
    private OffsetDateTime requestedAt;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    public void incrementRequest(String mapObjFragment, OffsetDateTime now) {
        this.requestedCount = this.requestedCount + 1;
        this.lastMapObjFragment = mapObjFragment;
        this.requestedAt = now;
        if (this.status == CollectionJobStatus.FAILED) {
            this.status = CollectionJobStatus.PENDING;
        }
    }

    public void markRunning(OffsetDateTime now) {
        this.status = CollectionJobStatus.RUNNING;
        this.startedAt = now;
    }

    public void markSuccess(OffsetDateTime now) {
        this.status = CollectionJobStatus.SUCCESS;
        this.finishedAt = now;
        this.lastError = null;
    }

    public void markFailed(String error, OffsetDateTime now) {
        this.status = CollectionJobStatus.FAILED;
        this.finishedAt = now;
        this.lastError = error;
    }
}
