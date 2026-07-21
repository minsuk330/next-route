package watoo.grd.nextroute.application.route.observability;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 한 번의 경로 검색 요청 동안 살아있는 컨텍스트. 경과시간과 단계별 완전성 판정을 모은다.
 *
 * <p>경과시간은 wall clock 변경의 영향을 받지 않도록 단조 시계를 쓴다.
 *
 * <p><b>Step 0 범위</b>: 계측만 담당하며 흐름을 바꾸지 않는다. 각 단계는 자신이 이 요청에
 * 적용 가능했는지({@link #requested})와 생략·실패했는지({@link #degrade})만 보고한다.
 * Step 2 에서 이 객체에 {@code deadlineAt}/{@code remainingMillis()} 를 추가해
 * 단계별 시간 예산 전파의 소유자로 확장한다(PLAN.md §4).
 *
 * <p>보강 단계 일부는 워커 스레드에서 실행되므로 보고 메서드는 동기화한다.
 * 호출 빈도가 요청당 한 자릿수라 경합 비용은 무시할 수 있다.
 */
public final class RouteSearchContext {

    private final String requestId;
    private final LongSupplier nanoTime;
    private final long startNanos;

    private final Set<SearchComponent> requested = EnumSet.noneOf(SearchComponent.class);
    private final Map<SearchComponent, DegradeReason> degraded = new EnumMap<>(SearchComponent.class);
    private boolean mandatoryFailed;

    private RouteSearchContext(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.requestId = UUID.randomUUID().toString();
        this.startNanos = nanoTime.getAsLong();
    }

    public static RouteSearchContext start() {
        return new RouteSearchContext(System::nanoTime);
    }

    /** 테스트용 — 단조 시계 주입. */
    public static RouteSearchContext start(LongSupplier nanoTime) {
        return new RouteSearchContext(nanoTime);
    }

    /**
     * 계측 대상이 아닌 호출 경로(단위 테스트 등)용 no-op 컨텍스트.
     * 보고는 받되 아무도 읽지 않으므로 메트릭에 반영되지 않는다.
     */
    public static RouteSearchContext noop() {
        return new RouteSearchContext(System::nanoTime);
    }

    public String requestId() {
        return requestId;
    }

    public long elapsedNanos() {
        return nanoTime.getAsLong() - startNanos;
    }

    /** 이 단계가 이번 요청에 적용 가능함(보강할 대상이 실제로 있었음)을 표시. */
    public synchronized void requested(SearchComponent component) {
        requested.add(component);
    }

    /** 이 단계가 생략·실패했음을 표시. 같은 단계의 첫 사유를 보존한다. */
    public synchronized void degrade(SearchComponent component, DegradeReason reason) {
        requested.add(component);
        degraded.putIfAbsent(component, reason);
        if (component.grade() == SearchComponent.Grade.MANDATORY) {
            mandatoryFailed = true;
        }
    }

    public synchronized boolean isDegraded(SearchComponent component) {
        return degraded.containsKey(component);
    }

    public synchronized Map<SearchComponent, DegradeReason> degradations() {
        return new EnumMap<>(degraded);
    }

    /**
     * 완전성 판정. 이 요청에 적용 가능했던 OPTIONAL 단계만 분모로 삼는다.
     * 버스 구간이 없는 경로에서 버스 보강이 없는 것은 degradation 이 아니다.
     * AUXILIARY(검색 이력)는 응답 내용에 기여하지 않으므로 판정에서 제외한다.
     */
    public synchronized SearchOutcome outcome() {
        if (mandatoryFailed) {
            return SearchOutcome.FAILED;
        }
        long applicable = requested.stream().filter(SearchComponent::isOptional).count();
        if (applicable == 0) {
            return SearchOutcome.FULL;
        }
        long failed = degraded.keySet().stream().filter(SearchComponent::isOptional).count();
        if (failed == 0) {
            return SearchOutcome.FULL;
        }
        return failed == applicable ? SearchOutcome.BASE_ONLY : SearchOutcome.PARTIAL;
    }
}
