package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RouteServiceWindowCheckerTest {

    @Mock BusRouteStopRepository repo;

    RouteServiceWindowChecker checker() {
        return new RouteServiceWindowChecker(repo);
    }

    @Test
    void parseMinuteOfDay_formats() {
        assertThat(RouteServiceWindowChecker.parseMinuteOfDay("0400")).isEqualTo(240);
        assertThat(RouteServiceWindowChecker.parseMinuteOfDay("04:30")).isEqualTo(270);
        assertThat(RouteServiceWindowChecker.parseMinuteOfDay("043000")).isEqualTo(270);
        assertThat(RouteServiceWindowChecker.parseMinuteOfDay("20250101043000")).isEqualTo(270);
        assertThat(RouteServiceWindowChecker.parseMinuteOfDay("25:30")).isEqualTo(25 * 60 + 30);
        assertThat(RouteServiceWindowChecker.parseMinuteOfDay("garbage")).isNull();
        assertThat(RouteServiceWindowChecker.parseMinuteOfDay(null)).isNull();
    }

    private BusRouteStop stop(String begin, String last) {
        try {
            var ctor = BusRouteStop.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            BusRouteStop s = ctor.newInstance();
            set(s, "beginTm", begin);
            set(s, "lastTm", last);
            return s;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void set(Object t, String f, Object v) {
        try {
            Class<?> c = t.getClass();
            Field fld = null;
            while (c != null && fld == null) {
                try { fld = c.getDeclaredField(f); } catch (NoSuchFieldException ignore) { c = c.getSuperclass(); }
            }
            fld.setAccessible(true);
            fld.set(t, v);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    @Test
    void insideWindow_available() {
        given(repo.findByRouteIdAndStopId(any(), any())).willReturn(List.of(stop("0400", "2300")));
        LocalDateTime boarding = LocalDateTime.of(2026, 6, 23, 14, 0);

        assertThat(checker().judge("r", "s", boarding))
                .isEqualTo(RouteServiceWindowChecker.Availability.AVAILABLE);
    }

    @Test
    void afterLastBus_outOfService() {
        given(repo.findByRouteIdAndStopId(any(), any())).willReturn(List.of(stop("0400", "2300")));
        LocalDateTime boarding = LocalDateTime.of(2026, 6, 23, 23, 30);

        assertThat(checker().judge("r", "s", boarding))
                .isEqualTo(RouteServiceWindowChecker.Availability.OUT_OF_SERVICE);
    }

    @Test
    void crossMidnight_earlyMorningAvailable() {
        // 첫차 22:00, 막차 01:00(다음날) → 00:30 승차는 운행중
        given(repo.findByRouteIdAndStopId(any(), any())).willReturn(List.of(stop("2200", "0100")));
        LocalDateTime boarding = LocalDateTime.of(2026, 6, 23, 0, 30);

        assertThat(checker().judge("r", "s", boarding))
                .isEqualTo(RouteServiceWindowChecker.Availability.AVAILABLE);
    }

    @Test
    void noData_unknown() {
        given(repo.findByRouteIdAndStopId(any(), any())).willReturn(List.of());

        assertThat(checker().judge("r", "s", LocalDateTime.now()))
                .isEqualTo(RouteServiceWindowChecker.Availability.UNKNOWN);
    }

    @Test
    void unparseable_unknown() {
        given(repo.findByRouteIdAndStopId(any(), any())).willReturn(List.of(stop("bad", "worse")));

        assertThat(checker().judge("r", "s", LocalDateTime.now()))
                .isEqualTo(RouteServiceWindowChecker.Availability.UNKNOWN);
    }
}
