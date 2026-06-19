# PR2 — Java ML Client (hexagonal)

> 환승 지점 도착예측 통합 — Java가 ML serving을 호출하는 outbound 어댑터.
> 브랜치 독립(fresh main). **API 계약은 [PR1](./PR1-fastapi-ml-serving.md)의 `/predict` 기준.** 후속: [PR3](./PR3-transfer-arrival-enricher.md)

## Context

PR1의 FastAPI `/predict`를 Java 백엔드가 호출할 수 있어야 한다. 기존 ODSAY/TMAP outbound 어댑터 패턴
(port/adapter/properties/exception)을 그대로 따라 ML 예측 클라이언트를 만든다. 아직 경로검색에 연결하지는
않는다(연결은 PR3).

## 작업

### `MlPredictorProperties` (`@ConfigurationProperties("ml.predictor")`)
필드: `baseUrl`, `timeoutMs`, `enabled`.
```yaml
# application.yaml
ml:
  predictor:
    base-url: ${ML_PREDICTOR_URL:http://localhost:8001}
    enabled: ${ML_PREDICTOR_ENABLED:false}
    timeout-ms: 3000
```

### Port `MlArrivalPredictorPort`
`List<MlPrediction> predict(List<MlFeatureVector> vectors)` — **batch 1콜**.
- `MlFeatureVector` = `requestId` + `train.py` feature 이름 동일 키.
- `MlPrediction` = `requestId, status, secondsToArrival, modelVersion`.
- **결과는 응답 순서가 아니라 `requestId`로 재결합**. item별 status(`AVAILABLE`/`UNSUPPORTED_ROUTE`)는
  그대로 상위에 전달. HTTP 422(envelope/feature)만 예외로 throw.
- **재결합 불변식**(부분응답/serving 결함이 다른 lane 덮어쓰기·조용한 누락 유발 방지):
  - 입력 `requestId` 집합 == 출력 집합 **정확히 일치 + 유일**(중복·누락·미요청 ID 있으면 `MlPredictionException`).
  - status별 필드 불변식: `AVAILABLE`이면 `secondsToArrival` non-null·**finite·non-negative**,
    `UNSUPPORTED_ROUTE`면 null. 위반 시 배치 전체 `MlPredictionException`.

### Adapter `infrastructure/.../api/mlpredictor/MlArrivalPredictorAdapter`
- `RestClient`(TMAP 어댑터 패턴), 전용 단축 timeout, **no-retry**.
- 4xx/5xx → `MlPredictionException`, timeout → 예외(상위에서 fallback).
- request/response DTO (`mlpredictor/dto/`).

### `MlPredictionException`
`statusCode` + `retryable`.

### bean 충돌 방지
공용 `RestClient` bean이 이미 존재 → 신규 bean은 `@Qualifier("mlPredictorRestClient")`로 명시.
(PR3의 버스 검색용은 `@Qualifier("busRealtimeRestClient")`.) 기존 TMAP client도 qualifier로 고정해
주입 모호성 제거.

## Verification
- `MlArrivalPredictorAdapterTest` (MockWebServer, 기존 `TmapPedestrianAdapterTest` 패턴):
  정상 예측 / 503 / 타임아웃 분기.
