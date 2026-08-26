package watoo.grd.nextroute.application.subway.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.subway.entity.SubwaySegment;
import watoo.grd.nextroute.domain.subway.repository.SubwaySegmentRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 두 역이 한 노선에서 <b>맞닿아 있는지</b> 알려준다.
 *
 * <p>왜 필요한가. 열차가 A역에서 B역으로 옮겨 간 것을 "A역을 출발했다"의 근거로 쓰는데,
 * "역이 달라졌다"만 보면 안 된다. 회차·차량기지 입출고·지선 분기·원천의 순간적인 역행에서도
 * 역은 달라진다. 그때 출발을 확정하면 있지도 않은 출발이 생긴다.
 *
 * <p><b>방향은 가리지 않는다.</b> 여기서 할 수 있는 판정은 "맞닿아 있는가"까지다.
 * "우리 여정의 진행 방향으로 다음 역인가"는 여정 맥락이 있어야 답할 수 있어 탑승 판정 쪽 몫이다.
 * 이 단계에서는 <b>순간이동을 막는 것</b>이 목적이다.
 *
 * <p>{@link SubwaySegmentLookup} 과 같은 원천({@code subway_segment})을 쓰지만 답하는 질문이
 * 다르다. 저쪽은 "구간 소요시간이 얼마인가", 이쪽은 "붙어 있는가"다. 한 클래스에 합치면
 * 소요시간이 없는 구간이 인접이 아닌 것으로 취급되는 사고가 난다 — 실제로
 * {@code SubwaySegmentLookup} 은 {@code travelTime == null} 인 구간을 통째로 버린다.
 *
 * <p><b>주의</b>: {@code subway_segment} 의 {@code departStationId}/{@code arriveStationId} 는
 * 이름이 ID 처럼 보이지만 실제로 담긴 값은 <b>역 이름</b>이다
 * ({@code SubwayStaticDataService} 가 {@code departStationName} 을 넣는다). 그래서 이 클래스도
 * 이름으로 묻고 답한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayStationAdjacency {

    private final SubwaySegmentRepository subwaySegmentRepository;

    /** volatile: 수집 스레드가 읽고, 정적 데이터 갱신 스케줄러가 통째로 갈아 끼운다. */
    private volatile Map<String, Set<String>> neighbors = Map.of();

    @PostConstruct
    public void load() {
        reload();
    }

    public void reload() {
        Map<String, Set<String>> next = new HashMap<>();
        for (SubwaySegment segment : subwaySegmentRepository.findAll()) {
            String line = segment.getLineId();
            String a = segment.getDepartStationId();
            String b = segment.getArriveStationId();
            if (line == null || a == null || b == null) {
                continue;
            }
            // 소요시간이 없어도 인접은 인접이다. 여기서 걸러 내면 그 구간의 이동이
            // 전부 "순간이동"으로 보여 출발 확정이 통째로 막힌다.
            next.computeIfAbsent(key(line, a), k -> new HashSet<>()).add(b);
            next.computeIfAbsent(key(line, b), k -> new HashSet<>()).add(a);
        }
        this.neighbors = Map.copyOf(next);
        log.info("[StationAdjacency] {} 역의 인접 관계 적재", next.size());
    }

    /**
     * 같은 노선에서 두 역이 맞닿아 있는지.
     *
     * <p>정적 데이터가 아직 없으면 <b>false</b> 다. 모르는 것을 인접이라고 답하면 순간이동을
     * 출발로 읽는다 — 판정을 늦추는 쪽이 안전하다.
     */
    public boolean isAdjacent(String lineId, String stationName, String otherStationName) {
        if (lineId == null || stationName == null || otherStationName == null) {
            return false;
        }
        Set<String> set = neighbors.get(key(lineId, stationName));
        return set != null && set.contains(otherStationName);
    }

    /** 적재 여부. 비어 있으면 인접 판정이 전부 false 라 출발 확정이 나오지 않는다. */
    public boolean isLoaded() {
        return !neighbors.isEmpty();
    }

    private String key(String lineId, String stationName) {
        return lineId + "|" + stationName;
    }
}
