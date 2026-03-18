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

	/** 호선 코드 (예: "1002" = 2호선) */
	private String lineId;

	/** 출발역 ID */
	private String departStationId;

	/** 도착역 ID */
	private String arriveStationId;

	/** 역간 거리 (km) */
	private Double distance;

	/** 평균 소요시간 (초) */
	private Double travelTime;

	/** 구간 순서 (노선 내 정렬용) */
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
