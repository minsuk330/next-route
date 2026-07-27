package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.OffsetDateTime;

@Entity
@Table(name = "bus_route_polyline")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusRoutePolyline extends BaseEntity {

	@Column(name = "route_id", nullable = false, unique = true)
	private String routeId;

	@Column(name = "point_count", nullable = false)
	private Integer pointCount;

	@Column(name = "polyline", columnDefinition = "jsonb", nullable = false)
	@ColumnTransformer(write = "?::jsonb")
	private String polyline;

	@Column(name = "source", nullable = false, length = 30)
	private String source;

	@Column(name = "fetched_at", nullable = false)
	private OffsetDateTime fetchedAt;

	@Builder
	public BusRoutePolyline(
			String routeId,
			Integer pointCount,
			String polyline,
			String source,
			OffsetDateTime fetchedAt
	) {
		this.routeId = routeId;
		this.pointCount = pointCount;
		this.polyline = polyline;
		this.source = source;
		this.fetchedAt = fetchedAt;
	}

	public void refresh(Integer pointCount, String polyline, String source, OffsetDateTime fetchedAt) {
		this.pointCount = pointCount;
		this.polyline = polyline;
		this.source = source;
		this.fetchedAt = fetchedAt;
	}
}
