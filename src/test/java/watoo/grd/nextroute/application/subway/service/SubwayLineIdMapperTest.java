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

    /** 실시간 위치 API 는 경로에 line_id 가 아니라 호선명을 받는다. */
    @Test
    void resolvesLineNameForPositionApi() {
        assertThat(SubwayLineIdMapper.toLineName("1002")).contains("2호선");
        assertThat(SubwayLineIdMapper.toLineName("1075")).contains("수인분당선");
        assertThat(SubwayLineIdMapper.toLineName("1032")).contains("GTX-A");
    }

    /** 왕복이 제자리로 돌아와야 한다. 한쪽만 고치면 부르는 노선이 달라진다. */
    @Test
    void lineNameRoundTripsBackToTheSameId() {
        for (String lineId : new String[]{"1001", "1002", "1009", "1063", "1092"}) {
            String name = SubwayLineIdMapper.toLineName(lineId).orElseThrow();
            assertThat(SubwayLineIdMapper.toInternalLineId(name)).isEqualTo(lineId);
        }
    }

    /**
     * 모르는 line_id 에서 호선명을 지어내지 않는다.
     *
     * <p>지어내면 엉뚱한 노선의 열차를 우리 노선인 줄 알고 추적한다. 안 부르는 편이 낫다.
     */
    @Test
    void doesNotInventALineNameForUnknownIds() {
        assertThat(SubwayLineIdMapper.toLineName("9999")).isEmpty();
        assertThat(SubwayLineIdMapper.toLineName("abc")).isEmpty();
        assertThat(SubwayLineIdMapper.toLineName(null)).isEmpty();
        assertThat(SubwayLineIdMapper.toLineName(" ")).isEmpty();
    }
}
