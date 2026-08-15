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
     * 신호가 늦었거나 남은 초를 얻지 못했다. 링은 마지막 값에 <b>멈춘다</b>.
     *
     * <p>정적 소요시간이나 최근 역간 중위값으로 <b>연장하지 않는다</b>. 그것은 실시간처럼 보이는
     * 추정이고, 틀렸을 때 사용자는 서버를 믿은 대가로 역을 지나친다.
     */
    STALE
}
