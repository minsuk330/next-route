# 경로 검색 외부 의존성 개선 결과

> **Step 0~6 완료** — PR-A ~ PR-G + Step 5(응답 완전성 계약).
> 검색 이력 저장의 bounded 비동기 오프로드만 후속으로 남겼다(아래 "다음 단계").

경로 검색은 ODsay(필수) 위에 지하철 폴리라인·TMAP 보행·실시간 버스·ML 예측을 얹는 동기 API 다.
문제는 이 조합이 **가장 느린 의존성의 속도로 응답한다**는 것이었다. 이 문서는 그 구조를
측정 → 교정 → 재측정한 기록이다.

## 요약 — before / after

| 항목 | before | after |
|---|---:|---:|
| TMAP 5s 지연 시 응답 | 5,197ms | **438ms** (기본 경로 정상 반환) |
| ODsay 무응답 시 응답 | 6,934ms | **805ms** (504) |
| ODsay 무응답 시 provider 호출 | 3회 + backoff 6s | **1회** |
| 동일 키 100건 동시 miss | provider 100회 | **1회** |
| 캐시 20초 창 provider 호출 | 79회(캐시 off) | **2회**(TTL 15s) |
| 느린 TMAP 12건 동시 요청 | 공용 ForkJoinPool 잠식 | **전용 풀 4건 실행 / 8건 거절, 기본 경로 12건 전부 반환** |
| 실패 응답 상태코드 | 전부 400 | **504 / 502 / 400 구분** |

운영 read timeout(30s) 기준으로 환산하면, ODsay 무응답 1건이 요청 스레드를 **최악 96초** 점유할 수 있었다.
지금은 시간 예산 안에서 끝난다.

> **측정 조건 주의.** 위 after 수치는 테스트용으로 짧게 잡은 예산에서 나온 값이다
> (ODsay 상한 800ms, TMAP 상한 400ms, TMAP 풀 size 2/queue 2).
> 배포 기본값은 더 여유 있게 잡혀 있으므로(ODsay 2,000ms · TMAP 1,200ms · 풀 8/16),
> 운영에서 ODsay 무응답 시 응답은 **805ms 가 아니라 약 2초**다.
> 여기서 증명한 것은 특정 절대값이 아니라 **응답 시간이 상한 안에서 끝난다는 성질**이다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| Machine | Apple M3 Pro, 18GB RAM |
| JVM | Java 17 (Gradle toolchain) |
| 외부 provider | ODsay·TMAP = MockWebServer (지연·HTTP 상태 제어) |
| Redis | Testcontainers redis:7 (직렬화·TTL·만료 포함) — Step 1·3 |
| 측정 테스트 | `RouteSearchDeadlineIT`(Step 0 baseline·Step 2), `RouteSearchCacheIT`(Step 1), `RouteSearchStampedeIT`(Step 3), `RouteSearchBulkheadIT`(Step 4) |
| ODsay read timeout(테스트, before) | 300ms |
| ODsay read timeout(운영, before) | 30s (`ApiClientConfig`) |

운영 네트워크, 실 provider rate limit, 실제 응답 크기는 이 수치에 포함하지 않는다.
아래 모든 수치는 로컬 Mock 환경 기준이다.

## Step 0 — Baseline (개선 전, PR-A)

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

후속 정확성 검토에서 실시간 버스·ML 보강이 자체 1.5초 예산을 다시 시작해 전체 요청 예산을
초과할 수 있음을 발견했다. 이를 수정해 검색 컨텍스트의 잔여 예산을 버스 도착/위치 조회,
Single-flight 합류 대기, ML HTTP 요청까지 전달하고 각 실제 HTTP timeout에 적용했다.
따라서 선택 보강 단계가 앞 단계에서 소비한 시간을 무시하고 새 timeout을 시작하지 않는다.

또한 TMAP timeout 뒤 완료 결과를 캐시한다는 정책을 completion callback에 구현하고 테스트로 고정했다.
이번 응답은 상한에서 기본 경로로 반환하되 이미 시작한 정상 결과는 후속 요청이 재사용한다.

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
| ODsay 500 | 3회 호출* | **2회 호출** | 원 호출 + 재시도 1회, 502 |
| TMAP 400 | 2회 호출* | **1회 호출** | 재시도 없음, 기본 경로 유지 |
| 정상 | 60ms | 유사 | 예산은 정상 응답을 방해하지 않음 |

\* baseline IT 가 직접 잰 것은 TMAP 500(2회)뿐이다. ODsay 500 의 3회와 TMAP 400 의 2회는
같은 재시도 코드 경로(`MAX_RETRY=3` / 4xx 포함 1회 재시도)에서 나오는 값으로, 측정이 아니라 코드 기준이다.

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

## Step 4 — Provider Bulkhead (PR-E)

측정 테스트: `RouteSearchBulkheadIT`

Step 2 에서 남겨둔 문제 — 예산 초과로 버린 TMAP 호출이 **공용 `ForkJoinPool` 에 그대로 살아 있다** — 를 없앤다.
TMAP 보강을 전용 bounded 풀로 옮기고, 포화되면 대기 없이 거절한다.

### TMAP 풀 포화 (pool=2, queue=2, 동시 요청 12건, TMAP 1,000ms)

| 항목 | 값 |
|---|---:|
| 실제 TMAP 호출 | 4회 (= pool 2 + queue 2) |
| bulkhead 거절 | 8회 |
| 기본 경로 반환 | **12건 전부** |

- 동시 실행이 풀 크기를 넘지 않는다. 느린 TMAP 이 몰려도 공용 자원을 잠식하지 못한다.
- 초과분은 **큐에 쌓이지 않고 거절**된다. 큰 대기열은 과부하를 숨기고 지연을 뒤로 미룰 뿐이다.
- 거절은 실패가 아니라 관측 가능한 degradation(`degraded[walk=bulkhead_full]`)이며,
  사용자는 기본 경로를 정상적으로 받는다.

### 설계 판단 — 포화 시 CallerRuns 를 쓰지 않는다

`ThreadPoolExecutor` 의 흔한 포화 정책인 `CallerRunsPolicy` 는 여기서 정확히 틀린 선택이다.
호출 스레드(=요청 스레드)가 TMAP 호출을 대신 실행하게 되고, 그러면 Step 2 에서 세운 deadline 을
지킬 수 없다. 거절해서 **보강만 생략**하는 것이 맞다.

## 최종 설정값과 선정 근거

| 설정 | 값 | 근거 |
|---|---:|---|
| `route.search.deadline-ms` | 3,000 | 정상 검색이 60ms 수준이므로 충분히 여유 있다. 목적은 평균 단축이 아니라 **tail 절단**이다. |
| `route.search.odsay-ms` | 2,000 | 필수 단계라 여유를 주되, 무응답 시 요청 스레드가 2초 안에 풀리게 한다. |
| `route.search.tmap-ms` | 1,200 | 선택 보강이므로 전체 예산의 절반 이하. 이 안에 못 오면 기본 경로를 먼저 낸다. |
| `route.search.min-remaining-for-optional-ms` | 150 | 이보다 적게 남았는데 호출을 시작하면 orphan 호출만 만들고 결과는 못 기다린다. |
| `route.search.tmap-pool.size` | 8 | 상한을 키워도 TMAP 이 빨라지지 않는다. provider rate limit 과 VPS CPU 만 압박한다. |
| `route.search.tmap-pool.queue` | 16 | 짧게 둔다. 큰 대기열은 과부하를 숨기고 지연을 뒤로 미룰 뿐이다. |
| `route.search.bus.join-wait-ms` | 1,800 | 버스 조회 자체가 ~1.5s timeout 이므로 owner 를 기다릴 여지를 조금 더 준다. |
| 실시간 캐시 TTL | 15s (유지) | hit ratio 는 97.4%. 더 늘려도 2.6%p 남짓 오르는 대신 **알림의 도착 판단 오차가 TTL 만큼 커진다**. |

## 한계 · 운영 제외 범위

- 모든 수치는 **로컬 Mock 환경**이다. 운영 네트워크 지연, 실 provider rate limit, 실제 응답 크기는 반영하지 않았다.
- Single-flight 는 **JVM 로컬**이다. 다중 인스턴스로 확장하면 인스턴스 수만큼 provider 호출이 남는다.
  단일 인스턴스 배포에서 Redis 분산 락을 먼저 넣는 것은 얻는 것 없이 왕복만 늘린다.
- Circuit Breaker 는 넣지 않았다. 버스 API 에는 이미 provider 가 명시적으로 주는 한도 신호(error code 7)
  기반의 자체 breaker 가 있고, ODsay·TMAP 은 지속 장애가 실측된 적이 없다.
  **근거 없이 라이브러리를 얹지 않는다** — 필요해지면 그때 실측을 근거로 넣는다.
- 검색 이력 저장(AUXILIARY)은 아직 응답 스레드에서 동기로 수행한다. bounded 비동기 오프로드는
  검색 로그가 필수 비즈니스 데이터인지 확정한 뒤로 미뤘다(아래 후속).

## 포트폴리오 요약 원문

경로 검색은 ODsay 위에 TMAP 보행·실시간 버스·ML 예측을 얹는 동기 API 라, 가장 느린 의존성의 속도로
응답하는 구조였다. 계측을 먼저 붙여 측정하니 TMAP 이 5초 지연되면 응답도 5,197ms 가 됐고,
ODsay 가 무응답이면 3회 재시도와 고정 backoff 6초 때문에 6,934ms(운영 설정 환산 최악 96초) 동안
요청 스레드가 묶였다.

이를 **요청 단위 시간 예산(deadline) 전파**, **조건부 재시도**, **single-flight**, **provider 전용 풀**로
교정해, TMAP 5초 지연에도 438ms 에 기본 경로를 반환하고 ODsay 무응답도 805ms 안에 504 로 끝나게 했다.
동일 키 100건 동시 miss 의 provider 호출은 100회에서 1회로 줄었다.

`RestTemplate` 은 timeout 이 client 단위 고정값이라 요청마다 달라지는 잔여 예산을 반영할 수 없어,
필수 단계인 ODsay 를 JDK `HttpClient` 로 옮겨 `HttpRequest.timeout(min(단계 상한, 남은 시간))` 으로
예산을 HTTP I/O 까지 내렸다. 캐시 TTL 은 검색의 hit ratio 곡선이 아니라 **같은 캐시를 공유하는
버스 알림이 허용하는 도착 판단 오차**가 상한을 정한다고 보고 15초를 유지했다.

## 부수 수정

- ODsay 검색 URI 전체를 `log.info` 로 남기던 코드가 apiKey 를 로그에 노출하고 있었다.
  좌표만 남기도록 바꿨다(`OdSayApiAdapter`).

## Step 5 — 응답 완전성 계약 (완료)

내부 완전성 판정(`FULL/PARTIAL/BASE_ONLY/FAILED`)을 응답 body 의 하위 호환 `meta` 필드로 노출했다.

```json
"meta": {
  "outcome": "partial",
  "degraded": [ { "component": "bus_realtime", "reason": "timeout" } ]
}
```

- `outcome` = 이 응답에 실린 보강 수준. `full` / `partial` / `base_only`.
  `failed`(필수 ODsay 실패)는 body 가 아니라 504/502 에러로 나가므로 여기 등장하지 않는다.
- `degraded` = 생략·실패한 **OPTIONAL** 보강만. AUXILIARY(검색 이력)는 응답 내용과 무관하므로 제외한다.
  클라이언트는 이 목록의 component 보강 필드(버스 도착정보·도보 상세 등)를 최신 정상값으로 오인하면 안 된다.
- 기존 클라이언트 호환: `RouteSearchResult` 레코드에 nullable `meta` 를 추가하고 6-arg 편의 생성자를 유지해
  기존 호출부·중간 보강 결과는 무변경. `@JsonInclude(NON_NULL)` 로 meta 없는 내부 경로는 필드가 생략된다.
- 판정은 컨트롤러 반환 직전 `RouteSearchResult.withMeta(...)` 로 한 번만 부착한다(모든 단계 보고 확정 후).

## 다음 단계 (후속)

| 단계 | 내용 | 필요한 판단 |
|---|---|---|
| — | 검색 이력 저장을 응답 경로에서 분리(bounded 비동기, 포화 시 drop metric) | 검색 로그가 필수 비즈니스 데이터인지 확정 |
| — | 운영 canary: 실 provider 로 timeout·degradation 계약만 확인 | VPS 배포 후 소량 요청 |
