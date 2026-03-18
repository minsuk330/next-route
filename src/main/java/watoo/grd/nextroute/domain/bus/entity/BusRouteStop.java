package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

	/** 노선 ID */
	@Column(name = "route_id")
	private String routeId;

	/** 정류소 ID (노드 ID) */
	@Column(name = "stop_id")
	private String stopId;

	/** 경유 순서 (1부터 시작) */
	private Integer seq;

	/** 구간 ID (두 정류소 사이 구간 식별자) */
	private String sectionId;

	/** 이전 정류소부터의 구간 거리 (km) */
	private Double sectionDistance;

	/** 방향 (예: "양천공영차고지방면") */
	private String direction;

	/** 환승 가능 여부 ("Y" / "N") */
	private String transferYn;

	/** 정류소 고유번호 */
	private String stationNo;

	/** 첫차 시간 */
	private String beginTm;

	/** 막차 시간 */
	private String lastTm;

	/** 회차지 ID */
	private String turnStopId;

	/** 구간 속도 (km/h) */
	private Double sectionSpeed;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "route_id", referencedColumnName = "route_id", insertable = false, updatable = false)
	private BusRoute busRoute;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "stop_id", referencedColumnName = "stop_id", insertable = false, updatable = false)
	private BusStop busStop;

	@Builder
	public BusRouteStop(String routeId, String stopId, Integer seq,
						String sectionId, Double sectionDistance,
						String direction, String transferYn,
						String stationNo, String beginTm, String lastTm,
						String turnStopId, Double sectionSpeed) {
		this.routeId = routeId;
		this.stopId = stopId;
		this.seq = seq;
		this.sectionId = sectionId;
		this.sectionDistance = sectionDistance;
		this.direction = direction;
		this.transferYn = transferYn;
		this.stationNo = stationNo;
		this.beginTm = beginTm;
		this.lastTm = lastTm;
		this.turnStopId = turnStopId;
		this.sectionSpeed = sectionSpeed;
	}
}
