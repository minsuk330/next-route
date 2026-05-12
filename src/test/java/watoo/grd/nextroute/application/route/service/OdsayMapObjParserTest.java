package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.OdsayMapObjFragment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OdsayMapObjParserTest {

    OdsayMapObjParser parser;

    @BeforeEach
    void setUp() {
        parser = new OdsayMapObjParser();
    }

    @Test
    void TC_base_없는_단일_fragment_파싱() {
        List<OdsayMapObjFragment> result = parser.parse("3:2:322:329");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).odsayRouteId()).isEqualTo("3");
        assertThat(result.get(0).laneClass()).isEqualTo(2);
        assertThat(result.get(0).startIdx()).isEqualTo(322);
        assertThat(result.get(0).endIdx()).isEqualTo(329);
    }

    @Test
    void TC_base_없는_복수_fragment_파싱() {
        List<OdsayMapObjFragment> result = parser.parse("3:2:322:329@17:2:534:572");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).odsayRouteId()).isEqualTo("3");
        assertThat(result.get(0).startIdx()).isEqualTo(322);
        assertThat(result.get(1).odsayRouteId()).isEqualTo("17");
        assertThat(result.get(1).startIdx()).isEqualTo(534);
    }

    @Test
    void TC_base_prefix_있는_mapObj_파싱() {
        List<OdsayMapObjFragment> result = parser.parse("0:0@3:2:322:329@17:2:534:572");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).odsayRouteId()).isEqualTo("3");
        assertThat(result.get(1).odsayRouteId()).isEqualTo("17");
    }

    @Test
    void TC_전체_노선_fragment_isWholeRoute_true() {
        List<OdsayMapObjFragment> result = parser.parse("0:0@3:2:-1:-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isWholeRoute()).isTrue();
    }

    @Test
    void TC_버스_laneClass_파싱되지만_isSubway_false() {
        List<OdsayMapObjFragment> result = parser.parse("5:1:100:200");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isSubway()).isFalse();
        assertThat(result.get(0).laneClass()).isEqualTo(1);
    }

    @Test
    void TC_malformed_fragment_skip() {
        // 토큰이 3개인 잘못된 fragment는 스킵
        List<OdsayMapObjFragment> result = parser.parse("3:2:322@17:2:534:572");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).odsayRouteId()).isEqualTo("17");
    }

    @Test
    void TC_null_빈_문자열_빈_리스트_반환() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }
}
