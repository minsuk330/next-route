package watoo.grd.nextroute.application.route.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 같은 키에 대해 진행 중인 호출이 있으면 결과를 공유한다(stampede 방지).
 *
 * <p>캐시가 만료되는 순간 같은 정류장·노선을 조회하는 요청이 동시에 miss 하면, 지금은 그 수만큼
 * provider 를 호출한다. 첫 요청만 실제로 호출하고 나머지는 그 결과를 기다리게 하면 호출이 1회로 줄어든다.
 *
 * <p><b>owner 는 호출 스레드에서 직접 실행한다.</b> 별도 executor 로 넘기면 스레드만 하나 더 쓰고
 * 대기 구조는 같다. joiner 는 자신의 남은 시간 예산까지만 기다린다 — owner 가 느리다고 joiner 의
 * deadline 을 어길 수는 없다.
 *
 * <p>범위는 JVM 로컬이다. 현재 단일 인스턴스 배포이므로 이걸로 충분하고, 다중 인스턴스로 가면
 * 인스턴스 수만큼 호출이 남는다(그때는 Redis 분산 락 또는 상류 캐시가 필요하다).
 */
public final class SingleFlight<K, V> {

    public enum Role {
        /** 실제로 provider 를 호출한 요청. */
        OWNER,
        /** 진행 중인 호출에 합류해 결과를 공유받은 요청. */
        JOINER,
        /** 합류했으나 자신의 예산 안에 결과를 받지 못한 요청. */
        JOIN_TIMEOUT
    }

    /** @param value JOIN_TIMEOUT 이면 null. */
    public record Outcome<V>(V value, Role role) {
        public boolean hasValue() {
            return value != null;
        }
    }

    private final ConcurrentMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    /**
     * @param joinWaitMs joiner 가 기다릴 수 있는 최대 시간(자신의 남은 예산).
     * @throws RuntimeException owner 인 경우 loader 의 예외를 그대로 전파한다.
     *                          joiner 는 owner 의 실패를 자기 실패로 만들지 않고 JOIN_TIMEOUT 으로 돌려받는다
     *                          (실패를 공유해 퍼뜨리지 않는다).
     */
    public Outcome<V> execute(K key, Supplier<V> loader, long joinWaitMs) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);

        if (existing == null) {
            try {
                V value = loader.get();
                mine.complete(value);
                return new Outcome<>(value, Role.OWNER);
            } catch (RuntimeException e) {
                mine.completeExceptionally(e);
                throw e;
            } finally {
                // 성공·실패·취소 모두 여기서 제거 — in-flight map 누수 방지.
                inFlight.remove(key, mine);
            }
        }

        return join(existing, joinWaitMs);
    }

    private Outcome<V> join(CompletableFuture<V> owner, long joinWaitMs) {
        if (joinWaitMs <= 0) {
            return new Outcome<>(null, Role.JOIN_TIMEOUT);
        }
        try {
            return new Outcome<>(owner.get(joinWaitMs, TimeUnit.MILLISECONDS), Role.JOINER);
        } catch (TimeoutException e) {
            return new Outcome<>(null, Role.JOIN_TIMEOUT);
        } catch (ExecutionException e) {
            // owner 가 실패했다. 실패 결과를 공유해 퍼뜨리지 않고, 이 요청은 값 없음으로 처리한다.
            return new Outcome<>(null, Role.JOIN_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome<>(null, Role.JOIN_TIMEOUT);
        }
    }

    /** 테스트용 — 진행 중인 호출 수. */
    public int inFlightCount() {
        return inFlight.size();
    }
}
