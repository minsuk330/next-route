# ⑥ journey-adaptive-interval — 다음 평가 시각을 상황에 따라 정한다

- 상위 인덱스: [../journey-navigation/PHASE-4.md](../journey-navigation/PHASE-4.md) ⑥
- 선행: ④ `journey-due-evaluator-claim`(머지 #113). ⑤ 와는 독립 — 겹치는 파일이 없다
- 티어: **M**
- 브랜치: `feat/journey-adaptive-interval` (fresh `main` 기준)
- 마이그레이션: **없음**
- 작성일: 2026-08-05

---

## 0. 결론부터

**지금은 모든 여정이 30초 고정이다. 이것을 "얼마나 급한가"에 따라 다르게 정한다.**

비유하면 — 끓는 냄비는 자주 들여다보고, 아직 불도 안 켠 냄비는 가끔 본다. 지금은 둘 다 30초마다
본다. 환승이 5분 뒤인 사용자와 40분 뒤인 사용자에게 같은 주기를 쓰면, 급한 쪽은 늦게 알고 한가한
쪽에는 외부 API 를 낭비한다.

현재 코드(`JourneyEvaluationService:102`):

```java
long spread = Math.floorMod(journeyId, properties.getJitterSeconds());
long seconds = properties.getIntervalSeconds() * (upstreamBlocked ? 4 : 1);
return now.plusSeconds(seconds + spread);
```

바꿀 것은 `seconds` 를 정하는 방법 하나다. 흩뜨리기(jitter)와 막힘 backoff 는 그대로 둔다.

### 규칙 (설정으로 뺀다)

우선순위가 높은 것부터 본다. 먼저 걸리는 규칙이 이긴다.

| # | 조건 | 다음 간격 |
|---|---|---|
| 1 | provider 가 막혔다(`upstreamBlocked`) | 기본 × 4 (**기존 유지**) |
| 2 | 남은 환승 중 `AT_RISK`·`MISSED` 가 있다 | `minIntervalSeconds`(10) |
| 3 | 가장 가까운 환승까지 ≤ `nearSeconds`(300) | `minIntervalSeconds`(10) |
| 4 | ≤ `farSeconds`(900) | `intervalSeconds`(30, 기본) |
| 5 | 그보다 멀거나 남은 환승이 없다 | `idleIntervalSeconds`(120) |
| 6 | 위 값이 정해진 뒤, **직전 평가와 아무것도 안 바뀌었다** | 직전 간격 × `unchangedBackoffMultiplier`(2) |

마지막에 `[minIntervalSeconds, maxIntervalSeconds]` 로 자르고 jitter 를 더한다.

- **2·3 이 "임박했으면 짧게"**, 5 가 "멀면 길게"다
- **6 이 backoff.** 값이 계속 그대로면 물어볼수록 얻는 것이 없다는 뜻이므로 점점 뜸하게 본다.
  단 2·3 에 걸리는 급한 상태에서는 backoff 를 적용하지 않는다 — 급한데 뜸해지면 알림이 늦는다
- **`minIntervalSeconds` 가 "한 여정이 계속 짧은 간격만 요구하지 못하게" 하는 바닥**이다(PHASE-4 ⑥ 3항)

### backoff 를 컬럼 없이 만드는 방법 (이 PR 의 설계 핵심)

"직전 간격"을 알아야 곱할 수 있는데, 지금 그것을 저장하는 곳이 없다. 새 컬럼을 만들면 마이그레이션이
따라온다. **그럴 필요가 없다** — 직전 평가 시각이 이미 저장돼 있다.

- `TransferConnection.calculated_at` 이 평가할 때마다 갱신된다
  (`JourneyEvaluationScheduleWriter:61` → `TransferAssessmentWriter.write`, 값이 바뀌든 말든 매번 쓴다)
- 그 값은 `JourneyEvaluationContextLoader` 가 이미 읽어 `previousAssessments` 로 넘겨준다
  (`TransferRiskAssessment.calculatedAt`)
- 따라서 **직전 간격 ≈ `now - max(previous.calculatedAt)`**

즉 `journey_session` 에 컬럼을 더하지 않고도 기하급수 backoff 가 된다. 저장하는 대신 **이미 저장된
것에서 되읽는다.**

주의할 경계 둘:

- **환승이 없는 여정**은 `previousAssessments` 가 비어 직전 간격을 모른다 → 규칙 5(`idleInterval`)에서
  시작해 backoff 로 `maxIntervalSeconds` 까지 물러난다. 바람직한 동작이다
- **되읽은 간격이 터무니없이 클 때**(워커가 오래 멈췄다가 돌아온 경우) 곱하면 폭주한다 →
  곱하기 전에 `maxIntervalSeconds` 로 먼저 자른다

### 최대 간격의 상한은 조회 경로가 정한다 ⚠

**`maxIntervalSeconds` 는 `staleAfterSeconds`(300)보다 작아야 한다.** 이유는 ④b 의 읽기 규칙이다 —
`StoredJourneyEvaluationReader:95` 가 `calculatedAt` 이 `staleAfter` 보다 오래되면 그 판정을 **낡음으로
보고 버린다.** 평가 간격을 300초로 늘리면 앱은 평가기가 정상인데도 UNKNOWN 을 보게 된다.

그래서 기본값은 `maxIntervalSeconds = 180`, 그리고 `@PostConstruct validate()` 에서 관계를 강제한다.
둘 중 하나를 바꿀 때 다른 쪽을 잊는 것이 이 기능에서 가장 하기 쉬운 실수다.

---

## 1. 변경 파일

| 파일 | 신규/수정 | 내용 |
|---|---|---|
| `src/main/java/watoo/grd/nextroute/application/journey/service/JourneyEvaluationIntervalPolicy.java` | 신규 | 규칙 표 하나. 순수 계산(외부·DB 없음) |
| `src/main/java/watoo/grd/nextroute/application/journey/service/JourneyEvaluationService.java` | 수정 | `nextEvaluationAt` 이 정책에 위임. 직전 간격을 `context` 에서 구해 넘긴다 |
| `src/main/java/watoo/grd/nextroute/application/journey/service/JourneyEvaluationContextLoader.java` | 수정 | `JourneyEvaluationContext` 에 `currentLegIndex` 추가(지나간 환승 제외용) |
| `src/main/java/watoo/grd/nextroute/application/journey/config/JourneyEvaluatorProperties.java` | 수정 | 간격 설정 5개 + 검증 |
| `src/main/java/watoo/grd/nextroute/application/journey/observability/JourneyEvaluationMetrics.java` | 수정 | `recordInterval(reason, seconds)` — 어느 규칙이 얼마를 골랐는지 |
| `src/main/resources/application.yaml` | 수정 | env override 5개 |
| `docs/plan/journey-navigation/PHASE-4.md` | 수정 | ⑥ 절에 결과 반영 |

### 정책 시그니처

```java
@Component
@RequiredArgsConstructor
public class JourneyEvaluationIntervalPolicy {

    /**
     * @param previousInterval 직전 평가와의 간격. 모르면 null(첫 평가·환승 없는 여정)
     * @param changed          앱에 보일 값이 바뀌었는지. false 면 backoff 대상
     */
    public Decision decide(List<TransferRiskAssessment> assessments, int currentLegIndex,
                           Instant now, Duration previousInterval,
                           boolean changed, boolean upstreamBlocked);

    /** @param reason 어느 규칙이 골랐는지. 지표 tag 로 쓴다(저카디널리티) */
    public record Decision(Duration interval, Reason reason) {}

    public enum Reason { BLOCKED, AT_RISK, NEAR, NORMAL, IDLE, BACKOFF }
}
```

- **순수 함수로 둔다.** DB·시계·외부 호출 없음 → 규칙 표를 표 그대로 단위 시험할 수 있다
- `assessments` 는 `isUpcoming(currentLegIndex)`(`TransferRiskAssessment:104`)로 거른다. 이미 지나온
  환승의 `userReadyAt` 은 과거라 그것만 보면 늘 "임박"이 된다

### `JourneyEvaluationService` 수정

```java
Duration previousInterval = previousInterval(context, now);   // now - max(calculatedAt)
Decision decision = intervalPolicy.decide(batch.assessments(), context.currentLegIndex(),
        now, previousInterval, changed, batch.upstreamBlocked());
metrics.recordInterval(decision.reason().name().toLowerCase(), decision.interval().toSeconds());
Instant nextAt = now.plus(decision.interval()).plusSeconds(spread(context.journeyId()));
```

- jitter(`Math.floorMod(journeyId, jitterSeconds)`)는 지금 자리 그대로 유지 — 여러 여정이 한 tick 에
  몰리는 것을 막는 별개 목적이다
- `BLOCKED_BACKOFF_MULTIPLIER` 상수는 정책 클래스로 옮긴다(규칙이 한곳에 모여야 한다)
- 지표 기록 위치는 서비스다. 정책은 계산만 한다

### 설정 (`JourneyEvaluatorProperties`)

```java
private long minIntervalSeconds = 10;    // 바닥. 한 여정이 이보다 자주 요구할 수 없다
private long maxIntervalSeconds = 180;   // 천장. staleAfterSeconds 보다 작아야 한다
private long idleIntervalSeconds = 120;  // 환승이 멀거나 없을 때
private long nearTransferSeconds = 300;  // 이 안이면 임박
private long farTransferSeconds = 900;   // 이 밖이면 한가
private int unchangedBackoffMultiplier = 2;
```

`validate()` 에 추가할 관계(모두 위반 시 부팅 실패):

- `0 < minIntervalSeconds ≤ intervalSeconds ≤ maxIntervalSeconds`
- `idleIntervalSeconds ≤ maxIntervalSeconds`
- `nearTransferSeconds < farTransferSeconds`
- **`maxIntervalSeconds < staleAfterSeconds`** ← 위 ⚠ 의 이유. 메시지에 이유를 적는다
- `unchangedBackoffMultiplier ≥ 1`

기존 관계(`leaseSeconds > intervalSeconds`, `jitterSeconds < intervalSeconds`)는 그대로 둔다.

### yaml

```yaml
journey:
  navigation:
    evaluator:
      min-interval-seconds: ${JOURNEY_EVALUATOR_MIN_INTERVAL_SECONDS:10}
      max-interval-seconds: ${JOURNEY_EVALUATOR_MAX_INTERVAL_SECONDS:180}
      idle-interval-seconds: ${JOURNEY_EVALUATOR_IDLE_INTERVAL_SECONDS:120}
      near-transfer-seconds: ${JOURNEY_EVALUATOR_NEAR_TRANSFER_SECONDS:300}
      far-transfer-seconds: ${JOURNEY_EVALUATOR_FAR_TRANSFER_SECONDS:900}
      unchanged-backoff-multiplier: ${JOURNEY_EVALUATOR_UNCHANGED_BACKOFF:2}
```

기존 `journey.navigation.evaluator` 블록(`application.yaml:343` 부터, `enabled`·`interval-seconds` 등이
이미 있다)에 이어 붙인다.

---

## 2. 작업 순서

- [ ] **1. 브랜치** — `git checkout main && git pull && git checkout -b feat/journey-adaptive-interval`
      (작업트리의 `docs/plan/journey-navigation/MOBILE_PLAN.md` 수정본은 무관하므로 스테이징하지 않는다)
- [ ] **2. 설정 + 검증** — `JourneyEvaluatorProperties` 필드·`validate()`·yaml
      → `./gradlew compileJava`
- [ ] **3. 정책 + 단위시험** — `JourneyEvaluationIntervalPolicy` + `JourneyEvaluationIntervalPolicyTest`
      → `./gradlew test --tests '*JourneyEvaluationIntervalPolicyTest'`
- [ ] **4. 배선** — `JourneyEvaluationContextLoader`(currentLegIndex)·`JourneyEvaluationService`·
      `JourneyEvaluationMetrics`
      → `./gradlew compileJava`
- [ ] **5. 통합 시험** — `JourneyEvaluationIT` 에 간격 시험 2개 추가
      → `./gradlew test --tests '*JourneyEvaluationIT'`
- [ ] **6. 문서** — `PHASE-4.md` ⑥ 절 갱신
- [ ] **7. 전체 스위트** — `./gradlew test` (약 3분. ⑤ 머지 전이면 1074, 뒤면 그 이상)
- [ ] **8. PR** — 제목 `feat(journey): 평가 간격을 상황에 맞춰 정한다`

---

## 3. 테스트 상세

### 3-1. `JourneyEvaluationIntervalPolicyTest` (단위, 규칙 표 그대로)

정책이 순수 함수라 시험이 표를 그대로 옮긴 모양이 된다.

- 막힘이 다른 모든 규칙을 이긴다(임박한 상태여도 backoff 배수 적용)
- `AT_RISK` 하나라도 있으면 최소 간격
- 가장 가까운 환승이 4분이면 최소 간격, 10분이면 기본, 20분이면 idle
- 환승이 하나도 없으면 idle
- **지나온 환승은 세지 않는다** — `toLegIndex < currentLegIndex` 인 환승의 과거 `userReadyAt` 때문에
  임박으로 잘못 판정하지 않는다
- 안 바뀌었으면 직전 간격 × 2, 다시 안 바뀌면 또 × 2, 천장에서 멈춘다
- **급한 상태(2·3)에서는 backoff 하지 않는다**
- 직전 간격을 모르면(null) 규칙 값을 그대로 쓴다
- 되읽은 직전 간격이 천장보다 크면 천장으로 자른 뒤 곱한다(폭주 방지)
- 결과는 항상 `[min, max]` 안이다

### 3-2. `JourneyEvaluationIT` 추가 (기존 파일에 2개)

- **임박하면 다음 평가가 가까워진다** — 환승을 곧(≤5분)으로 만들고 평가 → `next_evaluation_at` 이
  `now + 기본간격` 보다 앞이다
- **아무것도 안 바뀌면 다음 평가가 멀어진다** — 같은 값으로 두 번 평가(`givenFixedRealtimeArrival` +
  `makeDue()` 사용, 기존 `doesNotBumpWhenNothingChanges` 와 같은 준비) → 두 번째 간격이 첫 번째보다
  길다

> `given(mock.method(...))` 이중 스텁 금지. **`willAnswer(...).given(mock)`** 을 쓴다(④a 함정).

---

## 4. 함정

- **`maxIntervalSeconds < staleAfterSeconds`** — 어기면 앱이 정상 상태에서 UNKNOWN 을 본다. 검증으로
  부팅을 막는다
- **jitter 를 간격에 곱하지 마라.** 지금처럼 더하기만 한다. 곱하면 최소 간격 바닥이 무너진다
- **지나온 환승 제외를 잊지 마라.** 그러면 여정 후반부가 영원히 최소 간격으로 돌아 외부 호출이 는다
- **단계 전이·여정 시작은 지금도 즉시 평가를 심는다**(`JourneyCommandService:134`,
  `JourneyStartTxService:90`). 이 PR 은 그 경로를 건드리지 않는다 — 상황이 크게 바뀌는 순간은 간격
  규칙과 무관하게 바로 본다
- **평가기는 여전히 기본 off**. 이 PR 도 그 상태를 바꾸지 않는다
- 지표 tag 는 `Reason` enum 이름만 쓴다(저카디널리티). 초 값은 tag 가 아니라 값으로 기록

---

## 5. 완료 판정

- [ ] `./gradlew test` 전부 통과, 회귀 0
- [ ] 규칙 표 6줄이 각각 단위 시험으로 못박혀 있음
- [ ] 임박·정체 두 상황에서 `next_evaluation_at` 이 실제로 달라짐이 IT 로 확인됨
- [ ] `max-interval-seconds ≥ stale-after-seconds` 로 두면 부팅이 실패함
- [ ] 마이그레이션 없음(컬럼 추가 없이 backoff 동작)

## 6. 이 PR 이 하지 않는 것

- 평가기 전용 pool·deadline·bulkhead — ⑦ `journey-evaluator-resilience`
- 부하 실험·before/after 보고서 — ⑧ `journey-load-harness`
- 알림 발송 시점 조정 — Phase 5(여정 알림 테이블이 아직 없다)
- `staleAfterSeconds` 자체의 재조정 — 값을 바꾸려면 조회 계약(④b)을 함께 봐야 한다
