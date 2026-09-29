package watoo.grd.nextroute.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 시험이 손으로 넘기는 시계.
 *
 * <p>판정·링·evidence·push 서비스가 전부 {@link Clock} 빈을 주입받는다. 시나리오가 "2분 뒤 역삼에
 * 섰다"를 재현하려면 실제로 2분을 기다리는 대신 이 시계를 넘긴다. {@code Clock.fixed} 는 한 번 정한
 * 시각을 못 바꿔서 여러 tick 에 걸친 이야기를 못 쓴다.
 *
 * <p>여러 스레드가 읽을 수 있어 {@code volatile} 이다. 시험은 한 스레드에서 넘기고, 서비스가
 * 내부적으로 다른 스레드에서 읽는 경우가 있다.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
