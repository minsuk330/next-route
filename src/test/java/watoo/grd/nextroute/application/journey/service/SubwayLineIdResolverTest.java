package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubwayLineIdResolverTest {

    private final SubwayLineIdResolver resolver = new SubwayLineIdResolver();

    @Test
    void resolvesNumericAndNamedLinesToInternalIds() {
        assertThat(resolver.resolve("2호선", "2")).contains("1002");
        assertThat(resolver.resolve("수인분당선", "1075")).contains("1075");
        assertThat(resolver.resolve("GTX-A", null)).contains("1032");
    }

    @Test
    void doesNotTreatUnknownOdsayCodeAsAnInternalId() {
        assertThat(resolver.resolve(null, "900222")).isEmpty();
    }
}
