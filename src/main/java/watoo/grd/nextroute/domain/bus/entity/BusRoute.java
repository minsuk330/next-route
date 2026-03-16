package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

@Entity
@Table(name = "bus_route")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusRoute extends BaseEntity {

	@Column(nullable = false, unique = true)
	private String routeId;

	private String routeName;

	private Integer routeType;

	private String startStation;

	private String endStation;

	private Integer term;

	private String firstBusTime;

	private String lastBusTime;

	private String companyName;

	private Double totalDistance;

	@Builder
	public BusRoute(String routeId, String routeName, Integer routeType,
					String startStation, String endStation, Integer term,
					String firstBusTime, String lastBusTime,
					String companyName, Double totalDistance) {
		this.routeId = routeId;
		this.routeName = routeName;
		this.routeType = routeType;
		this.startStation = startStation;
		this.endStation = endStation;
		this.term = term;
		this.firstBusTime = firstBusTime;
		this.lastBusTime = lastBusTime;
		this.companyName = companyName;
		this.totalDistance = totalDistance;
	}
}
