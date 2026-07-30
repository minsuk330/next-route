# 버스 도착 알림 튜닝 흐름

> **현재 배포 주의 (2026-07-29).** 이 문서의 수치는 Apple M3 Pro에서 진행한 당시 로컬
> 실험 기록이다. 현재 배포 SHA `778ed9711d0d945aa45a8cfdceeccf718966a56e`에는 이 문서
> 7절의 temp table + set-based batch enqueue 구현이 존재하지 않으며, 실제 VPS 재검증에서
> 10,000건 기본 설정 중앙값은 38.479초·259.9건/s였다. Worker 32·Toss mock 20ms의 실제
> AFTER_COMMIT wake-up 경로도 p95 중앙값 1,553ms로 434ms를 재현하지 못했다.
> 현재 포트폴리오에는 아래 수치를 현행 배포 결과로 사용하지 말고
> [`docs/plan/bus-alert-vps-validation/RESULTS.md`](../bus-alert-vps-validation/RESULTS.md)를 기준으로 한다.

## 목표

버스 도착 알림은 사용자가 곧 탈 버스의 도착 여부를 알려주는 기능이므로, 단순히 "언젠가 발송"보다 발송 지연의 상한이 중요하다.

이번 튜닝의 목표는 다음과 같다.

- due alert 감지 후 Toss `SENT` 처리까지 p95 500ms 이내
- 동시 worker 환경에서도 중복 발송 0
- 외부 API 지연이 polling loop 전체를 막지 않도록 분리
- 채널 확장을 고려해 발송 책임을 구독 엔티티와 분리

## 측정 환경

| 항목 | 값 |
|---|---|
| Machine | Apple M3 Pro, 18GB RAM, SSD |
| DB | PostgreSQL 17 via Testcontainers |
| Spring datasource | HikariCP, 기본 max pool 10 기준 측정 |
| External send | Toss mock, latency 0-20ms 파라미터화 |
| Real external API | 미사용 |
| 주요 테스트 | `BusAlertDispatchBottleneckIT`, `BusAlertBatchEnqueueExperimentIT`, `BusAlertRelayWakeupLatencyIT`, `BusAlertRelayStageBreakdownIT` |

운영 네트워크, 실 Toss rate limit, 실제 서울버스 API 지연은 이 문서의 수치에 포함하지 않는다.

## 1. 기존 구조 측정

초기 구조는 scheduler가 due alert를 조회하고, 버스 도착 조회와 Toss 발송까지 같은 polling loop 안에서 처리했다.

```text
@Scheduled
  -> due alert 조회
  -> 버스 도착 조회
  -> Toss 발송
  -> alert 상태 변경
```

### 1초 polling 시뮬레이션

| Scenario | Alerts | Bus API latency | Toss latency | p95 duration | Overrun |
|---|---:|---:|---:|---:|---:|
| small | 300 | 0ms | 0ms | 71ms | 0% |
| toss-latency | 50 | 0ms | 20ms | 421ms | 0% |
| larger | 1,000 | 10ms | 20ms | 1,993ms | 100% |

해석:

- 작은 부하에서는 1초 polling budget 안에 들어온다.
- alert 수와 외부 호출 latency가 커지면 `dispatchDue()` 자체가 1초를 초과한다.
- 이 경우 다음 polling tick이 밀리므로 초단위 감지/발송을 보장하기 어렵다.

## 2. 동시성 결함 재현

기존 claim은 read-then-write 구조였다.

```text
findById()
  -> status == PENDING 확인
  -> markProcessing()
```

동시 worker가 같은 row를 거의 동시에 읽으면 둘 다 `PENDING`으로 판단할 수 있다.

| Metric | Value |
|---|---:|
| workers | 2 |
| pendingReads | 2 |
| claimSuccesses | 2 |
| tossCalls | 2 |
| duplicateSends | 1 |

해석:

- 상태 확인과 상태 변경이 하나의 DB 원자 연산이 아니면 중복 발송이 가능하다.
- 병렬 worker를 늘리기 전에 claim 정합성부터 해결해야 한다.

## 3. Atomic claim + Outbox 분리

### 변경

claim을 조건부 `UPDATE` 기반으로 바꾸고, 발송 작업을 `bus_alert_notification` outbox로 분리했다.

```text
BusArrivalAlert
  -> "언제 누구에게 알려야 하는가"

BusAlertNotification
  -> "어떤 채널로 발송해야 하고, 발송 상태가 무엇인가"
```

polling은 Toss를 직접 호출하지 않고 outbox row만 적재한다.

```text
polling
  -> due alert claim
  -> outbox enqueue
  -> commit

relay
  -> outbox claim with SKIP LOCKED
  -> Toss send
  -> SENT mark
```

### 효과

- polling loop에서 외부 발송 I/O 제거
- `SKIP LOCKED` 기반 relay 병렬화 가능
- `dedup_key`로 같은 발송 작업 중복 적재 방지
- 향후 Toss, iOS, Android 등 채널 확장 시 발송 상태를 구독 엔티티에 계속 추가하지 않아도 됨

## 4. Relay 병렬화

outbox relay는 `PENDING` notification을 배치 claim한 뒤 worker pool로 발송을 팬아웃한다.

| Backlog | Toss latency | 8w vs 1w | 8w send/s | p95 |
|---:|---:|---:|---:|---:|
| 96 | 20ms | 8.4x | 268/s | 353ms |
| 960 | 10ms | 7.9x | 438/s | 2.1s |
| 9,600 | 5ms | 7.3x | 706/s | 12.9s |

정합성 결과:

| Metric | Result |
|---|---:|
| duplicate sends | 0 |
| stuck PROCESSING | 0 |
| final status | 전량 `SENT` |

해석:

- relay 병렬화는 거의 선형으로 스케일했다.
- `SKIP LOCKED` 덕분에 여러 worker가 서로 다른 row를 claim해 중복 발송이 발생하지 않았다.
- backlog가 커질수록 tail latency는 backlog 크기와 worker 수에 따라 증가한다.

## 5. Relay interval 대기 제거

기존 relay는 고정 interval로만 깨어났다. outbox row가 커밋된 직후에도 다음 relay tick까지 최대 1 interval을 기다릴 수 있었다.

### 변경

outbox enqueue 성공 후 `AFTER_COMMIT` 이벤트로 relay를 깨우는 wake-up 구조를 추가했다.

```text
enqueue tx commit
  -> AFTER_COMMIT event
  -> in-process semaphore release
  -> relay loop 즉시 drain
```

설계 기준:

- wake signal은 힌트이고, 진실은 DB outbox row다.
- signal이 유실되어도 sweeper timeout이 다시 outbox를 조회한다.
- 단일 인스턴스이므로 PostgreSQL `LISTEN/NOTIFY` 대신 in-process semaphore를 사용했다.

### 결과

| Structure | p95 due->SENT |
|---|---:|
| interval relay | 2.395s |
| wake-up relay | 0.889s |

해석:

- relay tick 대기만 제거해도 due->SENT p95가 1초 미만으로 줄었다.
- 다만 wake-up이 제거하는 것은 dispatch->send 구간의 대기이며, 버스 데이터 신선도나 due 조건 감지 주기 자체를 없애는 것은 아니다.

## 6. Enqueue 병목 분석

wake-up 이후에도 큰 burst에서는 enqueue가 병목이 되었다.

### 기존 per-row enqueue

| Total | Wall time | Enqueue time | Enqueue share | Throughput |
|---:|---:|---:|---:|---:|
| 10,000 | 17.108s | 16.420s | 96.9% | 584/s |

초기 가설은 `findDispatchable` scan 비용이었다. 하지만 batch size sweep이 이 가설을 반박했다.

| Batch size | Cycles | Elapsed | Enqueue/s |
|---:|---:|---:|---:|
| 200 | 200 | 76.3s | 524/s |
| 500 | 80 | 76.3s | 525/s |
| 1,000 | 40 | 73.4s | 545/s |

해석:

- cycle 수를 5배 줄여도 처리량은 거의 변하지 않았다.
- scan이 아니라 건별 native query round-trip, 건별 `UPDATE`/`INSERT`, 트랜잭션/commit 비용이 병목이었다.

## 7. Batch enqueue 적용

### 실험

동일한 10,000건에 대해 세 방식을 비교했다.

| Method | Time | Relative |
|---|---:|---:|
| current per-row | 16.68s | 1.0x |
| single-tx loop | 17.97s | 0.9x |
| bulk SQL | 194ms | 86.0x |

해석:

- 단순히 외부 transaction 하나로 묶는 것만으로는 개선되지 않았다.
- 진짜 개선 지점은 DB round-trip과 row-by-row query 자체를 줄이는 것이었다.

### 적용

`BusAlertOutboxEnqueueService.enqueueTossArrivals(...)`를 추가해 temp table + set-based SQL로 한 번에 claim/enqueue한다.

```text
commands
  -> temp table batch insert
  -> UPDATE bus_arrival_alert ... RETURNING
  -> INSERT bus_alert_notification ... ON CONFLICT DO NOTHING
  -> AFTER_COMMIT wake event 1회
```

batch enqueue 실패 시에는 기존 single-row enqueue로 fallback한다.

### 생산 흐름 결과

| Total | Wall time | Enqueue time | Enqueue throughput |
|---:|---:|---:|---:|
| 4,000 | 417ms | 181ms | 9,570/s |
| 10,000 | 1.436s | 560ms | 6,959/s |

해석:

- enqueue 병목은 초당 수백 건 수준에서 수천 건 수준으로 개선됐다.
- polling loop는 더 이상 Toss latency나 건별 enqueue commit에 직접 묶이지 않는다.

## 8. due->SENT p95 500ms 검증

조건:

- total: 200 due alerts
- groups: 40
- Toss mock latency: 20ms
- relay sweep interval: 60s
- wake-up path 사용

| Relay workers | p50 | p95 | p99 |
|---:|---:|---:|---:|
| 8 | - | 696ms | - |
| 16 | - | 578ms | - |
| 24 | - | 511ms | - |
| 32 | 341ms | 434ms | 445ms |

해석:

- 20ms mock send latency, 200건 burst 조건에서 worker 32개일 때 p95 500ms 목표를 달성했다.
- worker를 무한히 늘리는 것은 정답이 아니다. DB connection pool, CPU context switch, 외부 rate limit 압박이 함께 증가한다.

## 9. Connection pool 압박과 다음 병목

Hikari max pool 10, relay workers 32, Toss mock 20ms 조건에서 stage breakdown을 측정했다.

| Stage | Value |
|---|---:|
| claim batch | 87ms |
| relay total | 325ms |
| loadTask p95 | 60ms |
| send p95 | 24ms |
| markSent p95 | 25ms |
| completion p95 | 315ms |
| Hikari peak active | 10 |
| Hikari peak awaiting | 22 |

Toss mock 0ms 조건:

| Stage | Value |
|---|---:|
| completion p95 | 283ms |
| loadTask p95 | 68ms |
| markSent p95 | 54ms |
| Hikari peak active | 10 |
| Hikari peak awaiting | 22 |

해석:

- 외부 API를 제외해도 relay 내부 p95는 약 283ms다.
- connection pool wait는 tail latency를 키우는 증폭 요인이다.
- 다음 병목은 Toss가 아니라 `loadTask`, `markSent` 같은 row별 DB/JPA 상태 전이다.

## 10. 현재 결론

### 달성한 것

| 목표 | 결과 |
|---|---|
| 중복 발송 방지 | atomic claim + `SKIP LOCKED`, duplicate 0 |
| polling loop 외부 I/O 제거 | Toss send를 outbox relay로 분리 |
| relay interval 대기 제거 | p95 2.395s -> 0.889s |
| enqueue 병목 개선 | 10,000건 per-row 16.68s -> bulk 194ms 실험, 생산 흐름 6,959/s |
| p95 500ms 목표 | 200건 burst, workers 32, Toss 20ms에서 p95 434ms |

### 보류한 것

| 항목 | 보류 이유 |
|---|---|
| Message Queue 도입 | 현재 목표는 DB outbox relay로 달성. MQ는 outbox를 대체하지 않고 relay 이후 전송층을 바꾸는 선택지다. |
| 추가 DB 최적화 | 현재 p95 목표 달성. 다음 최적화는 JPA row-by-row 상태 전이를 줄이는 구조 변경이 필요하다. |
| worker 무한 증설 | connection pool awaiting 증가, DB write 압박, 외부 rate limit 리스크가 커진다. |

## 포트폴리오 요약 문장

버스 도착 알림의 시간 민감도를 고려해 due alert 감지 후 Toss `SENT`까지 p95 500ms 이내를 목표로 정의했다.
기존 polling loop는 외부 발송과 DB 상태 전이가 결합되어 부하 증가 시 p95 1,993ms까지 밀렸고, read-then-write claim으로 중복 발송 가능성도 있었다.
이를 atomic claim, transactional outbox, `SKIP LOCKED` relay 병렬화, `AFTER_COMMIT` wake-up, batch enqueue로 개선해 200건 burst 기준 due->SENT p95 434ms와 중복 발송 0을 확인했다.

## 다음 개선 후보

1. `claimBatch`가 relay task projection을 바로 반환하게 해 `loadTask` row별 조회 제거
2. `markSent`를 entity load 기반에서 native update 기반으로 줄여 row별 JPA 상태 전이 비용 감소
3. 운영 환경에서 실 Toss latency와 rate limit을 반영한 soak test 추가
4. 다중 인스턴스 전환 시 in-process wake-up을 PostgreSQL `LISTEN/NOTIFY` 또는 MQ relay로 확장
