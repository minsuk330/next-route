package watoo.grd.nextroute.infrastructure.adapter.out.api.regionalsubway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalStationSeedCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 본문은 2026-11-16 실측(서면 scode 119, 1008행)을 줄였다. */
class BusanTrainTimeAdapterTest {

    private static final String BASE = "https://apis.data.go.kr/B551542/trainTime";

    private final RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(new ObjectMapper());
    private MockRestServiceServer server;
    private BusanTrainTimeAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new BusanTrainTimeAdapter(builder.build(), new ObjectMapper(), catalog, BASE, "KEY");
    }

    @Test
    @DisplayName("역 전체를 한 번에 받아 행의 dayType 으로 나눈다. endcode 는 같은 노선 scode 로 종착역 이름이 된다")
    void splitsByDayTypeAndResolvesTerminal() {
        server.expect(requestTo(allOf(containsString("/getTrainTime"), containsString("scode=119"),
                        containsString("numOfRows=1500"))))
                .andRespond(withSuccess("""
                        {"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[
                          {"scode":"119","sname":"서면(1)","line":"1","trainno":"1001","arrtime":"05:37:15","dayType":"1","updown":"0","endcode":"95"},
                          {"scode":"119","sname":"서면(1)","line":"1","trainno":"1002","arrtime":"05:37:15","dayType":"1","updown":"1","endcode":"134"},
                          {"scode":"119","sname":"서면(1)","line":"1","trainno":"1101","arrtime":"06:00:00","dayType":"2","updown":"0","endcode":"101"},
                          {"scode":"119","sname":"서면(1)","line":"1","trainno":"1201","arrtime":"24:00:00","dayType":"3","updown":"0","endcode":"95"},
                          {"scode":"119","sname":"서면(1)","line":"1","trainno":"9999","arrtime":"07:00:00","dayType":"3","updown":"0","endcode":"777"}
                        ]},"numOfRows":1500,"pageNo":1,"totalCount":5}}
                        """, MediaType.APPLICATION_JSON));

        RegionalStationSeed seomyeon = catalog.findByName("BS1", "서면");
        List<RegionalDeparture> out = adapter.fetch(seomyeon);

        server.verify();
        assertThat(out).containsExactly(
                new RegionalDeparture(RegionalDayType.WD, 20235, "다대포해수욕장"),
                new RegionalDeparture(RegionalDayType.WD, 20235, "노포"),
                new RegionalDeparture(RegionalDayType.SA, 21600, "신평"),
                new RegionalDeparture(RegionalDayType.HO, 86400, "다대포해수욕장"),
                new RegionalDeparture(RegionalDayType.HO, 25200, "#scode:777"));   // 모르는 종착 → 적재가 unmatched 로 버린다
    }

    @Test
    @DisplayName("3호선 endcode 318 = 대저, 4호선 415 = 안평(역 코드와 다른 종착 코드, 운영 실측)")
    void endcodeAliases() {
        server.expect(requestTo(containsString("scode=409")))
                .andRespond(withSuccess("""
                        {"header":{"resultCode":"00"},"body":{"items":{"item":[
                          {"scode":"409","line":"4","arrtime":"06:00:00","dayType":"1","updown":"0","endcode":"401"},
                          {"scode":"409","line":"4","arrtime":"06:05:00","dayType":"1","updown":"1","endcode":"415"}
                        ]},"totalCount":2}}
                        """, MediaType.APPLICATION_JSON));
        List<RegionalDeparture> out = adapter.fetch(catalog.findByProviderCode("BS4", "409"));
        assertThat(out).extracting(RegionalDeparture::terminalName).containsExactly("미남", "안평");

        RegionalStationSeed yeonsan = catalog.findByProviderCode("BS3", "305");
        server.reset();
        server.expect(requestTo(containsString("scode=305")))
                .andRespond(withSuccess("""
                        {"header":{"resultCode":"00"},"body":{"items":{"item":
                          {"scode":"305","line":"3","arrtime":"06:00:00","dayType":"1","updown":"1","endcode":"318"}
                        },"totalCount":1}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(adapter.fetch(yeonsan)).extracting(RegionalDeparture::terminalName).containsExactly("대저");
    }
}
