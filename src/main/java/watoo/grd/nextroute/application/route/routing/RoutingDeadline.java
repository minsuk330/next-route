package watoo.grd.nextroute.application.route.routing;

import java.time.Clock;
import java.time.Instant;

/**
 * 이 호출에 허용된 마감. 주입 Clock 으로 남은 예산을 잰다. 각 단계에 독립 예산을 주지 않고
 * 전체 요청 예산 안에서 이 마감을 공유한다(단계별 3초 금지, PLAN P2-H).
 */
public record RoutingDeadline(Instant deadlineAt) {

    public static RoutingDeadline of(Clock clock, long budgetMs) {
        return new RoutingDeadline(clock.instant().plusMillis(Math.max(0, budgetMs)));
    }

    public long remainingMillis(Clock clock) {
        long ms = deadlineAt.toEpochMilli() - clock.instant().toEpochMilli();
        return Math.max(0, ms);
    }

    public boolean expired(Clock clock) {
        return !clock.instant().isBefore(deadlineAt);
    }
}
