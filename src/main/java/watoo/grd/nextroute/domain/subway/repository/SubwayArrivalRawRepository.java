package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;

import java.time.LocalDateTime;
import java.util.List;

public interface SubwayArrivalRawRepository extends JpaRepository<SubwayArrivalRaw, Long> {

	List<SubwayArrivalRaw> findByStationIdAndCollectedAtBetween(
			String stationId, LocalDateTime from, LocalDateTime to);

	List<SubwayArrivalRaw> findByStationIdAndCollectedAtAfter(
			String stationId, LocalDateTime from);

	@Modifying
	@Query(value = """
			insert into subway_arrival_raw (
				created_at, updated_at, collected_at,
				station_id, station_name, line_id, direction,
				prev_station_id, next_station_id,
				transfer_count, ordkey, transfer_lines, transfer_stations,
				train_type, arrival_seconds, train_no,
				destination_id, destination_name,
				current_message, arrival_code,
				subway_id, arrival_msg3, received_at,
				train_line_name, last_train_yn
			) values (
				CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :#{#raw.collectedAt},
				:#{#raw.stationId}, :#{#raw.stationName}, :#{#raw.lineId}, :#{#raw.direction},
				:#{#raw.prevStationId}, :#{#raw.nextStationId},
				:#{#raw.transferCount}, :#{#raw.ordkey}, :#{#raw.transferLines}, :#{#raw.transferStations},
				:#{#raw.trainType}, :#{#raw.arrivalSeconds}, :#{#raw.trainNo},
				:#{#raw.destinationId}, :#{#raw.destinationName},
				:#{#raw.currentMessage}, :#{#raw.arrivalCode},
				:#{#raw.subwayId}, :#{#raw.arrivalMsg3}, :#{#raw.receivedAt},
				:#{#raw.trainLineName}, :#{#raw.lastTrainYn}
			) on conflict (line_id, station_id, direction, train_no, received_at, arrival_code, current_message)
			do nothing
			""", nativeQuery = true)
	int insertIgnore(@Param("raw") SubwayArrivalRaw raw);

	// service_date 범위 raw 도착 후보 조회
	// received_at은 String("yyyy-MM-dd HH:mm:ss"), 범위 비교는 문자열 lexicographic으로 안전
	@Query(value = """
			SELECT * FROM subway_arrival_raw
			WHERE arrival_code = '1'
			  AND received_at >= :fromReceivedAt
			  AND received_at < :toReceivedAt
			  AND line_id IS NOT NULL
			  AND station_id IS NOT NULL
			  AND train_no IS NOT NULL
			  AND direction IS NOT NULL
			  AND received_at IS NOT NULL
			""", nativeQuery = true)
	List<SubwayArrivalRaw> findArrivalCandidatesInRange(
			@Param("fromReceivedAt") String fromReceivedAt,
			@Param("toReceivedAt") String toReceivedAt);

	// Phase C: code=3(전역출발) 후보 — findArrivalCandidatesInRange 미러 + prev_station_id 필수
	@Query(value = """
			SELECT * FROM subway_arrival_raw
			WHERE arrival_code = '3'
			  AND received_at >= :fromReceivedAt
			  AND received_at < :toReceivedAt
			  AND line_id IS NOT NULL
			  AND station_id IS NOT NULL
			  AND train_no IS NOT NULL
			  AND direction IS NOT NULL
			  AND received_at IS NOT NULL
			  AND prev_station_id IS NOT NULL
			  AND line_id IN (:lineIds)
			""", nativeQuery = true)
	List<SubwayArrivalRaw> findPrevDepartureCandidatesInRange(
			@Param("fromReceivedAt") String fromReceivedAt,
			@Param("toReceivedAt") String toReceivedAt,
			@Param("lineIds") java.util.Collection<String> lineIds);
}
