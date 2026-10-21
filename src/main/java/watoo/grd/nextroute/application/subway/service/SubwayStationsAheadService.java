package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.BehindEnd;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.LineStrip;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.StripStation;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayLineStripUseCase;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationsAheadUseCase;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.RailPattern;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.SubwayErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 남은 역 목록(#283). 원천은 OTP 패턴({@link SubwayLinePatternService}), 계산은
 * {@link StationsAheadCalculator}, 역 연결은 {@link SubwayPatternStationMatcher}.
 *
 * <p>열차별 정차(subway-express PR-S3)는 {@link SubwayStationsAheadQueryService} 가 이 결과 위에 붙인다 — 급행 해석기가
 * {@link SubwayLineOrder} 를 거쳐 이 빈을 쓰므로 여기서 해석기를 부르면 빈 순환이 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayStationsAheadService implements GetSubwayLineStripUseCase {

    /** 노선도에 그리는 오는 쪽 역 수(#317). */
    static final int BEHIND_COUNT = 4;

    private final SubwayStationRepository subwayStationRepository;
    private final SubwayLinePatternService linePatternService;
    private final SubwayLineStationIndex stationIndex;

    /**
     * (line, direction) → 역에 이은 패턴. 정차마다 같은 노선 역 전부와 거리를 재므로 2호선은 한 번에
     * 약 240ms 다(#291). 원본 패턴 목록이 <b>같은 객체</b>일 때만 재사용한다 — 패턴 캐시가 갱신되면 새
     * 목록이 오므로 저절로 다시 계산된다.
     */
    private final Map<String, Resolved> resolved = new ConcurrentHashMap<>();

    /** 완행 기준 남은 역. 예외는 {@link GetSubwayStationsAheadUseCase} 참고. */
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
        List<StationsAheadCalculator.Pattern> patterns = resolvedPatterns(lineId, direction, directed, candidates);
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
                        .map(b -> new SubwayStationsAheadResponse.Branch(b.destinationName(), b.nextStationName(),
                                SubwayStationsAheadResponse.EndReason.valueOf(b.kind().name())))
                        .toList(),
                result.stations().stream().map(a -> toStation(a, lineId, candidates)).toList(),
                boardGtfsStopId(patterns, board.getStationId()));
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
                stop.stationId(), stop.name(), ahead.stopsAway(), ahead.minutes(), transfers,
                stop.gtfsStopId(), ahead.routeGtfsId(),
                ahead.secondsFromBoard(), ahead.runSeconds(), ahead.dwellSeconds());
    }

    /**
     * 역 도착 응답의 방향별 노선도(#317). 남은 역과 같은 패턴·역 연결 캐시를 쓰되 <b>OTP 를 부르지 않는다</b> — 적재된
     * 패턴이 없으면 empty 로 답하고 뒤에서 적재한다.
     */
    @Override
    public Optional<LineStrip> strip(String lineId, String direction, String stationId) {
        if (lineId == null || stationId == null || !SubwayRoutePatternAssembler.isKnownDirection(direction)) {
            return Optional.empty();
        }
        try {
            List<DirectedRailPattern> directed = linePatternService.cachedPatterns(lineId, direction);
            if (directed.isEmpty()) {
                linePatternService.loadLater(lineId, direction);
                return Optional.empty();
            }
            List<StationsAheadCalculator.Pattern> patterns =
                    resolvedPatterns(lineId, direction, directed, stationIndex.candidates(lineId));
            if (!StationsAheadCalculator.boardsAnywhere(patterns, stationId)) {
                return Optional.empty();
            }
            StationsAheadCalculator.Behind behind = StationsAheadCalculator.behind(patterns, stationId, BEHIND_COUNT);
            List<StripStation> behindStations = new ArrayList<>(behind.stops().size());
            for (int i = 0; i < behind.stops().size(); i++) {
                StationsAheadCalculator.Stop stop = behind.stops().get(i);
                behindStations.add(new StripStation(stop.stationId(), stop.name(), behind.secondsToHere().get(i),
                        behind.runSeconds().get(i), StationsAheadCalculator.seconds(stop.arrivalSec(), stop.departureSec())));
            }
            StripStation next = StationsAheadCalculator.calculate(patterns, stationId, null).stations().stream()
                    .findFirst()
                    .map(a -> new StripStation(a.stop().stationId(), a.stop().name(), a.secondsFromBoard(),
                            a.runSeconds(), a.dwellSeconds()))
                    .orElse(null);
            return Optional.of(new LineStrip(behindStations, next, behind.boardDwellSeconds(),
                    BehindEnd.valueOf(behind.end().name())));
        } catch (RuntimeException e) {
            log.warn("지하철 노선도 계산 실패 — 비워서 응답 line={} direction={} station={}: {}",
                    lineId, direction, stationId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 승차역과 이어진 첫 패턴 정차의 GTFS id(#298). 못 찾으면 null. */
    static String boardGtfsStopId(List<StationsAheadCalculator.Pattern> patterns, String boardStationId) {
        return patterns.stream()
                .flatMap(p -> p.stops().stream())
                .filter(s -> boardStationId.equals(s.stationId()) && s.gtfsStopId() != null)
                .map(StationsAheadCalculator.Stop::gtfsStopId)
                .findFirst()
                .orElse(null);
    }

    List<StationsAheadCalculator.Pattern> resolvedPatterns(
            String lineId, String direction, List<DirectedRailPattern> directed,
            List<SubwayPatternStationMatcher.Candidate> candidates) {
        String key = lineId + ":" + direction;
        Resolved cached = resolved.get(key);
        if (cached != null && cached.source() == directed) {
            return cached.patterns();
        }
        List<StationsAheadCalculator.Pattern> patterns = directed.stream().map(p -> resolve(p, candidates)).toList();
        resolved.put(key, new Resolved(directed, patterns));
        return patterns;
    }

    private record Resolved(List<DirectedRailPattern> source, List<StationsAheadCalculator.Pattern> patterns) {
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
                    .orElse(null), name, stop.arrivalSec(), stop.departureSec(), stop.gtfsId()));
        }
        return new StationsAheadCalculator.Pattern(pattern.routeGtfsId(), pattern.routeLongName(), pattern.loop(),
                pattern.tripCount(), stops);
    }
}
