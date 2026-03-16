package watoo.grd.nextroute.domain.subway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(name = "subway_arrival_raw")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayArrivalRaw extends BaseEntity {

	@Column(nullable = false)
	private LocalDateTime collectedAt;

	private String stationId;

	private String stationName;

	private String lineId;

	private String direction;

	private Integer arrivalSeconds;

	private String trainNo;

	private String destinationName;

	private String currentMessage;

	private String arrivalCode;

	@Builder
	public SubwayArrivalRaw(LocalDateTime collectedAt, String stationId, String stationName,
							String lineId, String direction, Integer arrivalSeconds,
							String trainNo, String destinationName,
							String currentMessage, String arrivalCode) {
		this.collectedAt = collectedAt;
		this.stationId = stationId;
		this.stationName = stationName;
		this.lineId = lineId;
		this.direction = direction;
		this.arrivalSeconds = arrivalSeconds;
		this.trainNo = trainNo;
		this.destinationName = destinationName;
		this.currentMessage = currentMessage;
		this.arrivalCode = arrivalCode;
	}
}
