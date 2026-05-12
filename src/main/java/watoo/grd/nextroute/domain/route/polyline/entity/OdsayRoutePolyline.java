package watoo.grd.nextroute.domain.route.polyline.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;

@Entity
@Table(name = "odsay_route_polyline")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OdsayRoutePolyline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String odsayRouteId;

    @Column(nullable = false)
    private Integer laneClass;

    private Integer laneType;

    @Column(nullable = false)
    private Integer pointCount;

    @Column(nullable = false)
    private String sourceMapObject;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String polyline;

    @Column(nullable = false)
    private OffsetDateTime fetchedAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public void update(String polylineJson, int pointCount, String sourceMapObject, OffsetDateTime now) {
        this.polyline = polylineJson;
        this.pointCount = pointCount;
        this.sourceMapObject = sourceMapObject;
        this.updatedAt = now;
        this.fetchedAt = now;
    }
}
