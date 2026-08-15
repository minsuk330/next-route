package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.subway.service.DestinationNormalizer;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 계획 역 <b>이름</b>을 서울 실시간 {@code statnId} 로 바꾼다.
 *
 * <h2>왜 이름을 이름으로 두지 않나</h2>
 *
 * <p>검색 결과(ODsay)의 역 식별자와 실시간 정보의 역 식별자는 다른 체계다 — 그래서 PR-1 이
 * 지하철 {@code stopId} 를 일부러 비웠고, 둘을 잇는 유일한 축이 이름이다. 하지만 <b>이름을
 * 판정에 그대로 쓰면 안 된다</b>. 표기가 흔들려서({@code 성수}/{@code 성수종착}) 같은 역인데
 * 링이 0 으로 리셋된다. 여기서 한 번만 이름으로 잇고, 그 뒤로는 {@code statnId} 로만 판정한다.
 *
 * <p>{@code statnId} 는 도착정보 조회의 인자이기도 해서 <b>하나가 두 목적을 겸한다</b>.
 *
 * <h2>노선별 표를 메모리에 든다</h2>
 *
 * <p>역 마스터는 하루 한 번 적재되는 정적 데이터인데, 링은 여정마다 5초에 한 번 돈다. 매 tick
 * 노선 전체를 다시 읽으면 그 질의가 곧 tick 비용이 된다.
 *
 * <p>적재 이후 새로 생긴 역은 재시작 전까지 해석되지 않는다. 그 역은 도착 조회를 못 해 위치
 * 폴백으로 떨어질 뿐이라 <b>안전한 쪽으로 실패한다</b>.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayRingStationResolver {

    private final SubwayStationRepository stationRepository;
    private final DestinationNormalizer normalizer;

    /** {@code lineId → (정규화한 역 이름 → statnId)}. */
    private final Map<String, Map<String, String>> byLine = new ConcurrentHashMap<>();

    /**
     * 계획 역 이름 목록을 체인으로 바꾼다. 첫 원소가 승차역이다.
     *
     * <p>해석하지 못한 역은 {@code canonicalKey} 가 {@code null} 인 채로 남는다 — 체인에서
     * 빼면 순번이 밀려 <b>남은 역 수가 조용히 틀린다</b>.
     */
    public SubwayRingChain chainOf(String lineId, List<String> stationNames) {
        if (stationNames == null || stationNames.isEmpty()) {
            return new SubwayRingChain(List.of());
        }
        Map<String, String> idsByName = idsOf(lineId);
        List<SubwayRingChain.Station> stations = new ArrayList<>(stationNames.size());
        for (int sequence = 0; sequence < stationNames.size(); sequence++) {
            String name = stationNames.get(sequence);
            String normalized = normalizer.normalize(name);
            stations.add(new SubwayRingChain.Station(sequence,
                    normalized == null ? null : idsByName.get(normalized), name));
        }
        return new SubwayRingChain(stations);
    }

    /**
     * 같은 이름이 여럿이면 <b>먼저 읽은 것</b>을 남긴다.
     *
     * <p>지선을 든 노선에서 실제로 일어난다. 어느 쪽을 고르든 조회역이 한 칸 어긋날 수 있는데,
     * 그때 링은 그 구간에서 도착정보를 못 찾아 위치 폴백으로 떨어진다 — 멈추기는 해도 틀린
     * 진행률을 만들지는 않는다.
     */
    private Map<String, String> idsOf(String lineId) {
        if (lineId == null) {
            return Map.of();
        }
        Map<String, String> cached = byLine.get(lineId);
        if (cached != null) {
            return cached;
        }
        Map<String, String> loaded = new HashMap<>();
        for (SubwayStation station : stationRepository.findByLineId(lineId)) {
            String normalized = normalizer.normalize(station.getStationName());
            if (normalized != null && station.getStationId() != null) {
                loaded.putIfAbsent(normalized, station.getStationId());
            }
        }
        if (loaded.isEmpty()) {
            // 적재 전일 수 있다. 빈 표를 캐시하면 그 노선은 재시작 전까지 영영 해석되지 않는다.
            log.debug("[SubwayRing] 역 마스터가 비어 있다 — lineId={}", lineId);
            return Map.of();
        }
        Map<String, String> frozen = Map.copyOf(loaded);
        byLine.put(lineId, frozen);
        return frozen;
    }
}
