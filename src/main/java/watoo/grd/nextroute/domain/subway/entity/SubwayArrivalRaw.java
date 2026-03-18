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

	/** 수집 시각 */
	@Column(nullable = false)
	private LocalDateTime collectedAt;

	/** 역 ID */
	private String stationId;

	/** 역 이름 */
	private String stationName;

	/** 호선 코드 */
	private String lineId;

	/** 상하행 방향 (예: "상행", "내선") */
	private String direction;

	/** 도착까지 남은 시간 (초) */
	private Integer arrivalSeconds;

	/** 열차 번호 */
	private String trainNo;

	/** 행선지 역 이름 (예: "성수행") */
	private String destinationName;

	/** 현재 위치 메시지 (예: "잠실새내 도착") */
	private String currentMessage;

	/** 도착 코드 (0:진입, 1:도착, 2:출발, 3:전역출발, 4:전역진입, 5:전역도착, 99:운행중) */
	private String arrivalCode;

	/** 호선 코드 (예: "1002" = 2호선) */
	private String subwayId;

	/** 세 번째 도착 메시지 */
	private String arrivalMsg3;

	/** API 수신 시각 */
	private String receivedAt;

	/** 열차 노선명 (예: "2호선성수행") */
	private String trainLineName;

	@Builder
	public SubwayArrivalRaw(LocalDateTime collectedAt, String stationId, String stationName,
							String lineId, String direction, Integer arrivalSeconds,
							String trainNo, String destinationName,
							String currentMessage, String arrivalCode,
							String subwayId, String arrivalMsg3,
							String receivedAt, String trainLineName) {
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
		this.subwayId = subwayId;
		this.arrivalMsg3 = arrivalMsg3;
		this.receivedAt = receivedAt;
		this.trainLineName = trainLineName;
	}
}
