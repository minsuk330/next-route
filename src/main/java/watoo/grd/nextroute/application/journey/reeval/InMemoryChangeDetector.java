package watoo.grd.nextroute.application.journey.reeval;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 기준선을 메모리에 두는 감지기.
 *
 * <p>감지는 리더 한 대만 한다(리더 잠금). 기준선을 Redis 에 두면 잠깐 리더가 둘일 때 두 대가 서로
 * 다른 스냅숏으로 번갈아 써서 "변화"가 깜빡인다. 메모리에 두면 리더가 바뀔 때 기준선이 한 번 비는
 * 대가만 치른다(그 주기 하나는 기준선만 채운다. 그사이 변화는 평가기의 간격 폴링이 잡는다).
 *
 * <p>스레드 안전하게 동기화한다. 한 수집기가 순서대로 부르지만, 시험·운영 점검이 끼어들 수 있다.
 */
public class InMemoryChangeDetector<K, F> implements ChangeDetector<K, F> {

    private final Map<K, F> baseline = new HashMap<>();
    /** 첫 관측을 받아 들였는지. 그 뒤로는 새로 나타난 키도 바뀐 것이다(사라졌던 역에 다음 열차가 보임). */
    private boolean primed;

    @Override
    public synchronized Set<K> changed(Map<K, F> observed) {
        Set<K> out = new HashSet<>();
        for (Map.Entry<K, F> entry : observed.entrySet()) {
            F before = baseline.get(entry.getKey());
            if (before == null ? primed : !Objects.equals(before, entry.getValue())) {
                out.add(entry.getKey());
            }
        }
        for (K key : baseline.keySet()) {
            if (!observed.containsKey(key)) {
                out.add(key);
            }
        }
        return out;
    }

    @Override
    public synchronized void accept(Map<K, F> observed, Collection<K> keep) {
        primed = true;
        Set<K> held = new HashSet<>(keep);
        baseline.keySet().removeIf(key -> !observed.containsKey(key) && !held.contains(key));
        observed.forEach((key, fingerprint) -> {
            if (!held.contains(key) || !baseline.containsKey(key)) {
                baseline.put(key, fingerprint);
            }
        });
    }

    /** 기준선을 비운다. 리더를 잃었다 다시 얻으면 그사이 다른 리더가 본 것을 모른다. */
    public synchronized void reset() {
        baseline.clear();
        primed = false;
    }

    synchronized int size() {
        return baseline.size();
    }
}
