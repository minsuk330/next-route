package watoo.grd.nextroute.common.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공용 RestTemplate 의 호스트당 연결 수(#427 처방 2차). 기본(Apache HttpClient 5)은 호스트당 5 라, 평가 워커 16 이
 * 같은 호스트를 부르면 5 에서 줄을 선다 — 워커를 늘린 효과가 숨은 상한에 막힌다.
 */
@DisplayName("공용 RestTemplate 연결 풀")
class RestTemplatePoolTest {

    private HttpServer server;
    private ExecutorService serverPool;
    private ExecutorService clients;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (serverPool != null) {
            serverPool.shutdownNow();
        }
        if (clients != null) {
            clients.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 호스트로 16 건을 동시에 보내면 5 를 넘겨 동시에 나간다")
    void allowsMoreThanFiveConcurrentCallsPerHost() throws Exception {
        assertThat(maxConcurrent(ApiClientConfig.pooledRestTemplate(new RestTemplateBuilder(), 32, 64), 16))
                .isGreaterThan(5);
    }

    @Test
    @DisplayName("대조: 호스트당 5 로 두면 동시 5 에서 막힌다(기본값이 만드는 숨은 상한)")
    void defaultLikePoolCapsAtFive() throws Exception {
        assertThat(maxConcurrent(ApiClientConfig.pooledRestTemplate(new RestTemplateBuilder(), 5, 25), 16))
                .isEqualTo(5);
    }

    private int maxConcurrent(RestTemplate restTemplate, int calls) throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        serverPool = Executors.newFixedThreadPool(32);
        server.setExecutor(serverPool);
        server.createContext("/", exchange -> {
            int now = inFlight.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x";

        clients = Executors.newFixedThreadPool(calls);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> restTemplate.getForObject(url, String.class), clients));
        }
        for (CompletableFuture<String> f : futures) {
            assertThat(f.get()).isEqualTo("ok");
        }
        return max.get();
    }
}
