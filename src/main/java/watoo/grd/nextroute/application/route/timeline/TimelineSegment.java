package watoo.grd.nextroute.application.route.timeline;

import watoo.grd.nextroute.application.route.routing.ActualSource;

import java.time.Instant;

/**
 * 하나의 시간축 위 한 구간.
 *
 * @param source  이 구간 시각의 출처(STATIC/REALTIME/MODEL). 정적/실시간 혼용 시 구간별로 보존
 * @param legIndex 원 itinerary leg 인덱스(WALK 초기 접근도보 포함). 참조용
 */
public record TimelineSegment(
        SegmentType type,
        Instant startAt,
        Instant endAt,
        int seconds,
        ActualSource source,
        int legIndex
) {
}
