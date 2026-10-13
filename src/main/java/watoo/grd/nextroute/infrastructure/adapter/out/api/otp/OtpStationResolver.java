package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 장소가 지하철역이면 가장 가까운 그 역 승강장의 <b>가상 역</b>(parent station) id 를 준다. 그래프 빌드
 * ({@code add_station_entrances.py})가 승강장마다 "그 승강장 + 역의 출구 지점들"을 자식으로 둔 역을 만든다.
 * OTP 는 역 id 로 출발하면 자식 전부에서 비용 0 으로 시작하므로, 버스는 가장 가까운 출구에서 걷기 시작하고
 * 지하철은 그 노선 승강장에서 탄다(다른 노선은 지금처럼 환승 규칙으로 걸어 간다).
 *
 * <p><b>왜</b>(2026-10-12): 장소 검색의 역 좌표는 한 노선 승강장 중심이라 그 점에서 걸으면 다른 출구 옆
 * 정류장까지 672m 가 된다(잠실역 8호선 → 잠실역.롯데월드). 네이버는 역을 출구 여러 개인 장소로 본다.
 *
 * <ul>
 *   <li><b>노선이 있는 이름은 승강장별 역</b>: 노선을 다 묶은 역 하나로 출발하면 OTP 는 모든 노선 승강장에
 *       비용 0 으로 서 있는 것으로 본다 — "잠실역 8호선"→강남역 2호선이 도보 0분 11분(네이버 21분).</li>
 *   <li><b>노선이 없는 이름은 모든 노선 역</b>({@code VSA_}, 승강장 2개 이상인 역만): "서울역"은 그 역 전체다.
 *       가장 가까운 승강장 역에 붙이면 다른 노선으로 도착해 출구까지 더 걷는다(2026-10-13 강남→서울역: 1호선 역에
 *       붙어 4호선 도착 후 298m, 지하철이 5위로 밀림). 자식은 모든 승강장(일반철도 포함)과 출구의 복제 정류장이다.
 *       없으면(승강장 1개 역) 승강장 역 — 뜻이 같다.</li>
 *   <li><b>이름이 맞을 때만</b>: KTDB 승강장 좌표는 출구에서 중앙값 34m 라, 좌표만 보면 출구·버스정류장에 선
 *       사용자 GPS 도 승강장으로 붙어 버린다(출발 비용 0 → 못 타는 열차, 버스엔 통로 시간이 붙음). 장소 이름이
 *       "잠실역 8호선"처럼 역 이름이고 그 역 승강장이 가까울 때만 붙인다. 이름이 없으면(재탐색·현재 위치) 안 붙인다.</li>
 * </ul>
 *
 * <p>역 목록은 OTP {@code stations} 에서 받는다. 검색을 기다리게 하지 않는다 — 아직 없으면 백그라운드로 받기
 * 시작하고 이번 검색은 좌표 그대로다. 출구를 넣지 않은 그래프엔 부모 역이 없어 목록이 비고, 그러면 언제나
 * 좌표 그대로다(앱을 그래프보다 먼저 배포해도 동작이 같다). 그래프가 바뀔 수 있어 주기적으로 다시 받는다.
 */
@Slf4j
@Component
public class OtpStationResolver {

    private static final String GRAPHQL_PATH = "/otp/routers/default/index/graphql";
    private static final String QUERY = "{ stations { gtfsId name stops { gtfsId name lat lon vehicleMode } } }";
    private static final Set<String> RAIL_MODES = Set.of("SUBWAY", "RAIL", "TRAM", "MONORAIL", "FUNICULAR");
    /** 모든 노선 역과 그 승강장 복제 정류장의 id 머리({@code add_station_entrances.py}). */
    private static final String ALL_LINES_STATION = "VSA_";
    private static final String ALL_LINES_PLATFORM = "VSA_P_";
    private static final Duration REFRESH_AFTER_SUCCESS = Duration.ofHours(6);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(5);
    /** 받았는데 비었으면(출구 없는 그래프) 이만큼 지나 다시 본다. */
    private static final Duration RECHECK_WHEN_EMPTY = Duration.ofMinutes(30);
    private static final long LOAD_TIMEOUT_MS = 10_000;

    /**
     * 역 이름 장소: "잠실역", "잠실역 8호선", "강남역 신분당선", "총신대입구(이수)역", "홍대입구", "서울역 (고속철도)",
     * "서울역 공항철도", "서울역 GTX-A". 첫 묶음이 역 이름, 뒤에는 괄호 설명과 노선 이름(…호선/…선/…라인/…철도/GTX-X)만
     * 올 수 있다 — "강남역 스타벅스"·"홍대입구역 9번출구" 같은 역 근처 장소는 아니다. 둘째 묶음 = 노선.
     */
    private static final Pattern STATION_PLACE = Pattern.compile(
            "^([^\\s()]+?)(?:\\([^)]*\\))?역?(?:\\s*\\([^)]*\\))?(?:\\s+(\\S*(?:호선|선|라인|철도)|GTX-?[A-Z]))?$");
    private static final Pattern PARENTHESES = Pattern.compile("\\(.*?\\)");

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final OtpRoutingProperties properties;
    private final Clock clock;

    private volatile List<Platform> platforms = List.of();
    private volatile Instant nextLoadAt = Instant.EPOCH;
    private final AtomicBoolean loading = new AtomicBoolean(false);

    public OtpStationResolver(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                              OtpRoutingProperties properties, Clock clock) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** 역에 붙인 결과. {@code stationName} 은 출발·도착이 같은 역(다른 노선 승강장 포함)인지 가리는 데 쓴다. */
    public record Snap(String stationId, String stationName) {
    }

    /**
     * 장소 이름이 역 이름이고 그 역 승강장이 {@code station-snap-meters} 안이면 가상 역. 노선이 없는 이름은 그 역의
     * 모든 노선 역(없으면 가장 가까운 승강장의 역), 노선이 있는 이름은 가장 가까운 승강장의 역.
     * 꺼져 있거나, 이름이 역이 아니거나, 목록이 아직 없으면 empty.
     */
    public Optional<Snap> snap(double lat, double lon, String placeName) {
        if (!properties.isStationSnapEnabled()) {
            return Optional.empty();
        }
        PlaceName name = parse(placeName);
        if (name == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(nextLoadAt)) {
            loadInBackground();
        }
        Platform best = name.hasLine() ? null : nearest(name.key(), lat, lon, true);
        if (best == null) {
            best = nearest(name.key(), lat, lon, false);
        }
        return best == null ? Optional.empty() : Optional.of(new Snap(best.stationId(), best.stationName()));
    }

    /** 이름이 {@code key} 인 역의 승강장 중 반경 안 최근접. {@code allLines} = 모든 노선 역의 승강장 복제만 본다. */
    private Platform nearest(String key, double lat, double lon, boolean allLines) {
        Platform best = null;
        double bestMeters = properties.getStationSnapMeters();
        for (Platform p : platforms) {
            if (p.allLines() != allLines || !p.names().contains(key)) {
                continue;
            }
            double d = distanceMeters(lat, lon, p.lat(), p.lon());
            if (d <= bestMeters) {
                best = p;
                bestMeters = d;
            }
        }
        return best;
    }

    /** 역 이름 장소의 역 이름과 노선 지정 여부. "잠실역 8호선" → (잠실, 노선 있음), "서울역 (고속철도)" → (서울, 없음). */
    record PlaceName(String key, boolean hasLine) {
    }

    /** 역 이름 모양이 아니면 null. */
    static PlaceName parse(String placeName) {
        if (placeName == null || placeName.isBlank()) {
            return null;
        }
        Matcher m = STATION_PLACE.matcher(placeName.trim());
        return m.matches() ? new PlaceName(m.group(1), m.group(2) != null) : null;
    }

    /** "잠실역 8호선" → "잠실". 역 이름 모양이 아니면 null. */
    static String stationKey(String placeName) {
        PlaceName name = parse(placeName);
        return name == null ? null : name.key();
    }

    /** "잠실(8호선)" → "잠실", "서울역" → "서울". */
    static String baseName(String stopName) {
        String n = PARENTHESES.matcher(stopName == null ? "" : stopName).replaceAll("").trim();
        return n.length() > 1 && n.endsWith("역") ? n.substring(0, n.length() - 1) : n;
    }

    private void loadInBackground() {
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                load();
            } finally {
                loading.set(false);
            }
        });
    }

    /** 패키지 공개: 테스트가 동기적으로 부른다. */
    void load() {
        try {
            List<Platform> loaded = fetch();
            platforms = loaded;
            nextLoadAt = clock.instant().plus(loaded.isEmpty() ? RECHECK_WHEN_EMPTY : REFRESH_AFTER_SUCCESS);
            log.info("[OTP] 역 승강장 {}개 적재(모든 노선 역 승강장 {}개, 역 판별 {})",
                    loaded.stream().filter(p -> !p.allLines()).count(),
                    loaded.stream().filter(Platform::allLines).count(),
                    loaded.isEmpty() ? "안 함 — 부모 역 없는 그래프" : "켜짐");
        } catch (RuntimeException e) {
            nextLoadAt = clock.instant().plus(RETRY_AFTER_FAILURE);
            log.warn("[OTP] 역 목록 적재 실패 — 좌표로 검색: {}", e.toString());
        }
    }

    private List<Platform> fetch() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(LOAD_TIMEOUT_MS));
        String body = restClientBuilder.clone().requestFactory(factory).build().post()
                .uri(properties.getBaseUrl() + GRAPHQL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("query", QUERY))
                .retrieve()
                .body(String.class);
        JsonNode stations;
        try {
            stations = objectMapper.readTree(body == null ? "" : body).path("data").path("stations");
        } catch (Exception e) {
            throw new IllegalStateException("stations 응답 파싱 실패", e);
        }
        if (!stations.isArray()) {
            throw new IllegalStateException("stations 응답에 data.stations 없음");
        }
        List<Platform> out = new ArrayList<>();
        // 부모 역의 자식 중 승강장만 본다(출구 지점은 운행이 없어 vehicleMode 가 비어 있다).
        // 모든 노선 역은 승강장 복제(운행 없음)가 승강장이다. 출구를 넣은 그래프에만 부모 역이 있다.
        for (JsonNode station : stations) {
            String stationId = station.path("gtfsId").asText(null);
            if (stationId == null) {
                continue;
            }
            boolean allLines = localId(stationId).startsWith(ALL_LINES_STATION);
            String stationName = baseName(station.path("name").asText(""));
            for (JsonNode stop : station.path("stops")) {
                boolean platform = allLines
                        ? localId(stop.path("gtfsId").asText("")).startsWith(ALL_LINES_PLATFORM)
                        : RAIL_MODES.contains(stop.path("vehicleMode").asText(""));
                if (platform && stop.hasNonNull("lat") && stop.hasNonNull("lon")) {
                    Set<String> names = new HashSet<>();
                    names.add(stationName);
                    names.add(baseName(stop.path("name").asText("")));
                    out.add(new Platform(stationId, stationName, Set.copyOf(names),
                            stop.path("lat").asDouble(), stop.path("lon").asDouble(), allLines));
                }
            }
        }
        return List.copyOf(out);
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return 6_371_000 * Math.hypot(x, y);
    }

    /** "1:VSA_RS_A" → "VSA_RS_A"(피드 id 머리 제거). */
    private static String localId(String gtfsId) {
        int colon = gtfsId.indexOf(':');
        return colon < 0 ? gtfsId : gtfsId.substring(colon + 1);
    }

    private record Platform(String stationId, String stationName, Set<String> names, double lat, double lon,
                            boolean allLines) {
    }
}
