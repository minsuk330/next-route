package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;

import java.util.List;
import java.util.Optional;

/**
 * "그 환승 정류장이 지도 위 어디인가"를 알아낸다.
 *
 * <p><b>{@code journey_leg} 에 좌표 칸이 없어서</b> 필요한 코드다. 그래서 개정본이 들고 있는 검색
 * 결과 스냅샷을 같은 계산기로 <b>한 번 더 쪼개</b> {@code legIndex} 로 좌표를 찾는다. 이름·좌표로
 * 거꾸로 맞히는 것보다 어긋날 여지가 없다 — 쪼개기는 같은 입력에 같은 결과를 준다
 * ({@link JourneyCompiler#compileDetailed} 의 같은 논거).
 *
 * <p>실패하면 빈 결과다. 좌표를 못 찾는 것은 "재검색을 못 한다"일 뿐이라 예외로 올리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneyWaypointResolver {

    private final JourneyRevisionRepository revisionRepository;
    private final JourneyCompiler compiler;
    private final ObjectMapper objectMapper;

    /**
     * @param routeRevision <b>{@code context.routeRevision()} 을 넘긴다.</b> "지금 ACTIVE 인 개정본"을
     *                      읽으면 후보를 만드는 사이 수락으로 개정본이 오른 경우 옛 단계와 새
     *                      스냅샷 좌표가 섞인다
     * @param legIndex      검색을 시작할 단계(= 환승의 {@code toLegIndex})
     * @return 스냅샷이 없거나 해당 단계·좌표가 없으면 빈 결과
     */
    public Optional<Waypoints> resolve(Long journeyId, int routeRevision, int legIndex) {
        Optional<JourneyRevision> revision = revisionRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(journeyId, routeRevision);
        if (revision.isEmpty() || revision.get().getRouteSnapshot() == null) {
            return Optional.empty();
        }

        List<JourneyLegDraft> legs;
        try {
            PathResult path = objectMapper.treeToValue(revision.get().getRouteSnapshot(),
                    PathResult.class);
            legs = compiler.compile(path).legs();
        } catch (Exception e) {
            log.warn("[JourneyReroute] 개정본 스냅샷을 다시 쪼개지 못했다 journeyId={} revision={}: {}",
                    journeyId, routeRevision, e.getMessage());
            return Optional.empty();
        }
        if (legs.isEmpty()) {
            return Optional.empty();
        }

        Optional<JourneyLegDraft> origin = legs.stream()
                .filter(leg -> leg.legIndex() == legIndex)
                .findFirst();
        JourneyLegDraft destination = legs.get(legs.size() - 1);
        if (origin.isEmpty()
                || origin.get().startX() == null || origin.get().startY() == null
                || destination.endX() == null || destination.endY() == null) {
            log.debug("[JourneyReroute] 좌표를 못 찾았다 journeyId={} legIndex={}", journeyId, legIndex);
            return Optional.empty();
        }
        return Optional.of(new Waypoints(
                new Coord(origin.get().startX(), origin.get().startY()),
                new Coord(destination.endX(), destination.endY())));
    }

    /**
     * @param origin      그 단계의 시작 좌표. 재검색의 출발점이다
     * @param destination 마지막 단계의 끝 좌표. 목적지는 바뀌지 않는다
     */
    public record Waypoints(Coord origin, Coord destination) {
    }

    /** 경도(x) · 위도(y). 검색 API 가 받는 순서를 그대로 쓴다. */
    public record Coord(double x, double y) {
    }
}
