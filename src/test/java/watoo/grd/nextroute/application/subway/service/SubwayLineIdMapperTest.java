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

    /** OTP 노선명 전수(2026-10-10 운영 그래프 SUBWAY·RAIL 99개 이름)에서 뽑은 대표. */
    @Test
    void readsOtpRouteNamesFromTheExplicitTable() {
        assertThat(SubwayLineIdMapper.resolveOdsay("서울2호선", null)).contains("1002");
        assertThat(SubwayLineIdMapper.resolveOdsay("수도권 1호선", null)).contains("1001");
        assertThat(SubwayLineIdMapper.resolveOdsay("신림선", null)).contains("1094");
        assertThat(SubwayLineIdMapper.resolveOdsay("우이신설경전철", null)).contains("1092");
    }

    /** 이름 속 숫자만 뽑으면 지방 지하철·철도가 서울 노선이 된다(부산1호선 → 1001). */
    @Test
    void doesNotTurnOtherRegionLinesIntoSeoulLines() {
        for (String name : new String[]{"부산1호선", "대구3호선", "대전1호선", "광주1호선", "인천1호선", "인천2호선",
                "부산김해경전철", "김포도시철도", "[일반]1경부서울부산", "[고속]1호남고속서울광주"}) {
            assertThat(SubwayLineIdMapper.resolveOdsay(name, "1:RR_ACC1_S-1-02-1I")).as(name).isEmpty();
        }
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
