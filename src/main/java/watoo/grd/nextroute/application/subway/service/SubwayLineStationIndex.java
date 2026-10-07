package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public List<SubwayPatternStationMatcher.Candidate> candidates(String lineId) {
        return stations().stream()
                .filter(s -> lineId.equals(s.getLineId()) && s.getLatitude() != null && s.getLongitude() != null)
                .map(s -> new SubwayPatternStationMatcher.Candidate(
                        s.getStationId(), s.getStationName(), s.getLatitude(), s.getLongitude()))
                .toList();
    }

    /** 이 역에서 갈아탈 수 있는 다른 노선. 이름이 같고 {@value #TRANSFER_RADIUS_M}m 안이어야 한다. */
    public List<Transfer> transfers(String stationName, double lat, double lon, String excludeLineId) {
        String name = NORMALIZER.normalize(stationName);
        if (name == null) {
            return List.of();
        }
        Map<String, Transfer> byLine = new LinkedHashMap<>();
        for (SubwayStation s : stations()) {
            if (excludeLineId.equals(s.getLineId()) || s.getLatitude() == null || s.getLongitude() == null
                    || !name.equals(NORMALIZER.normalize(s.getStationName()))) {
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

    private List<SubwayStation> stations() {
        List<SubwayStation> cached = all;
        if (cached == null) {
            cached = List.copyOf(repository.findAll());
            all = cached;
        }
        return cached;
    }
}
