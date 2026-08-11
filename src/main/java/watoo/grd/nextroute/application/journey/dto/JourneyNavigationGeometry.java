package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 여정 전체의 지도·진행 판정용 좌표.
 *
 * <p>SC-8 에 따라 leg 하나가 아니라 선택 경로 전체를 한 번에 내려준다. 앱은 이것을
 * {@code (journeyId, routeRevision)} 로 캐시하고 1초 GPS 갱신마다 다시 만들지 않는다.
 *
 * @param journeyId       여정 식별자
 * @param routeRevision   이 좌표가 속한 경로 개정본. 앱 캐시 fencing 키의 일부다
 * @param legs            단계별 좌표. 저장된 단계 순서를 그대로 따른다
 * @param sourceFetchedAt 좌표 원천 중 가장 오래된 수집 시각. 없으면 null
 */
public record JourneyNavigationGeometry(
        Long journeyId,
        int routeRevision,
        List<LegGeometry> legs,
        OffsetDateTime sourceFetchedAt
) {

    public JourneyNavigationGeometry {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    /**
     * 좌표 품질.
     *
     * <p>{@code REVIEW}·{@code FAILED}·{@code APPROXIMATE} 를 응답에서 빼지 않는 것이 계약이다.
     * 빼면 앱이 구간 수를 서버와 다르게 세고 진행 순번이 어긋난다. 대신 앱은 이 값을 보고
     * "지도에는 그리되 진행 확정의 단독 근거로는 쓰지 않는다"를 판단한다.
     */
    public enum GeometryQuality {
        /** 원천 좌표가 정상이다. */
        OK,
        /** 원천이 검토 대상으로 표시했다. 지도 표시는 가능하다. */
        REVIEW,
        /** 원천 좌표가 없다. */
        FAILED,
        /** 실측 좌표가 없어 직선으로 대체했다. */
        APPROXIMATE
    }

    /**
     * 단계 하나의 좌표.
     *
     * @param legIndex                단계 순번
     * @param type                    단계 종류
     * @param routeId                 버스·지하철 노선 식별자. 도보·대기는 null
     * @param plannedDurationSeconds  이 단계의 계획 소요시간(초). 대기 단계는 null
     * @param totalDistanceMeters     좌표를 이어 붙인 총 길이(m)
     * @param quality                 단계 전체 품질. segment 중 가장 나쁜 값을 따른다
     * @param points                  지도 표시용 전체 좌표
     * @param segments                진행 판정용 구간. 대기 단계는 비어 있다
     * @param stops                   버스 승차~하차 정류장. 다른 단계는 비어 있다
     * @param maneuvers               걷기 단계의 turn-by-turn 안내점. 다른 단계는 비어 있다
     */
    public record LegGeometry(
            int legIndex,
            JourneyLegType type,
            String routeId,
            Integer plannedDurationSeconds,
            double totalDistanceMeters,
            GeometryQuality quality,
            List<GeoPoint> points,
            List<SegmentGeometry> segments,
            List<StopGeometry> stops,
            List<ManeuverGeometry> maneuvers
    ) {

        public LegGeometry {
            points = points == null ? List.of() : List.copyOf(points);
            segments = segments == null ? List.of() : List.copyOf(segments);
            stops = stops == null ? List.of() : List.copyOf(stops);
            // 안내점은 null 대신 빈 목록이다. 앱이 "없음"과 "필드 누락"을 구분할 필요가 없어야 한다.
            maneuvers = maneuvers == null ? List.of() : List.copyOf(maneuvers);
        }

        /** 기존 서버 코드와 테스트가 새 필드 배포 중에도 그대로 컴파일되도록 유지한다. */
        public LegGeometry(int legIndex, JourneyLegType type, String routeId,
                           Integer plannedDurationSeconds, double totalDistanceMeters,
                           GeometryQuality quality, List<GeoPoint> points,
                           List<SegmentGeometry> segments) {
            this(legIndex, type, routeId, plannedDurationSeconds, totalDistanceMeters,
                    quality, points, segments, List.of(), List.of());
        }

        /** 정류장까지만 있던 시절의 호출부를 위해 유지한다. */
        public LegGeometry(int legIndex, JourneyLegType type, String routeId,
                           Integer plannedDurationSeconds, double totalDistanceMeters,
                           GeometryQuality quality, List<GeoPoint> points,
                           List<SegmentGeometry> segments, List<StopGeometry> stops) {
            this(legIndex, type, routeId, plannedDurationSeconds, totalDistanceMeters,
                    quality, points, segments, stops, List.of());
        }
    }

    /**
     * 걷기 단계의 안내점 하나.
     *
     * <p>서버는 <b>지금 어디인지 판정하지 않는다.</b> 안내점이 경로 위 어디에 있는지만 알려주고,
     * GPS 매칭과 "다음 안내"의 선택은 앱이 한다. 그래서 요청에 현재 위치를 받지 않는다.
     *
     * @param maneuverIndex                이 목록 안에서의 순번. 0 부터. 원천의 순번이 아니다 —
     *                                     안내점 하나가 빠져도 번호에 구멍이 남지 않아야 한다
     * @param type                         정규화한 회전 종류
     * @param instruction                  "횡단보도를 건넌 후 좌회전하세요" 등 원천 안내 문구
     * @param geometrySegmentIndex         이 단계 {@code segments} 안에서의 구간 순번
     * @param segmentProgress              그 구간 안의 위치 비율. 0…1
     * @param distanceFromLegStartMeters   단계 시작점부터의 경로 거리(m). 안내점 순서대로 증가한다
     * @param x                            경도
     * @param y                            위도
     */
    public record ManeuverGeometry(
            int maneuverIndex,
            ManeuverType type,
            String instruction,
            int geometrySegmentIndex,
            double segmentProgress,
            double distanceFromLegStartMeters,
            double x,
            double y
    ) {
    }

    /** 버스 노선의 승차 정류장부터 하차 정류장까지의 순서·좌표. */
    public record StopGeometry(
            int seq,
            String stopId,
            String stopName,
            Double x,
            Double y
    ) {
    }

    /**
     * 진행 판정의 최소 단위.
     *
     * <p>{@code geometrySegmentIndex} 는 앱이 progress 로 되돌려 보내는 값이라 순서가 곧 계약이다.
     * 버스만 {@code sectionId}·정류장 순번을 추가로 가진다.
     *
     * @param geometrySegmentIndex   단계 안에서의 구간 순번. 0 부터
     * @param sectionId              버스 구간 식별자. 다른 단계는 null
     * @param fromSeq                버스 출발 정류장 순번. 다른 단계는 null
     * @param toSeq                  버스 도착 정류장 순번. 다른 단계는 null
     * @param distanceMeters         구간 길이(m)
     * @param plannedDurationSeconds 거리 비율로 배분한 소요시간(초). 배분 불가면 null
     * @param quality                구간 품질
     * @param qualityReason          품질이 정상이 아닌 이유. 정상이면 null
     * @param points                 구간 좌표. 품질 {@code FAILED} 면 비어 있다
     */
    public record SegmentGeometry(
            int geometrySegmentIndex,
            String sectionId,
            Integer fromSeq,
            Integer toSeq,
            double distanceMeters,
            Integer plannedDurationSeconds,
            GeometryQuality quality,
            String qualityReason,
            List<GeoPoint> points
    ) {

        public SegmentGeometry {
            points = points == null ? List.of() : List.copyOf(points);
            // 품질은 비워 둘 수 없다. 비면 "가장 나쁜 구간" 비교가 터지고, 앱도 믿을지 말지를
            // 판단할 근거를 잃는다.
            quality = quality == null ? GeometryQuality.OK : quality;
        }

        /** 거리·배분 시간·순번만 채운 새 값. 좌표와 품질은 그대로 둔다. */
        public SegmentGeometry withAllocation(int index, double meters, Integer seconds) {
            return new SegmentGeometry(index, sectionId, fromSeq, toSeq, meters, seconds,
                    quality, qualityReason, points);
        }

        /** 품질만 바꾼 새 값. */
        public SegmentGeometry withQuality(GeometryQuality newQuality, String reason) {
            return new SegmentGeometry(geometrySegmentIndex, sectionId, fromSeq, toSeq,
                    distanceMeters, plannedDurationSeconds, newQuality, reason, points);
        }
    }

    /** 경도·위도. 검색 결과와 버스 폴리라인이 쓰는 {@code (x, y)} 순서를 그대로 따른다. */
    public record GeoPoint(double x, double y) {
    }
}
