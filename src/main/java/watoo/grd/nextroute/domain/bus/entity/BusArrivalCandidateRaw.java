package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(name = "bus_arrival_candidate_raw")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusArrivalCandidateRaw extends BaseEntity {

	@Column(name = "collected_at", nullable = false)
	private LocalDateTime collectedAt;

	@Column(name = "finalized_at", nullable = false)
	private LocalDateTime finalizedAt;

	@Column(name = "first_seen_at")
	private LocalDateTime firstSeenAt;

	@Column(name = "last_seen_at")
	private LocalDateTime lastSeenAt;

	@Column(name = "last_collected_at")
	private LocalDateTime lastCollectedAt;

	@Column(name = "route_id")
	private String routeId;

	@Column(name = "route_abrv")
	private String routeAbrv;

	@Column(name = "route_name")
	private String routeName;

	@Column(name = "stop_id")
	private String stopId;

	@Column(name = "ars_id")
	private String arsId;

	@Column(name = "stop_name")
	private String stopName;

	@Column(name = "seq")
	private Integer seq;

	@Column(name = "direction")
	private String direction;

	@Column(name = "route_type")
	private Integer routeType;

	@Column(name = "term")
	private Integer term;

	@Column(name = "data_timestamp")
	private String dataTimestamp;

	@Column(name = "detour_yn")
	private String detourYn;

	@Column(name = "next_bus_yn")
	private String nextBusYn;

	@Column(name = "first_bus_time")
	private String firstBusTime;

	@Column(name = "last_bus_time")
	private String lastBusTime;

	@Column(name = "arrival_order")
	private Integer arrivalOrder;

	@Column(name = "arrival_msg")
	private String arrivalMsg;

	@Column(name = "vehicle_id")
	private String vehicleId;

	@Column(name = "plain_no")
	private String plainNo;

	@Column(name = "vehicle_identity")
	private String vehicleIdentity;

	@Column(name = "vehicle_identity_type")
	private String vehicleIdentityType;

	@Column(name = "bus_type")
	private Integer busType;

	@Column(name = "section_order")
	private Integer sectionOrder;

	@Column(name = "station_name")
	private String stationName;

	@Column(name = "is_arrive")
	private String isArrive;

	@Column(name = "is_last")
	private String isLast;

	@Column(name = "is_full")
	private String isFull;

	@Column(name = "predict_time")
	private Integer predictTime;

	@Column(name = "kal_predict_time")
	private Integer kalPredictTime;

	@Column(name = "neu_predict_time")
	private Integer neuPredictTime;

	@Column(name = "goal_time")
	private Integer goalTime;

	@Column(name = "avg_coefficient")
	private Double avgCoefficient;

	@Column(name = "exp_coefficient")
	private Double expCoefficient;

	@Column(name = "kal_coefficient")
	private Double kalCoefficient;

	@Column(name = "neu_coefficient")
	private Double neuCoefficient;

	@Column(name = "section_time")
	private Integer sectionTime;

	@Column(name = "section_speed")
	private Double sectionSpeed;

	@Column(name = "congestion_num")
	private Integer congestionNum;

	@Column(name = "congestion_div")
	private Integer congestionDiv;

	@Column(name = "ride_num")
	private Integer rideNum;

	@Column(name = "ride_div")
	private Integer rideDiv;

	@Column(name = "next_stop_id")
	private String nextStopId;

	@Column(name = "next_stop_ord")
	private Integer nextStopOrd;

	@Column(name = "next_stop_sec")
	private Integer nextStopSec;

	@Column(name = "next_stop_spd")
	private Integer nextStopSpd;

	@Column(name = "main_stop_ord")
	private Integer mainStopOrd;

	@Column(name = "main_stop_sec")
	private Integer mainStopSec;

	@Column(name = "main_stop_id")
	private String mainStopId;

	@Column(name = "main2_stop_ord")
	private Integer main2StopOrd;

	@Column(name = "main2_stop_sec")
	private Integer main2StopSec;

	@Column(name = "main2_stop_id")
	private String main2StopId;

	@Column(name = "main3_stop_ord")
	private Integer main3StopOrd;

	@Column(name = "main3_stop_sec")
	private Integer main3StopSec;

	@Column(name = "main3_stop_id")
	private String main3StopId;
}
