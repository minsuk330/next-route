# Phase 6 ⑥ — 자동 제안 + 알림 (`journey-reroute-notification`)

- 설계 근거: [DESIGN.md](./DESIGN.md) — 결정의 *이유*는 전부 그쪽에 있다
- 상위: [PHASE-6.md §⑥](../journey-navigation/PHASE-6.md) · 선행: ①②③④⑤(#133·#135·#136·#139·#140·#143·#144)
- 티어: **L**(PHASE-6 의 M 에서 올림 — 자동 생성이 들어왔다) · 마이그레이션: **없음**
- 작성일: 2026-08-13

## PR 두 개로 나눈다

| | 슬러그 | 티어 | 내용 | 선행 |
|---|---|---|---|---|
| **PR-1** | `feat/journey-reroute-auto-propose` | **L** | 방아쇠 → 자동 후보 생성(전용 풀·자체 스위치·일일 상한) | ⑤(#144) |
| **PR-2** | `feat/journey-reroute-notification` | **M** | `REROUTE_PROPOSED` 알림 + 취소 + 문구·deep link | PR-1 |

**나눈 이유** — PR-1 은 **요금과 워커 부하**의 문제이고 PR-2 는 **키와 문구**의 문제다. 한 PR 에
넣으면 "알림이 안 온다"의 원인이 자동 생성인지 알림 경로인지 가릴 수 없다.

## 1. 확정 결정 (요약 — 근거는 DESIGN.md §1)

| # | 결정 |
|---|---|
| D1 | 방아쇠가 cooldown 을 통과하면 **서버가 후보 생성을 부른다**(자동 생성) |
| D2 | **평가 트랜잭션 밖 + 전용 bounded 풀.** 포화 시 거절하고 지표만 남긴다 |
| D3 | 자체 스위치 `journey.reroute.auto.enabled`(기본 **꺼짐**) |
| D4 | **일일 상한 두 겹** — 여정당·전체. 소진되면 자동 생성만 막고 사용자 요청은 통과 |
| D5 | 상한 저장소 장애는 **fail-closed**(자동 생성 차단) |
| D6 | cooldown 은 기존 **`TRIGGER` 키** 그대로 |
| D7 | 알림 논리 키에 **proposalId 접미사**. 마이그레이션 없음 |
| D8 | 제안 알림이 같은 환승의 PENDING **`MISSED` 를 밀어낸다** |
| D9 | 만료는 **제안 TTL 그대로**(바닥 없음) |
| D10 | payload 는 식별자 + `proposalId` 만 |
| D11 | 제안 만료·교체 시 알림 취소(`proposal-expired`·`proposal-superseded`) |
| D12 | 후보가 빈손이면 **아무 일도 없다**(알림 없음) |

## 2. 착수 시점의 실제 코드 상태 (2026-08-13 확인)

| 부품 | 상태 |
|---|---|
| `RerouteTriggerEvaluator` | shadow. `RerouteProposalService` 를 **주입받지 않는다**(계약을 구조로 보장). `metrics.recordTriggerAdmitted` 뒤에 `log.info` 한 줄이 전부(`:73-75`) |
| 호출 자리 | `JourneyEvaluationService.evaluateRerouteTrigger`(`:112`) — **소유권 확인 뒤·트랜잭션 밖**, 예외를 삼킨다 |
| `RerouteProposalService.request` | 사용자 요청 진입점. 소유자 확인 → `enabled` → 적격성 → 공급자 순회 → `txService.store` |
| `RerouteCooldownPort` | `Scope{TRIGGER, REQUEST}` 로 이미 갈려 있다 |
| `JourneyRerouteProperties` | `enabled`·`proposalTtl`·`maxCandidates`·`trigger`·`routeOption`·`remainingPath` |
| `BusApiQuotaPort` / `RedisBusApiQuota` | 일일 상한의 선례. Redis 원자 카운터, **fail-closed** |
| `SearchHistoryExecutorConfig` | bounded 풀 + `AbortPolicy` + 거절 지표의 선례 |
| `JourneyCheckpoint` | 셋. DB CHECK 없음 → 값 추가에 마이그레이션 불필요 |
| `logical_checkpoint_key` | **varchar(255)** — proposalId(36자) 접미사가 들어간다 |
| `JourneyNotificationEnqueuer` | `enqueueTransferRisk`(바닥 있음) · `enqueueTargetApproach`(**바닥 없음** — D9 의 본보기) |
| `FcmMessageFactory` | 문구·`messageKey`·`deepLink` 가 전부 여기 하나. `switch` 가 enum 전수라 가지를 빼먹으면 컴파일이 막는다 |
| `RerouteProposalRepository` | `supersedeActive`·`resolveStale`(RETURNING 사유)·`expireDueBatch`(**개수만**) |

---

## PR-1 — 자동 제안 생성 (L)

### 3.1 신규 `application/journey/config/RerouteAutoProperties.java` (또는 `JourneyRerouteProperties.Auto`)

```yaml
journey:
  reroute:
    auto:
      enabled: ${JOURNEY_REROUTE_AUTO_ENABLED:false}   # 자체 스위치(D3)
      daily-limit-per-journey: 3                        # 여정당 하루 자동 생성 상한
      daily-limit-total: 500                            # 전체 하루 상한(요금 천장)
      executor:
        core-size: 2
        max-size: 4
        queue-capacity: 100
```

`validate()` — 셋 다 1 이상, 풀 값도 1 이상.

### 3.2 신규 `application/journey/port/out/RerouteAutoQuotaPort.java`

```java
public interface RerouteAutoQuotaPort {
    /** 자동 생성 1건을 원자적으로 점유한다. 여정당·전체 상한을 함께 본다. */
    boolean tryAcquire(Long journeyId);
}
```

`infrastructure/adapter/out/cache/journey/RedisRerouteAutoQuota` — KST 일 단위 키 두 개
(`journey:reroute:auto:total:{yyyyMMdd}`, `journey:reroute:auto:journey:{id}:{yyyyMMdd}`),
`INCR` + TTL. **실패하면 false**(fail-closed, D5). `RedisBusApiQuota` 와 같은 모양이다.

### 3.3 신규 `application/journey/service/RerouteAutoProposeService.java`

```java
/** 방아쇠가 통과한 신호 하나를 받아 후보를 만들고 저장한다. 전용 풀에서 돈다. */
public void proposeAsync(RerouteTriggerSignal signal, Long userId);
```

- `@Async("rerouteAutoExecutor")` 또는 `Executor.execute(...)` — **평가 스레드를 물지 않는다**(D2)
- 순서: 스위치 확인 → `quota.tryAcquire` → `proposalService.createForTrigger(...)`
- **예외를 삼킨다.** 자동 생성 실패가 평가·여정을 건드리면 안 된다
- 지표: `journey.reroute.auto.attempted` / `.created` / `.skipped{reason=disabled|quota|rejected|pool-full}`

### 3.4 수정 `RerouteProposalService` — 자동 생성 진입점

지금 `request(userId, journeyId, transferIndex)` 는 **소유자 확인·사용자 오류(409)** 가 섞여 있다.
자동 생성은 사용자에게 돌려줄 오류가 없으므로 진입점을 하나 더 둔다.

```java
/** 방아쇠가 부르는 경로. 거절은 예외가 아니라 빈 결과다 — 돌려줄 사람이 없다. */
public Optional<RerouteProposalView> createForTrigger(Long journeyId, Long userId,
                                                      int transferIndex, Instant now);
```

- 적격성 판정(`RerouteEligibilityPolicy`)은 **그대로 태운다** — 지나간 환승·창 밖은 자동에서도 막는다
- **cooldown 은 다시 묻지 않는다.** 방아쇠가 이미 `TRIGGER` 키로 통과했다(D6)
- 공급자 순회·저장은 사용자 요청과 **같은 코드**를 쓴다
- `txService.store(...)` 에 **출처**를 넘긴다(`AUTO` / `USER_REQUEST`) — PR-2 의 알림이 그 값으로 갈린다

### 3.5 수정 `RerouteTriggerEvaluator` — shadow 를 푼다

```java
metrics.recordTriggerAdmitted(signal.reason().tag());
record(signal);
autoProposeService.proposeAsync(signal, context.userId());   // 신규
```

**클래스 주석의 "제안을 만들지 않는다"를 함께 고친다.** 그 문장이 계약이었고 이 PR 이 그것을 바꾼다 —
주석만 남으면 다음 사람이 코드와 주석 중 어느 쪽이 진짜인지 알 수 없다.

### 3.6 시험(PR-1)

| 경로 | 내용 |
|---|---|
| `RerouteAutoProposeServiceTest` | 스위치 꺼짐·상한 소진·풀 거절이 각각 `skipped` 사유로 남는다. 예외를 삼킨다 |
| `RedisRerouteAutoQuotaTest` | 여정당·전체 상한, KST 일 경계, **장애 시 false** |
| `RerouteTriggerEvaluatorTest`(기존) | 스위치가 꺼져 있으면 자동 생성을 부르지 않는다(기존 단언 무수정) |
| `RerouteAutoProposeIT` | 방아쇠가 켜지는 여정에서 제안이 저장된다 · 사용자 요청 cooldown 이 타지 않는다 · 후보가 빈손이면 아무 일도 없다 |

---

## PR-2 — 제안 알림 (M)

### 4.1 작업

- [ ] `JourneyCheckpoint.REROUTE_PROPOSED` 추가
- [ ] `JourneyNotificationKeys.logical(...)` **접미사 오버로드**(기존 시그니처 동작 불변)
- [ ] `JourneyNotificationEnqueuer.enqueueRerouteProposal(...)` — 만료는 **인자 그대로**(D9),
      payload 는 식별자 + `proposalId`(D10)
- [ ] `JourneyNotificationRepository.cancelPendingByLogicalKey(logicalKey, reason)`
      (`clearAutomatically = false`)
- [ ] `RerouteProposalTxService.store` 에서 **출처가 `AUTO` 일 때만** 알림을 건다.
      같은 트랜잭션에서 같은 환승의 PENDING `MISSED` 를 밀어낸다(D8)
- [ ] 제안이 접히는 세 경로에서 알림 취소(D11) — `supersedeActive`(교체) · `resolveStale`(만료·개정본) ·
      `expireDueBatch`(배치). **배치는 지금 개수만 돌려주므로 `RETURNING id` 로 바꾼다**
- [ ] `FcmMessageFactory` — `REROUTE_PROPOSED` 의 문구·`messageKey`·deep link(`&proposalId=`)
- [ ] 문구 확정: 제목 **"더 빠른 길을 찾았어요"** / 본문 **"앱에서 새 경로를 확인하세요."**
      (개선폭·후보 수는 **넣지 않는다** — 상태를 payload 에 담지 않는다는 계약)

### 4.2 시험(PR-2)

| 경로 | 내용 |
|---|---|
| `JourneyNotificationKeysTest`(기존) | 접미사 오버로드가 기존 키를 바꾸지 않는다 |
| `JourneyNotificationEnqueuerTest` | 만료에 **바닥이 붙지 않는다**, payload 에 상태가 없다 |
| `FcmMessageFactoryTest`(기존) | `REROUTE_PROPOSED` 문구·deep link 에 `proposalId` |
| `RerouteProposalNotificationIT` | 같은 환승에 제안이 두 번 뜨면 알림도 **두 건** · 만료·교체 시 사유와 함께 취소 · **사용자 요청 제안에는 알림 0건** · PENDING `MISSED` 가 밀려난다 |

---

## 5. 검증

```bash
./gradlew test --tests '*Reroute*' --tests '*Notification*' --tests '*Journey*'
```

### 통과해야 하는 것

- [ ] (PR-1) 스위치가 꺼져 있으면 방아쇠가 **후보 생성을 부르지 않는다**(기존 shadow 동작)
- [ ] (PR-1) 상한이 소진되면 자동 생성만 막히고 **사용자 요청은 통과**한다
- [ ] (PR-1) 상한 저장소가 죽으면 자동 생성이 **막힌다**(fail-closed)
- [ ] (PR-1) 자동 생성이 **평가 스레드를 물지 않는다** — 풀이 포화면 거절하고 지표만 남는다
- [ ] (PR-1) 자동 생성이 실패해도 **평가는 성공으로 기록된다**
- [ ] (PR-1) 자동 생성은 `TRIGGER` cooldown 만 쓴다 — 직후 사용자 버튼이 거절되지 않는다
- [ ] (PR-2) 같은 환승에서 제안이 두 번 뜨면 알림이 **두 건**이다
- [ ] (PR-2) 제안 알림 만료가 **제안 TTL 그대로**다
- [ ] (PR-2) 제안이 만료·교체되면 PENDING 이 사유와 함께 취소된다
- [ ] (PR-2) 제안 알림이 나가면 같은 환승의 PENDING `MISSED` 가 밀려난다
- [ ] (PR-2) **사용자 요청 제안에는 알림 행이 생기지 않는다**
- [ ] 기존 알림(위험·접근)·③④⑤ 시험 회귀 없음

### 배포 메모

마이그레이션 없음. 켜는 순서가 **계약**이다.

1. `journey.reroute.enabled=true` — 사용자 요청 경로만 열린다(요금은 사용자가 누른 만큼)
2. Grafana 에서 `journey.reroute.trigger.admitted` 를 며칠 본다 — **자동 생성 시 예상 호출 수**다
3. 그 수로 `daily-limit-*` 를 정하고 `journey.reroute.auto.enabled=true`
4. `journey.notification.sender=fcm` 은 PHASE-5 §1.1 절차를 함께 탄다

## 6. 함정

- **방아쇠 클래스 주석을 함께 고쳐라.** "제안을 만들지 않는다"가 계약으로 적혀 있다
- **평가 스레드에서 동기로 부르지 마라.** 후보 생성은 최악 6.5초다
- **cooldown 을 다시 묻지 마라.** 방아쇠가 이미 `TRIGGER` 키를 태웠다 — 다시 물으면 자기 자신에게 막힌다
- **자동 생성 경로에서 예외를 올리지 마라.** 평가가 실패로 기록돼 지표가 거짓말을 한다
- **사용자 요청 제안에 알림을 걸지 마라.** 그 사람은 화면을 보고 있다 — 출처를 저장 쪽까지 넘기는 이유가 그것이다
- **기존 논리 키 형식을 바꾸지 마라.** 이미 큐에 있는 행과 어긋나면 중복 알림이 난다
- **제안 알림에 만료 바닥을 씌우지 마라.** 만료된 제안을 광고하게 된다
- **배치 sweep 의 `RETURNING id` 로 바꿀 때 기존 호출부(지표)를 함께 고쳐라**
