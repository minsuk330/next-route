package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.subway.service.SubwayLineStationIndex;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher;
import watoo.grd.nextroute.application.subway.service.SubwayRoutePatternAssembler;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 단독 추적 조회(#313)의 GTFS id 를 앱 실시간 기능이 받는 id 로 바꾼다. <b>못 풀면 null 이다</b> — 가짜 id 를
 * 주면 앱은 다른 정류장 도착을 보여 준다.
 *
 * <ul>
 *   <li>버스: {@link OtpSeoulIdentityResolver} 의 정규식 규칙(서울 SEB=DB 확인, 경기 GGB=GBIS, 그 밖=TAGO 접두사 포함).
 *       버스 도착 API 가 이 모양으로 원천을 고른다.</li>
 *   <li>지하철: OTP 는 호선마다 정류장이 따로다({@code 강남(2호선)}). 노선 이름으로 호선을 알고, 역은 #283 의
 *       {@link SubwayPatternStationMatcher}(이름 우선, 19노선 전수 커버리지)로 잇는다. 좌표 50m 최근접만 쓰는
 *       {@code resolveSubway} 는 1~9호선만 되고 노선별 10~26% 가 끊겼다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TrackRealtimeIdResolver {

    private final OtpSeoulIdentityResolver identityResolver;
    private final BusStopRepository busStopRepository;
    private final SubwayLineStationIndex stationIndex;

    /** @param arsId 서울 정류장 번호(5자리). 서울 밖이면 null */
    public record BusStopIds(String arrivalStopId, String arsId) {
        public static final BusStopIds NONE = new BusStopIds(null, null);
    }

    /** 세 값이 모두 null 이면 실시간 역을 모른다. */
    public record SubwayStationIds(String arrivalStationId, String lineId, String lineName) {
        static final SubwayStationIds NONE = new SubwayStationIds(null, null, null);
    }

    public BusStopIds busStop(String stopGtfsId) {
        return identityResolver.busStopArrivalId(stopGtfsId)
                .map(id -> new BusStopIds(id, busStopRepository.findByStopId(id).map(BusStop::getArsId).orElse(null)))
                .orElse(BusStopIds.NONE);
    }

    public String busRoute(String routeGtfsId) {
        return identityResolver.busRouteArrivalId(routeGtfsId).orElse(null);
    }

    /**
     * 정류장을 지나는 호선마다 하나. 호선을 모르면 전부 null 인 한 줄 — 정류장 자체는 목록에 남긴다.
     *
     * @param routeShortNames OTP 노선 이름({@code 서울2호선}, {@code 신분당선})
     */
    public List<SubwayStationIds> subway(TransitStop stop, List<String> routeShortNames) {
        Set<String> lineIds = new LinkedHashSet<>();
        for (String name : routeShortNames == null ? List.<String>of() : routeShortNames) {
            SubwayRoutePatternAssembler.lineIdOf(name).ifPresent(lineIds::add);
        }
        List<SubwayStationIds> rows = new ArrayList<>();
        for (String lineId : lineIds) {
            Optional<SubwayPatternStationMatcher.Candidate> station = SubwayPatternStationMatcher.match(
                    stop.name(), stop.lat(), stop.lon(), stationIndex.candidates(lineId));
            rows.add(station
                    .map(c -> new SubwayStationIds(c.stationId(), lineId, stationIndex.lineName(lineId)))
                    .orElse(new SubwayStationIds(null, lineId, stationIndex.lineName(lineId))));
        }
        return rows.isEmpty() ? List.of(SubwayStationIds.NONE) : rows;
    }
}
