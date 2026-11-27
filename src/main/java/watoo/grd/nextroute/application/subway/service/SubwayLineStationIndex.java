package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 역 마스터를 메모리에 든다(#283). 696행이라 통째로 한 번 읽는다.
 *
 * <p>역 마스터는 정적 데이터이고 일배치도 꺼져 있다. 적재 이후 새로 생긴 역은 재시작 전까지 안
 * 보인다 — 그 역은 역 ID 없이 이름으로만 나갈 뿐이라 안전한 쪽으로 실패한다
 * ({@code SubwayRingStationResolver} 와 같은 판단).
 */
@Component
@RequiredArgsConstructor
public class SubwayLineStationIndex {

    /** 같은 이름의 다른 노선 역을 환승으로 보는 거리. 양평(5호선↔경의중앙 55km)·신촌(경의중앙↔2호선 약 670m)은 환승이 아니다. */
    static final double TRANSFER_RADIUS_M = 300.0;

    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    private final SubwayStationRepository repository;

    private volatile List<SubwayStation> all;
    /** 정규화 이름 → 역(#510). 검색 응답이 역마다 환승을 물으므로 696행을 매번 훑지 않는다. */
    private volatile Lookup lookup;

    /**
     * 역 마스터를 다음 호출에 다시 읽는다. 첫 비어 있지 않은 읽기를 재시작 전까지 들고 있으므로, 역 행을
     * 나중에 넣는 쪽(시험 무대, 적재 배치)이 부른다.
     */
    public void reload() {
        all = null;
        lookup = null;
    }

    public List<SubwayPatternStationMatcher.Candidate> candidates(String lineId) {
        return stations().stream()
                .filter(s -> lineId.equals(s.getLineId()) && s.getLatitude() != null && s.getLongitude() != null)
                .map(s -> new SubwayPatternStationMatcher.Candidate(
                        s.getStationId(), s.getStationName(), s.getLatitude(), s.getLongitude()))
                .toList();
    }

    /** 노선 표시 이름({@code 2호선}). 역 마스터에 없는 노선이면 null. */
    public String lineName(String lineId) {
        return stations().stream()
                .filter(s -> lineId.equals(s.getLineId()) && s.getLineName() != null)
                .map(SubwayStation::getLineName)
                .findFirst()
                .orElse(null);
    }

    /**
     * 역 마스터의 역 이름 — 서울 실시간 도착 API 가 받는 이름이다({@code 논현}, {@code 총신대입구(이수)}).
     * 경로 단계의 역 이름은 GTFS 표기라 노선이 붙어 있어({@code 논현(7호선)}) API 가 못 찾는다.
     * 괄호를 떼어 맞추면 안 된다 — 마스터 이름 자체에 괄호가 든 역이 29곳이다. 모르는 역이면 empty.
     */
    public Optional<String> stationName(String stationId) {
        if (stationId == null) {
            return Optional.empty();
        }
        return stations().stream()
                .filter(s -> stationId.equals(s.getStationId()) && s.getStationName() != null)
                .map(SubwayStation::getStationName)
                .findFirst();
    }

    /** 이 역에서 갈아탈 수 있는 다른 노선. 이름이 같고 {@value #TRANSFER_RADIUS_M}m 안이어야 한다. */
    public List<Transfer> transfers(String stationName, double lat, double lon, String excludeLineId) {
        String name = NORMALIZER.normalize(stationName);
        if (name == null) {
            return List.of();
        }
        Map<String, Transfer> byLine = new LinkedHashMap<>();
        for (SubwayStation s : lookup().byName().getOrDefault(name, List.of())) {
            if (excludeLineId.equals(s.getLineId()) || s.getLatitude() == null || s.getLongitude() == null) {
                continue;
            }
            if (SubwayPatternStationMatcher.distanceMeters(lat, lon, s.getLatitude(), s.getLongitude())
                    <= TRANSFER_RADIUS_M) {
                byLine.putIfAbsent(s.getLineId(), new Transfer(s.getLineId(), s.getLineName()));
            }
        }
        return new ArrayList<>(byLine.values());
    }

    public record Transfer(String lineId, String lineName) {
    }

    /**
     * 경로 단계의 역 하나에서 갈아탈 다른 노선(#510 — 길안내 geometry·검색 결과). 단계의 역 이름·좌표는 GTFS 표기라
     * 같은 노선의 마스터 역을 이름으로 찾아 그 좌표로 잰다 — 남은 역 응답({@code stations[].transfers})과 같은 기준이다.
     *
     * @return 모르면 empty — 좌표가 없거나, {@code lineId} 가 역 마스터에 없는 노선이면 제 노선이 환승으로 나오므로
     *         답하지 않는다. 알면 목록(없으면 빈 목록)
     */
    public Optional<List<Transfer>> transfersAt(String stationName, Double lat, Double lon, String lineId) {
        if (lat == null || lon == null || lineId == null || !lookup().lineIds().contains(lineId)) {
            return Optional.empty();
        }
        String name = NORMALIZER.normalize(stationName);
        if (name == null) {
            return Optional.of(List.of());
        }
        Optional<SubwayStation> own = lookup().byName().getOrDefault(name, List.of()).stream()
                .filter(s -> lineId.equals(s.getLineId()) && s.getLatitude() != null && s.getLongitude() != null)
                .min(Comparator.comparingDouble(s ->
                        SubwayPatternStationMatcher.distanceMeters(lat, lon, s.getLatitude(), s.getLongitude())));
        double atLat = own.map(SubwayStation::getLatitude).orElse(lat);
        double atLon = own.map(SubwayStation::getLongitude).orElse(lon);
        return Optional.of(transfers(stationName, atLat, atLon, lineId));
    }

    private record Lookup(Map<String, List<SubwayStation>> byName, Set<String> lineIds) {
    }

    private Lookup lookup() {
        Lookup cached = lookup;
        if (cached == null) {
            List<SubwayStation> stations = stations();
            Map<String, List<SubwayStation>> byName = new HashMap<>();
            Set<String> lineIds = new HashSet<>();
            for (SubwayStation s : stations) {
                String key = NORMALIZER.normalize(s.getStationName());
                if (key != null) {
                    byName.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
                }
                if (s.getLineId() != null) {
                    lineIds.add(s.getLineId());
                }
            }
            cached = new Lookup(byName, lineIds);
            if (!stations.isEmpty()) {
                // stations() 와 같은 규칙: 빈 마스터로 만든 색인은 남기지 않는다
                lookup = cached;
            }
        }
        return cached;
    }

    private List<SubwayStation> stations() {
        List<SubwayStation> cached = all;
        if (cached == null) {
            cached = List.copyOf(repository.findAll());
            if (!cached.isEmpty()) {
                // 빈 표는 남기지 않는다 — 역 마스터 적재 전 첫 호출이 재시작 전까지 모든 역 연결(급행 카탈로그 포함)을 끈다
                all = cached;
            }
        }
        return cached;
    }
}
