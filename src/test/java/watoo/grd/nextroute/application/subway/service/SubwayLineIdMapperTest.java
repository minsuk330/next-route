package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubwayLineIdMapperTest {

    @Test
    void resolvesNumericAndNamedLinesToInternalIds() {
        assertThat(SubwayLineIdMapper.resolveOdsay("2호선", "2")).contains("1002");
        assertThat(SubwayLineIdMapper.resolveOdsay("수인분당선", "1075")).contains("1075");
        assertThat(SubwayLineIdMapper.resolveOdsay("GTX-A", null)).contains("1032");
    }

    @Test
    void doesNotTreatUnknownOdsayCodeAsAnInternalId() {
        assertThat(SubwayLineIdMapper.resolveOdsay(null, "900222")).isEmpty();
    }

    @Test
    void seoulStaticDataUsesTheSameMapping() {
        assertThat(SubwayLineIdMapper.toInternalLineId("02호선")).isEqualTo("1002");
        assertThat(SubwayLineIdMapper.toInternalLineId("수인분당선")).isEqualTo("1075");
    }
}
