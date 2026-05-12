package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.route.config.OdSayProperties;
import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OdSayApiAdapterTest {

    @Mock
    RestTemplate restTemplate;

    OdSayApiAdapter adapter;

    @BeforeEach
    void setUp() {
        OdSayProperties properties = new OdSayProperties();
        properties.setBaseUrl("https://api.odsay.com/v1/api");
        properties.setKey("test-key");

        adapter = new OdSayApiAdapter(restTemplate, new ObjectMapper(), properties);
    }

    @Test
    void TC_searchPubTransPathT_mapObj는_loadLane_형식으로_base를_보정한다() {
        when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("""
                        {
                          "result": {
                            "lane": [
                              {
                                "class": 2,
                                "type": 3,
                                "section": [
                                  {
                                    "graphPos": [
                                      {"x": 126.1, "y": 37.1},
                                      {"x": 126.2, "y": 37.2}
                                    ]
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """);

        List<LaneGraphicResult> result = adapter.loadLane("3:2:322:329@17:2:534:572");

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(uriCaptor.capture(), eq(String.class));

        assertThat(uriCaptor.getValue().toString())
                .contains("mapObject=0:0@3:2:322:329@17:2:534:572");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).sections().get(0).get(0).x()).isEqualTo(126.1);
        assertThat(result.get(0).sections().get(0).get(0).y()).isEqualTo(37.1);
    }

    @Test
    void TC_base가_포함된_mapObject는_그대로_사용하고_좌표에_base를_더한다() {
        when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("""
                        {
                          "result": {
                            "lane": [
                              {
                                "class": 1,
                                "type": 11,
                                "section": [
                                  {
                                    "graphPos": [
                                      {"x": 0.25, "y": 0.5}
                                    ]
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """);

        List<LaneGraphicResult> result = adapter.loadLane("126:37@12018:1:-1:-1");

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(uriCaptor.capture(), eq(String.class));

        assertThat(uriCaptor.getValue().toString())
                .contains("mapObject=126:37@12018:1:-1:-1");
        assertThat(result.get(0).sections().get(0).get(0).x()).isEqualTo(126.25);
        assertThat(result.get(0).sections().get(0).get(0).y()).isEqualTo(37.5);
    }

    @Test
    void TC_searchPath는_경로검색만_하고_loadLane은_호출하지_않는다() {
        // loadLane은 관리자 적재 API에서만 호출되며, 검색 중에는 DB 캐시를 사용한다.
        when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("""
                        {
                          "result": {
                            "searchType": 0,
                            "busCount": 0,
                            "subwayCount": 1,
                            "path": [
                              {
                                "pathType": 1,
                                "info": {
                                  "totalTime": 25,
                                  "payment": 1400,
                                  "totalWalk": 300,
                                  "subwayTransitCount": 1,
                                  "mapObj": "3:2:322:329@17:2:534:572",
                                  "firstStartStation": "출발역",
                                  "lastEndStation": "도착역"
                                },
                                "subPath": []
                              }
                            ]
                          }
                        }
                        """);

        RouteSearchResult result = adapter.searchPath(126.9, 37.5, 127.0, 37.6);

        // searchPath 단 1회만 호출, loadLane 추가 호출 없음
        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate, times(1)).getForObject(uriCaptor.capture(), eq(String.class));
        assertThat(uriCaptor.getValue().toString())
                .contains("/searchPubTransPathT")
                .contains("SX=126.9")
                .contains("EY=37.6");

        assertThat(result.paths()).hasSize(1);
        // mapObj는 PathInfo에 저장되어 RoutePolylineEnricher가 사용
        assertThat(result.paths().get(0).info().mapObj()).isEqualTo("3:2:322:329@17:2:534:572");
        // laneGraphics는 비어있음 (DB 캐시 기반 enricher가 별도로 채움)
        assertThat(result.paths().get(0).laneGraphics()).isEmpty();
    }
}
