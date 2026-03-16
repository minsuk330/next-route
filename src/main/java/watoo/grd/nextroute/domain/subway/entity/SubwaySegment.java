package watoo.grd.nextroute.domain.subway.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

@Entity
@Table(name = "subway_segment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwaySegment extends BaseEntity {

	private String lineId;

	private String departStationId;

	private String arriveStationId;

	private Double distance;

	private Double travelTime;

	private Integer seq;

	@Builder
	public SubwaySegment(String lineId, String departStationId, String arriveStationId,
						 Double distance, Double travelTime, Integer seq) {
		this.lineId = lineId;
		this.departStationId = departStationId;
		this.arriveStationId = arriveStationId;
		this.distance = distance;
		this.travelTime = travelTime;
		this.seq = seq;
	}
}
