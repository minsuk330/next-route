package watoo.grd.nextroute.application.journey.dto;

/**
 * 미완료 고정을 왜 접었는지.
 *
 * <p>지속 칼럼이 아니라 <b>지표 tag</b> 다. 상태 칼럼에는 {@code ABANDONED} 하나만 남고, 사유는
 * 여기서 갈린다 — 어느 접점이 얼마나 접는지 모르면 판정 기회를 잃고 있는 것인지 정상 종료인지
 * 구분할 수 없다.
 *
 * <p>값 집합이 고정이라는 것이 중요하다. 여정 번호처럼 요청마다 달라지는 값을 tag 에 넣으면
 * 시계열이 무한히 늘어난다.
 */
public enum SubwayBindingAbandonReason {

    /** 사용자가 끝냈거나 완료했다. 앱이 보고한 lease 만료도 여기로 온다. */
    ENDED,

    /** 앱에서 연락이 끊겨 만료 배치가 접었다. */
    LEASE_EXPIRED,

    /** 새 여정을 시작하며 옛 여정을 갈아탔다. */
    SUPERSEDED_BY_NEW_JOURNEY,

    /** 재탐색 수락으로 개정본이 올랐고, 그 단계는 이어받을 조건이 아니었다. */
    SUPERSEDED_BY_REVISION;

    /** 지표 tag 값. 소문자 고정 어휘다. */
    public String tag() {
        return name().toLowerCase();
    }
}
