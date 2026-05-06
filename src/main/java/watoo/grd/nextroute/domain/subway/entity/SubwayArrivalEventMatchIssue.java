package watoo.grd.nextroute.domain.subway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "subway_arrival_event_match_issue",
		indexes = {
				@Index(name = "idx_sam_issue_service_date", columnList = "service_date"),
				@Index(name = "idx_sam_issue_match_group_key", columnList = "match_group_key")
		})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayArrivalEventMatchIssue extends BaseEntity {

	/** 운행 날짜 */
	@Column(name = "service_date", nullable = false)
	private LocalDate serviceDate;

	/** 매칭 이슈 유형 */
	@Column(name = "issue_type", nullable = false)
	private String issueType;

	/** 호선 ID */
	@Column(name = "line_id")
	private String lineId;

	/** 역 ID */
	@Column(name = "station_id")
	private String stationId;

	/** 역 이름 */
	@Column(name = "station_name")
	private String stationName;

	/** 하차 역 ID */
	@Column(name = "tago_station_id")
	private String tagoStationId;

	/** 상하행 방향 */
	@Column(name = "direction")
	private String direction;

	/** 요일 타입 */
	@Column(name = "day_type")
	private String dayType;

	/** 매칭 그룹 키 */
	@Column(name = "match_group_key")
	private String matchGroupKey;

	/** 시간표 ID */
	@Column(name = "timetable_id")
	private Long timetableId;

	/** 도착 이벤트 ID */
	@Column(name = "arrival_event_id")
	private Long arrivalEventId;

	/** 예정 도착 시각 */
	@Column(name = "scheduled_arrival_at")
	private LocalDateTime scheduledArrivalAt;

	/** 실제 도착 시각 */
	@Column(name = "actual_arrived_at")
	private LocalDateTime actualArrivedAt;

	/** 시간표 내 순서 인덱스 */
	@Column(name = "timetable_order_index")
	private Integer timetableOrderIndex;

	/** 이벤트 내 순서 인덱스 */
	@Column(name = "event_order_index")
	private Integer eventOrderIndex;

	/** 시간표 총 건수 */
	@Column(name = "timetable_count")
	private Integer timetableCount;

	/** 이벤트 총 건수 */
	@Column(name = "event_count")
	private Integer eventCount;

	/** 예정 시간 소스 */
	@Column(name = "scheduled_time_source")
	private String scheduledTimeSource;

	/** 상세 정보 */
	@Column(name = "details", columnDefinition = "TEXT")
	private String details;

	@Builder
	public SubwayArrivalEventMatchIssue(LocalDate serviceDate, String issueType,
										String lineId, String stationId, String stationName,
										String tagoStationId, String direction, String dayType,
										String matchGroupKey, Long timetableId, Long arrivalEventId,
										LocalDateTime scheduledArrivalAt, LocalDateTime actualArrivedAt,
										Integer timetableOrderIndex, Integer eventOrderIndex,
										Integer timetableCount, Integer eventCount,
										String scheduledTimeSource, String details) {
		this.serviceDate = serviceDate;
		this.issueType = issueType;
		this.lineId = lineId;
		this.stationId = stationId;
		this.stationName = stationName;
		this.tagoStationId = tagoStationId;
		this.direction = direction;
		this.dayType = dayType;
		this.matchGroupKey = matchGroupKey;
		this.timetableId = timetableId;
		this.arrivalEventId = arrivalEventId;
		this.scheduledArrivalAt = scheduledArrivalAt;
		this.actualArrivedAt = actualArrivedAt;
		this.timetableOrderIndex = timetableOrderIndex;
		this.eventOrderIndex = eventOrderIndex;
		this.timetableCount = timetableCount;
		this.eventCount = eventCount;
		this.scheduledTimeSource = scheduledTimeSource;
		this.details = details;
	}
}
