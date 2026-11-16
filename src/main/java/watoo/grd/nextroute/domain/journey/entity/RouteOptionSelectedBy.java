package watoo.grd.nextroute.domain.journey.entity;

/**
 * 묶인 노선 중 지금 선택이 누구의 것인가(#614).
 *
 * <p>{@code DEFAULT} = 서버가 정함(여정 시작 때 먼저 오는 노선, 없으면 첫 후보). {@code USER} = 사용자가 탈(탄) 노선으로
 * 고름(#598). 서버가 대기 중 선택을 옮기는 일은 {@code DEFAULT} 일 때만 한다. 칸이 생기기 전(V78) 행은 null = DEFAULT.
 */
public enum RouteOptionSelectedBy {
    DEFAULT,
    USER
}
