package watoo.grd.nextroute.application.regionalsubway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 커밋된 역 시드({@code resources/subway/regional-stations.json}). 생성은 {@code tools/regional-subway/build_stations.py}.
 *
 * <p>역은 거의 안 바뀐다. 런타임 자동 매칭 대신 PR diff 로 사람이 확인한 값을 쓴다.
 */
@Component
public class RegionalStationSeedCatalog {

    static final String RESOURCE = "subway/regional-stations.json";

    private final List<RegionalStationSeed> stations;
    private final Map<String, List<RegionalStationSeed>> byLine;
    private final Map<String, Map<String, RegionalStationSeed>> byLineAndName;

    @Autowired
    public RegionalStationSeedCatalog(ObjectMapper objectMapper) {
        this(read(objectMapper, RESOURCE));
    }

    RegionalStationSeedCatalog(List<RegionalStationSeed> stations) {
        this.stations = List.copyOf(stations);
        Map<String, List<RegionalStationSeed>> lines = new LinkedHashMap<>();
        Map<String, Map<String, RegionalStationSeed>> names = new HashMap<>();
        for (RegionalStationSeed s : stations) {
            lines.computeIfAbsent(s.lineCode(), k -> new ArrayList<>()).add(s);
            names.computeIfAbsent(s.lineCode(), k -> new HashMap<>())
                    .put(RegionalTimetableText.normalizeName(s.stationName()), s);
        }
        lines.values().forEach(l -> l.sort(Comparator.comparingInt(RegionalStationSeed::seq)));
        lines.replaceAll((k, v) -> List.copyOf(v));
        this.byLine = Collections.unmodifiableMap(lines);
        this.byLineAndName = names;
    }

    public List<RegionalStationSeed> all() {
        return stations;
    }

    public List<RegionalStationSeed> line(String lineCode) {
        return byLine.getOrDefault(lineCode, List.of());
    }

    /** 노선 안에서 역명(정규화)으로 찾는다. 종착역 이름을 seq 로 바꿀 때 쓴다. */
    public RegionalStationSeed findByName(String lineCode, String name) {
        Map<String, RegionalStationSeed> m = byLineAndName.get(lineCode);
        return m == null ? null : m.get(RegionalTimetableText.normalizeName(name));
    }

    public RegionalStationSeed findByProviderCode(String lineCode, String code) {
        return line(lineCode).stream()
                .filter(s -> code != null && code.equals(s.providerStationCode()))
                .findFirst().orElse(null);
    }

    static List<RegionalStationSeed> read(ObjectMapper objectMapper, String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<RegionalStationSeed> out = new ArrayList<>();
            for (JsonNode n : root) {
                JsonNode gtfs = n.path("gtfsStopIds");
                String gtfsStopId = gtfs.isArray() && !gtfs.isEmpty() ? gtfs.get(0).asText() : null;
                out.add(new RegionalStationSeed(
                        n.path("stationKey").asText(),
                        n.path("lineCode").asText(),
                        n.path("lineName").asText(),
                        n.path("stationName").asText(),
                        n.path("seq").asInt(),
                        n.path("tagoStationId").asText(null),
                        n.path("providerStationCode").asText(null),
                        gtfsStopId,
                        n.path("lat").asDouble(),
                        n.path("lon").asDouble()));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("regional subway seed read failed: " + resource, e);
        }
    }
}
