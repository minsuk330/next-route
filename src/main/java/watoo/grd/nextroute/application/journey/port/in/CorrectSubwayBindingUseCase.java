package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;

/**
 * 사용자가 직접 고정을 되돌린다.
 *
 * <p>자동 판정은 확률이라 틀릴 수 있다. 틀린 채로 두면 사용자는 엉뚱한 역에서 내리므로,
 * 손으로 고칠 통로를 반드시 남긴다.
 *
 * <p><b>사용자 행위만이 확정을 뒤집을 수 있다.</b> 늦게 도착한 관측이나 다시 도는 판정기가
 * 조용히 뒤집으면, 사용자는 자기 화면이 왜 바뀌었는지 모른다.
 */
public interface CorrectSubwayBindingUseCase {

    /**
     * 다른 열차를 탔다.
     *
     * <p>거부한 열차를 기억해 다시 고르지 않는다. 그냥 상태만 되돌리면 판정기가 같은 관측을
     * 다시 읽어 방금 거부한 열차를 그대로 고른다.
     */
    SubwayBindingView reset(Long userId, Long journeyId, int routeRevision, int legIndex);

    /** 타지 않았다. 이 단계에서는 더 이상 고정을 시도하지 않는다. */
    SubwayBindingView notBoarded(Long userId, Long journeyId, int routeRevision, int legIndex);
}
