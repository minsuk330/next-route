package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.journey.dto.SubwayRingState;

import java.util.Optional;

/**
 * 진행 링 상태의 보관소.
 *
 * <h2>사라져도 되는 값이다</h2>
 *
 * <p>Redis 는 비영속이고 {@code allkeys-lru} 다. 여기 있는 것이 날아가면 링이 한 tick 동안
 * {@code STALE} 로 보일 뿐이다 — DB 의 {@code CONFIRMED} 고정과 실시간 도착정보로 다음 tick 에
 * 다시 만들어진다. 그래서 복구를 시도하지 않는다.
 *
 * <p>대신 <b>TTL 은 반드시 있어야 한다.</b> 수명주기 네 접점이 지우지만 그중 하나라도 새면
 * 키가 영원히 남아 다른 캐시를 밀어낸다.
 */
public interface SubwayRingPort {

    /**
     * 링 하나를 저장한다. <b>TTL 과 여정 색인 등록이 같은 원자 단위</b>여야 한다.
     *
     * <p>쪼개면 TTL 없는 키가 생기거나, 색인에 없는 키가 생겨 여정이 끝나도 안 지워진다.
     */
    void save(SubwayRingState state);

    /**
     * @param bindingVersion 고정 세대. 재탐색 계승이 이 값을 0 으로 되돌리므로 개정본과 <b>둘 다</b>
     *                       키에 들어간다 — 하나만 넣으면 옛 worker 가 새 링을 덮는다
     */
    Optional<SubwayRingState> find(Long journeyId, int routeRevision, int legIndex,
                                   int bindingVersion);

    /**
     * 그 여정의 링을 전부 지운다.
     *
     * <p><b>{@code SCAN} 을 쓰지 않는다.</b> 여정 하나를 지우자고 키공간을 훑으면 여정 수만큼
     * 전수 조회가 된다. 여정별 색인만 읽고 지운다.
     *
     * @return 지운 키 수
     */
    int deleteByJourney(Long journeyId);
}
