package watoo.grd.nextroute.domain.subway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "subway_arrival_event",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_subway_arrival_event",
				columnNames = {"service_date", "line_id", "station_id", "train_no", "direction", "arrived_at"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayArrivalEvent extends BaseEntity {

	/** 운행 날짜 */
	@Column(name = "service_date", nullable = false)
	private LocalDate serviceDate;

	/** 호선 ID */
	@Column(name = "line_id", nullable = false)
	private String lineId;

	/** 역 ID */
	@Column(name = "station_id", nullable = false)
	private String stationId;

	/** 역 이름 */
	private String stationName;

	/** 열차 번호 */
	@Column(name = "train_no", nullable = false)
	private String trainNo;

	/** 상하행 방향 */
	@Column(nullable = false)
	private String direction;

	/** 종착 키 (두 값이 모두 null 이면 "UNKNOWN") */
	@Column(name = "destination_key")
	private String destinationKey;

	/** 종착 역 ID */
	private String destinationId;

	/** 종착 역 이름 */
	private String destinationName;

	/** 열차 종류 */
	private String trainType;

	/** 이벤트 소스 (예: "OBSERVED_CODE_1") */
	@Column(name = "event_source")
	private String eventSource;

	/** 종착 정보 충돌 여부 */
	private Boolean destinationConflicted;

	/** 종착 정보 충돌 횟수 */
	private Integer destinationConflictCount;

	/** 도착 시각 */
	@Column(name = "arrived_at", nullable = false)
	private LocalDateTime arrivedAt;

	/** 최초 관측 시각 */
	private LocalDateTime firstObservedAt;

	/** 최종 관측 시각 */
	private LocalDateTime lastObservedAt;

	/** 원시 데이터 건수 */
	private Integer rawCount;

	/** 원본 raw ID 목록 (JSON 배열 문자열) */
	@Column(name = "source_raw_ids", columnDefinition = "jsonb")
	@ColumnTransformer(write = "?::jsonb")
	private String sourceRawIds;

	@Builder
	public SubwayArrivalEvent(LocalDate serviceDate, String lineId, String stationId,
							String stationName, String trainNo, String direction,
							String destinationKey, String destinationId, String destinationName,
							String trainType, String eventSource,
							Boolean destinationConflicted, Integer destinationConflictCount,
							LocalDateTime arrivedAt, LocalDateTime firstObservedAt,
							LocalDateTime lastObservedAt, Integer rawCount, String sourceRawIds) {
		this.serviceDate = serviceDate;
		this.lineId = lineId;
		this.stationId = stationId;
		this.stationName = stationName;
		this.trainNo = trainNo;
		this.direction = direction;
		this.destinationKey = destinationKey;
		this.destinationId = destinationId;
		this.destinationName = destinationName;
		this.trainType = trainType;
		this.eventSource = eventSource;
		this.destinationConflicted = destinationConflicted;
		this.destinationConflictCount = destinationConflictCount;
		this.arrivedAt = arrivedAt;
		this.firstObservedAt = firstObservedAt;
		this.lastObservedAt = lastObservedAt;
		this.rawCount = rawCount;
		this.sourceRawIds = sourceRawIds;
	}
}
