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
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSourceException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 본문은 2026-11-16 실측(대전 시청 MTRDJ10011)을 줄였다. */
class TagoSubwayTimetableAdapterTest {

    private static final String BASE = "http://apis.data.go.kr/1613000/SubwayInfo";
    private static final RegionalStationSeed CITY_HALL = new RegionalStationSeed(
            "DJ1-011", "DJ1", "대전1호선", "시청", 10, "MTRDJ10011", "011", "1:x", 36.35, 127.38);

    private MockRestServiceServer server;
    private TagoSubwayTimetableAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new TagoSubwayTimetableAdapter(builder.build(), new ObjectMapper(), BASE, "KEY");
    }

    private void respond(String day, String ud, String items, int total) {
        server.expect(requestTo(containsString("dailyTypeCode=" + day + "&upDownTypeCode=" + ud)))
                .andRespond(withSuccess("""
                        {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                         "body":{"items":%s,"numOfRows":500,"pageNo":1,"totalCount":%d}}}
                        """.formatted(items, total), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("평일 01, 휴일 03 × U, D 를 부른다. 한 건(객체), 0건(\"\"), depTime \"0\" 을 처리한다. 토요일 02 는 부르지 않는다")
    void fetchesWeekdayAndHoliday() {
        respond("01", "U", """
                {"item":[{"depTime":"053200","endSubwayStationNm":"판암(대전대)"},
                         {"depTime":"054100","endSubwayStationNm":"판암(대전대)"}]}""", 2);
        respond("01", "D", """
                {"item":{"depTime":"053800","endSubwayStationNm":"반석"}}""", 1);
        respond("03", "U", """
                {"item":[{"depTime":"0","endSubwayStationNm":"판암(대전대)"},
                         {"depTime":"053200","endSubwayStationNm":"판암(대전대)"}]}""", 2);
        respond("03", "D", "\"\"", 0);

        List<RegionalDeparture> out = adapter.fetch(CITY_HALL);

        server.verify();
        assertThat(out).containsExactly(
                new RegionalDeparture(RegionalDayType.WD, 19920, "판암(대전대)"),
                new RegionalDeparture(RegionalDayType.WD, 20460, "판암(대전대)"),
                new RegionalDeparture(RegionalDayType.WD, 20280, "반석"),
                new RegionalDeparture(RegionalDayType.HO, 19920, "판암(대전대)"));
    }

    @Test
    @DisplayName("포털 미등록 키는 blocking — 같은 원천의 남은 역을 건너뛰게 한다")
    void unregisteredKeyBlocks() {
        server.expect(requestTo(containsString("GetSubwaySttnAcctoSchdulList")))
                .andRespond(withSuccess("""
                        {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
                         "returnReasonCode":"30"}}}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.fetch(CITY_HALL))
                .isInstanceOfSatisfying(RegionalSourceException.class, e -> assertThat(e.blocking()).isTrue());
    }
}
