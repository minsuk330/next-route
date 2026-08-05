# ⑤ journey-evaluation-dedupe — 평가 tick 안의 중복 외부 조회 제거

- 상위 인덱스: [../journey-navigation/PHASE-4.md](../journey-navigation/PHASE-4.md) ⑤
- 선행 제약: [../journey-due-evaluator-claim/PLAN.md](../journey-due-evaluator-claim/PLAN.md) §7
- 티어: **M**
- 브랜치: `feat/journey-evaluation-dedupe` (fresh `main` = `5cf40d4` 기준)
- 마이그레이션: **없음**
- 작성일: 2026-08-05

---

## 0. 결론부터

**ML serving 호출과 지하철 역별 실시간 호출에만 캐시 껍데기를 씌운다.** 버스 도착·위치는 이미
합쳐지고 있으므로 건드리지 않는다.

비유하면 — 같은 반 학생 30명이 각자 급식 메뉴를 물으러 가는 대신 한 명이 묻고 칠판에 적는다.
버스 쪽 칠판은 이미 있고(`CachedSearchTimeBusAdapter`), ML·지하철 쪽만 칠판이 없어 30명이 각각
물으러 가고 있었다.

### 착수 전 조사에서 확정된 사실

| 외부 호출 | 현재 dedupe | 근거 |
|---|---|---|
| 버스 도착 `getArrInfoByStop` | **이미 됨** | `CachedSearchTimeBusAdapter:70` Redis TTL 15s + SingleFlight(`stop:route:ord`) |
| 버스 위치 `getBusPosByRtid` | **이미 됨** | 같은 어댑터 `:98`, 키 `routeId` |
| **ML `mlPort.predict(vectors)`** | **없음** | `MlArrivalPredictorAdapter` 에 캐시·SingleFlight 전무 |
| **지하철 `getRealtimeArrivalByStation`** | **없음** | `SubwayRealtimeArrivalReader:77` 이 API 직행 |

ACTIVE 여정 10,000건 · 평가 간격 30초 기준 ML serving 호출이 30초에 10,000회(≈333 rps). 이것이
⑤가 없앨 유일한 큰 구멍이다.

### ④가 남긴 제약을 어떻게 지키는가

④ PLAN §7: "`userReadyAt` 이 여정마다 달라 **원시 조회 응답까지만** 합칠 수 있고, 어느 차량을
탈지 고르는 것(`pickRealtime`·`chooseBoardable`)은 여정별로 남겨야 한다."

- ML feature vector 는 `MlFeatureVectorBuilder.build(reqId, pos, targetSeq, routeId)` 로 만들어지고,
  **시간 feature 조차 `pos.dataTm()`(차량 관측 시각)에서 뽑는다.** 요청 시각·`userReadyAt` 이
  들어가는 자리가 한 곳도 없다 → **ML 예측 결과는 합쳐도 안전하다**
- 앵커는 그 뒤 `SingleTransferPredictor.chooseBoardable(picks, userArrivalAt)`(`:240`)에서만 쓰인다.
  이 선택 로직은 **손대지 않는다**
- `pickRealtime`(`:258`)도 그대로. 실시간 도착 응답은 이미 캐시에서 오고 선택만 여정별로 돈다
- T9·R2(노선당 차량 후보 ≥2대): 원시 응답을 통째로 캐시하므로 후보가 줄지 않는다.
  ML 캐시도 **차량 단위(vector 단위)** 라 후보 수에 영향이 없다

### 기존 SingleFlight 와의 역할 경계 (④가 요구한 결정)

| 대상 | Redis 캐시 | SingleFlight | 이유 |
|---|---|---|---|
| 버스 도착·위치 | 있음(그대로) | 있음(그대로) | 변경 없음 |
| **ML 예측** | **추가** | **추가 안 함** | 호출 단위가 batch 인데 batch 구성이 요청마다 달라(`isValidVehicle` 이 시각 기준으로 차량을 거른다) 배치 키가 거의 재사용되지 않는다. 평가 worker 는 4스레드라 세대 경계의 동시 miss 가 최대 4건 — 얻는 것보다 복잡도가 크다 |
| **지하철 역별** | **추가** | **추가** | 호출 단위가 `stationName` 하나로 명확하고, 조회 경로(tomcat 스레드)도 이 API 를 쓰므로 동시 miss 가 워커 수에 묶이지 않는다 |

### ML 캐시 키를 features 로 잡는 이유

키 = **feature map 전체의 정규화 해시**. `routeId:vehicleId:targetSeq:dataTm` 같은 조합 키보다 낫다:

- 같은 입력이면 같은 예측 — 모델 함수의 정의 그대로다. 키가 곧 입력이면 틀릴 수가 없다
- `dataTm` 이 features(`hour_of_day`·`minute_of_day`·`section_progress` 등)에 이미 반영돼 있어
  **위치 스냅샷이 갱신되면 키가 저절로 죽는다.** TTL 을 짧게 조일 필요가 없다
- 날씨·휴일 feature 가 바뀌어도 자동 무효화된다(`putWeatherFeatures`·`putCalendarFeatures`)
- 서로 다른 차량이 우연히 같은 feature 를 가지면 예측도 같으므로 충돌이 무해하다

**의존 주의**: 이 키가 안정적인 이유는 상류(버스 위치 응답)가 이미 15초 캐시되기 때문이다.
`TRANSFER_ARRIVAL_CACHE_TTL=0` 으로 위치 캐시를 끄면 매 호출 `dataTm` 이 달라져 ML 캐시가 상시
miss 가 된다(오늘 동작으로 퇴화할 뿐 나빠지지는 않는다). 이 결합을 코드 주석·SUMMARY 에 남기고
테스트로 못박는다.

---

## 1. 변경 파일

### 1-A. ML 예측 캐시

| 파일 | 신규/수정 | 내용 |
|---|---|---|
| `src/main/java/watoo/grd/nextroute/application/route/port/out/MlPredictionCachePort.java` | 신규 | 배치 read/write 포트 |
| `src/main/java/watoo/grd/nextroute/application/route/service/MlFeatureCacheKey.java` | 신규 | feature map → 안정 키 |
| `src/main/java/watoo/grd/nextroute/infrastructure/adapter/out/cache/ml/RedisMlPredictionCacheAdapter.java` | 신규 | Redis 구현. 키/TTL/직렬화 단일 소유자 |
| `src/main/java/watoo/grd/nextroute/infrastructure/adapter/out/api/mlpredictor/CachedMlArrivalPredictorAdapter.java` | 신규 | `@Primary` 데코레이터 |
| `src/main/java/watoo/grd/nextroute/application/route/config/MlPredictorProperties.java` | 수정 | `cacheTtlSeconds` 추가 |
| `src/main/java/watoo/grd/nextroute/application/route/observability/RouteSearchMetrics.java` | 수정 | `recordMlCacheAccess`·`recordMlCacheError` 추가 (`ml.prediction.cache.*`) |
| `src/main/resources/application.yaml` | 수정 | `ml.predictor.cache-ttl-seconds` |

**포트 시그니처** (`MlPredictionCachePort`)

```java
public interface MlPredictionCachePort {
    /** 캐시에 있는 것만 담아 돌려준다. 키 순서·개수는 보장하지 않는다. */
    Map<String, MlArrivalPredictorPort.MlPrediction> findAll(List<String> cacheKeys);

    void putAll(Map<String, MlArrivalPredictorPort.MlPrediction> predictions);
}
```

- 배치 read 인 이유: vector 가 노선당 수십 개라 키마다 왕복하면 Redis RTT 가 ML 호출만큼 든다.
  `MGET` 한 번으로 끝낸다
- 값에는 `requestId` 를 넣지 않는다. `requestId` 는 배치 안에서만 유효한 결합 키라 캐시에 저장하면
  다음 배치에서 엉뚱한 vector 에 붙는다. **디코딩할 때 호출 측 `requestId` 로 다시 채운다**

**키 생성** (`MlFeatureCacheKey`)

```java
public final class MlFeatureCacheKey {
    /** 같은 feature map 이면 항상 같은 문자열. 키 순서에 영향받지 않는다. */
    public static String of(Map<String, Object> features);
}
```

- `TreeMap` 으로 정렬 → `name=value` 를 `` 로 이어 붙임 → SHA-256 hex(앞 32자)
- null 값은 빈 문자열. `Double` 은 `String.valueOf` (NaN·정수형 표기 일관성은 같은 JVM 안에서 보장)
- 접두사 `ml:pred:v1:` 은 Redis 어댑터가 붙인다. **feature 스키마가 바뀌면 `v1` 을 올린다**

**데코레이터 동작** (`CachedMlArrivalPredictorAdapter`)

```
predict(vectors[, timeoutMs]):
  1. vector 마다 키 계산 → keysByRequestId
  2. cachePort.findAll(keys)  ← 실패하면 빈 map 으로 간주(로그+metric), 계속 진행
  3. hit 는 requestId 를 호출 측 값으로 다시 붙여 결과 목록에 담는다
  4. miss 인 vector 만 모아 delegate.predict(missing[, timeoutMs])
     - missing 이 비면 delegate 를 아예 부르지 않는다  ← 이 PR 의 목적
  5. delegate 결과를 캐시에 putAll (실패해도 무시)
  6. hit + fresh 를 합쳐 입력 순서대로 돌려준다
```

- `MlPredictionException` 은 그대로 전파한다. 검색 경로가 그 예외로 degradation 을 판단한다
- `predict(vectors, timeoutMs)` 도 같은 경로. 캐시 조회는 예산을 거의 안 쓰지만, 조회 후 남은
  예산이 0 이하면 delegate 를 부르지 않고 hit 만 돌려준다(검색 deadline 계약 유지)
- 빈 입력이면 즉시 `List.of()`
- `@Primary` + `@Component`. 기존 `MlArrivalPredictorAdapter` 는 그대로 두고 생성자 주입으로 감싼다
  (`CachedSearchTimeBusAdapter:50` 과 같은 모양)

**Redis 어댑터**

- 키: `ml:pred:v1:{featureHash}`
- 값: `{"status":"AVAILABLE","secondsToArrival":123.4,"modelVersion":"v3"}` JSON
- TTL: `MlPredictorProperties.cacheTtlSeconds`(기본 30). **0 이하면 read miss·write no-op** —
  `RedisBusRealtimeCacheAdapter:92,120` 과 같은 규칙
- read/write 예외는 삼키고 `metrics.recordCacheError("ml_prediction", "read"|"write")`
- TTL 30초 근거: 상류 위치 캐시가 15초라 세대는 15초마다 바뀐다. 30초면 세대 전환 직후에도 직전
  세대 값이 남아 있어 경계 miss 가 줄고, 그보다 길게 잡을 이유는 없다(메모리만 쓴다)

### 1-B. 지하철 역별 실시간 캐시

| 파일 | 신규/수정 | 내용 |
|---|---|---|
| `src/main/java/watoo/grd/nextroute/application/subway/config/SubwayStationArrivalProperties.java` | 신규 | `cacheTtlSeconds`(10), `joinWaitMs`(1000) |
| `src/main/java/watoo/grd/nextroute/application/subway/port/out/SubwayStationArrivalCachePort.java` | 신규 | 역 단위 read/write |
| `src/main/java/watoo/grd/nextroute/application/subway/observability/SubwayRealtimeMetrics.java` | 신규 | cache hit/miss·single-flight·cache error |
| `src/main/java/watoo/grd/nextroute/infrastructure/adapter/out/cache/subway/RedisSubwayStationArrivalCacheAdapter.java` | 신규 | Redis 구현 |
| `src/main/java/watoo/grd/nextroute/infrastructure/adapter/out/api/subway/CachedSubwayApiAdapter.java` | 신규 | `@Primary` `SubwayApiPort` 데코레이터 |
| `src/main/resources/application.yaml` | 수정 | `realtime.subway.station-arrival.*` |

- `SubwayApiPort` 구현체는 `SeoulSubwayApiAdapter` 하나뿐(확인 완료). 데코레이터는 나머지 3개
  메서드(`getSubwayStationMaster`·`getStationDistance`·`getRealtimeArrival`)를 **그대로 위임**한다.
  전체 수집(`getRealtimeArrival`)은 수집기 소유라 캐시하지 않는다
- `getRealtimeArrivalByStation(stationName)` 만 캐시 + `SingleFlight<String, List<SubwayArrivalInfo>>`
  (키 = `stationName`, `CachedSearchTimeBusAdapter:105` 와 같은 모양)
- 빈 결과는 저장하지 않는다(장애로 인한 빈 목록을 TTL 동안 고착시키지 않기 위해 — 버스 규칙과 동일)
- TTL 10초 근거: 지하철 도착정보는 초 단위로 줄어드는 값이지만,
  `SubwayRealtimeArrivalReader.adjustedSeconds`(`:161`)가 응답에 담긴 `receivedAt` 기준으로 경과분을
  빼 준다(확인 완료). `receivedAt` 은 provider 가 준 값이라 캐시에서 읽어도 보정이 그대로 맞는다 —
  캐시가 남은 시간을 부풀리지 않는다
- 호출부(`SubwayRealtimeArrivalReader:77`)는 **수정하지 않는다**. 포트만 바뀐다

### 1-C. 문서

| 파일 | 내용 |
|---|---|
| `docs/plan/journey-navigation/PHASE-4.md` | ⑤ 절에 "버스는 이미 합쳐져 있었고 실제 범위는 ML·지하철" 을 한 문단으로 반영, PR 표 상태 갱신 |
| `docs/plan/journey-evaluation-dedupe/PLAN.md`·`SUMMARY.md` | 이 PR 에 포함해 커밋 |

---

## 2. 작업 순서

의존이 있는 것만 순서를 지키면 된다. A 와 B 는 서로 독립이라 순서를 바꿔도 된다.

- [ ] **1. 브랜치** — `git checkout main && git pull && git checkout -b feat/journey-evaluation-dedupe`
      (작업트리의 `docs/plan/journey-navigation/MOBILE_PLAN.md` 수정본은 이 PR 과 무관하므로 **스테이징하지 않는다**)
- [ ] **2. 키 생성기 + 단위테스트** — `MlFeatureCacheKey` + `MlFeatureCacheKeyTest`
      → `./gradlew test --tests '*MlFeatureCacheKeyTest'`
- [ ] **3. 포트 + Redis 어댑터** — `MlPredictionCachePort`, `RedisMlPredictionCacheAdapter`,
      `MlPredictorProperties.cacheTtlSeconds`, yaml
      → `./gradlew compileJava`
- [ ] **4. ML 데코레이터 + 단위테스트** — `CachedMlArrivalPredictorAdapter` + `...Test`
      → `./gradlew test --tests '*CachedMlArrivalPredictorAdapterTest'`
- [ ] **5. 지하철 props·포트·캐시 어댑터·메트릭** → `./gradlew compileJava`
- [ ] **6. 지하철 데코레이터 + 단위테스트** — `CachedSubwayApiAdapter` + `...Test`
      → `./gradlew test --tests '*CachedSubwayApiAdapterTest'`
- [ ] **7. 통합 시험(작은 것)** — `JourneyEvaluationDedupeIT`
      → `./gradlew test --tests '*JourneyEvaluationDedupeIT'`
- [ ] **8. 문서 반영** — PHASE-4.md ⑤ 절 갱신
- [ ] **9. 전체 스위트** — `./gradlew test` (약 3분, 기준 1049 tests. 회귀 0)
- [ ] **10. PR** — 제목 `feat(journey): 평가 tick 의 중복 ML·지하철 조회를 합친다`

---

## 3. 테스트 상세

### 3-1. `MlFeatureCacheKeyTest` (단위)

- 키 순서만 다른 두 map 이 같은 키를 만든다
- 값 하나가 다르면 키가 다르다
- null 값이 섞여도 예외 없이 안정적이다
- 같은 map 을 두 번 호출하면 같은 문자열이다(멱등)

### 3-2. `CachedMlArrivalPredictorAdapterTest` (Mockito 단위)

- **전부 hit 이면 delegate 를 부르지 않는다** ← 이 PR 의 핵심 단언
- 부분 hit — vector 3개 중 2개가 캐시에 있으면 delegate 는 **나머지 1개만** 받는다
  (`ArgumentCaptor` 로 전달된 목록 크기·내용 확인)
- 응답 결합 — 결과의 `requestId` 집합이 입력 vector 의 것과 정확히 일치한다
  (캐시 값에 저장하지 않은 `requestId` 를 다시 붙이는 부분의 회귀 방지)
- 캐시 read 가 예외를 던져도 delegate 를 불러 정상 응답한다
- `MlPredictionException` 은 그대로 전파된다
- 빈 입력이면 delegate 를 부르지 않고 빈 목록

> `given(mock.method(...))` 로 같은 mock 을 두 번 스텁하면 두 번째가 첫 답을 실행해 엉뚱한 곳에서
> NPE 가 난다(④a 에서 겪음). **`willAnswer(...).given(mock)` 형태를 쓴다.**

### 3-3. `CachedSubwayApiAdapterTest` (Mockito 단위)

- 같은 역을 두 번 부르면 delegate 는 한 번만 불린다
- 빈 응답은 저장하지 않는다(두 번째 호출도 delegate 를 탄다)
- 나머지 3개 메서드는 캐시 없이 그대로 위임된다
- SingleFlight — 동시 2건이면 role 이 owner/joiner 로 갈린다

### 3-4. `JourneyEvaluationDedupeIT` (작은 통합 시험)

**목적**: 통과 기준("호출 수가 여정 수가 아니라 서로 다른 관심 수에 가깝다")을 코드로 못박는다.

- 위치: `src/test/java/watoo/grd/nextroute/application/journey/JourneyEvaluationDedupeIT.java`
- 형태: `@SpringBootTest` + 개발 Postgres + **Testcontainers Redis**(`redis:7-alpine`, `@DynamicPropertySource`).
  개발자 Redis 를 쓰면 앞선 실행이 남긴 키가 첫 호출을 삼켜 호출 수 단언이 조용히 통과한다.
  `RouteSearchCacheIT` 가 같은 방식이다. setUp/cleanUp 구조는 `JourneyDueEvaluatorIT` 를 따른다
- **mock 은 가장 아래 delegate 만** — `@MockBean SearchTimeBusAdapter`(캐시 어댑터의 위임 대상),
  `@MockBean MlArrivalPredictorAdapter`, `@MockBean PredictionSupportService`(SUPPORTED 고정).
  포트를 목킹하면 우리가 넣은 캐시 데코레이터가 통째로 빠져 시험이 무의미해진다
- 준비: 같은 `(stopId, routeId, boardingSeq)` 를 보는 여정 3건 생성 →
  `journey_leg.start_stop_id`·`journey_leg_route_option.boarding_seq` 를 SQL 로 채운다(④a 에서 확인한
  전제. 없으면 예측기가 "노선·정류장 미해석"으로 조회를 건너뛴다).
  `bus_route_stop` 에 `(routeId, stopId, seq)` 행을 넣어야
  `SingleTransferPredictor:69` 의 `existsByRouteIdAndStopIdAndSeq` 를 통과한다
- 실행: 여정 3건에 대해 `TransferRiskEvaluator.assess(...)` 를 차례로 호출
  (스케줄러 tick 을 돌리지 않는 이유 — 선점·lease 는 ②④ 가 이미 시험한다. 여기서 보려는 것은
  같은 관심을 보는 평가들이 외부를 몇 번 부르는가 하나다)
- 단언:
  - `verify(mlDelegate, times(1)).predict(any())` — 여정 3건, ML 호출 1회
  - `verify(busDelegate, times(1)).getBusPosByRtid(routeId)` — 위치 조회도 1회(기존 캐시 확인)
  - 여정 3건의 판정이 모두 나온다(합쳐도 답이 사라지지 않는다)
- **상류 캐시 의존**: 별도 컨텍스트를 띄우지 않는다. "위치 스냅샷이 갱신되면 ML 캐시 키가 바뀐다"는
  성질은 `MlFeatureCacheKeyTest.TC_관측시각이_바뀌면_키가_바뀐다` 가 단위로 못박는다 — 상류 TTL 을 0
  으로 두면 매 호출 스냅샷이 달라지므로 그 시험이 곧 상시 miss 의 증명이다. 컨텍스트를 하나 더 띄우는
  값이 그만큼 되지 않는다
- 지하철: 같은 역을 세 번 물으면 `SeoulSubwayApiAdapter` 가 1회만 불린다. 여정을 만들지 않고
  `SubwayRealtimeArrivalReader` 를 직접 부른다 — 합치는 단위가 역이라 여정 설정이 결과에 관여하지 않는다
  (버스와 같은 IT 안의 별도 중첩 클래스)
- 매 시험 시작 시 Redis 의 `ml:pred:v1:*`·역 캐시 키를 지운다(시험 간 오염 방지)

**시험이 무거워지면**: `bus_route_stop`·support 준비가 과하게 커지면 `TransferRiskEvaluator` 대신
`SingleTransferPredictor.predict(...)` 를 직접 3회 호출하는 형태로 낮춘다. 단언(호출 1회)은 같다.
그 경우 여정 레벨 확인은 ⑧ 부하 하네스에 넘긴다.

---

## 4. 함정 (구현 중 반드시 지킬 것)

- **`requestId` 를 캐시에 저장하지 마라.** 배치 내부 결합 키라서 다음 배치에 붙으면 결과가 엉뚱한
  차량에 매핑된다. 캐시에는 `status`·`secondsToArrival`·`modelVersion` 만
- **`MlArrivalPredictorAdapter` 는 입력/출력 requestId 집합 일치를 강제한다.** miss 만 골라 부르는
  것은 안전하지만(부분집합 요청), 응답을 합칠 때 입력 vector 전부가 결과에 있어야 한다
- **`secondsToArrival` 은 스냅샷 기준 상대값이다.** 호출 측이 `snap + seconds` 로 절대 시각을 만든다
  (`SingleTransferPredictor:223`). 스냅샷이 키에 반영돼 있으므로 캐시해도 시각이 밀리지 않는다
- **캐시 실패는 절대 요청을 실패시키지 않는다.** Redis 가 죽으면 오늘 동작(전부 직접 호출)으로
  퇴화할 뿐이어야 한다. Redis 어댑터가 삼키고, **데코레이터도 한 번 더 막는다** — 캐시는 예측을
  빠르게 할 뿐 예측의 전제가 아니다
- **`@Primary` 를 붙인 뒤 기존 테스트 확인** — `SingleTransferPredictorTest`·`TransferArrivalEnricherTest`
  는 `@Mock MlArrivalPredictorPort`(Spring 컨텍스트 아님)라 영향이 없다. Spring 컨텍스트에서 이 포트를
  `@MockBean` 하는 테스트는 현재 없다
- **평가기는 기본 off** 이고 이 PR 도 그 상태를 바꾸지 않는다. 캐시는 검색 경로에서 즉시 유효하므로
  **머지만으로 검색 경로 동작이 바뀐다** — TTL 기본값을 보수적으로(ML 30초, 지하철 10초) 두는 이유다
- 새 설정값은 `application.yaml` 에 env override 형태로 적는다
  (`${ML_PREDICTION_CACHE_TTL:30}` · `${SUBWAY_STATION_ARRIVAL_CACHE_TTL:10}`)

---

## 5. 완료 판정

- [ ] `./gradlew test` 전부 통과(기존 1049 tests 회귀 0, 신규 시험 추가)
- [ ] 같은 관심을 보는 여정 3건 → ML delegate 호출 1회가 시험으로 단언됨
- [ ] 같은 역을 보는 여정 2건 → 지하철 API 호출 1회가 시험으로 단언됨
- [ ] Redis 장애·TTL 0 에서 예외 없이 오늘 동작으로 퇴화함이 시험으로 단언됨
- [ ] `PHASE-4.md` ⑤ 절이 실제 범위와 일치
- [ ] 마이그레이션 없음(스키마 무변경)

## 6. 이 PR 이 하지 않는 것

- 부하 실험(여정 10,000건)과 before/after 보고서 — ⑧ `journey-load-harness`
- tick 단위 프리페치(스케줄러가 관심을 미리 모아 조회) — ⑧ 측정에서 필요하다고 나오면 그때.
  "먼저 재고 나중에 넣는다"(PHASE-4 §3)
- 평가 간격 조정 — ⑥ `journey-adaptive-interval`
- 평가기 전용 bulkhead·deadline — ⑦ `journey-evaluator-resilience`
- 버스 도착·위치 캐시 변경 — 이미 목표를 만족한다
