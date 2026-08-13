package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운행일 경계.
 *
 * <p>자정을 넘겨 다니는 열차는 <b>전날의 운행</b>이다. 이걸 틀리면 어제 01:30 의 열차번호와
 * 오늘 01:30 의 같은 번호가 한 열차로 뭉친다.
 */
class ServiceDateResolverTest {

    private final ServiceDateResolver resolver = new ServiceDateResolver();

    @Test
    void treatsAfterMidnightAsThePreviousServiceDay() {
        assertThat(resolver.resolve(LocalDateTime.parse("2026-08-14T01:30:00")))
                .isEqualTo(LocalDate.parse("2026-08-13"));
        assertThat(resolver.resolve(LocalDateTime.parse("2026-08-14T03:59:59")))
                .isEqualTo(LocalDate.parse("2026-08-13"));
    }

    @Test
    void fourAmStartsANewServiceDay() {
        assertThat(resolver.resolve(LocalDateTime.parse("2026-08-14T04:00:00")))
                .isEqualTo(LocalDate.parse("2026-08-14"));
        assertThat(resolver.resolve(LocalDateTime.parse("2026-08-14T23:59:59")))
                .isEqualTo(LocalDate.parse("2026-08-14"));
    }
}
