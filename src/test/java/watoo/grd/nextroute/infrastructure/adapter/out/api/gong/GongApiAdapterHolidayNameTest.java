package watoo.grd.nextroute.infrastructure.adapter.out.api.gong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GongApiAdapterHolidayNameTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private GongApiAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        adapter = new GongApiAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "apiKey", "KEY");
    }

    private static String xml(String items) {
        return "<response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>"
                + "<body><items>" + items + "</items><numOfRows>50</numOfRows><pageNo>1</pageNo><totalCount>1</totalCount></body></response>";
    }

    @Test
    @DisplayName("공휴일 이름을 캐시해 돌려준다 — 설날, 대체공휴일(설날)")
    void holidayNames() {
        for (int m = 1; m <= 12; m++) {
            String month = String.format("%02d", m);
            String items = m == 2
                    ? "<item><dateKind>01</dateKind><dateName>설날</dateName><isHoliday>Y</isHoliday><locdate>20270206</locdate><seq>1</seq></item>"
                    + "<item><dateKind>01</dateKind><dateName>대체공휴일(설날)</dateName><isHoliday>Y</isHoliday><locdate>20270209</locdate><seq>1</seq></item>"
                    : "";
            server.expect(requestTo(containsString("solYear=2027&solMonth=" + month)))
                    .andRespond(withSuccess(xml(items), MediaType.APPLICATION_XML));
        }
        assertThat(adapter.holidayName(LocalDate.of(2027, 2, 6))).contains("설날");
        assertThat(adapter.holidayName(LocalDate.of(2027, 2, 9))).contains("대체공휴일(설날)");
        assertThat(adapter.isHoliday(LocalDate.of(2027, 2, 9))).isTrue();
        assertThat(adapter.holidayName(LocalDate.of(2027, 2, 10))).isEmpty();
        server.verify();   // 12번만 — 두 번째 조회부터 캐시
    }

    @Test
    @DisplayName("한 달이라도 실패하면 그 해는 폴백, 1시간 동안 다시 부르지 않는다(매 요청 12회 동기 호출 방지)")
    void failureIsNegativelyCached() {
        server.expect(ExpectedCount.once(), requestTo(containsString("solYear=2028&solMonth=01")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        adapter.setClock(Clock.fixed(Instant.parse("2028-01-01T00:00:00Z"), ZoneOffset.UTC));

        assertThat(adapter.isHoliday(LocalDate.of(2028, 1, 1))).isTrue();          // 하드코딩 폴백(신정)
        assertThat(adapter.holidayName(LocalDate.of(2028, 1, 1))).isEmpty();      // 폴백은 이름을 모른다
        assertThat(adapter.isHoliday(LocalDate.of(2028, 3, 4))).isFalse();
        server.verify();   // 첫 실패 뒤 추가 호출 없음
    }
}
