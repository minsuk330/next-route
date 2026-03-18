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

	/** 노선 고유 ID (예: "100100124") */
	@Column(nullable = false, unique = true, name = "route_id")
	private String routeId;

	/** 노선 번호/이름 (예: "360", "강남01") */
	private String routeName;

	/** 노선 유형 (1:공항, 2:마을, 3:간선, 4:지선, 5:순환, 6:광역, 7:인천, 8:경기, 9:폐지, 0:공용) */
	private Integer routeType;

	/** 기점 정류소명 */
	private String startStation;

	/** 종점 정류소명 */
	private String endStation;

	/** 배차 간격 (분) */
	private Integer term;

	/** 첫차 시간 (예: "20250101040000") */
	private String firstBusTime;

	/** 막차 시간 */
	private String lastBusTime;

	/** 운수 회사명 */
	private String companyName;

	/** 노선 총 거리 (km) */
	private Double totalDistance;

	/** 막차 운행 여부 ("Y" / "N") */
	private String lastBusYn;

	/** 금일 저상 첫차 시간 */
	private String firstLowTime;

	/** 금일 저상 막차 시간 */
	private String lastLowTime;

	@Builder
	public BusRoute(String routeId, String routeName, Integer routeType,
					String startStation, String endStation, Integer term,
					String firstBusTime, String lastBusTime,
					String companyName, Double totalDistance,
					String lastBusYn, String firstLowTime, String lastLowTime) {
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
		this.lastBusYn = lastBusYn;
		this.firstLowTime = firstLowTime;
		this.lastLowTime = lastLowTime;
	}
}
