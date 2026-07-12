# 다중 외부 API 기반 경로 검색 안정화 — 실행 플랜

> 포트폴리오 두 번째 주제. 기존 Outbox 사례와 겹치지 않게, 다중 외부 의존성을 조합하는 동기 API의 응답시간·장애 전파·호출량 제어에 초점.
> 핵심 서사: **캐시 Hit은 빠르게 재사용하고, Miss는 하나로 병합하며, 남은 Deadline 안에 격리된 외부 호출만 수행하고, 선택적 보강 실패에도 기본 경로를 반환**.

## 1. 현재 구조와 확인된 문제

### 현재 요청 경로

```text
GET /api/route/search
  → ODSAY 기본 대중교통 경로                    [필수]
  → PostgreSQL 지하철 polyline 보강              [선택]
  → TMAP 보행 구간 보강                          [선택]
  → Redis 버스 도착·위치 조회
       └ miss 시 서울버스 API / ML 예측 보강     [선택]
  → 검색 이력 PostgreSQL 저장                     [부가]
  → 최종 응답
```

### 코드로 확인된 한계

1. **전체 Deadline 부재**
   - 공용 `RestTemplate`의 ODSAY read timeout은 30초이고, 모든 예외를 최대 3회 재시도한다.
   - 재시도 사이 `Thread.sleep(2s, 4s)`가 요청 스레드를 점유한다.
   - 버스 도착 보강에 1.5초 deadline이 있지만, ODSAY·TMAP 뒤에 시작되므로 전체 검색시간을 제한하지 못한다.

2. **TMAP 지연의 공용 자원 전파**
   - `CompletableFuture.supplyAsync()`가 별도 Executor 없이 공용 `ForkJoinPool`을 사용한다.
   - 보행 구간 중 하나라도 느리면 `allOf().join()`이 전체 보강을 대기한다.
   - TMAP은 5초 read timeout 후 1회 재시도할 수 있어 선택적 보강이 기본 경로 응답을 지연시킨다.

3. **Cache Miss 동시 호출 병합 부재**
   - 버스 실시간 정보는 Redis 15초 TTL, 보행 구간은 PostgreSQL 캐시를 사용한다.
   - 하나의 검색 내부 중복은 제거하지만, 여러 요청이 동시에 같은 키를 miss하면 동일 provider를 중복 호출할 수 있다.

4. **필수·선택 의존성의 실패 계약이 불명확**
   - 대중교통 경로 생성에 필수인 ODSAY와, 품질을 높이는 TMAP·실시간 버스·ML 보강이 하나의 동기 흐름에 연결돼 있다.
   - 보강 실패 시 원본을 유지하는 로직은 일부 있지만, 응답의 완전성과 생략 이유를 일관되게 관측하는 계약이 없다.

5. **정상 경로의 캐시 효과가 수치로 검증되지 않음**
   - 수집기가 도착·위치 정보를 Redis에 사전 적재하고, 검색은 cache-aside로 miss를 복구하는 hybrid 구조가 이미 있다.
   - 다만 hit ratio, provider 호출 감소량, hit/miss 지연, TTL 만료 시 stampede가 측정되지 않았다.

## 2. 목표와 비목표

### 목표

- `/api/route/search`에 설정 가능한 **End-to-end Deadline**을 부여하고 단계별로 남은 시간을 전파.
- 외부 provider별 제한된 **Bulkhead**로 동시 호출·대기열·거절을 통제.
- 캐시 miss 시 동일 키의 진행 중 요청을 공유하는 **Single-flight**로 stampede 방지.
- 선택적 보강 실패 시 ODSAY 기본 경로를 반환하는 **부분 실패 허용** 계약 정의.
- Hybrid Cache의 hit ratio·provider 호출 감소·응답시간 효과를 정량 검증.
- 정상·지연·timeout·5xx·Redis 장애·동시 miss 실험을 자동화.

### 비목표

- WebFlux/Reactive Stack 전환
- Kafka·RabbitMQ 등 메시지 브로커 도입
- Redis 분산 락을 이용한 다중 인스턴스 Single-flight
- 경로 검색 결과 전체 캐싱
- 경험적 근거 없이 Circuit Breaker 라이브러리를 일괄 적용
- 외부 API 장애를 숨기고 완전한 응답으로 표현

## 3. 목표 아키텍처

```text
경로 검색 요청
  → RouteSearchContext(requestId, deadlineAt)
  → ODSAY Bulkhead + 남은 시간 내 호출                    [필수]
  → 기본 경로
       ├─ PostgreSQL polyline cache                               [best effort]
       ├─ TMAP cache
       │    └ miss → Single-flight → TMAP Bulkhead → provider       [best effort]
       ├─ Redis 버스 도착·위치
       │    └ miss → Single-flight → Bus Bulkhead → provider        [best effort]
       │                                      └ 필요 시 ML fallback
       └─ 검색 이력 비동기 저장                           [best effort]
  → FULL / PARTIAL / BASE_ONLY / FAILED
```

### 의존성 등급과 실패 계약

| 단계 | 등급 | 지연·실패 시 처리 |
|---|---|---|
| ODSAY 대중교통 경로 | 필수 | 남은 Deadline 내 성공하지 못하면 빠르게 504/502 |
| ODSAY `-98` 도보 전용 | 조건부 필수 | TMAP 도보 경로 성공 시만 응답, 실패 시 원 오류 유지 |
| 지하철 polyline | 선택 | 빈 polyline/원본 경로 유지 + PARTIAL |
| TMAP 보행 상세 | 선택 | ODSAY 기본 보행 구간 유지 + PARTIAL |
| 버스 도착·위치 | 선택 | 배차간격/`NONE·LIMITED·ERROR` 표시 + PARTIAL |
| ML 예측 | 선택 | 실시간 값 또는 미제공 + PARTIAL |
| 검색 이력 | 부가 | 응답에서 분리, 포화 시 drop + metric |

## 4. 공통 설계 규칙

### Deadline

- `RouteSearchContext`는 `deadlineAt`과 `remainingMillis()`를 소유한다.
- 경과시간 계산은 wall clock 변경의 영향을 받지 않도록 단조 시계(`System.nanoTime` 추상)를 사용한다.
- 각 단계는 시작 전 남은 시간을 확인하고, provider timeout은 `min(단계 상한, 남은 시간)`을 넘지 않도록 한다.
- `Future.orTimeout()`만으로는 이미 시작된 blocking HTTP I/O가 종료되지 않을 수 있다. HTTP connect/read timeout도 Deadline 안으로 맞추고, 취소 후 스레드가 남는지 테스트한다.
- 초기 기본값은 baseline 측정 후 확정하며, 코드와 포트폴리오에 임의 수치를 먼저 약속하지 않는다.

#### Step 2 구현 결정 게이트 — 요청별 timeout

현재 ODSAY가 사용하는 `RestTemplate` timeout은 request factory/client 단위의 고정값이므로, 요청마다 변하는 `remainingMillis()`를 그대로 적용할 수 없다. Step 2 시작 전 아래 선택지를 spike로 검증하고 결정한다.

| 선택지 | 방식 | 장점 | 한계·판단 기준 |
|---|---|---|---|
| A. JDK `HttpClient` 전용 adapter | `HttpRequest.timeout(min(stageLimit, remaining))` 설정 | 요청별 남은 시간을 직접 반영, 추가 dependency 없음, Toss mTLS에서 이미 사용 중 | JSON/error mapping·metric interceptor를 adapter가 직접 소유. **mandatory ODSAY 권장안** |
| B. Timeout preset client | short/medium/long 2~3개 client 중 `timeout ≤ remaining` 인 프리셋 선택 | 기존 Spring 동기 client 구조 유지, 구현 변경 작음 | coarse 근사로 시간 예산을 충분히 쓰지 못하거나, 남은 시간보다 큰 preset은 사용 불가 |
| C. `RestClient` 전환 | provider 전용 `RestClient` + request-level timeout을 지원하는 request factory/client 구성 | 현재 Spring API 스타일과 통일 | **`RestClient` 전환만으로는 per-request timeout이 해결되지 않음**. 하부 client가 요청별 설정을 지원하는지 먼저 검증 |

초기 권장은 **ODSay에 A를 적용**하여 mandatory 단계의 실제 HTTP I/O를 Deadline에 맞추는 것이다. TMAP·Bus·ML은 기존의 짧은 고정 timeout과 bounded Executor/Bulkhead를 유지하되, 응답 Deadline 후에도 남는 orphan 호출 수·점유 시간을 측정한다. 그 수치가 Executor 회수를 방해하면 해당 provider도 A 또는 검증된 C로 이관한다.

### Bulkhead

- provider별로 실행 자원을 분리한다. TMAP 병렬 보강은 전용 bounded Executor를 필수로 사용한다.
- ODSAY·Bus·ML은 현재 호출 방식을 고려해 전용 Executor 또는 `Semaphore.tryAcquire`를 선택하되, 무제한 대기는 허용하지 않는다.
- 대기열은 짧게 두고 포화 시 선택 보강을 거절한다. 큰 대기열로 과부하를 숨기지 않는다.
- 상한은 임의로 키우지 않고 provider Rate Limit, HTTP Connection Pool, VPS CPU/메모리, 부하 실험의 p95/p99로 튜닝한다.

### Single-flight

- 키: 버스 도착 `stopId:routeId:seq`, 버스 위치 `routeId`, TMAP `WalkCacheKey`.
- 첫 miss 요청만 owner가 되어 provider를 호출하고, 후속 요청은 같은 `CompletableFuture`를 공유한다.
- 대기자는 자신의 남은 Deadline까지만 결과를 기다린다.
- 성공·실패·취소 모두 `finally` 성격의 정리 로직으로 in-flight map에서 제거해 누수를 방지한다.
- 실패 결과는 장기 공유하지 않고, 기존 정책대로 빈 결과도 실시간 버스 Redis에는 저장하지 않는다.
- 현재 단일 인스턴스 제약을 반영해 우선 JVM 로컬로 구현한다. 향후 다중 인스턴스에서는 인스턴스 수만큼 provider 호출이 남는 한계를 문서화한다.

### Retry

- ODSAY/TMAP의 "모든 예외 재시도"를 제거한다.
- 멱등 요청이고, 일시적 연결 오류·재시도 가능한 5xx이며, backoff와 다음 호출 timeout을 제외한 시간이 남았을 때만 1회 재시도한다.
- 비즈니스 오류, 대부분의 4xx, provider quota 초과, 남은 시간이 부족한 timeout은 재시도하지 않는다.
- backoff에 jitter를 두되, 요청 스레드의 장시간 `sleep`은 제거한다.

### Hybrid Cache

- 기존 정책을 유지한다: 수집기 refresh-ahead + 검색 cache-aside + 짧은 TTL + non-empty 결과만 저장 + Redis 장애 시 miss 처리.
- Cache hit은 provider quota와 검색별 외부 호출 상한을 소모하지 않는 현재 계약을 유지한다.
- TTL은 실시간성과 provider 호출 감소의 trade-off로 측정하며, 초기 범위는 5/10/15/30초 A/B 후 결정한다.
- 이 캐시는 경로 검색 뿐 아니라 버스 알림의 발송 조건 판단도 공유한다. 따라서 TTL 상한은 검색 hit ratio로만 결정하지 않고, 알림 도착 판단의 허용 오차·false positive/negative·데이터 age를 함께 검증한다. 알림 정확도 기준을 넘는 TTL은 hit ratio가 좋아도 채택하지 않는다.
- 일반 상황의 TTL을 늘리기 전에 Single-flight로 만료 순간의 호출 폭증을 먼저 제거한다.

## 5. 관측성

Micrometer/Actuator로 다음 저카디널리티 지표를 수집한다. 좌표·노선 ID·requestId는 tag로 사용하지 않는다.

`FULL/PARTIAL/BASE_ONLY/FAILED`의 **내부 완전성 판정은 Step 0의 메트릭용으로 최소 구현**한다. Step 5는 이 판정 결과를 클라이언트 응답 메타데이터로 노출하고 API 계약을 문서화하는 후속 단계이다. Step 5를 수행하지 않아도 내부 outcome metric은 유지된다.

| 지표 | 주요 tag | 의미 |
|---|---|---|
| `route.search.duration` | `outcome=full/partial/base/failed` | 전체 p50/p95/p99 |
| `route.search.stage.duration` | `stage`, `outcome` | ODSAY/TMAP/bus/ML/DB 단계별 지연 |
| `route.search.deadline.exceeded` | `stage` | 시간 예산 초과 지점 |
| `route.search.degraded` | `component`, `reason` | 부분 응답 비율과 원인 |
| `bus.realtime.cache.requests` | `type=arrival/position`, `result=hit/miss/error` | Redis 캐시 효과 |
| `external.api.calls` | `provider`, `outcome`, `retry` | 실제 provider 호출과 결과 |
| `external.api.duration` | `provider`, `outcome` | provider별 p95/p99 |
| `singleflight.requests` | `provider`, `role=owner/joiner` | 병합된 요청 수 |
| `bulkhead.active` | `provider` | 현재 실행 수 |
| `bulkhead.queue` | `provider` | 현재 대기 수 |
| `bulkhead.rejected` | `provider` | 포화로 생략된 보강 수 |

## 6. 단계별 실행 계획

| 단계 | 목표 | 핵심 변경 | 완료 근거 |
|---|---|---|---|
| 0 | Baseline | 행동 변경 없이 단계별 Timer·provider 호출·cache hit/miss 계측 | 정상/지연 시나리오 before 결과 |
| 1 | Hybrid Cache 검증 | TTL/collector priming on·off A/B, Redis 장애 fallback 테스트 | hit ratio·호출 감소·hit/miss p95 |
| 2 | Deadline·Retry | `RouteSearchContext`, provider timeout 조정, 조건부 1회 재시도 | mandatory 지연도 설정 상한 내 실패 |
| 3 | Stampede 방지 | 버스·TMAP miss에 JVM Single-flight 적용 | 동일 키 N건 동시 miss 시 provider 호출 1회/instance |
| 4 | Bulkhead | TMAP 전용 bounded Executor, ODSAY/Bus/ML 동시성 상한 | provider별 실측 최대 동시성 ≤ 설정값 |
| 5 | 부분 실패 계약 | 완전성 메타데이터, 선택 보강 fallback, 검색 로그 분리 | 선택 provider 장애 중 기본 경로 반환 |
| 6 | 튜닝·재측정 | 동시성·queue·TTL·Deadline 조정 | before/after 결과와 포트폴리오 원자료 |

### Step 0 — Baseline과 실험 하네스

1. `RouteSearchService`의 전체/단계별 Timer와 메트릭용 최소 완전성 판정(`FULL/PARTIAL/BASE_ONLY/FAILED`) 추가. 이 단계에서는 응답 DTO를 변경하지 않는다.
2. MockWebServer로 ODSAY·TMAP 응답 지연·HTTP 상태·응답 없음을 제어.
3. 버스·ML은 포트 테스트 대역 또는 Mock server로 호출 수와 지연을 기록.
4. Redis/PostgreSQL은 Testcontainers로 실제 직렬화·TTL·DB 경로까지 포함.
5. 시나리오: 전체 20ms, TMAP 5s, Bus 5xx, ODSAY timeout, Redis 중단, 동일 키 동시 요청.

**중요**: 이 단계에서는 timeout·retry·스레드 구조를 바꾸지 않고 before를 먼저 보존한다.

### Step 1 — 기존 Hybrid Cache 계측과 계약 고정

1. 수집기 사전 적재와 검색 cache-aside가 같은 키/직렬화 계약을 사용하는지 IT로 고정.
2. Cache hit 시 delegate·provider·quota·per-search call budget을 소모하지 않음을 검증.
3. 빈 결과·에러·차단 결과가 Redis에 저장되지 않음을 검증.
4. Redis read/write 실패 시 검색 요청은 provider 경로로 계속되며 오류 metric을 남김.
5. TTL 조건별 hit ratio·provider 호출 수·데이터 age를 측정해 기본값 유지/변경 근거를 만듦.
6. 같은 TTL 조건에서 버스 알림 발송 조건 판단의 false positive/negative와 도착 오차를 함께 측정해, 검색 hit ratio 개선이 알림 품질을 훼손하지 않는 범위에서만 TTL을 선택.

### Step 2 — End-to-end Deadline과 조건부 Retry

1. 컨트롤러/서비스 진입에서 `RouteSearchContext`를 한 번 생성.
2. ODSAY·TMAP·Bus·ML 포트에 절대 deadline 또는 남은 시간을 전달하는 계약 추가.
3. ODSAY 전용 HTTP client를 공용 30초 client에서 분리하고, API key가 포함된 URI 로그를 제거/마스킹.
4. ODSAY 3회 일괄 재시도와 TMAP 4xx 재시도를 제거하고, 시간이 남은 일시 오류만 1회 재시도.
5. mandatory ODSAY timeout은 일관된 504, 재시도 불가 외부 응답은 502로 매핑.

### Step 3 — Single-flight

1. 공통 `SingleFlight<K,V>` 추상을 작게 두고 owner/joiner/cleanup 규칙을 단위 테스트.
2. `CachedSearchTimeBusAdapter`의 Redis miss 뒤 delegate 호출을 도착·위치 키로 병합.
3. `WalkSegmentEnricher`의 JPA cache miss 뒤 TMAP 호출을 `WalkCacheKey`로 병합.
4. joiner는 Step 2에서 정의한 자신의 남은 Deadline까지만 owner 결과를 대기.
5. 100건 동시 miss, owner 실패, joiner timeout, 완료 후 다시 miss 시 재호출을 검증.

### Step 4 — Provider Bulkhead

1. TMAP 전용 `ThreadPoolTaskExecutor`: core/max/queue/rejection을 설정으로 분리.
2. ODSAY·Bus·ML은 기존 HTTP 동시성과 예상 트래픽을 기준으로 독립 상한 적용. 기존 Bus Semaphore는 공통 관측 계약으로 통합.
3. 슬롯 획득 대기도 남은 Deadline을 넘지 않게 하고, 선택 provider는 포화 시 즉시 degradation.
4. TMAP 포화 중 ODSAY 성공률·일반 API 응답·다른 provider 동시성이 영향받지 않는지 검증.

### Step 5 — 부분 실패 계약과 응답 투명성

1. Step 0에서 메트릭용으로 정의한 내부 완전성 판정을 응답에 하위 호환적인 메타데이터로 노출:
   - `FULL`: 요청된 보강 완료
   - `PARTIAL`: 일부 보강 생략
   - `BASE_ONLY`: ODSAY 기본 경로만 제공
   - `FAILED`: 필수 경로 생성 실패
2. `degradedComponents` 및 저카디널리티 `reason`(`TIMEOUT`, `BULKHEAD_FULL`, `UPSTREAM_5XX`, `CACHE_ERROR` 등)을 정의.
3. 클라이언트가 기본 경로를 표시하되, 비어 있는 보강 필드를 정상 값으로 오인하지 않도록 API 계약 문서화.
4. 검색 이력 저장은 bounded 비동기 실행 또는 완료 이벤트로 응답 경로에서 분리. 포화 시 요청 실패 대신 drop metric을 남김.

### Step 6 — 부하·장애 주입 재측정

#### 핵심 실험

| 실험 | 조건 | 확인 결과 |
|---|---|---|
| 정상 기준 | 모든 provider 20ms | FULL p50/p95/p99, 단계별 시간 |
| Cache Hit/Miss | Redis TTL on/off, priming on/off | hit ratio, provider 호출 수, hit/miss p95 |
| Stampede | 동일 버스/보행 키 100건 동시 miss | Single-flight 전/후 provider 호출 수 |
| Slow TMAP | TMAP 5초, 여러 보행 구간 | Deadline 내 PARTIAL, TMAP 동시성 상한 |
| Bus 5xx/timeout | 버스 API 오류 | 기본 경로 반환, 상태/원인 표시 |
| ODSAY timeout | 응답 없음 | Deadline 내 504, 잘못된 재시도 없음 |
| Bulkhead 포화 | TMAP 느린 요청 집중 | 포화 보강 거절, 다른 provider 격리 |
| Redis 중단 | read/write 실패 | miss fallback, 검색 가용성 유지, 호출 증가 측정 |
| 로그 저장 포화 | DB 지연/queue full | 응답 지연 비전파, drop 관측 |

#### 실험 환경 기록

- 로컬 CPU/메모리, JVM, Spring Boot, PostgreSQL, Redis, Hikari Pool, 외부 API Mock 지연, 동시 요청 수를 결과 문서에 명시.
- 서버 스레드 상한(`server.tomcat.threads.max`)도 함께 기록하고, 실험 동시성이 이 상한보다 작도록 유지한다. 상한을 넘으면 provider Bulkhead가 아니라 Tomcat 요청 큐 대기를 측정하게 되어 격리 효과를 확인할 수 없다.
- 외부 운영망·실제 provider Rate Limit은 로컬 실험에서 제외했음을 분리 표기.
- 로컬 장애 주입 후 VPS에서는 실 provider 부하를 만들지 않고, 소량 canary 요청으로 timeout·metric·degradation 계약만 확인.

## 7. MVP 범위와 후속 범위

### 포트폴리오 최소 완성 범위

- Step 0 Baseline·관측성
- Step 1 Hybrid Cache 효과 측정
- Step 2 End-to-end Deadline·Retry 교정
- Step 3 Single-flight
- Step 4 TMAP 전용 Executor·provider Bulkhead
- Step 6 정상·지연·Stampede·Redis 장애 before/after

위 범위만으로도 "호출량 감소 + 응답시간 상한 + 자원 격리"를 수치로 증명할 수 있다.

### 후속/선택 범위

- Step 5의 FULL/PARTIAL/BASE_ONLY 응답 메타데이터
- 검색 이력 비동기 저장
- 지속 장애가 실측된 provider에 한한 Circuit Breaker

후속 범위는 클라이언트 API 계약·데이터 유실 정책·장애 패턴의 추가 판단이 필요하므로 핵심 개선을 막지 않게 분리한다.

## 8. 완료 기준

### 기능

- 선택 provider(TMAP·Bus·ML) 지연·실패 중에도 ODSAY 기본 경로를 설정된 Deadline 내 반환.
- 필수 provider ODSAY 지연은 Deadline 내 일관된 timeout 응답으로 종료.
- 동일 키 N건 동시 miss에서 실제 provider 호출은 인스턴스당 1회.
- provider별 최대 동시 호출이 설정값을 넘지 않고, 포화가 다른 provider Executor로 전파되지 않음.
- Redis 장애 시 캐시는 miss로 degrade하고 기본 검색 기능은 유지.
- FULL/PARTIAL/BASE_ONLY와 생략 이유가 응답 또는 관측 지표에서 일관되게 확인됨.

### 성능·운영

- before/after에서 같은 실험 조건·건수·Mock 지연을 사용.
- 최종 Deadline, provider별 timeout, Bulkhead 크기, queue 크기, TTL은 실험 결과로 확정하고 근거를 기록.
- p50/p95/p99, Deadline 초과율, FULL/PARTIAL/FAILED 비율, provider 호출 수, cache hit ratio, bulkhead reject를 한 결과표에 보존.
- 메트릭 tag에 사용자·좌표·노선·requestId 등 고카디널리티 값이 없음.

### 포트폴리오 원자료

- 정상 성능 뿐 아니라 Slow TMAP·ODSay timeout·Redis 장애·Stampede 수치를 모두 보존.
- Cache 효과와 장애 격리 효과를 섞지 않고 각각 before/after로 측정.
- 로컬 Mock 환경과 VPS canary 확인 범위를 명확히 구분.
- 단일 VPS에서 JVM 로컬 Single-flight를 선택한 이유와 다중 인스턴스 확장 시 한계를 트레이드오프로 기록.

## 9. 권장 PR 단위

| PR | 범위 | 독립 롤백 |
|---|---|---|
| PR-A | 메트릭 + MockWebServer baseline | 계측 코드/테스트 제거 |
| PR-B | Hybrid Cache IT + 효과 측정 | 계측/테스트 제거 |
| PR-C | End-to-end Deadline + ODSAY/TMAP Retry 교정 | 행동 변경은 `git revert`; timeout 수치만 설정 복구. 신구 retry 루프를 운영 코드에 병존시키는 플래그는 두지 않음 |
| PR-D | JVM Single-flight | 기능 플래그로 Single-flight off |
| PR-E | Provider Bulkhead + TMAP 전용 Executor | provider별 플래그/Executor 설정 복구 |
| PR-F | 부분 응답 메타데이터 + 로그 분리(선택) | 추가 필드/비동기 로깅 off |
| PR-G | 부하·장애 주입 튜닝 + RESULTS | 코드 변경 없음 |

## 10. 리스크와 트레이드오프

- **짧은 Deadline**: PARTIAL 비율이 늘고 상세 보행/도착 정보가 자주 빠질 수 있음. FULL 비율과 p95를 함께 보고 결정.
- **짧은 TTL**: 실시간성은 높지만 provider 호출이 증가. hit ratio와 데이터 age를 함께 측정.
- **Single-flight**: 이 프로세스 안의 stampede만 방지. 현재 단일 인스턴스에 맞는 선택이며 수평 확장 시 재검토.
- **Bulkhead 거절**: 일부 보강 품질을 포기해 전체 안정성을 확보. reject를 실패가 아닌 관측 가능한 degradation으로 취급.
- **부분 응답**: 클라이언트가 생략된 값을 정상 값으로 오인할 수 있음. 추가 메타데이터와 API 문서 필수.
- **비동기 검색 로그**: 응답 지연은 줄지만 포화 시 로그 손실 가능. 검색 로그가 필수 비즈니스 데이터가 아닌지 먼저 확정.
- **구현 복잡도**: Deadline·Bulkhead·Single-flight를 한 PR에 모두 넣지 않고, 각 단계의 독립 수치와 롤백 경로를 유지.

## 11. 최종 결과 문서 틀

`docs/plan/route-search-resilience/RESULTS.md`에 아래 구조로 기록한다.

```markdown
# 경로 검색 외부 의존성 개선 결과

## 테스트 환경
## Baseline
## Hybrid Cache A/B
## Stampede 재현·개선
## Deadline·Slow Provider 실험
## Bulkhead 포화 실험
## Redis 장애 실험
## 최종 설정값과 선정 근거
## 한계·운영 제외 범위
## 포트폴리오 요약 원문
```
