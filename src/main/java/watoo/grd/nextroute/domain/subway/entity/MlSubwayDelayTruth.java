package watoo.grd.nextroute.domain.subway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ML 학습 정답 테이블 — subway_arrival_event(실측) × subway_timetable(예정)
 * 성공 매칭 pair의 지연 라벨.
 *
 * <p>id/createdAt/updatedAt/deletedAt 은 {@link BaseEntity} 제공.
 * 멱등성은 service_date 단위 delete-and-insert + uk(arrival_event_id).
 */
@Entity
@Table(name = "ml_subway_delay_truth",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ml_delay_truth_event",
                        columnNames = {"arrival_event_id"}),
                @UniqueConstraint(name = "uk_ml_delay_truth_observation",
                        columnNames = {"service_date", "line_id", "station_id",
                                "direction", "train_no", "scheduled_arrival_at"})
        },
        indexes = {
                @Index(name = "idx_ml_delay_truth_service_date", columnList = "service_date"),
                @Index(name = "idx_ml_delay_truth_station_line_dir_date",
                        columnList = "station_id,line_id,direction,service_date"),
                @Index(name = "idx_ml_delay_truth_timetable", columnList = "timetable_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MlSubwayDelayTruth extends BaseEntity {

    /** 04:00 기준 운행일 */
    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    /** 호선 ID (서울 API 기준) */
    @Column(name = "line_id", nullable = false)
    private String lineId;

    /** 역 ID (서울 API 기준) */
    @Column(name = "station_id", nullable = false)
    private String stationId;

    /** 역 이름 */
    @Column(name = "station_name")
    private String stationName;

    /** 시간표 매칭에 사용한 TAGO 역 ID */
    @Column(name = "tago_station_id")
    private String tagoStationId;

    /** 시간표 기준 방향 "U"/"D" */
    @Column(nullable = false)
    private String direction;

    /** "01" 평일 / "02" 토 / "03" 일·공휴일 */
    @Column(name = "day_type", nullable = false)
    private String dayType;

    /** 실측 event 열차 번호 */
    @Column(name = "train_no", nullable = false)
    private String trainNo;

    /** 실측 event 열차 종류 */
    @Column(name = "train_type")
    private String trainType;

    /** 실측 event 행선지 ID */
    @Column(name = "destination_id")
    private String destinationId;

    /** 실측 event 행선지명 */
    @Column(name = "destination_name")
    private String destinationName;

    /** 시간표 종착역명 (destination mismatch 분석용) */
    @Column(name = "end_station_name")
    private String endStationName;

    /** 매칭된 subway_arrival_event.id (FK 미적용 — 스냅샷 참조) */
    @Column(name = "arrival_event_id", nullable = false)
    private Long arrivalEventId;

    /** 매칭된 subway_timetable.id (FK 미적용 — 스냅샷 참조) */
    @Column(name = "timetable_id", nullable = false)
    private Long timetableId;

    /** 시간표 기준 예정 도착 시각 */
    @Column(name = "scheduled_arrival_at", nullable = false)
    private LocalDateTime scheduledArrivalAt;

    /** 실측 도착 시각 */
    @Column(name = "actual_arrived_at", nullable = false)
    private LocalDateTime actualArrivedAt;

    /** actual - scheduled (초, 음수=조착 / 양수=지연) */
    @Column(name = "delay_seconds", nullable = false)
    private Integer delaySeconds;

    /** 관측 provenance: OBSERVED_CODE_1 / INFERRED_FROM_PREV_DEPARTURE */
    @Column(name = "event_source", nullable = false)
    private String eventSource;

    /** ARR_TIME / DEP_TIME_MINUS_30S_FOR_ZERO_ARRIVAL */
    @Column(name = "scheduled_time_source")
    private String scheduledTimeSource;

    /** 그룹 내 시간표 순서 인덱스 */
    @Column(name = "timetable_order_index")
    private Integer timetableOrderIndex;

    /** 그룹 내 이벤트 순서 인덱스 */
    @Column(name = "event_order_index")
    private Integer eventOrderIndex;

    /** serviceDate|lineId|stationId|dayType|direction 디버그 키 */
    @Column(name = "match_group_key")
    private String matchGroupKey;

    /** V1=ORDINAL (이후 COST_ALIGN/MANUAL/INFERRED) */
    @Column(name = "match_strategy", nullable = false)
    private String matchStrategy;

    /** pair-level 신뢰도 = groupCompleteness × delayDecay */
    @Column(name = "match_confidence")
    private Double matchConfidence;

    /** 학습 제외 여부 (row는 보존, 분포 분석용) */
    @Column(name = "excluded_from_training", nullable = false)
    private boolean excludedFromTraining;

    /** OUTLIER_DELAY / LOW_CONFIDENCE / DESTINATION_MISMATCH / INFERRED_EVENT */
    @Column(name = "exclude_reason")
    private String excludeReason;

    @Builder
    public MlSubwayDelayTruth(LocalDate serviceDate, String lineId, String stationId,
                              String stationName, String tagoStationId, String direction,
                              String dayType, String trainNo, String trainType,
                              String destinationId, String destinationName, String endStationName,
                              Long arrivalEventId, Long timetableId,
                              LocalDateTime scheduledArrivalAt, LocalDateTime actualArrivedAt,
                              Integer delaySeconds, String eventSource, String scheduledTimeSource,
                              Integer timetableOrderIndex, Integer eventOrderIndex,
                              String matchGroupKey, String matchStrategy, Double matchConfidence,
                              boolean excludedFromTraining, String excludeReason) {
        this.serviceDate = serviceDate;
        this.lineId = lineId;
        this.stationId = stationId;
        this.stationName = stationName;
        this.tagoStationId = tagoStationId;
        this.direction = direction;
        this.dayType = dayType;
        this.trainNo = trainNo;
        this.trainType = trainType;
        this.destinationId = destinationId;
        this.destinationName = destinationName;
        this.endStationName = endStationName;
        this.arrivalEventId = arrivalEventId;
        this.timetableId = timetableId;
        this.scheduledArrivalAt = scheduledArrivalAt;
        this.actualArrivedAt = actualArrivedAt;
        this.delaySeconds = delaySeconds;
        this.eventSource = eventSource;
        this.scheduledTimeSource = scheduledTimeSource;
        this.timetableOrderIndex = timetableOrderIndex;
        this.eventOrderIndex = eventOrderIndex;
        this.matchGroupKey = matchGroupKey;
        this.matchStrategy = matchStrategy;
        this.matchConfidence = matchConfidence;
        this.excludedFromTraining = excludedFromTraining;
        this.excludeReason = excludeReason;
    }
}
