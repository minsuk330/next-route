package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;

import java.time.Instant;
import java.util.Optional;

/**
 * 검색 결과 스냅샷 저장소. 여정 시작 API 가 경로 JSON 전체 대신
 * {@code searchSessionId + pathKey} 두 값만 받게 하려고, 서버가 만든 원본을 짧게 보관한다.
 *
 * <p><b>구현체는 예외를 밖으로 던지지 않는다.</b> 이 포트는 호출량이 가장 많은
 * {@code /api/route/search} 응답 경로에서 호출되므로, 저장소 장애가 검색을 실패시키면 안 된다.
 * 저장 실패는 {@code null} 반환으로, 조회 실패는 {@link Optional#empty()} 로 드러낸다.
 */
public interface RouteSearchSnapshotPort {

    /**
     * 검색 결과를 저장하고 서버가 만든 searchSessionId 를 반환한다.
     *
     * @param excludeExpress 그 검색의 급행 끄기 <b>요청값</b>(subway-express S5). 결과가 D10 으로 필터를 풀었어도
     *                       요청값을 그대로 넣는다 — 여정이 이 값을 세션에 남겨 재탐색이 같은 선호로 찾는다
     * @return 저장에 성공하면 searchSessionId, 실패하면 {@code null}
     */
    String save(Long ownerUserId, RouteSearchResult result, boolean excludeExpress, Instant expiresAt);

    /** searchSessionId + pathKey 로 경로 하나를 조회한다. 만료·불일치면 empty. */
    Optional<StoredPath> find(String searchSessionId, String pathKey);

    /**
     * @param ownerUserId    검색을 수행한 사용자. 익명 검색이면 null (소유자 검증은 여정 시작 쪽 책임).
     * @param excludeExpress 그 검색의 급행 끄기 요청값. 칸이 없던 옛 스냅샷은 false(급행 포함 = 옛 동작)
     */
    record StoredPath(Long ownerUserId, PathResult path, Instant expiresAt, boolean excludeExpress) {

        /** 급행 끄기를 모르는 호출부·시험용(false = 급행 포함). */
        public StoredPath(Long ownerUserId, PathResult path, Instant expiresAt) {
            this(ownerUserId, path, expiresAt, false);
        }
    }
}
