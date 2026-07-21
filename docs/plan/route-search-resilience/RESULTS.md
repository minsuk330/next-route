# 경로 검색 외부 의존성 개선 결과

> 진행 상태: **Step 0(Baseline) 완료** — PR-A. 이후 단계는 이 문서에 계속 누적한다.

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

## 부수 수정

- ODsay 검색 URI 전체를 `log.info` 로 남기던 코드가 apiKey 를 로그에 노출하고 있었다.
  좌표만 남기도록 바꿨다(`OdSayApiAdapter`).

## 다음 단계

| 단계 | 내용 | 이 문서에 추가할 수치 |
|---|---|---|
| Step 1 (PR-B) | Hybrid Cache 계측·A/B | hit ratio, provider 호출 감소, TTL별 데이터 age·알림 정확도 |
| Step 2 (PR-C) | End-to-end Deadline, 조건부 Retry | 지연·무응답 시 상한 내 종료, 재시도 횟수 |
| Step 3 (PR-D) | Single-flight | 동일 키 100건 동시 miss 시 provider 호출 수 |
| Step 4 (PR-E) | Provider Bulkhead | provider별 최대 동시성, 포화 시 격리 |
| Step 6 (PR-G) | 부하·장애 주입 재측정 | before/after 최종 결과표 |
