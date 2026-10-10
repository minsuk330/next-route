package watoo.grd.nextroute.application.journey.dto;

/**
 * 실시간 신호의 <b>신선도</b>. 고정 상태와 <b>다른 축</b>이다.
 *
 * <p>{@code SIGNAL_STALE} 같은 값을 고정 상태에 섞지 않는다. 섞으면 "판정이 취소된 것"과
 * "신호가 늦은 것"을 구분할 수 없고, 후자에서 후보를 갈아치우는 사고가 열린다. 그래서
 * {@code bindingStatus=CONFIRMED} 이면서 {@code quality=STALE} 인 상태가 정상적으로 존재한다.
 */
public enum SubwayRingQuality {

    /** 최근 관측이 있고 진행률이 살아 움직인다. */
    NORMAL,

    /**
     * 서버가 우리 열차의 새 관측을 받지 못한다(도착 스냅샷 수집 시각 60초·원천 시각 240초 초과, 위치는 원천 시각 기준). 링은
     * 지금 화면 값에 <b>멈춘다</b>. 새 근거가 없을 뿐 수집이 살아 있으면 STALE 이 아니다 — 그때는 그리던 직선을 이어 간다.
     */
    STALE
}
