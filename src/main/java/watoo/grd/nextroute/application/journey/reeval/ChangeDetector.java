package watoo.grd.nextroute.application.journey.reeval;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 키별 지문을 기준선과 비교해 바뀐 키를 고른다.
 *
 * <p>두 단계로 나눈 이유: 바뀐 키를 알려 준 뒤 <b>실제로 반영된 키만</b> 기준선을 새 지문으로 바꿔야
 * 한다. 반영하지 못한 키(상한 초과·DB 실패)의 기준선을 같이 바꾸면 그 변화는 다시 보이지 않아
 * 이벤트를 잃는다.
 *
 * @param <K> 키
 * @param <F> 지문. {@code equals} 로 비교한다
 */
public interface ChangeDetector<K, F> {

    /**
     * 이번 관측에서 기준선과 달라진 키. <b>첫 관측</b>에서는 아무것도 바뀐 것으로 치지 않는다 — 비교할
     * 과거가 없다. 그 뒤로는 기준선에 없던 키가 나타난 것도 바뀐 것이다(열차가 다 지나가 사라졌던 역에
     * 다음 열차가 보이면 평가기의 "모름"이 예정 시각으로 바뀐다).
     *
     * <p>지난번에 있던 키가 이번 관측에 없으면 "비었다"로 바뀐 것이다(열차가 다 지나감).
     */
    Set<K> changed(Map<K, F> observed);

    /**
     * 기준선을 갱신한다.
     *
     * @param observed 이번 관측
     * @param keep     기준선을 <b>옛 값으로 남길</b> 키. 반영하지 못한 변화라 다음 관측에서 다시 나와야 한다
     */
    void accept(Map<K, F> observed, Collection<K> keep);
}
