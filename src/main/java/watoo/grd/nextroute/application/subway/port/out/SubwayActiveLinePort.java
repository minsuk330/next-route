package watoo.grd.nextroute.application.subway.port.out;

import java.util.Set;

/**
 * 지금 진행 링이 도는 열차의 노선(line_id). 위치 수집기가 이 노선만 더 자주 받는다(#371).
 */
public interface SubwayActiveLinePort {

    /** 조회에 실패하면 빈 집합 — 부르는 쪽은 평소 주기로만 돈다. */
    Set<String> activeRingLineIds();
}
