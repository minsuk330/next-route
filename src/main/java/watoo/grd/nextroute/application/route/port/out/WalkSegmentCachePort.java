package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.dto.WalkCacheKey;
import watoo.grd.nextroute.application.route.dto.WalkSegment;

import java.time.Duration;
import java.util.Optional;

/**
 * TMAP 보행자 경로 응답 캐시.
 *
 * 만료 처리: 조회 시 stale 판정 → MISS 처리 후 덮어쓰기.
 * Negative cache: TMAP 빈 응답(서비스 지역 외 등)은 짧은 TTL로 보관해 재호출 회피.
 */
public interface WalkSegmentCachePort {

    /**
     * @return 정상 hit이면 WalkSegment, negative hit이면 {@link WalkSegment#empty()}, miss/stale이면 empty.
     */
    Optional<WalkSegment> get(WalkCacheKey key);

    void put(WalkCacheKey key, WalkSegment segment, Duration ttl);

    /** 빈 응답을 short-TTL로 캐싱. 같은 좌표쌍 재호출 방지용. */
    void putNegative(WalkCacheKey key, Duration ttl);

    /** 관리자용 — 전체 무효화. 삭제된 row 수 반환. */
    int invalidateAll();

    /** 관리자용 — prefix로 시작하는 캐시 키 무효화. 삭제된 row 수 반환. */
    int invalidateByPrefix(String prefix);
}
