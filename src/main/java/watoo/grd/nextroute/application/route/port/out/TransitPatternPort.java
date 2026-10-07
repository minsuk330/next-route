package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * OTP(GTFS) 노선 패턴 원천 — 단독 추적(#265)이 경로 검색 없이 구간을 오려 내는 재료.
 *
 * <p><b>OTP 동시성 permit 은 부르는 쪽이 잡는다</b>(어댑터는 안 잡는다). 한 요청이 여러 번 부르므로 permit 하나와
 * {@link RoutingDeadline} 하나를 그 묶음 전체가 나눠 쓴다.
 *
 * <p>실패(포화 아님 — 호출 오류·시간 초과·GraphQL 오류)는 {@link TransitPatternUnavailableException} 이다. 빈
 * 목록·{@code Optional.empty()} 는 "그런 것이 없다"는 뜻으로만 쓴다.
 *
 * <p><b>패턴 trip 의 stoptimes 를 한 번에 받지 않는다</b> — 제한 인자가 없어 2호선이면 수만 행이다. trip 목록과
 * trip 하나의 stoptimes 를 따로 받는다.
 */
public interface TransitPatternPort {

    /** 좌표 주변 정류장/역. 가까운 순. */
    List<TransitStop> stopsNear(double lat, double lon, int radiusMeters, int limit, RoutingDeadline deadline);

    /** 그 정류장을 지나는 모든 패턴(가벼운 모양: 정류장 id·이름만). 정류장이 없으면 빈 목록. */
    List<TransitPattern> patternsAtStop(String gtfsStopId, RoutingDeadline deadline);

    /** 패턴 하나의 정류장 좌표·형상. 코드가 낡았으면(그래프 재빌드) 비어 있다. */
    Optional<TransitPatternDetail> pattern(String patternCode, RoutingDeadline deadline);

    /** 그 운행일에 도는 패턴의 trip 과 첫 출발 시각. */
    List<TransitTripRef> tripsForDate(String patternCode, LocalDate serviceDate, RoutingDeadline deadline);

    /** trip 하나의 정차 시각. 패턴 정류장과 순서·개수가 같다. */
    List<TransitStopTime> tripStopTimes(String tripGtfsId, LocalDate serviceDate, RoutingDeadline deadline);

    /**
     * @param mode    중립 수단(SUBWAY·TRAM → SUBWAY). 미지원은 UNSUPPORTED
     * @param otpMode OTP 원문(BUS·SUBWAY·RAIL·TRAM) — {@code RoutingLeg.sourceDetailType} 으로 간다
     * @param color   GTFS route_color("#" 없는 6자리). 없으면 null
     */
    record TransitRoute(String gtfsId, String shortName, String longName, RoutingMode mode,
                        String otpMode, String color, String agencyGtfsId) {
    }

    record TransitStop(String gtfsId, String name, double lat, double lon, RoutingMode mode) {
    }

    /** 패턴 안의 정류장 1회 방문(같은 정류장이 여러 번 나올 수 있다 — 2호선 패턴은 한 패턴이 여러 바퀴). */
    record PatternStop(String gtfsId, String name) {
    }

    /**
     * @param headsign  없으면 null(TAGO·지하철 실측 전부 null)
     * @param tripCount 이 패턴의 전체 trip 수(운행일 무관). 같은 구간을 가진 패턴끼리 대표를 고를 때만 쓴다
     */
    record TransitPattern(String code, String headsign, TransitRoute route, List<PatternStop> stops,
                          int tripCount) {
        public TransitPattern {
            stops = stops == null ? List.of() : List.copyOf(stops);
        }
    }

    /** @param geometryPoints pattern 형상(인코딩 폴리라인). 없으면 null */
    record TransitPatternDetail(String code, TransitRoute route, List<TransitStop> stops, String geometryPoints) {
        public TransitPatternDetail {
            stops = stops == null ? List.of() : List.copyOf(stops);
        }
    }

    /** @param firstDepartureSec 운행일 자정 기준 첫 정류장 출발(초). 모르면 null */
    record TransitTripRef(String gtfsId, Integer firstDepartureSec) {
    }

    /** 운행일 자정 기준 초. */
    record TransitStopTime(String stopGtfsId, int scheduledArrival, int scheduledDeparture) {
    }

    /** OTP 호출 실패(오류·시간 초과). 포화(permit 못 얻음)는 부르는 쪽이 따로 판단한다. */
    class TransitPatternUnavailableException extends RuntimeException {
        public TransitPatternUnavailableException(String message) {
            super(message);
        }

        public TransitPatternUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
