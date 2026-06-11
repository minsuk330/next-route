package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnTransformer;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 버스 ML 학습 라벨 테이블.
 *
 * <p>spine은 bus_arrival_candidate_raw(도착 API candidate).
 * label_source=ARRIVAL_API_ETA가 기본이며, position stop_flag=1 매칭 시
 * POSITION_STOP_FLAG_CORRECTED로 승격된다.
 *
 * <p>멱등성: service_date 단위 delete-and-insert + uk(arrival_lifecycle_id).
 */
@Entity
@Table(name = "bus_arrival_label_event",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bus_label_event_lifecycle",
                        columnNames = {"arrival_lifecycle_id"})
        },
        indexes = {
                @Index(name = "idx_bus_label_event_service_date",
                        columnList = "service_date"),
                @Index(name = "idx_bus_label_event_scope",
                        columnList = "service_date,route_id,stop_id,seq")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusArrivalLabelEvent extends BaseEntity {

    // ── label_source 상수 ────────────────────────────────────────────────────
    public static final String SOURCE_ARRIVAL_API_ETA = "ARRIVAL_API_ETA";
    public static final String SOURCE_POSITION_STOP_FLAG_CORRECTED = "POSITION_STOP_FLAG_CORRECTED";

    // ── label_confidence 상수 ────────────────────────────────────────────────
    public static final String CONFIDENCE_MEDIUM = "medium";
    public static final String CONFIDENCE_HIGH_PROVISIONAL = "high_provisional";

    // ── exclude_reason 상수 ──────────────────────────────────────────────────
    public static final String EXCLUDE_INVALID_API_ETA = "INVALID_API_ETA";
    public static final String EXCLUDE_STALE_POSITION = "STALE_POSITION";
    public static final String EXCLUDE_INVALID_DATA_TM = "INVALID_DATA_TM";
    public static final String EXCLUDE_SECTION_UNMATCHED = "SECTION_UNMATCHED";
    public static final String EXCLUDE_TRIP_BOUNDARY = "TRIP_BOUNDARY";
    public static final String EXCLUDE_OFF_HOURS_GAP = "OFF_HOURS_GAP";
    public static final String EXCLUDE_OUTLIER_TRAVEL = "OUTLIER_TRAVEL";

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "route_id", nullable = false)
    private String routeId;

    @Column(name = "vehicle_identity_type", nullable = false)
    private String vehicleIdentityType;

    @Column(name = "vehicle_identity", nullable = false)
    private String vehicleIdentity;

    /** position correction 경로에서만 채워진다. */
    @Column(name = "trip_id")
    private String tripId;

    @Column(name = "stop_id", nullable = false)
    private String stopId;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    /** bus_route_stop 조인으로 확정. position correction 경로에서만 채워진다. */
    @Column(name = "section_id")
    private String sectionId;

    /** parse(data_timestamp) + predict_time. INVALID_API_ETA면 null. */
    @Column(name = "api_estimated_arrival_at")
    private LocalDateTime apiEstimatedArrivalAt;

    /** POSITION_STOP_FLAG_CORRECTED 경로에서만 채워진다. */
    @Column(name = "corrected_arrival_at")
    private LocalDateTime correctedArrivalAt;

    /** 최종 학습 label 시각. INVALID_API_ETA면 null. */
    @Column(name = "label_arrival_at")
    private LocalDateTime labelArrivalAt;

    /** 정차 종료 시각 — 연속 stop_flag=1 snapshot 2개 이상일 때만 채워진다. */
    @Column(name = "departed_at")
    private LocalDateTime departedAt;

    /** 정차 시간(초) — observed-only. 1개 snapshot이면 null. 0 확정 금지. */
    @Column(name = "dwell_seconds")
    private Integer dwellSeconds;

    @Column(name = "label_source", nullable = false)
    private String labelSource;

    @Column(name = "label_confidence", nullable = false)
    private String labelConfidence;

    @Column(name = "correction_source")
    private String correctionSource;

    @Column(name = "correction_confidence")
    private String correctionConfidence;

    @Column(name = "excluded_from_training", nullable = false)
    private boolean excludedFromTraining;

    @Column(name = "exclude_reason")
    private String excludeReason;

    /** bus_arrival_candidate_raw.id */
    @Column(name = "arrival_raw_id", nullable = false)
    private Long arrivalRawId;

    /** bus_arrival_candidate_raw.lifecycle_id — uk */
    @Column(name = "arrival_lifecycle_id", nullable = false)
    private String arrivalLifecycleId;

    /** 보정에 사용한 bus_position_raw id 목록 (JSON 배열). */
    @Column(name = "position_raw_ids", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String positionRawIds;

    @Builder
    public BusArrivalLabelEvent(LocalDate serviceDate, String routeId,
                                String vehicleIdentityType, String vehicleIdentity,
                                String tripId, String stopId, Integer seq, String sectionId,
                                LocalDateTime apiEstimatedArrivalAt,
                                LocalDateTime correctedArrivalAt, LocalDateTime labelArrivalAt,
                                LocalDateTime departedAt, Integer dwellSeconds,
                                String labelSource, String labelConfidence,
                                String correctionSource, String correctionConfidence,
                                boolean excludedFromTraining, String excludeReason,
                                Long arrivalRawId, String arrivalLifecycleId,
                                String positionRawIds) {
        this.serviceDate = serviceDate;
        this.routeId = routeId;
        this.vehicleIdentityType = vehicleIdentityType;
        this.vehicleIdentity = vehicleIdentity;
        this.tripId = tripId;
        this.stopId = stopId;
        this.seq = seq;
        this.sectionId = sectionId;
        this.apiEstimatedArrivalAt = apiEstimatedArrivalAt;
        this.correctedArrivalAt = correctedArrivalAt;
        this.labelArrivalAt = labelArrivalAt;
        this.departedAt = departedAt;
        this.dwellSeconds = dwellSeconds;
        this.labelSource = labelSource;
        this.labelConfidence = labelConfidence;
        this.correctionSource = correctionSource;
        this.correctionConfidence = correctionConfidence;
        this.excludedFromTraining = excludedFromTraining;
        this.excludeReason = excludeReason;
        this.arrivalRawId = arrivalRawId;
        this.arrivalLifecycleId = arrivalLifecycleId;
        this.positionRawIds = positionRawIds;
    }
}
