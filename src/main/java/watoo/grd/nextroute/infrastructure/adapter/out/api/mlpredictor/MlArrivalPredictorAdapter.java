package watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.exception.MlPredictionException;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.dto.MlPredictRequestDto;
import watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.dto.MlPredictResponseDto;

import java.util.ArrayList;
import java.net.URI;
import watoo.grd.nextroute.common.config.JdkHttpClientMetrics;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ML serving(FastAPI) /predict 호출 어댑터. 전용 단축 timeout RestClient, no-retry.
 * 응답은 requestId로 재결합하며 ID 집합 일치·status별 필드 불변식을 강제한다.
 */
@Slf4j
@Component
public class MlArrivalPredictorAdapter implements MlArrivalPredictorPort {

    private static final String PATH = "/predict";

    private final RestClient restClient;
    private final MlPredictorProperties properties;
    private final JdkHttpClientMetrics httpMetrics;
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MlArrivalPredictorAdapter(
            @Qualifier("mlPredictorRestClient") RestClient restClient,
            MlPredictorProperties properties,
            JdkHttpClientMetrics httpMetrics) {
        this.restClient = restClient;
        this.properties = properties;
        this.httpMetrics = httpMetrics;
    }

    @Override
    public List<MlPrediction> predict(List<MlFeatureVector> vectors) {
        return predictInternal(vectors, null);
    }

    @Override
    public List<MlPrediction> predict(List<MlFeatureVector> vectors, long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new MlPredictionException(-1, "ML 호출 전에 시간 예산이 소진됨", true);
        }
        return predictInternal(vectors, timeoutMs);
    }

    private List<MlPrediction> predictInternal(List<MlFeatureVector> vectors, Long timeoutMs) {
        if (vectors == null || vectors.isEmpty()) {
            return List.of();
        }

        List<MlPredictRequestDto.Item> items = vectors.stream()
                .map(v -> new MlPredictRequestDto.Item(v.requestId(), v.features()))
                .toList();

        MlPredictRequestDto request = new MlPredictRequestDto(items);
        MlPredictResponseDto response = timeoutMs == null
                ? callApi(request)
                : callApiWithin(request, timeoutMs);
        return toResults(vectors, response);
    }

    /** 검색 요청마다 달라지는 잔여 예산을 HTTP 요청 timeout으로 적용한다. */
    private MlPredictResponseDto callApiWithin(MlPredictRequestDto body, long timeoutMs) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + PATH))
                    .timeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpMetrics.send(httpClient, request,
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 500) {
                throw new MlPredictionException(status, "ML serving 5xx", true);
            }
            if (status >= 400) {
                throw new MlPredictionException(status, "ML serving 4xx", false);
            }
            return objectMapper.readValue(response.body(), MlPredictResponseDto.class);
        } catch (MlPredictionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MlPredictionException(-1, "ML serving interrupted", true, e);
        } catch (Exception e) {
            throw new MlPredictionException(-1, "ML serving call failed: " + e.getMessage(), true, e);
        }
    }

    private MlPredictResponseDto callApi(MlPredictRequestDto body) {
        try {
            return restClient.post()
                    .uri(properties.getBaseUrl() + PATH)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        // 503(모델 미로드) 포함 — 일시 장애로 보고 재시도 가능 표시.
                        throw new MlPredictionException(res.getStatusCode().value(),
                                "ML serving 5xx: " + res.getStatusText(), true);
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        // 422(요청 계약 위반) 등 — 재시도 무의미.
                        throw new MlPredictionException(res.getStatusCode().value(),
                                "ML serving 4xx: " + res.getStatusText(), false);
                    })
                    .body(MlPredictResponseDto.class);
        } catch (MlPredictionException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            boolean retryable = e.getStatusCode().is5xxServerError();
            throw new MlPredictionException(e.getStatusCode().value(),
                    "ML serving HTTP error: " + e.getMessage(), retryable, e);
        } catch (ResourceAccessException e) {
            throw new MlPredictionException(-1, "ML serving I/O timeout: " + e.getMessage(), true, e);
        } catch (Exception e) {
            // 타임아웃/연결 실패 등 I/O 원인은 재시도 가능. 그 외는 재시도 무의미.
            boolean retryable = hasIoCause(e);
            throw new MlPredictionException(-1, "ML serving call failed: " + e.getMessage(), retryable, e);
        }
    }

    private static boolean hasIoCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    /** ID 집합 일치·유일성 + status별 필드 불변식 강제 후 입력 순서대로 결과 반환. */
    private List<MlPrediction> toResults(List<MlFeatureVector> vectors, MlPredictResponseDto response) {
        if (response == null || response.results() == null) {
            throw new MlPredictionException(-1, "ML serving null response body", false);
        }

        Set<String> inputIds = new HashSet<>();
        for (MlFeatureVector v : vectors) {
            inputIds.add(v.requestId());
        }

        java.util.Map<String, MlPrediction> byId = new java.util.HashMap<>();
        for (MlPredictResponseDto.Result r : response.results()) {
            MlPrediction prediction = toPrediction(r);
            if (!inputIds.contains(prediction.requestId())) {
                throw new MlPredictionException(-1,
                        "ML serving returned unknown request_id: " + prediction.requestId(), false);
            }
            if (byId.put(prediction.requestId(), prediction) != null) {
                throw new MlPredictionException(-1,
                        "ML serving returned duplicate request_id: " + prediction.requestId(), false);
            }
        }
        if (byId.size() != inputIds.size()) {
            throw new MlPredictionException(-1,
                    "ML serving result count " + byId.size() + " != request count " + inputIds.size(), false);
        }

        List<MlPrediction> ordered = new ArrayList<>(vectors.size());
        for (MlFeatureVector v : vectors) {
            ordered.add(byId.get(v.requestId()));
        }
        return ordered;
    }

    private MlPrediction toPrediction(MlPredictResponseDto.Result r) {
        if (r.requestId() == null) {
            throw new MlPredictionException(-1, "ML serving result missing request_id", false);
        }
        MlPredictionStatus status;
        try {
            status = MlPredictionStatus.valueOf(r.status());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new MlPredictionException(-1,
                    "ML serving unknown status '" + r.status() + "' for " + r.requestId(), false);
        }

        Double seconds = r.secondsToArrival();
        if (status == MlPredictionStatus.AVAILABLE) {
            if (seconds == null || seconds.isNaN() || seconds.isInfinite() || seconds < 0) {
                throw new MlPredictionException(-1,
                        "ML serving AVAILABLE with invalid seconds for " + r.requestId(), false);
            }
        } else if (seconds != null) {
            throw new MlPredictionException(-1,
                    "ML serving " + status + " must have null seconds for " + r.requestId(), false);
        }
        return new MlPrediction(r.requestId(), status, seconds, r.modelVersion());
    }
}
