package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;
import watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpSubwayRoutePatternAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

/** 운영 OTP·역 마스터 fixture({@code otp/subway-patterns/}, 2026-10-07)를 운영 코드 경로 그대로 읽는다. */
final class SubwayPatternFixtures {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    static final List<OtpRailRoute> ROUTES = new ArrayList<>();
    static final Map<String, List<RailPattern>> PATTERNS = new HashMap<>();
    static final Map<String, List<SubwayPatternStationMatcher.Candidate>> STATIONS = new TreeMap<>();

    static {
        try {
            for (JsonNode route : read("otp/subway-patterns/routes-with-patterns.json").path("routes")) {
                String id = route.path("gtfsId").asText();
                ROUTES.add(new OtpRailRoute(id, route.path("shortName").asText(), route.path("longName").asText()));
                PATTERNS.put(id, OtpSubwayRoutePatternAdapter.parsePatterns(id, route));
            }
            for (JsonNode s : read("otp/subway-patterns/subway-stations.json").path("stations")) {
                STATIONS.computeIfAbsent(s.path("lineId").asText(), k -> new ArrayList<>())
                        .add(new SubwayPatternStationMatcher.Candidate(s.path("stationId").asText(),
                                s.path("stationName").asText(), s.path("latitude").asDouble(),
                                s.path("longitude").asDouble()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SubwayPatternFixtures() {
    }

    /** 서비스가 계산기에 넘기는 것과 같은 패턴. */
    static List<StationsAheadCalculator.Pattern> patterns(String lineId, String direction) {
        List<StationsAheadCalculator.Pattern> out = new ArrayList<>();
        for (OtpRailRoute route : SubwayRoutePatternAssembler.selectRoutes(ROUTES, lineId, direction)) {
            SubwayRoutePatternAssembler.orient(route, PATTERNS.get(route.gtfsId()), direction)
                    .forEach(p -> out.add(SubwayStationsAheadService.resolve(p, STATIONS.get(lineId))));
        }
        return out;
    }

    /** 노선형 급행 노선의 패턴(subway-express S4, {@code SubwayLinePatternService.expressPatterns} 와 같은 선택). */
    static List<StationsAheadCalculator.Pattern> expressPatterns(String lineId, String direction) {
        List<StationsAheadCalculator.Pattern> out = new ArrayList<>();
        for (OtpRailRoute route : SubwayRoutePatternAssembler.selectExpressRoutes(ROUTES, lineId, direction)) {
            SubwayRoutePatternAssembler.orient(route, PATTERNS.get(route.gtfsId()), direction)
                    .forEach(p -> out.add(SubwayStationsAheadService.resolve(p, STATIONS.get(lineId))));
        }
        return out;
    }

    static String stationId(String lineId, String name) {
        return STATIONS.get(lineId).stream()
                .filter(c -> name.equals(NORMALIZER.normalize(c.stationName())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(lineId + " " + name))
                .stationId();
    }

    private static JsonNode read(String path) throws IOException {
        return OM.readTree(new ClassPathResource(path).getContentAsByteArray());
    }
}
