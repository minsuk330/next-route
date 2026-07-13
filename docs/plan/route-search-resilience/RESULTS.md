# 경로 검색 외부 의존성 개선 결과

> 진행 상태: **Step 0(Baseline) · Step 1(Hybrid Cache) 완료** — PR-A, PR-B. 이후 단계는 이 문서에 계속 누적한다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| Machine | Apple M3 Pro, 18GB RAM |
| JVM | Java 17 (Gradle toolchain) |
| 외부 provider | ODsay·TMAP = MockWebServer (지연·HTTP 상태 제어) |
| DB·Redis | 미사용 — 이 단계는 외부 호출 구간만 격리해 측정 |
| 측정 테스트 | `RouteSearchBaselineIT` |
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

## 부수 수정

- ODsay 검색 URI 전체를 `log.info` 로 남기던 코드가 apiKey 를 로그에 노출하고 있었다.
  좌표만 남기도록 바꿨다(`OdSayApiAdapter`).

## 다음 단계

| 단계 | 내용 | 이 문서에 추가할 수치 |
|---|---|---|
| Step 2 (PR-C) | End-to-end Deadline, 조건부 Retry | 지연·무응답 시 상한 내 종료, 재시도 횟수 |
| Step 3 (PR-D) | Single-flight | 동일 키 100건 동시 miss 시 provider 호출 수 |
| Step 4 (PR-E) | Provider Bulkhead | provider별 최대 동시성, 포화 시 격리 |
| Step 6 (PR-G) | 부하·장애 주입 재측정 | before/after 최종 결과표 |
