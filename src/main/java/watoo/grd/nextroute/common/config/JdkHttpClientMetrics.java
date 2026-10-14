package watoo.grd.nextroute.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * JDK {@link HttpClient} 로 직접 보내는 외부 호출을 {@code http.client.requests} 로 기록한다.
 *
 * <p><b>왜 필요한가.</b> 요청마다 남은 시간 예산을 소켓 대기까지 걸려고 일부 어댑터(검색·평가기의
 * 서울 버스 실시간, ML 예측)는 Spring 클라이언트를 거치지 않고 JDK 클라이언트를 직접 쓴다. 그 호출은
 * 관측이 붙지 않아 지표에 아예 없었다 — 정작 평가기가 기다리는 호출의 지연을 볼 수 없었다(#415).
 *
 * <p><b>Spring 이 남기는 것과 이름·tag 열쇠를 똑같이 맞춘다.</b> Prometheus 는 이름이 같은 지표의
 * tag 열쇠가 다르면 받아 주지 않는다. 그래서 {@code client.name·method·uri·status·outcome·exception·
 * error} 일곱 개를 같은 값 규칙으로 채운다. {@code uri} 는 {@link ExternalApiObservationConvention}
 * 과 같은 규칙으로 가린다.
 *
 * <p>기한 초과({@code HttpTimeoutException})는 {@code status=IO_ERROR}·{@code exception=HttpTimeoutException}
 * 으로 남는다. 예산을 다 써서 끊은 호출과 상대가 실패한 호출을 가를 수 있다.
 */
@Component
public class JdkHttpClientMetrics {

    static final String NAME = "http.client.requests";
    private static final String NONE = "none";

    private final MeterRegistry registry;

    public JdkHttpClientMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 보내고, 걸린 시간을 결과와 함께 남긴다. 예외는 그대로 다시 던진다.
     */
    public <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                    HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        Timer.Sample sample = Timer.start(registry);
        try {
            HttpResponse<T> response = client.send(request, handler);
            sample.stop(timer(request, String.valueOf(response.statusCode()),
                    outcomeOf(response.statusCode()), NONE));
            return response;
        } catch (IOException e) {
            sample.stop(timer(request, "IO_ERROR", "UNKNOWN", e.getClass().getSimpleName()));
            throw e;
        } catch (InterruptedException | RuntimeException e) {
            sample.stop(timer(request, "CLIENT_ERROR", "UNKNOWN", e.getClass().getSimpleName()));
            throw e;
        }
    }

    private Timer timer(HttpRequest request, String status, String outcome, String exception) {
        URI uri = request.uri();
        return Timer.builder(NAME)
                .tags(Tags.of(
                        "client.name", uri.getHost() == null ? NONE : uri.getHost(),
                        "method", request.method(),
                        "uri", ExternalApiObservationConvention.maskPath(uri.getRawPath()),
                        "status", status,
                        "outcome", outcome,
                        "exception", exception,
                        "error", exception))
                .register(registry);
    }

    private static String outcomeOf(int statusCode) {
        HttpStatusCode code = HttpStatusCode.valueOf(statusCode);
        if (code.is2xxSuccessful()) {
            return "SUCCESS";
        }
        if (code.is4xxClientError()) {
            return "CLIENT_ERROR";
        }
        if (code.is5xxServerError()) {
            return "SERVER_ERROR";
        }
        if (code.is3xxRedirection()) {
            return "REDIRECTION";
        }
        if (code.is1xxInformational()) {
            return "INFORMATIONAL";
        }
        return "UNKNOWN";
    }
}
