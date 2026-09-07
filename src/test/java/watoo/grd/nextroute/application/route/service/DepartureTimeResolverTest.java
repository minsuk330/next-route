package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.exception.InvalidDepartureTimeException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepartureTimeResolverTest {

    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final DepartureTimeResolver resolver =
            new DepartureTimeResolver(Clock.fixed(now, ZoneOffset.UTC));

    @Test
    @DisplayName("생략(null/blank)이면 주입 Clock 의 지금으로 확정")
    void resolvesNowWhenAbsent() {
        assertThat(resolver.resolve(null)).isEqualTo(now);
        assertThat(resolver.resolve("   ")).isEqualTo(now);
    }

    @Test
    @DisplayName("offset 포함 ISO-8601 은 그 Instant 로 확정(서버 시간대로 보정하지 않음)")
    void resolvesOffsetString() {
        assertThat(resolver.resolve("2026-09-08T08:00:00+09:00"))
                .isEqualTo(Instant.parse("2026-09-07T23:00:00Z"));
        assertThat(resolver.resolve("2026-09-08T00:00:00Z")).isEqualTo(now);
    }

    @Test
    @DisplayName("offset 없는 로컬시각은 입력 오류(무경로 아님)")
    void rejectsOffsetless() {
        assertThatThrownBy(() -> resolver.resolve("2026-09-08T08:00:00"))
                .isInstanceOf(InvalidDepartureTimeException.class);
    }

    @Test
    @DisplayName("형식 불량은 입력 오류")
    void rejectsGarbage() {
        assertThatThrownBy(() -> resolver.resolve("내일 아침"))
                .isInstanceOf(InvalidDepartureTimeException.class);
    }

    @Test
    @DisplayName("기존 6-인자 생성자는 그대로 컴파일(departureAt=null) — back-compat")
    void backCompatConstructor() {
        RouteSearchRequest req = new RouteSearchRequest(127.0, 37.5, 127.01, 37.51, "출발", "도착");
        assertThat(req.departureAt()).isNull();
        assertThat(resolver.resolve(req.departureAt())).isEqualTo(now);
    }
}
