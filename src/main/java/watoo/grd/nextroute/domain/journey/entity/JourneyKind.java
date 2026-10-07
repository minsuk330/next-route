package watoo.grd.nextroute.domain.journey.entity;

/**
 * 여정이 어떻게 시작됐는가(V59, #265).
 *
 * <p>둘 다 같은 여정 기계(단계·M7 열차 고정·링·Live Activity·lease)를 탄다. 갈리는 곳은 재탐색뿐이다 —
 * 단독 추적은 사용자가 노선을 직접 고른 것이라 "더 나은 길"을 권하지 않는다. 보드 갱신·예약은 SQL 에서
 * {@code kind = 'NAVIGATION'} 으로 거른다(세션을 읽고 거르면 경쟁 창이 생긴다).
 */
public enum JourneyKind {
    /** 경로 검색 결과로 시작한 일반 여정. 기존 여정은 전부 이것이다. */
    NAVIGATION,
    /** 노선·방향·승하차만 골라 시작한 단독 추적(구간 1개). 재탐색 없음. */
    TRACK
}
