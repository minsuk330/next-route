package watoo.grd.nextroute.infrastructure.adapter.out.persistence.bus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineRange;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineSection;
import watoo.grd.nextroute.application.bus.port.out.BusRouteSegmentPolylinePort;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PostgisBusRouteSegmentPolylineAdapter implements BusRouteSegmentPolylinePort {

	private static final String INSERT_SQL = """
			INSERT INTO bus_route_segment_polyline (
			    route_id, from_seq, to_seq, from_stop_id, to_stop_id, section_id,
			    point_count, polyline, geom, start_fraction, end_fraction,
			    start_error_m, end_error_m, expected_distance_m, actual_distance_m,
			    distance_error_m, quality_status, quality_reason, matcher_version, source_fetched_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb,
			        CASE WHEN ? IS NULL THEN NULL ELSE ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) END,
			        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	@Override
	@Transactional
	public void replaceRouteSegments(String routeId, List<BusRouteSegmentPolylineData> segments) {
		jdbcTemplate.update("DELETE FROM bus_route_segment_polyline WHERE route_id = ?", routeId);
		if (segments.isEmpty()) {
			return;
		}

		jdbcTemplate.batchUpdate(INSERT_SQL, segments, 200, (statement, segment) -> {
			String polylineJson = segment.points().isEmpty()
					? null
					: serialize(new BusRoutePolylineData(segment.points()));
			String geoJson = segment.points().isEmpty() ? null : serialize(Map.of(
					"type", "LineString",
					"coordinates", segment.points().stream().map(point -> List.of(point.x(), point.y())).toList()));

			statement.setString(1, segment.routeId());
			statement.setInt(2, segment.fromSeq());
			statement.setInt(3, segment.toSeq());
			statement.setString(4, segment.fromStopId());
			statement.setString(5, segment.toStopId());
			statement.setString(6, segment.sectionId());
			statement.setInt(7, segment.points().size());
			statement.setString(8, polylineJson);
			statement.setString(9, geoJson);
			statement.setString(10, geoJson);
			setNullableDouble(statement, 11, segment.startFraction());
			setNullableDouble(statement, 12, segment.endFraction());
			setNullableDouble(statement, 13, segment.startErrorMeters());
			setNullableDouble(statement, 14, segment.endErrorMeters());
			setNullableDouble(statement, 15, segment.expectedDistanceMeters());
			setNullableDouble(statement, 16, segment.actualDistanceMeters());
			setNullableDouble(statement, 17, segment.distanceErrorMeters());
			statement.setString(18, segment.qualityStatus().name());
			statement.setString(19, segment.qualityReason());
			statement.setString(20, segment.matcherVersion());
			statement.setObject(21, segment.sourceFetchedAt(), Types.TIMESTAMP_WITH_TIMEZONE);
		});
	}

	@Override
	public Optional<BusRouteSegmentPolylineRange> findRange(String routeId, int fromSeq, int toSeq) {
		List<StoredSegment> stored = jdbcTemplate.query("""
				SELECT from_seq, to_seq, section_id, quality_status, quality_reason, polyline::text
				FROM bus_route_segment_polyline
				WHERE route_id = ? AND from_seq >= ? AND to_seq <= ?
				ORDER BY from_seq, to_seq
				""", (resultSet, rowNum) -> new StoredSegment(
				resultSet.getInt("from_seq"),
				resultSet.getInt("to_seq"),
				resultSet.getString("section_id"),
				BusRouteSegmentPolylineData.QualityStatus.valueOf(resultSet.getString("quality_status")),
				resultSet.getString("quality_reason"),
				deserializeNullable(resultSet.getString("polyline"))), routeId, fromSeq, toSeq);

		if (stored.isEmpty()) {
			return Optional.empty();
		}

		List<BusRouteSegmentPolylineRange.SegmentSummary> summaries = stored.stream()
				.map(segment -> new BusRouteSegmentPolylineRange.SegmentSummary(
						segment.fromSeq(), segment.toSeq(), segment.sectionId(),
						segment.qualityStatus(), segment.qualityReason()))
				.toList();
		List<BusRoutePolylineData.Point> merged = new ArrayList<>();
		int cursor = fromSeq;
		boolean hasGap = false;
		for (StoredSegment segment : stored) {
			if (segment.fromSeq() != cursor || segment.toSeq() <= segment.fromSeq()
					|| segment.qualityStatus() != BusRouteSegmentPolylineData.QualityStatus.OK
					|| segment.polyline() == null || segment.polyline().size() < 2) {
				hasGap = true;
				break;
			}
			append(merged, segment.polyline().points());
			cursor = segment.toSeq();
		}
		if (cursor != toSeq) {
			hasGap = true;
		}
		if (hasGap) {
			merged = List.of();
		}

		return Optional.of(new BusRouteSegmentPolylineRange(
				routeId, fromSeq, toSeq, hasGap, List.copyOf(merged), summaries));
	}

	@Override
	public List<BusRouteSegmentPolylineSection> findSections(String routeId, int fromSeq, int toSeq) {
		return jdbcTemplate.query("""
				SELECT from_seq, to_seq, section_id, quality_status, quality_reason,
				       polyline::text, source_fetched_at
				FROM bus_route_segment_polyline
				WHERE route_id = ? AND from_seq >= ? AND to_seq <= ?
				ORDER BY from_seq, to_seq
				""", (resultSet, rowNum) -> {
			BusRoutePolylineData polyline = deserializeNullable(resultSet.getString("polyline"));
			OffsetDateTime fetchedAt = resultSet.getObject("source_fetched_at", OffsetDateTime.class);
			return new BusRouteSegmentPolylineSection(
					resultSet.getInt("from_seq"),
					resultSet.getInt("to_seq"),
					resultSet.getString("section_id"),
					BusRouteSegmentPolylineData.QualityStatus.valueOf(resultSet.getString("quality_status")),
					resultSet.getString("quality_reason"),
					polyline == null ? List.of() : polyline.points(),
					fetchedAt);
		}, routeId, fromSeq, toSeq);
	}

	private void append(List<BusRoutePolylineData.Point> target, List<BusRoutePolylineData.Point> source) {
		for (BusRoutePolylineData.Point point : source) {
			if (target.isEmpty()) {
				target.add(point);
				continue;
			}
			BusRoutePolylineData.Point last = target.get(target.size() - 1);
			if (Double.compare(last.x(), point.x()) != 0 || Double.compare(last.y(), point.y()) != 0) {
				target.add(point);
			}
		}
	}

	private void setNullableDouble(java.sql.PreparedStatement statement, int index, Double value)
			throws java.sql.SQLException {
		if (value == null) {
			statement.setNull(index, Types.DOUBLE);
		} else {
			statement.setDouble(index, value);
		}
	}

	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize bus route segment polyline", exception);
		}
	}

	private BusRoutePolylineData deserializeNullable(String json) {
		if (json == null) {
			return null;
		}
		try {
			return objectMapper.readValue(json, BusRoutePolylineData.class);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to deserialize bus route segment polyline", exception);
		}
	}

	private record StoredSegment(
			int fromSeq,
			int toSeq,
			String sectionId,
			BusRouteSegmentPolylineData.QualityStatus qualityStatus,
			String qualityReason,
			BusRoutePolylineData polyline
	) {
	}
}
