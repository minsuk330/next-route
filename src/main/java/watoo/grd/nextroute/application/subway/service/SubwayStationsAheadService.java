package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationsAheadUseCase;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.RailPattern;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.SubwayErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 남은 역 목록(#283). 원천은 OTP 패턴({@link SubwayLinePatternService}), 계산은
 * {@link StationsAheadCalculator}, 역 연결은 {@link SubwayPatternStationMatcher}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayStationsAheadService implements GetSubwayStationsAheadUseCase {

    private final SubwayStationRepository subwayStationRepository;
    private final SubwayLinePatternService linePatternService;
    private final SubwayLineStationIndex stationIndex;

    @Override
    public SubwayStationsAheadResponse getStationsAhead(String stationId, String direction, String destination) {
        SubwayStation board = subwayStationRepository.findByStationId(stationId)
                .orElseThrow(() -> new BusinessException(SubwayErrorCode.SUBWAY_STATION_NOT_FOUND));
        if (!SubwayRoutePatternAssembler.isKnownDirection(direction)) {
            throw new BusinessException(SubwayErrorCode.SUBWAY_DIRECTION_INVALID);
        }
        String lineId = board.getLineId();

        List<DirectedRailPattern> directed;
        try {
            directed = linePatternService.patterns(lineId, direction);
        } catch (RuntimeException e) {
            log.warn("[OTP] 남은 역 패턴 조회 실패 line={} direction={}: {}", lineId, direction, e.getMessage());
            throw new BusinessException(SubwayErrorCode.SUBWAY_ROUTE_UNAVAILABLE, e);
        }
        if (directed.isEmpty()) {
            throw new BusinessException(SubwayErrorCode.SUBWAY_DIRECTION_INVALID);
        }

        List<SubwayPatternStationMatcher.Candidate> candidates = stationIndex.candidates(lineId);
        List<StationsAheadCalculator.Pattern> patterns = directed.stream()
                .map(p -> resolve(p, candidates))
                .toList();
        if (!StationsAheadCalculator.boardsAnywhere(patterns, board.getStationId())) {
            throw new BusinessException(SubwayErrorCode.SUBWAY_STATION_NOT_ON_ROUTE);
        }

        StationsAheadCalculator.Result result =
                StationsAheadCalculator.calculate(patterns, board.getStationId(), destination);
        return new SubwayStationsAheadResponse(
                lineId,
                board.getStationId(),
                board.getStationName(),
                direction,
                result.destinationApplied(),
                SubwayStationsAheadResponse.EndReason.valueOf(result.endReason().name()),
                result.branches().stream()
                        .map(b -> new SubwayStationsAheadResponse.Branch(b.destinationName(), b.nextStationName()))
                        .toList(),
                result.stations().stream().map(a -> toStation(a, lineId, candidates)).toList());
    }

    private SubwayStationsAheadResponse.Station toStation(StationsAheadCalculator.Ahead ahead, String lineId,
                                                         List<SubwayPatternStationMatcher.Candidate> candidates) {
        StationsAheadCalculator.Stop stop = ahead.stop();
        List<SubwayStationsAheadResponse.Transfer> transfers = Optional.ofNullable(stop.stationId())
                .flatMap(id -> candidates.stream().filter(c -> id.equals(c.stationId())).findFirst())
                .map(c -> stationIndex.transfers(c.stationName(), c.lat(), c.lon(), lineId).stream()
                        .map(t -> new SubwayStationsAheadResponse.Transfer(t.lineId(), t.lineName()))
                        .toList())
                .orElse(List.of());
        return new SubwayStationsAheadResponse.Station(
                stop.stationId(), stop.name(), ahead.stopsAway(), ahead.minutes(), transfers);
    }

    /** OTP 정차를 역에 잇는다. 이으면 역 ID·역 마스터 이름, 못 이으면 OTP 이름(노선 괄호 제거). */
    static StationsAheadCalculator.Pattern resolve(DirectedRailPattern pattern,
                                                   List<SubwayPatternStationMatcher.Candidate> candidates) {
        List<StationsAheadCalculator.Stop> stops = new ArrayList<>(pattern.stops().size());
        for (RailPattern.Stop stop : pattern.stops()) {
            Optional<SubwayPatternStationMatcher.Candidate> matched =
                    SubwayPatternStationMatcher.match(stop.name(), stop.lat(), stop.lon(), candidates);
            String name = matched.map(SubwayPatternStationMatcher.Candidate::stationName)
                    .orElseGet(() -> SubwayPatternStationMatcher.displayName(stop.name()));
            String key = matched.map(SubwayPatternStationMatcher.Candidate::stationId).orElse("name:" + name);
            stops.add(new StationsAheadCalculator.Stop(key, matched.map(SubwayPatternStationMatcher.Candidate::stationId)
                    .orElse(null), name, stop.arrivalSec(), stop.departureSec()));
        }
        return new StationsAheadCalculator.Pattern(pattern.routeGtfsId(), pattern.routeLongName(), pattern.loop(),
                pattern.tripCount(), stops);
    }
}
