package watoo.grd.nextroute.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 공용 RestTemplate 이 gzip 을 요청하고 받은 gzip 을 풀어 준다 — 서울 위치 API 10배 절감(#371). */
@DisplayName("gzip 응답 인터셉터")
class GzipResponseInterceptorTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server;

    {
        restTemplate.getInterceptors().add(new GzipResponseInterceptor());
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    @DisplayName("Accept-Encoding: gzip 을 보내고, Content-Encoding: gzip 응답을 풀어 읽는다")
    void decompressesGzip() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        server.expect(requestTo("http://api/x")).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "gzip"))
                .andRespond(withSuccess(gzip("{\"a\":\"위치\"}"), MediaType.APPLICATION_JSON).headers(headers));

        assertThat(restTemplate.getForObject("http://api/x", String.class)).isEqualTo("{\"a\":\"위치\"}");
        server.verify();
    }

    @Test
    @DisplayName("서버가 압축하지 않으면 그대로 — 도착 전체 응답은 gzip 을 주지 않는다")
    void passesThroughPlain() {
        server.expect(requestTo("http://api/y"))
                .andRespond(withSuccess("{\"b\":1}", MediaType.APPLICATION_JSON));

        assertThat(restTemplate.getForObject("http://api/y", String.class)).isEqualTo("{\"b\":1}");
    }

    @Test
    @DisplayName("gzip 표시가 있어도 본문이 비면 빈 응답 — 생성에서 EOF 로 터지지 않는다")
    void emptyGzipBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        server.expect(requestTo("http://api/z")).andRespond(withStatus(HttpStatus.NO_CONTENT).headers(headers));

        assertThat(restTemplate.getForObject("http://api/z", String.class)).isNull();
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
