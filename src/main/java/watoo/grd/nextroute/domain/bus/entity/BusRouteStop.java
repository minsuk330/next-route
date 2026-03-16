package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

@Entity
@Table(name = "bus_route_stop")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusRouteStop extends BaseEntity {

	private String routeId;

	private String stopId;

	private Integer seq;

	private String sectionId;

	private Double latitude;

	private Double longitude;

	private Double sectionDistance;

	private String direction;

	private String transferYn;

	@Builder
	public BusRouteStop(String routeId, String stopId, Integer seq,
						String sectionId, Double latitude, Double longitude,
						Double sectionDistance, String direction, String transferYn) {
		this.routeId = routeId;
		this.stopId = stopId;
		this.seq = seq;
		this.sectionId = sectionId;
		this.latitude = latitude;
		this.longitude = longitude;
		this.sectionDistance = sectionDistance;
		this.direction = direction;
		this.transferYn = transferYn;
	}
}
