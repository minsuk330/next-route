package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.config.RouteSnapshotProperties;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SearchMeta;
import watoo.grd.nextroute.application.route.observability.DegradeReason;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 검색 결과를 스냅샷 저장소에 넣고, 응답에 {@code searchSessionId}·{@code pathKey}·{@code expiresAt} 을 채운다.
 *
 * <p>저장은 응답 반환 직전 한 번뿐이고 재시도하지 않는다. 저장이 실패하면 예외를 삼키고
 * 원본 결과를 그대로 돌려주되 {@code meta.degraded} 에 {@code journey_snapshot} 을 남긴다(PLAN §4.3).
 * 여정 기능 때문에 호출량이 가장 많은 검색 엔드포인트가 느려지거나 실패하면 안 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteSearchSnapshotRecorder {

    /** {@code meta.degraded} 의 component 태그. 저카디널리티 고정 문자열. */
    static final String COMPONENT = "journey_snapshot";

    private final RouteSearchSnapshotPort snapshotPort;
    private final RouteSnapshotProperties properties;
    private final Clock clock;

    /**
     * @param ownerUserId    검색을 수행한 사용자. 익명 검색이면 null.
     * @param excludeExpress 요청의 급행 끄기(subway-express S5). 결과의 {@code meta.degraded} 가 필터 해제(D10)를 말해도
     *                       <b>요청값</b>을 그대로 저장한다 — 사용자 선호이지 이번 검색의 결과가 아니다
     * @return 스냅샷 필드가 채워진 결과. 저장이 생략·실패했으면 원본(+degraded 표시).
     */
    public RouteSearchResult attach(Long ownerUserId, RouteSearchResult result, boolean excludeExpress) {
        if (!properties.isEnabled() || result == null) {
            return result;
        }
        // 저장할 경로가 없으면 스냅샷도 의미가 없다. degraded 도 아니다(실패가 아니므로).
        if (result.paths() == null || result.paths().isEmpty()) {
            return result;
        }

        Instant expiresAt = Instant.now(clock).plusSeconds(properties.getTtlSeconds());
        RouteSearchResult keyed = result.withPathKeys();

        String searchSessionId;
        try {
            searchSessionId = snapshotPort.save(ownerUserId, keyed, excludeExpress, expiresAt);
        } catch (Exception e) {
            // 포트 계약상 예외는 나오지 않지만, 계약이 깨져도 검색 응답을 실패시키지 않는다.
            log.warn("[RouteSnapshot] 스냅샷 저장 중 예외 — 검색 응답은 유지한다: {}", e.toString());
            searchSessionId = null;
        }

        if (searchSessionId == null) {
            return result.withMeta(degraded(result.meta()));
        }
        return keyed.withSnapshot(searchSessionId, expiresAt);
    }

    /** 기존 degraded 목록을 보존한 채 journey_snapshot 항목을 덧붙인다. */
    private SearchMeta degraded(SearchMeta meta) {
        List<SearchMeta.DegradedComponent> degraded = new ArrayList<>();
        if (meta != null && meta.degraded() != null) {
            degraded.addAll(meta.degraded());
        }
        degraded.add(new SearchMeta.DegradedComponent(
                COMPONENT, DegradeReason.UPSTREAM_ERROR.tag()));
        return new SearchMeta(meta == null ? null : meta.outcome(), List.copyOf(degraded));
    }
}
