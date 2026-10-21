package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import watoo.grd.nextroute.application.route.config.OdSayProperties;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OdSayApiAdapter implements OdSayApiPort {

    private static final String PROVIDER = "odsay";
    /** 재시도는 최대 1회. 무한·다회 재시도는 상류 장애 시 부하를 키우고 요청 스레드를 오래 붙잡는다. */
    private static final int MAX_ATTEMPTS = 2;
    /** 재시도 backoff 기준값. 요청 시간 예산 안에서만 쓴다(초 단위 sleep 금지). */
    private static final long RETRY_BACKOFF_MS = 100;
    /** 재시도 호출이 성립하려면 최소 이만큼은 남아 있어야 한다. */
    private static final long MIN_RETRY_CALL_MS = 200;

    private final ObjectMapper objectMapper;
    private final OdSayProperties odSayProperties;
    private final RouteSearchMetrics metrics;
    /**
     * searchPath 발신 호출용 빌더(자동설정 주입). 요청별 read timeout 을 걸어야 하므로 호출마다
     * 남은 예산으로 RestClient 를 만든다. 주입 빌더에서 만들어야 트레이싱(ObservationRestClientCustomizer)이
     * 적용되어 외부 호출 span 이 생긴다. 정적 RestClient.builder() 는 계측되지 않는다.
     */
    private final RestClient.Builder restClientBuilder;
    /** 연결 timeout 은 client 단위(2s)로 공유하고, read timeout 만 요청별로 건다. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Override
    public RouteSearchResult searchPath(double sx, double sy, double ex, double ey, long timeoutMs) {
        if (timeoutMs <= 0) {
            throw OdSayApiException.timeout("OdSay 호출 전에 시간 예산이 소진됨");
        }
        URI uri = UriComponentsBuilder.fromHttpUrl(odSayProperties.getBaseUrl() + "/searchPubTransPathT")
                .queryParam("apiKey", odSayProperties.getKey())
                .queryParam("SX", sx)
                .queryParam("SY", sy)
                .queryParam("EX", ex)
                .queryParam("EY", ey)
                .queryParam("OPT", 0)
                .build(true)
                .toUri();
        // apiKey 가 포함된 전체 URI 는 로그에 남기지 않는다.
        log.info("[OdSay] searchPath SX={} SY={} EX={} EY={} budget={}ms", sx, sy, ex, ey, timeoutMs);

        OdSaySearchResponse response = callApi(uri, timeoutMs);

        if (response.getError() != null && !response.getError().isNull()) {
            JsonNode errorNode = response.getError();
            JsonNode first = errorNode.isArray() ? errorNode.get(0) : errorNode;
            String codeStr = first.has("code") ? first.get("code").asText() : "-1";
            String msg = first.has("message") ? first.get("message").asText()
                       : first.has("msg") ? first.get("msg").asText()
                       : "Unknown OdSay error";
            int code;
            try { code = Integer.parseInt(codeStr); } catch (Exception e) { code = -1; }
            log.error("[OdSay] API error: code={}, msg={}", code, msg);
            throw new OdSayApiException(code, msg);
        }

        if (response.getResult() == null) {
            throw new OdSayApiException(-1, "OdSay API returned null result");
        }

        return toRouteSearchResult(response.getResult());
    }

    /**
     * 시간 예산 안에서 호출한다. 계측 단위는 논리적 호출 1건(재시도·backoff 포함).
     *
     * <p>재시도 규칙(Step 2):
     * <ul>
     *   <li>재시도 가능: timeout·연결 실패·5xx — 멱등 GET 이므로 안전하다</li>
     *   <li>재시도 안 함: 4xx, 비즈니스 오류 — 다시 불러도 같은 답이다</li>
     *   <li>재시도는 최대 1회, 그리고 <b>남은 예산이 backoff + 다음 호출 timeout 을 감당할 때만</b></li>
     * </ul>
     * 이전 구현은 모든 예외를 3회까지 재시도하며 2s·4s 를 sleep 했고, 그 6초는 timeout 값과
     * 무관한 고정 비용이었다(RESULTS.md Baseline).
     */
    private OdSaySearchResponse callApi(URI uri, long budgetMs) {
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long remainingMs = remainingMillis(deadlineNanos);
            if (remainingMs <= 0) {
                break;
            }
            try {
                OdSaySearchResponse parsed = sendOnce(uri, remainingMs);
                metrics.recordExternalCall(PROVIDER, "ok", attempt, System.nanoTime() - startNanos);
                return parsed;
            } catch (OdSayApiException e) {
                lastFailure = e;
                boolean lastAttempt = attempt == MAX_ATTEMPTS;
                if (lastAttempt || !isRetryable(e) || !canRetryWithin(deadlineNanos)) {
                    String outcome = e.getKind() == OdSayApiException.Kind.TIMEOUT ? "timeout" : "error";
                    metrics.recordExternalCall(PROVIDER, outcome, attempt, System.nanoTime() - startNanos);
                    throw e;
                }
                log.warn("[OdSay] attempt {}/{} 실패({}) — 재시도: {}",
                        attempt, MAX_ATTEMPTS, e.getKind(), e.getMessage());
                if (!sleepBackoff()) {
                    metrics.recordExternalCall(PROVIDER, "interrupted", attempt, System.nanoTime() - startNanos);
                    throw OdSayApiException.upstream("Interrupted during retry");
                }
            }
        }

        metrics.recordExternalCall(PROVIDER, "timeout", MAX_ATTEMPTS, System.nanoTime() - startNanos);
        throw lastFailure != null ? lastFailure : OdSayApiException.timeout("OdSay 시간 예산 소진");
    }

    /**
     * 요청별 timeout 을 건 단발 호출. 여기서만 HTTP 오류를 Kind 로 분류한다.
     *
     * <p>트레이싱을 위해 주입된(계측된) RestClient.Builder 로 호출한다. read timeout 은 남은 예산으로
     * 요청마다 달라지므로, 공유 HttpClient(연결 2s)를 감싼 JdkClientHttpRequestFactory 에 read timeout 을
     * 걸어 RestClient 를 만든다. 빌더는 싱글턴에서 공유되므로 동시 호출 경합을 피하려 clone 후 설정한다.
     * 상태코드는 직접 검사해야 하므로 onStatus 로 기본 예외 변환을 끈다.
     */
    private OdSaySearchResponse sendOnce(URI uri, long timeoutMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        RestClient client = restClientBuilder.clone().requestFactory(factory).build();
        try {
            ResponseEntity<String> response = client.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })  // 기본 4xx/5xx 예외 억제 — 아래서 직접 분류
                    .toEntity(String.class);
            int status = response.getStatusCode().value();
            if (status >= 500) {
                throw OdSayApiException.upstream("OdSay 5xx: " + status);
            }
            if (status >= 400) {
                // 4xx 는 다시 불러도 같은 답 — 재시도 대상 아님.
                throw new OdSayApiException(status, "OdSay 4xx: " + status);
            }
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw OdSayApiException.upstream("OdSay 빈 응답");
            }
            return objectMapper.readValue(body, OdSaySearchResponse.class);
        } catch (ResourceAccessException e) {
            // read timeout(HttpTimeoutException) → TIMEOUT, 그 외 I/O → UPSTREAM.
            if (e.getCause() instanceof HttpTimeoutException) {
                throw OdSayApiException.timeout("OdSay timeout(" + timeoutMs + "ms)");
            }
            if (e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw OdSayApiException.upstream("OdSay 호출 중 인터럽트");
            }
            throw OdSayApiException.upstream("OdSay I/O 실패: " + e.getMessage());
        } catch (OdSayApiException e) {
            throw e;
        } catch (Exception e) {
            // 파싱 실패 등 — 재호출로 회복될 여지가 있어 상류 장애로 본다.
            throw OdSayApiException.upstream("OdSay 응답 처리 실패: " + e.getMessage());
        }
    }

    private static boolean isRetryable(OdSayApiException e) {
        return e.getKind() == OdSayApiException.Kind.TIMEOUT
                || e.getKind() == OdSayApiException.Kind.UPSTREAM;
    }

    /** backoff 를 쓰고도 의미 있는 재호출이 가능한 만큼 예산이 남았는가. */
    private static boolean canRetryWithin(long deadlineNanos) {
        return remainingMillis(deadlineNanos) > RETRY_BACKOFF_MS + MIN_RETRY_CALL_MS;
    }

    private static long remainingMillis(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    private static boolean sleepBackoff() {
        try {
            // jitter — 동시 실패한 요청들이 같은 순간에 재시도해 상류를 다시 때리지 않게 한다.
            Thread.sleep(RETRY_BACKOFF_MS + ThreadLocalRandom.current().nextLong(50));
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private RouteSearchResult toRouteSearchResult(OdSayResult result) {
        List<PathResult> paths = result.getPath() != null
                ? result.getPath().stream().map(this::toPathResult).toList()
                : Collections.emptyList();

        return new RouteSearchResult(
                result.getSearchType() != null ? result.getSearchType() : 0,
                result.getBusCount() != null ? result.getBusCount() : 0,
                result.getSubwayCount() != null ? result.getSubwayCount() : 0,
                result.getTrainCount() != null ? result.getTrainCount() : 0,
                result.getAirCount() != null ? result.getAirCount() : 0,
                paths
        );
    }


    private PathResult toPathResult(OdSayPath path) {

        List<SubPathResult> subPaths = path.getSubPath() != null
                ? path.getSubPath().stream().map(this::toSubPathResult).toList()
                : Collections.emptyList();

        return new PathResult(
                path.getPathType() != null ? path.getPathType() : 0,
                toPathInfo(path.getInfo()),
                subPaths,
                Collections.emptyList()
        );
    }

    private PathInfo toPathInfo(OdSayPathInfo info) {
        if (info == null) {
            return new PathInfo(0, 0, 0, 0, "", "", null);
        }
        int payment = info.getTotalPayment() != null ? info.getTotalPayment()
                    : info.getPayment() != null ? info.getPayment() : 0;
        int transferCount;
        if (info.getTransitCount() != null) {
            transferCount = info.getTransitCount();
        } else {
            int legs = (info.getBusTransitCount() != null ? info.getBusTransitCount() : 0)
                     + (info.getSubwayTransitCount() != null ? info.getSubwayTransitCount() : 0);
            transferCount = legs > 0 ? legs - 1 : 0;
        }
        return new PathInfo(
                info.getTotalTime() != null ? info.getTotalTime() : 0,
                payment,
                info.getTotalWalk() != null ? info.getTotalWalk() : 0,
                transferCount,
                info.getFirstStartStation() != null ? info.getFirstStartStation() : "",
                info.getLastEndStation() != null ? info.getLastEndStation() : "",
                info.getMapObj()
        );
    }

    private SubPathResult toSubPathResult(OdSaySubPath subPath) {
        List<LaneResult> lanes = subPath.getLane() != null
                ? subPath.getLane().stream().map(this::toLaneResult).toList()
                : Collections.emptyList();

        List<StationResult> stations = Collections.emptyList();
        if (subPath.getPassStopList() != null && subPath.getPassStopList().getStations() != null) {
            stations = subPath.getPassStopList().getStations().stream()
                    .map(this::toStationResult)
                    .toList();
        }

        return new SubPathResult(
                subPath.getTrafficType() != null ? subPath.getTrafficType() : 0,
                subPath.getSectionTime() != null ? subPath.getSectionTime() : 0,
                subPath.getDistance(),
                lanes,
                stations,
                subPath.getStartName(),
                subPath.getEndName(),
                subPath.getStartX(),
                subPath.getStartY(),
                subPath.getEndX(),
                subPath.getEndY(),
                subPath.getTrainType(),
                subPath.getPayment(),
                subPath.getStartID(),
                subPath.getWay(),
                subPath.getWayCode(),
                null,                              // polyline
                subPath.getStartExitNo(),
                subPath.getStartExitX(),
                subPath.getStartExitY(),
                subPath.getEndExitNo(),
                subPath.getEndExitX(),
                subPath.getEndExitY(),
                null,                              // walkSteps
                subPath.getStartLocalStationID(),
                subPath.getEndLocalStationID(),
                subPath.getStartArsID(),
                subPath.getEndArsID(),
                subPath.getEndID(),
                null,                              // walkTotalTimeSeconds (WalkSegmentEnricher가 채움)
                null,                              // transferArrivals (TransferArrivalEnricher가 채움)
                null                               // trainKind: ODsay 는 판정하지 않는다(subway-express S2 는 OTP 전용)
        );
    }

    private LaneResult toLaneResult(OdSayLane lane) {
        return new LaneResult(
                lane.getName(),
                lane.getBusNo(),
                lane.getSubwayCode(),
                lane.getType(),
                lane.getBusID(),
                lane.getBusLocalBlID(),
                null
        );
    }

    private StationResult toStationResult(OdSayStation station) {
        Double x = null;
        Double y = null;
        try { if (station.getX() != null) x = Double.parseDouble(station.getX()); } catch (NumberFormatException ignored) {}
        try { if (station.getY() != null) y = Double.parseDouble(station.getY()); } catch (NumberFormatException ignored) {}
        return new StationResult(
                station.getIndex() != null ? station.getIndex() : 0,
                station.getStationID(),
                station.getStationName(),
                x,
                y
        );
    }
}
