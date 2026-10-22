package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 서울 API 키가 로그·예외 메시지에 새지 않는다.
 *
 * <p>키는 URL 경로에 들어간다({@code /api/subway/{키}/json/…}). 경로를 찍던 경고와 RestTemplate 예외 메시지
 * ("I/O error on GET request for …")가 키를 그대로 운영 로그(Loki)에 남겼다(2026-10-22 실측 30일 242줄).
 */
@DisplayName("서울 지하철 API 키 가리기")
@ExtendWith(OutputCaptureExtension.class)
class SeoulSubwayApiAdapterRedactionTest {

    private static final String ARRIVAL_KEY = "arrival-secret-0123456789";
    private static final String NON_SUCCESS = """
            {"status":500,"code":"ERROR-337","message":"일일 호출 한도 초과","total":0}
            """;

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final SeoulSubwayApiAdapter adapter = new SeoulSubwayApiAdapter(restTemplate, new ApiCallBlocker(),
            ARRIVAL_KEY, "position-secret-0123456789", "http://swopenapi.test/api/subway",
            "master-secret-0123456789", "route-secret-0123456789", "http://openapi.test");

    @Test
    @DisplayName("실패 응답 경고·I/O 예외 경고·최종 오류·던지는 예외 어디에도 키가 없다")
    void arrivalFailuresNeverCarryTheKey(CapturedOutput output) {
        server.expect(once(), anything()).andRespond(withSuccess(NON_SUCCESS, MediaType.APPLICATION_JSON));
        server.expect(once(), anything()).andRespond(withException(new IOException("connect timed out")));
        server.expect(once(), anything()).andRespond(withException(new IOException("connect timed out")));

        assertThatThrownBy(() -> adapter.getRealtimeArrivalByStation("논현"))
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(ARRIVAL_KEY).contains("****");
                    assertThat(e.getCause()).isNull();
                });

        server.verify();
        assertThat(output.getAll()).doesNotContain(ARRIVAL_KEY)
                .contains("/api/subway/****/json/realtimeStationArrival")
                .contains("I/O error on GET request");
    }

    @Test
    @DisplayName("모든 키를 가리고, 8자 미만 값은 키로 보지 않는다")
    void redactsEveryKeyButIgnoresShortValues() {
        assertThat(adapter.redact("http://x/api/subway/" + ARRIVAL_KEY + "/json?p=position-secret-0123456789"))
                .isEqualTo("http://x/api/subway/****/json?p=****");
        assertThat(adapter.redact(null)).isNull();

        SeoulSubwayApiAdapter shortKeys = new SeoulSubwayApiAdapter(restTemplate, new ApiCallBlocker(),
                "", "key", "http://swopenapi.test/api/subway", "", "", "http://openapi.test");
        assertThat(shortKeys.redact("monkey business")).isEqualTo("monkey business");
    }
}
