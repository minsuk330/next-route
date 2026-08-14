package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;

import java.util.Comparator;
import java.util.List;

/**
 * 그 개정본의 <b>계획 역 체인</b>. 승차역부터 하차역까지, 검색 시점에 얼려 둔 순서 그대로다.
 *
 * <h2>왜 도착 로그가 아니라 계획인가</h2>
 *
 * <p>후행 확정은 "승차 다음 역"에서 후보들을 가른다. 그 역을 도착 로그에서 뽑으면
 * <b>"출발 후 첫 도착"이 다음 역이라는 가정</b>이 들어가는데, 관측 해상도가 60~90초라 한 역을
 * 통째로 놓치는 일이 실제로 있다. 후보들이 다 같이 놓치면 비교역이 서로 일치해 <b>합의된 것처럼</b>
 * 보이고, 앱의 정차 관측과 한 구간(약 2분) 어긋난 채 자신 있게 확정된다.
 *
 * <p>계획에서 정하면 결측과 무관하게 정해진다. 그 역의 도착 로그가 없으면 "가르지 못했다"가 되어
 * 확정이 한 번 쉬어 갈 뿐이다.
 *
 * <h2>세션이 아니라 개정본에서 읽는다</h2>
 *
 * <p>{@code JourneySession.routeSnapshot} 은 여정을 시작할 때의 원본이고 영원히 바뀌지 않는다.
 * 재탐색을 수락하면 개정본만 오르므로, 세션 쪽을 읽으면 <b>옛 역 체인</b>이 조용히 나온다.
 *
 * <h2>이름으로 대조한다</h2>
 *
 * <p>여기서 나오는 것은 ODsay 역 <b>이름</b>이다. 검색 결과의 역 식별자와 실시간 정보의 역
 * 식별자는 다른 체계라 맞대어 보면 아무것도 일치하지 않는다 — 그래서 PR-1 이 지하철
 * {@code stopId} 를 일부러 비워 뒀다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneySubwayStationChainReader {

    private final JourneyRevisionRepository revisionRepository;
    private final JourneyCompiler compiler;
    private final ObjectMapper objectMapper;

    /**
     * 그 지하철 단계의 계획 역 이름. 첫 원소가 승차역이다.
     *
     * <p>못 읽으면 빈 목록이다 — 예외를 던지지 않는다. 체인이 없으면 확정이 한 번 쉬어 갈 뿐이고,
     * 여기서 막으면 잠정 판정까지 함께 죽는다.
     */
    public List<String> stationNames(Long journeyId, int routeRevision, int legIndex) {
        JsonNode snapshot = revisionRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(journeyId, routeRevision)
                .map(revision -> revision.getRouteSnapshot())
                .orElse(null);
        if (snapshot == null || snapshot.isNull()) {
            return List.of();
        }
        try {
            PathResult path = objectMapper.treeToValue(snapshot, PathResult.class);
            JourneyCompiler.Compiled compiled = compiler.compileDetailed(path);
            Integer subPathIndex = compiled.subPathIndexOf(legIndex);
            if (subPathIndex == null || path.subPaths() == null
                    || subPathIndex < 0 || subPathIndex >= path.subPaths().size()) {
                return List.of();
            }
            return names(path.subPaths().get(subPathIndex));
        } catch (Exception e) {
            log.warn("[SubwayBinding] 계획 역 체인 재쪼개기 실패 — journeyId={} revision={}: {}",
                    journeyId, routeRevision, e.getMessage());
            return List.of();
        }
    }

    /**
     * 승차 다음 역부터 세어 {@code sequence} 번째 역.
     *
     * <p>앱의 정차 순번이 1 부터라 {@code stations[sequence]} 가 곧 그 역이다 — 0 번이 승차역이다.
     * <b>앱이 정차를 하나 놓치면 한 칸씩 밀린다.</b> 서버는 그것을 검증할 수 없고, 앱이 보장하는
     * 조건이다.
     *
     * @return 체인이 짧거나 없으면 {@code null}
     */
    public String stationAfterBoarding(List<String> chain, Integer sequence) {
        if (sequence == null || sequence < 1 || chain.size() <= sequence) {
            return null;
        }
        return chain.get(sequence);
    }

    private List<String> names(SubPathResult sub) {
        if (sub == null || sub.stations() == null || sub.stations().isEmpty()) {
            return List.of();
        }
        return sub.stations().stream()
                .sorted(Comparator.comparingInt(StationResult::index))
                .map(StationResult::stationName)
                .toList();
    }
}
