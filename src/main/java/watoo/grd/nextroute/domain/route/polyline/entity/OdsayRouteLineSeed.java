package watoo.grd.nextroute.domain.route.polyline.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "odsay_route_line_seed")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OdsayRouteLineSeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String odsayRouteId;

    @Column(nullable = false)
    private Integer laneClass;

    private Integer laneType;

    private String lineId;

    @Column(nullable = false)
    private String lineName;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;
}
