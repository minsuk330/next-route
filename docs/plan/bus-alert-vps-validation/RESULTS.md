# 버스 도착 알림 Worker VPS 재검증

## 결론

현재 배포와 같은 Git SHA의 코드를 실제 VPS에서 실행해 포트폴리오 1페이지의 Worker 성능 주장을
재검증했다. Worker 2개 경쟁의 정합성은 재현됐지만, 10,000건 enqueue와 200건 due-to-SENT
지연은 기존 로컬 수치를 재현하지 못했다.

| 포트폴리오 주장 | VPS 재검증 | 판정 |
|---|---|---|
| Worker 2개 경쟁에서 중복·고착 0, 전량 SENT | 200건 × 3회, 두 Worker가 각 100건 처리, 매회 중복 0·고착 0·전량 SENT | 검증 |
| interval p95 2,395ms → wake-up 889ms | 중앙값 2,694ms → 1,553ms, 42.4% 단축 | 효과는 검증, 절대값은 불일치 |
| 200건·Worker 32·Mock 20ms p95 434ms, p99 445ms | 실제 wake-up 중앙값 p95 1,553ms, p99 1,593ms | 재현 실패 |
| 10,000건 batch enqueue 194ms, 적용 경로 6,959건/s | 운영 기본 설정 중앙값 38.479초, 259.9건/s | 재현 실패 |

현재 포트폴리오에서 그대로 유지할 수 있는 것은 **Worker 2개 정합성 결과**다. 나머지 성능 수치는
현행 배포 결과로 교체하거나, 누락된 set-based batch enqueue 구현을 복원한 뒤 다시 측정해야 한다.

## 측정 환경

| 항목 | 값 |
|---|---|
| 측정 시각 | 2026-07-29 21:42~21:57 KST |
| Git SHA | `778ed9711d0d945aa45a8cfdceeccf718966a56e` |
| VPS | 4 vCPU, 7.7GiB RAM, 4GiB swap |
| JVM | OpenJDK 17.0.19 |
| DB | PostgreSQL 17 Testcontainers, VPS Docker/CPU/디스크 사용 |
| 외부 발송 | 실제 Toss 미호출, 20ms tracking/counting stub |
| 운영 격리 | 운영 DB·Redis·사용자 알림 데이터 미사용 |
| 운영 상태 | 측정 후 API·Worker 컨테이너 모두 healthy |

테스트는 VPS `/root/bench/nextroute-worker-778ed97`의 격리된 소스와 Testcontainers에서 실행했다.
운영 API와 Worker 컨테이너는 실행 중인 상태로 두어 VPS 자원 경합을 포함하되 운영 데이터에는
접근하지 않았다.

## 1. 10,000건 enqueue

테스트: `BusAlertEnqueueLoadIT`

조건:

- alerts: 10,000
- stop/route/ord groups: 200
- 운영 기본 batch size: 200
- 발송 제외, outbox enqueue까지만 측정
- 전량 enqueue, dedup key 중복 0, 부모 `PROCESSING`과 outbox `PENDING` 수 일치 검증

### 운영 기본 batch 200

| 실행 | cycle | elapsed | enqueue/s |
|---:|---:|---:|---:|
| 1 | 50 | 38,479ms | 259.9 |
| 2 | 50 | 37,279ms | 268.2 |
| 3 | 50 | 40,090ms | 249.4 |
| **중앙값** | **50** | **38,479ms** | **259.9** |

### batch 10,000 통제 실행

스캔 cycle의 영향을 분리하기 위해 한 cycle에 10,000건을 모두 로드하도록 강제했다.

| cycle | elapsed | enqueue/s |
|---:|---:|---:|
| 1 | 40,447ms | 247.2 |

cycle을 50회에서 1회로 줄여도 처리시간이 감소하지 않았다. 현행 코드는 각 alert에서
`enqueueTossArrival(...)`을 호출하고, 호출마다 별도 `@Transactional` DB 작업을 수행한다.
현재 소스에는 포트폴리오에 적힌 temp table·set-based batch enqueue 메서드가 존재하지 않는다.

따라서 기존 `194ms`, `6,959건/s`는 현재 배포의 성능 근거로 사용할 수 없다.

## 2. Worker 2개 Outbox 경쟁

테스트: `BusAlertTwoRelayWorkersIT`

조건:

- outbox backlog: 200
- 독립 Relay 인스턴스: 2
- 인스턴스별 내부 발송 worker: 1
- claim batch: 10
- Toss stub latency: 20ms
- 두 인스턴스를 latch로 동시에 시작
- 수신자 키별 호출 수를 추적해 중복 발송 검증

| 실행 | Worker 1 | Worker 2 | elapsed | 중복 발송 | PROCESSING 고착 | SENT |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 100 | 100 | 3,562ms | 0 | 0 | 200 |
| 2 | 100 | 100 | 3,350ms | 0 | 0 | 200 |
| 3 | 100 | 100 | 3,401ms | 0 | 0 | 200 |

세 번 모두 두 Worker가 실제로 작업을 나눠 처리했고, `SKIP LOCKED` claim 이후 중복 발송 0,
고착 0, 전량 SENT를 만족했다. 이 포트폴리오 주장은 VPS에서도 유지할 수 있다.

## 3. 200건 due-to-SENT, 실제 AFTER_COMMIT wake-up

테스트: `BusAlertWakeupBurstIT`

실제 경로:

```text
dispatch transaction commit
→ AFTER_COMMIT listener
→ RelayWaker
→ Worker BusAlertRelayLoop
→ 32개 발송 worker
→ SENT
```

조건:

- due alerts: 200
- groups: 40
- relay workers: 32
- Toss stub latency: 20ms
- fallback sweep: 60초
- 제한시간 내 발송은 event wake-up 경로로만 가능

| 실행 | dispatch 반환 | 전량 SENT | p50 | p95 | p99 | max |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1,497ms | 1,614ms | 1,090ms | 1,553ms | 1,593ms | 1,595ms |
| 2 | 1,453ms | 1,553ms | 1,027ms | 1,483ms | 1,529ms | 1,530ms |
| 3 | 1,634ms | 1,688ms | 1,110ms | 1,634ms | 1,673ms | 1,680ms |
| **중앙값** | **1,497ms** | **1,614ms** | **1,090ms** | **1,553ms** | **1,593ms** | **1,595ms** |

모든 실행에서 200건 전량 SENT, 구독·outbox `PROCESSING` 고착 0, Toss stub 호출 정확히
200회를 검증했다.

## 4. 1초 interval 대기와 비교

통제 테스트: `BusAlertDueToSentLatencyIT.dueToSentLatency_intervalRelayBaseline`

동일한 200건·40그룹·Worker 32·Toss 20ms 조건에서 enqueue 후 relay 시작 전 1,000ms의
고정 interval을 적용했다.

| 실행 | dispatch | relay | p50 | p95 | p99 | max |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 729ms | 731ms | 2,169ms | 2,418ms | 2,428ms | 2,456ms |
| 2 | 1,022ms | 692ms | 2,465ms | 2,694ms | 2,706ms | 2,707ms |
| 3 | 1,041ms | 733ms | 2,526ms | 2,751ms | 2,764ms | 2,768ms |
| **중앙값** | **1,022ms** | **731ms** | **2,465ms** | **2,694ms** | **2,706ms** | **2,707ms** |

AFTER_COMMIT wake-up의 실제 VPS p95 중앙값은 1,553ms로, interval 모델 2,694ms보다
1,141ms(42.4%) 짧다. wake-up 자체의 효과는 확인됐지만 enqueue가 느려 p95 500ms 목표는
달성하지 못했다.

## 5. 즉시 relay 통제 측정

`BusAlertDueToSentLatencyIT.dueToSentLatency_p50p95p99`로 event loop를 거치지 않고
dispatch 직후 relay를 직접 호출한 최선 경로도 세 번 측정했다.

| 실행 | dispatch | relay | p50 | p95 | p99 | max |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 908ms | 791ms | 1,398ms | 1,657ms | 1,674ms | 1,678ms |
| 2 | 987ms | 855ms | 1,467ms | 1,773ms | 1,804ms | 1,820ms |
| 3 | 748ms | 644ms | 1,179ms | 1,369ms | 1,382ms | 1,389ms |
| **중앙값** | **908ms** | **791ms** | **1,398ms** | **1,657ms** | **1,674ms** | **1,678ms** |

실제 wake-up 경로와 유사한 범위여서, 주된 병목이 이벤트 신호가 아니라 현재 건별 enqueue와
row별 relay 상태 전이에 있음을 뒷받침한다.

## 포트폴리오 교체 문구

현재 코드 기준으로는 아래 문구만 사용한다.

> 실제 4 vCPU·8GB VPS의 PostgreSQL 17 격리 환경에서 Relay 인스턴스 2개가 동일 Outbox
> 200건을 동시에 처리하도록 3회 검증했습니다. 매 실행에서 두 Worker가 100건씩 처리했고,
> 중복 발송 0건·PROCESSING 고착 0건·전량 SENT를 확인했습니다.
>
> 200건·40그룹·Worker 32·외부 발송 Mock 20ms 조건의 실제 AFTER_COMMIT wake-up 경로는
> p95 중앙값 1,553ms, p99 1,593ms였습니다. 1초 interval 모델 p95 2,694ms보다
> 1,141ms(42.4%) 단축됐지만 p95 500ms 목표는 달성하지 못했습니다.

10,000건 성능 문장은 제거한다. 현행 배포를 기준으로 반드시 수치를 쓴다면 아래처럼 제한한다.

> 현행 배포 코드의 10,000건 outbox enqueue는 운영 기본 batch 200에서 중앙값 38.479초,
> 259.9건/s였으며, 건별 트랜잭션과 DB round-trip을 다음 병목으로 확인했습니다.

## 다음 조치

성능 주장을 다시 강화하려면 먼저 현재 브랜치에 없는 set-based batch enqueue를 실제 코드로
복원하거나 재구현해야 한다. 같은 VPS·동일 테스트로 다음 조건을 모두 재검증한 뒤에만
`194ms`, `6,959건/s`, `p95 500ms 이하`와 같은 수치를 포트폴리오에 다시 사용할 수 있다.

1. 10,000건 기본 설정 3회 중앙값
2. 한 cycle 10,000건 통제 측정
3. 실제 AFTER_COMMIT wake-up 200건 3회 p95/p99
4. Worker 2개 경쟁 중복·고착 0
