package watoo.grd.nextroute.application.subway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 지하철 출입구와 출구 번호(#266). 원천은 OSM {@code railway=subway_entrance} 의 {@code ref}
 * ({@code subway/entrances.csv}, {@code tools/subway-entrances} 로 생성, © OpenStreetMap contributors, ODbL).
 *
 * <p>OTP 그래프에도 출입구 노드는 있지만 도보 단계에 출구가 안 나온다 — 정류장이 역 중심에서 가장 가까운 도로에
 * 붙어 출입구를 지나지 않는다(2026-10-10 실측). 그래서 직접 고른다: 역 반경 {@value #STATION_RADIUS_M}m 안
 * 출입구 중 걸어갈 목표에 가장 가까운 것.
 *
 * <p>큰 환승역은 반경 안에 다른 역 출구가 섞인다. 출입구 이름표에 역 이름이 들어 있는 것이 하나라도 있으면 그것만
 * 본다. 4천여 행이라 메모리 목록 그대로 훑는다.
 */
@Slf4j
@Component
public class SubwayEntranceIndex {

    static final double STATION_RADIUS_M = 350.0;
    private static final String RESOURCE = "subway/entrances.csv";

    private volatile List<Entrance> entrances;

    public record Entrance(double lat, double lon, String ref, String label) {
    }

    public SubwayEntranceIndex() {
    }

    /** 고정 목록(테스트·도구용). */
    public static SubwayEntranceIndex of(List<Entrance> entrances) {
        SubwayEntranceIndex index = new SubwayEntranceIndex();
        index.entrances = List.copyOf(entrances);
        return index;
    }

    /**
     * @param stationName 역 이름(정규화 전 그대로 받아도 된다). 이름표 거르기에 쓴다
     * @return 출구 번호. 역 반경 안에 출입구가 없거나 좌표를 모르면 empty
     */
    public Optional<String> exitToward(String stationName, Double stationLat, Double stationLon,
                                       Double targetLat, Double targetLon) {
        if (stationLat == null || stationLon == null || targetLat == null || targetLon == null) {
            return Optional.empty();
        }
        List<Entrance> near = new ArrayList<>();
        for (Entrance e : all()) {
            if (Math.abs(e.lat() - stationLat) < 0.004 && Math.abs(e.lon() - stationLon) < 0.005
                    && distanceMeters(stationLat, stationLon, e.lat(), e.lon()) <= STATION_RADIUS_M) {
                near.add(e);
            }
        }
        String name = stationName == null ? null : SubwayPatternStationMatcher.displayName(stationName);
        if (name != null && !name.isBlank()) {
            List<Entrance> named = near.stream().filter(e -> e.label() != null && e.label().contains(name)).toList();
            if (!named.isEmpty()) {
                near = named;
            }
        }
        return near.stream()
                .min(Comparator.comparingDouble(e -> distanceMeters(targetLat, targetLon, e.lat(), e.lon())))
                .map(Entrance::ref);
    }

    private List<Entrance> all() {
        List<Entrance> loaded = entrances;
        if (loaded == null) {
            synchronized (this) {
                if (entrances == null) {
                    entrances = load();
                }
                loaded = entrances;
            }
        }
        return loaded;
    }

    private static List<Entrance> load() {
        List<Entrance> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                String[] f = line.split(",", 5);
                if (f.length < 4 || f[3].isBlank()) {
                    continue;
                }
                out.add(new Entrance(Double.parseDouble(f[1]), Double.parseDouble(f[2]), f[3].trim(),
                        f.length > 4 ? f[4].trim() : null));
            }
        } catch (Exception e) {
            log.warn("[Subway] 출입구 목록 적재 실패 — 출구 번호 없이 진행: {}", e.getMessage());
            return List.of();
        }
        log.info("[Subway] 출입구 목록 적재 entrances={}", out.size());
        return List.copyOf(out);
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        return SubwayPatternStationMatcher.distanceMeters(lat1, lon1, lat2, lon2);
    }
}
