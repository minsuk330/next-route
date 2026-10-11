package watoo.grd.nextroute.common.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 외부 API 응답을 gzip 으로 받는다. 공용 {@code RestTemplate} 의 기본 클라이언트(JDK HttpClient)는
 * {@code Accept-Encoding} 을 보내지도, 압축을 풀지도 않는다.
 *
 * <p>서울 지하철 위치 API 는 gzip 을 주면 10배 작다(2026-10-11 실측: 1호선 26.8KB → 2.0KB). VPS 수신 트래픽은 월 2TB 상한이라
 * 위치 수집 주기를 줄이려면 먼저 이것부터다. 서버가 압축하지 않으면({@code Content-Encoding} 없음) 그대로 넘긴다 — 도착 전체
 * 응답(정적 JSON 파일로 리다이렉트)이 그렇다.
 *
 * <p>호출하는 쪽이 {@code Accept-Encoding} 을 직접 정했으면 건드리지 않는다.
 */
public class GzipResponseInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!request.getHeaders().containsKey(HttpHeaders.ACCEPT_ENCODING)) {
            request.getHeaders().set(HttpHeaders.ACCEPT_ENCODING, "gzip");
        }
        ClientHttpResponse response = execution.execute(request, body);
        String encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (encoding == null || !encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            return response;
        }
        return new Decompressed(response);
    }

    /** 압축을 푼 응답. 길이·인코딩 헤더는 원래 바이트의 것이라 뺀다. */
    private static final class Decompressed implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final HttpHeaders headers;
        private InputStream body;

        private Decompressed(ClientHttpResponse delegate) {
            this.delegate = delegate;
            HttpHeaders copy = new HttpHeaders();
            copy.putAll(delegate.getHeaders());
            copy.remove(HttpHeaders.CONTENT_ENCODING);
            copy.remove(HttpHeaders.CONTENT_LENGTH);
            this.headers = HttpHeaders.readOnlyHttpHeaders(copy);
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                // 본문이 비었으면(204·HEAD 등) GZIPInputStream 이 생성에서 EOF 로 터진다 — 빈 스트림으로 둔다.
                PushbackInputStream raw = new PushbackInputStream(delegate.getBody(), 1);
                int first = raw.read();
                if (first == -1) {
                    body = InputStream.nullInputStream();
                } else {
                    raw.unread(first);
                    body = new GZIPInputStream(raw);
                }
            }
            return body;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
