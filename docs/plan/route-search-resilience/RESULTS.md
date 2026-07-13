# 경로 검색 외부 의존성 개선 결과

> 진행 상태: **Step 0(Baseline) · Step 1(Hybrid Cache) · Step 2(Deadline·Retry) · Step 3(Single-flight) 완료** — PR-A ~ PR-D. 이후 단계는 이 문서에 계속 누적한다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| Machine | Apple M3 Pro, 18GB RAM |
| JVM | Java 17 (Gradle toolchain) |
| 외부 provider | ODsay·TMAP = MockWebServer (지연·HTTP 상태 제어) |
| DB·Redis | 미사용 — 이 단계는 외부 호출 구간만 격리해 측정 |
| 측정 테스트 | Step 0·2 = `RouteSearchDeadlineIT`(baseline 은 PR-A 시점 `RouteSearchBaselineIT`), Step 1 = `RouteSearchCacheIT` |
| ODsay read timeout(테스트) | 300ms |
| ODsay read timeout(운영) | 30s (`ApiClientConfig`) |

운영 네트워크, 실 provider rate limit, 실제 응답 크기는 이 수치에 포함하지 않는다.
버스·ML·Redis 캐시를 포함한 측정은 Step 1(PR-B)에서 Testcontainers 로 별도 수행한다.

## Baseline — 현재 구조

동작을 바꾸지 않고 계측만 추가한 상태에서 4개 시나리오를 측정했다.

| 시나리오 | 전체 소요 | outcome | ODsay 단계 | TMAP(walk) 단계 | provider 호출 |
|---|---:|---|---:|---:|---|
| 정상 (ODsay 20ms / TMAP 20ms) | 60ms | FULL | 29ms | 27ms | odsay 1, tmap 1 |
| TMAP 5s 지연 | 5,197ms | FULL | 103ms | 5,072ms | odsay 1, tmap 1 |
| TMAP 500 (재시도 on) | 302ms | BASE_ONLY | 31ms | 263ms | odsay 1, **tmap 2** |
| ODsay 무응답 | 6,934ms | FAILED | 6,933ms | — | **odsay 3** |

### 1. 선택 보강 지연이 전체 응답에 100% 전파된다

TMAP 은 품질을 높이는 선택 보강인데, 5초 지연이 그대로 전체 응답시간이 됐다(5,197ms 중 walk 단계 5,072ms).
전체 요청에 시간 예산(deadline)이 없고, 보행 보강 결과를 `allOf().join()` 으로 무조건 기다리기 때문이다.

→ Step 2(End-to-end Deadline) + Step 4(Bulkhead)의 before.

### 2. 부분 실패 허용은 이미 성립하지만, 재시도 비용은 그대로 문다

TMAP 500 에서도 ODsay 기본 경로는 정상 반환됐다(BASE_ONLY). 다만 현재 정책은 5xx·4xx 를 가리지 않고
1회 재시도하므로 provider 호출이 2회 발생했고, 그 시간이 응답에 실렸다.

→ Step 2(조건부 Retry)의 before.

### 3. ODsay 재시도의 고정 backoff 가 요청 스레드를 붙잡는다

ODsay 무응답 시 3회 시도 + `Thread.sleep(2s)` + `Thread.sleep(4s)` 로 6,934ms 를 소비했다.
**backoff 6초는 read timeout 값과 무관한 고정 비용**이다. 테스트는 read timeout 을 300ms 로 줄여 이 사실만 분리했다.

운영 설정(read timeout 30s)으로 환산하면 최악의 경우는 다음과 같다.

```text
30s × 3회 + 2s + 4s ≈ 96s
```

즉 필수 provider 가 응답하지 않으면 요청 하나가 요청 스레드를 1분 반 넘게 점유할 수 있다.

→ Step 2(ODsay 전용 client 분리 + Deadline + 조건부 1회 재시도)의 before.

### 4. 계측으로 확인된 현재 완전성 분포

`FULL/PARTIAL/BASE_ONLY/FAILED` 판정은 이 단계에서 메트릭용으로만 구현했다(응답 DTO 변경 없음).
선택 provider 실패가 `BASE_ONLY` 로, 필수 provider 실패가 `FAILED` 로 각각 분리 관측됨을 확인했다.

## Step 1 — Hybrid Cache 계측과 계약 고정 (PR-B)

측정 테스트: `RouteSearchCacheIT` (Testcontainers Redis 7, 직렬화·TTL·만료 포함)

### 고정한 계약

| 항목 | 결과 |
|---|---|
| 수집기 write ↔ 검색 read | 같은 키(`bus:search:arr:{stopId}:{routeId}:{seq}`)·직렬화로 왕복, 값 보존 |
| cache hit | provider 호출 0회 — 검색 호출 cap·quota 미소모 |
| 저장 대상 | non-empty 결과만. 빈 결과·`BLOCKED`·`LIMITED`·`ERROR` 는 저장되지 않음 |
| Redis 장애 | read 실패 → miss 로 degrade, 검색은 provider 경로로 계속. 응답 정상 |

"빈 결과를 저장하지 않는다"가 중요한 이유는, 차단·오류로 인한 빈 응답을 TTL 동안 캐시에 고착시키면
provider 가 회복해도 그 시간만큼 계속 빈 결과를 돌려주기 때문이다.

Redis 장애는 검색 입장에서 miss 지만, 원인을 hit ratio 분모에 섞으면 캐시 효과를 오독하게 된다.
그래서 `bus.realtime.cache.errors{type,op}` 를 별도 지표로 분리했다.

### TTL A/B

동일 키 1개, 20초 동안 250ms 간격 조회.

| TTL | 요청 | hit | provider 호출 | hit ratio | hit 시점 age p50 | age max |
|---|---:|---:|---:|---:|---:|---:|
| off (캐시 우회) | 79 | 0 | 79 | 0% | — | — |
| 5s | 77 | 73 | 4 | 94.8% | 3s | 5s |
| 15s (운영값) | 78 | 76 | 2 | 97.4% | 6s | 15s |

해석:

- 캐시가 provider 호출을 **79회 → 2회(운영 TTL 15s)로 줄인다**. 같은 정류장·노선을 여러 요청이
  동시에 조회하는 검색 특성상 이 감소가 곧 일일 예산 절약이다.
- provider 호출 수는 `ceil(window / TTL)` 로 수렴한다. TTL 을 3배로 늘려도 hit ratio 는
  94.8% → 97.4% 로 2.6%p 밖에 오르지 않는다. **TTL 을 키워서 얻는 호출 감소는 빠르게 체감된다.**
- 반면 hit 데이터의 **최대 staleness 는 정확히 TTL** 이다(5s → 5s, 15s → 15s).

### TTL 상한을 결정하는 것은 검색이 아니라 알림이다

이 캐시는 경로 검색만 쓰는 게 아니라 **버스 알림의 발송 조건 판단**도 공유한다.
따라서 TTL 을 30초로 늘리면 hit ratio 는 조금 오르지만, 알림이 보는 도착 정보가 최대 30초 낡을 수 있다.
도착 알림은 "곧 온다"를 알리는 기능이라 30초 오차는 기능 자체를 무의미하게 만든다.

즉 TTL 상한은 검색의 hit ratio 곡선이 아니라 **알림이 허용하는 도착 판단 오차**가 정한다.
현재 15초는 이 제약 안에서 호출 감소를 거의 다 얻는 지점이다(97.4%). TTL 을 더 늘릴 이유가 없다.

### 남은 한계 — TTL 만료 순간의 stampede

이 실험은 요청이 1개일 때의 hit ratio 다. 같은 키를 여러 요청이 **동시에** miss 하면
그 수만큼 provider 를 호출한다(현재 병합 없음). TTL 을 늘리는 것보다 이 폭증을 먼저 없애야 한다.

→ Step 3(Single-flight)의 before.

## Step 2 — End-to-end Deadline과 조건부 Retry (PR-C)

측정 테스트: `RouteSearchDeadlineIT` (Step 0 baseline 과 동일한 MockWebServer 시나리오)

### 구현 결정 — 요청별 timeout

`RestTemplate` 의 timeout 은 client 단위 고정값이라 요청마다 달라지는 잔여 예산을 반영할 수 없다.
PLAN.md §4 의 선택지 중 **A(JDK `HttpClient`)** 를 ODsay 에 적용해
`HttpRequest.timeout(min(단계 상한, 남은 시간))` 으로 예산을 HTTP I/O 까지 전파했다.
TMAP 은 기존 고정 timeout 을 유지하되, **호출자가 잔여 예산까지만 기다리도록** 바꿨다
(늦게 도착한 응답은 캐시에만 반영되고 이번 응답에는 실리지 않는다).

### 재시도 교정

| 항목 | before | after |
|---|---|---|
| ODsay 재시도 | 모든 예외 3회 | timeout·5xx 만, 최대 1회, 예산이 남을 때만 |
| ODsay backoff | `sleep(2s)` + `sleep(4s)` = 6s 고정 | 100ms + jitter, 예산 안에서만 |
| TMAP 재시도 | 4xx 포함 1회 | 4xx 제외(timeout·5xx 만) |
| 실패 응답 | 전부 400 | timeout→504, 상류 장애→502, 비즈니스(-98 등)→400 |

### before / after

테스트 예산: 전체 1,500ms · ODsay 800ms · TMAP 400ms

| 시나리오 | before | after | 결과 |
|---|---:|---:|---|
| TMAP 5s 지연 | 5,197ms | **438ms** | BASE_ONLY, `degraded[walk=timeout]`, 기본 경로 정상 반환 |
| ODsay 무응답 | 6,934ms | **805ms** | FAILED(504), ODsay 호출 3회 → **1회** |
| ODsay 500 | — | — | 호출 3회 → **2회**(원 호출 + 재시도 1회), 502 |
| TMAP 400 | 2회 호출 | **1회 호출** | 재시도 없음, 기본 경로 유지 |
| 정상 | 60ms | 유사 | 예산은 정상 응답을 방해하지 않음 |

해석:

- 선택 보강의 지연이 더 이상 응답 시간을 끌지 않는다. TMAP 이 5초 걸려도 응답은 단계 상한에서 잘리고,
  사용자는 기본 경로를 즉시 받는다(**5,197ms → 438ms**).
- 필수 provider 가 죽어도 요청 스레드가 예산 안에서 풀린다(**6,934ms → 805ms**).
  운영 설정 환산으로 최악 96초였던 점유가 사라졌다.
- 재시도가 "혹시 되나" 식 3회에서 **근거 있는 1회**로 줄었다. 4xx 는 다시 물어도 같은 답이므로 재시도하지 않는다.

### 남은 것

TMAP 응답을 예산 안에서 못 받으면 이번 응답에서 생략하지만, **진행 중인 호출 자체는 계속 살아 있다**(orphan).
현재는 공용 `ForkJoinPool` 을 쓰기 때문에 느린 TMAP 이 쌓이면 공용 자원을 잠식할 수 있다.

→ Step 4(Bulkhead)의 before.

## Step 3 — Single-flight (PR-D)

측정 테스트: `RouteSearchStampedeIT` (Testcontainers Redis)

Step 1 에서 남겨둔 한계 — 같은 키를 **동시에** miss 하면 그 수만큼 provider 를 호출한다 — 를 없앤다.
첫 요청(owner)만 실제로 호출하고, 나머지는 그 결과를 공유받는다(joiner).

### 동일 키 100건 동시 miss (provider 응답 200ms)

| 항목 | before | after |
|---|---:|---:|
| provider 호출 | 100회 | **1회** |
| owner / joiner | — | 1 / 99 |
| join timeout | — | 0 |
| 응답 데이터 | — | 100건 모두 동일 값 수신 |

두 번째 물결(같은 키 100건 재요청)은 owner 가 채운 캐시에 그대로 hit 해 **provider 호출 0회**다.

### 설계 판단

- **owner 는 호출 스레드에서 직접 실행한다.** 별도 executor 로 넘기면 스레드만 더 쓰고 대기 구조는 같다.
- **joiner 는 자신의 남은 예산까지만 기다린다.** owner 가 느리다고 joiner 의 deadline 을 어길 수는 없다.
  예산 안에 못 받으면 `LIMITED` — "조회를 못 했다"이지 "버스가 없다"가 아니다(이 구분은 이미 `BusQueryResult` 가 갖고 있다).
- **owner 의 실패를 joiner 에게 전파하지 않는다.** 실패를 공유하면 한 번의 장애가 합류한 요청 전부의 실패가 된다.
- **JVM 로컬 범위다.** 현재 단일 인스턴스 배포라 이걸로 충분하다. 다중 인스턴스로 가면
  인스턴스 수만큼 호출이 남는다 — 그때는 Redis 분산 락이나 상류 캐시가 필요하다.
  단일 인스턴스에서 분산 락을 먼저 넣는 것은 얻는 것 없이 Redis 왕복만 늘린다.

## 부수 수정

- ODsay 검색 URI 전체를 `log.info` 로 남기던 코드가 apiKey 를 로그에 노출하고 있었다.
  좌표만 남기도록 바꿨다(`OdSayApiAdapter`).

## 다음 단계

| 단계 | 내용 | 이 문서에 추가할 수치 |
|---|---|---|
| Step 4 (PR-E) | Provider Bulkhead | provider별 최대 동시성, 포화 시 격리 |
| Step 6 (PR-G) | 부하·장애 주입 재측정 | before/after 최종 결과표 |
