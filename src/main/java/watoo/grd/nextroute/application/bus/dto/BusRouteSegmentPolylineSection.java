package watoo.grd.nextroute.application.bus.dto;

import java.time.OffsetDateTime;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineData;
import java.util.List;

/**
 * 정류장 구간 하나의 폴리라인. 합치지 않은 원본 단위다.
 *
 * <p>{@link BusRouteSegmentPolylineRange} 는 지도에 한 줄로 그릴 목적이라 구간을 이어 붙이고
 * 중간에 끊기면 좌표를 통째로 비운다. 여정 진행 판정은 그 반대가 필요하다 — 어느 구간이
 * 불량한지 알아야 그 구간만 근거에서 빼고 다음 정상 구간에서 다시 맞출 수 있다.
 *
 * @param fromSeq         출발 정류장 순번
 * @param toSeq           도착 정류장 순번
 * @param sectionId       구간 식별자
 * @param qualityStatus   좌표 품질
 * @param qualityReason   품질이 정상이 아닌 이유. 정상이면 null
 * @param points          구간 좌표. {@code FAILED} 면 비어 있다
 * @param sourceFetchedAt 원천 수집 시각
 */
public record BusRouteSegmentPolylineSection(
		int fromSeq,
		int toSeq,
		String sectionId,
		BusRouteSegmentPolylineData.QualityStatus qualityStatus,
		String qualityReason,
		List<BusRoutePolylineData.Point> points,
		OffsetDateTime sourceFetchedAt
) {

	public BusRouteSegmentPolylineSection {
		points = points == null ? List.of() : List.copyOf(points);
	}

	public boolean usableForProgress() {
		return qualityStatus == BusRouteSegmentPolylineData.QualityStatus.OK && points.size() >= 2;
	}
}
