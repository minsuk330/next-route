# ⑦ journey-evaluator-resilience — 평가기가 사용자 요청을 밀어내지 않게 한다

- 상위 인덱스: [../journey-navigation/PHASE-4.md](../journey-navigation/PHASE-4.md) ⑦
- 선행: ④ `journey-due-evaluator-claim`(머지 #113). ⑤(PR #116)·⑥(PR #117)과 겹치는 파일 없음
- 티어: **M**
- 브랜치: `feat/journey-evaluator-resilience` (fresh `main` 기준)
- 마이그레이션: **없음**
- 작성일: 2026-08-06

여기서 "평가기"는 앱 요청 없이 주기적으로 환승 위험을 다시 계산하는 백그라운드 작업이다
(`JourneyDueEvaluatorScheduler` + `JourneyEvaluationService`, Phase 4 ④에서 신설).

---

## 0. 착수 전 조사 결과 — PHASE-4 ⑦ 항목 4개 중 3개는 이미 있다

| PHASE-4 ⑦ 항목 | 상태 |
|---|---|
| 평가기 전용 스레드 풀 분리 | **있음** — `JourneyEvaluatorPoolConfig`, `journey-eval-` 4스레드 + 큐 200 + AbortPolicy |
| 기능 플래그로 즉시 끄기 | **있음** — `journey.navigation.evaluator.enabled`(기본 false) + worker 프로필 |
| 기존 복원력 자산 재사용 | **일부 있음** — Redis 캐시·SingleFlight·breaker·quota를 검색과 공유해서 쓴다 |
| 실시간 데이터 없을 때 유예 후 UNKNOWN | **없음** |

그래서 이 PR이 실제로 채우는 것은 셋이다.

### 1) 평가기가 쓸 수 있는 provider 동시 호출 수가 스레드 수에 묶여 있다

버스 API로 나가는 모든 호출은 `SearchTimeBusAdapter.call()`을 지나며, 거기서 permit 8개짜리
fair Semaphore(`ApiClientConfig:81`, `busSearchConcurrencyLimiter`)를 획득해야 한다. 못 잡으면
500ms 대기 후 `LIMITED`(조회를 못 했다는 뜻, "버스가 없다"와 구분되는 값)로 응답한다.

이 Semaphore를 **경로 검색 요청 스레드와 평가기 워커 스레드가 함께 쓴다.** 지금 평가기가 permit을
최대 4개까지만 쥐는 이유는 워커 풀 크기가 4이기 때문이지, 어디에도 그렇게 강제한 코드가 없다.
⑧ 부하 실험에서 `poolSize`를 8이나 16으로 올리면 **사용자 검색 몫이 조용히 0이 된다.**

→ 평가기 전용 Semaphore를 하나 두고, 평가기는 그것을 먼저 통과한 뒤에 공용 Semaphore로 간다.
총 동시 호출 수는 안 늘어난다(공용 8은 그대로).

### 2) 평가 경로가 검색용 시간 예산을 그대로 쓴다

`SingleTransferPredictor`는 `transfer.predict.deadline-ms`(2500ms)를 예산으로 잡는다
(`SingleTransferPredictor:57`). 이 값은 사용자가 화면에서 기다리는 검색 기준이다.

평가기는 기다리는 사람이 없고 대신 **배치 상한 4초**(`batchDeadlineMs`)와 **선점 60초**(`leaseSeconds`)가
있다. 환승 2개짜리 여정이면 최대 5초가 걸려 배치 상한을 넘고, 더 밀리면 선점이 만료돼 다른 워커가
같은 여정을 가져가면서 **계산은 다 해 놓고 결과를 버린다**(`LOST_CLAIM`).

→ 평가기가 자기 예산을 명시적으로 넘긴다. 기본 1500ms.

### 3) provider가 한 번 실패하면 곧바로 화면이 "모름"이 된다

지금은 조회 실패·차단이면 그 환승 판정을 `UNKNOWN`으로 만들어 즉시 저장한다. 30초 뒤 복구돼도
그 사이 사용자는 근거 없는 화면을 본다. 직전 판정이 아직 유효한데도 버리는 것이다.

→ 직전 판정이 아직 쓸 만하면(가리키는 도착 시각이 미래 + 계산한 지 유예 시간 안) 덮어쓰지 않고
유지한다. 유예를 넘기면 그때 `UNKNOWN`으로 바꾼다.

### 이번 PR에서 뺀 것 — 일일 호출 예산 분리

검색과 평가기가 `bus.quota.search` 카운터를 함께 쓰는 것은 **정상이다.** 같은 외부 API의 하루 한도를
쓰는 두 소비자라, 카운터를 쪼개면 한쪽이 남기고 다른 쪽이 굶는 상황만 만든다. 사용자와 논의 후 범위에서
제외했다(2026-08-06). 저녁에 예산이 말라 검색이 죽는 문제가 실제로 관측되면 그때 "평가기에만 낮은
상한" 방식으로 다시 검토한다.

---

## 1. 변경 파일

| 파일 | 신규/수정 | 내용 |
|---|---|---|
| `src/main/java/watoo/grd/nextroute/common/config/JourneyEvaluatorPoolConfig.java` | 수정 | `journeyEvaluatorProviderLimiter` Semaphore 빈 추가 |
| `src/main/java/watoo/grd/nextroute/application/journey/service/JourneyEvaluationService.java` | 수정 | 외부 호출 구간을 Semaphore로 감싼다, 유예 정책 적용 |
| `src/main/java/watoo/grd/nextroute/application/journey/service/TransferAssessmentGracePolicy.java` | 신규 | 새 판정과 직전 판정 중 무엇을 저장할지 고르는 순수 계산 |
| `src/main/java/watoo/grd/nextroute/application/route/port/in/PredictTransferUseCase.java` | 수정 | 예산(ms)을 받는 오버로드 추가 |
| `src/main/java/watoo/grd/nextroute/application/route/service/SingleTransferPredictor.java` | 수정 | 넘어온 예산을 쓰고, 없으면 기존 설정값 |
| `src/main/java/watoo/grd/nextroute/application/journey/service/BusTransferTargetArrivalPredictor.java` | 수정 | 평가기 예산을 넘겨 호출 |
| `src/main/java/watoo/grd/nextroute/application/journey/config/JourneyEvaluatorProperties.java` | 수정 | 설정 4개 + 검증 |
| `src/main/java/watoo/grd/nextroute/application/journey/observability/JourneyEvaluationMetrics.java` | 수정 | `recordProviderSlot(result)`, `recordGraceHeld()` |
| `src/main/resources/application.yaml` | 수정 | env override 4개 |
| `docs/plan/journey-navigation/PHASE-4.md` | 수정 | ⑦ 절에 결과 반영 |

### 1-A. 평가기 전용 Semaphore

```java
// JourneyEvaluatorPoolConfig
@Bean("journeyEvaluatorProviderLimiter")
public Semaphore journeyEvaluatorProviderLimiter(JourneyEvaluatorProperties properties) {
    return new Semaphore(properties.getProviderPermits(), true);   // 기본 4, fair
}
```

`JourneyEvaluationService.evaluate()`에서 **외부 호출이 일어나는 구간만** 감싼다.

```
[읽기 트랜잭션] contextLoader.load()          ← Semaphore 밖
   permit 획득 (tryAcquire, providerAcquireMs=300ms)
     loadGeometry() + transferRiskEvaluator.assess()   ← 여기서만 외부 API를 부른다
   permit 반납 (finally)
[쓰기 트랜잭션] scheduleWriter.finish()        ← Semaphore 밖
```

- **DB 작업을 permit 안에 넣지 않는다.** 넣으면 provider 동시성 제한이 DB 처리 시간까지 잡아먹는다
- permit을 못 잡으면 이번 여정은 건너뛴다. 결과를 저장하지 않고 `Result.SKIPPED`로 돌려보낸다 —
  선점이 만료되면(60초) 다른 tick이 다시 잡는다. 여기서 `next_evaluation_at`을 쓰지 않는 이유는
  쓰기 트랜잭션을 한 번 더 열 만큼 급한 상황이 아니기 때문이다
- 지표: `journey.evaluation.provider-slot{result=acquired|rejected}`
- **`@Profile("worker")` 안에 두면 안 된다.** 풀 설정 클래스가 worker 프로필 전용이지만, 이 빈은
  `JourneyEvaluationService`(프로필 무관 `@Service`)가 주입받는다. 별도 `@Configuration`으로 빼거나
  프로필 조건을 없앤다. **컴파일은 되고 부팅에서 깨지는 자리라 주의할 것**

### 1-B. 평가기 전용 시간 예산

```java
// PredictTransferUseCase — 기존 5인자는 그대로 두고 예산 인자만 더한다
default TransferPredictionResult predict(String stopId, String routeId, Integer seq,
                                         Instant userArrivalAt, Mode mode) {
    return predict(stopId, routeId, seq, userArrivalAt, mode, 0L);   // 0 = 설정값 사용
}

TransferPredictionResult predict(String stopId, String routeId, Integer seq,
                                 Instant userArrivalAt, Mode mode, long budgetMs);
```

- `SingleTransferPredictor`: `long budget = budgetMs > 0 ? budgetMs : predictProps.getDeadlineMs();`
  나머지 로직(`remaining(deadline)` 검사)은 그대로
- `BusTransferTargetArrivalPredictor`가 `properties.getPredictDeadlineMs()`(기본 1500)를 넘긴다
- 지하철 경로(`SubwayTransferTargetArrivalPredictor`)는 그대로 둔다. 역별 조회 한 번이라 예산을
  나눌 대상이 없다

> **함정** — ④a에서 4인자→5인자로 바꿀 때 겪은 것과 같다. 테스트에서 `given(mock.predict(4인자))`만
> 스텁하면 프로덕션은 5인자로 부르므로 스텁이 안 걸려 조용히 null이 온다. **6인자 시그니처로 스텁하거나
> `any()` 매처를 쓴다.** 기존 테스트(`JourneyEvaluationIT:243` 등)가 5인자 스텁을 쓰고 있으므로 함께 고친다.

### 1-C. 유예 후 UNKNOWN

```java
@Component
public class TransferAssessmentGracePolicy {

    /**
     * @param fresh    이번 계산 결과
     * @param previous 저장돼 있던 직전 판정. 없으면 null
     * @return 저장할 판정. 직전 것을 유지하기로 하면 previous 를 그대로 돌려준다
     */
    public TransferRiskAssessment choose(TransferRiskAssessment fresh,
                                         TransferRiskAssessment previous, Instant now);
}
```

직전 판정을 유지하는 조건 — **넷 다 만족할 때만**:

1. 새 판정이 `UNKNOWN`이다
2. 직전 판정이 있고 `UNKNOWN`이 아니다
3. 직전 판정이 가리키는 도착 시각(`nextBusArrivalAt`)이 아직 안 지났다
4. 직전 판정을 계산한 지 `unknownGraceSeconds`(기본 120초)가 안 지났다

3·4는 ④b의 조회 낡음 규칙(`StoredJourneyEvaluationReader:88`)과 같은 모양이다 — **읽는 쪽이 낡았다고
버릴 값을 쓰는 쪽이 붙잡고 있으면 안 된다.**

- 유지하면 값이 그대로이므로 `changed=false`가 되어 평가 번호(`evaluationVersion`)가 안 오른다.
  기존 `hasChanged` 비교를 **유지 결정 후의 판정 목록**으로 해야 이 성질이 자동으로 나온다
- 지표: `journey.evaluation.grace-held` 카운터
- 유예를 넘기면 그대로 `UNKNOWN`을 저장한다. 무한정 붙잡지 않는다

### 1-D. 설정 (`JourneyEvaluatorProperties`)

```java
/** 평가기가 동시에 외부 provider 를 부를 수 있는 수. 워커 스레드 수와 분리한다. */
private int providerPermits = 4;

/** provider 슬롯을 기다리는 시간. 넘으면 이번 여정은 건너뛴다. */
private long providerAcquireMs = 300;

/** 환승 예측 한 건의 시간 예산. 검색용(2500ms)보다 짧다 — 기다리는 사용자가 없다. */
private long predictDeadlineMs = 1_500;

/** provider 가 답을 못 줄 때 직전 판정을 붙잡고 있는 시간. */
private long unknownGraceSeconds = 120;
```

검증(`validate()`)에 추가:

- 넷 다 0보다 커야 한다
- `predictDeadlineMs * 2 <= batchDeadlineMs` — 환승 2개짜리 여정이 배치 상한 안에 끝나야 한다.
  어기면 매 tick 미완료가 나고 그 이유가 로그에 안 드러난다
- `unknownGraceSeconds <= staleAfterSeconds` — 조회가 버릴 값을 평가기가 붙잡으면 안 된다

### 1-E. yaml

```yaml
journey:
  navigation:
    evaluator:
      provider-permits: ${JOURNEY_EVALUATOR_PROVIDER_PERMITS:4}
      provider-acquire-ms: ${JOURNEY_EVALUATOR_PROVIDER_ACQUIRE_MS:300}
      predict-deadline-ms: ${JOURNEY_EVALUATOR_PREDICT_DEADLINE_MS:1500}
      unknown-grace-seconds: ${JOURNEY_EVALUATOR_UNKNOWN_GRACE_SECONDS:120}
```

---

## 2. 작업 순서

- [ ] **1. 브랜치** — `git fetch origin && git checkout -b feat/journey-evaluator-resilience origin/main`.
      **작업트리에 커밋 안 된 변경이 있으면 먼저 `git stash push -m`으로 대피시킨다**(2026-08-05에
      `reset --hard`로 남의 문서를 날린 적 있다)
- [ ] **2. 설정 + 검증 + yaml** → `./gradlew compileJava`
- [ ] **3. 유예 정책 + 단위시험** — `TransferAssessmentGracePolicy` + `...Test`
      → `./gradlew test --tests '*TransferAssessmentGracePolicyTest'`
- [ ] **4. 예산 전달** — `PredictTransferUseCase` 오버로드 → `SingleTransferPredictor` →
      `BusTransferTargetArrivalPredictor`. **기존 5인자 스텁 테스트를 함께 고친다**
      → `./gradlew test --tests '*SingleTransferPredictorTest' --tests '*TransferTargetArrivalPredictorTest'`
- [ ] **5. Semaphore 빈 + 배선** — 빈이 worker 프로필에 갇히지 않게 할 것
      → `./gradlew compileJava`
- [ ] **6. 서비스 배선 + 지표** — `JourneyEvaluationService`
      → `./gradlew test --tests '*JourneyEvaluationIT'`
- [ ] **7. 통합 시험 추가** — 아래 3-3
- [ ] **8. 문서** — `PHASE-4.md` ⑦ 절
- [ ] **9. 전체 스위트** — `./gradlew test` (약 3분)
- [ ] **10. PR** — 제목 `feat(journey): 평가기가 사용자 요청 자원을 밀어내지 않게 한다`

---

## 3. 테스트 상세

### 3-1. `TransferAssessmentGracePolicyTest` (단위)

- 새 판정이 UNKNOWN이고 직전이 아직 유효하면 **직전을 유지한다**
- 직전이 가리키는 도착 시각이 지났으면 UNKNOWN으로 바꾼다
- 직전을 계산한 지 유예를 넘겼으면 UNKNOWN으로 바꾼다
- 직전도 UNKNOWN이면 그대로 UNKNOWN
- 직전이 없으면 그대로 UNKNOWN
- 새 판정이 UNKNOWN이 아니면 항상 새 판정을 쓴다(유예는 실패일 때만 동작한다)

### 3-2. `SingleTransferPredictorTest` 추가

- 예산을 0으로 넘기면 설정값(2500ms)을 쓴다 — 기존 동작이 안 바뀐다
- 예산을 짧게 넘기면 그 예산으로 잘린다(`LIMITED`)

### 3-3. `JourneyEvaluationIT` 추가

- **provider가 막혔을 때 직전 판정을 유지한다** — 정상 평가 한 번 → provider 차단 상태로 다시 평가
  → 저장된 판정이 그대로이고 평가 번호가 안 오른다
- **유예를 넘기면 UNKNOWN이 된다** — `transfer_connection.calculated_at`을 SQL로 과거(유예+α)로 민 뒤
  다시 평가 → `status='UNKNOWN'`
- **provider 슬롯을 못 잡으면 저장하지 않는다** — permit을 0으로 만든 설정으로 평가 →
  `Result.SKIPPED`, `transfer_connection`이 안 바뀐다

> 시계를 건드리지 않고 `calculated_at`을 SQL로 미는 방식은 ⑥의 `agePreviousEvaluation`과 같다.

---

## 4. 함정

- **Semaphore 빈을 `@Profile("worker")` 아래 두지 마라.** `JourneyEvaluationService`는 프로필 조건이
  없어서 다른 프로필에서 주입 실패로 부팅이 깨진다
- **permit 안에서 DB를 만지지 마라.** 외부 호출 구간만 감싼다
- **`hasChanged` 비교는 유예 결정 뒤에 해야 한다.** 순서를 바꾸면 직전 판정을 유지했는데도 평가 번호가
  올라 앱이 같은 값을 다시 받는다
- **6인자 스텁** — 5인자만 스텁한 기존 테스트는 조용히 null을 받는다(④a에서 겪음)
- **`predictDeadlineMs`를 batchDeadlineMs보다 크게 두지 마라.** 검증으로 막지만 값을 만질 때 기억할 것
- 평가기는 여전히 기본 off. 이 PR도 그 상태를 바꾸지 않는다

---

## 5. 완료 판정

- [ ] `./gradlew test` 전부 통과, 회귀 0
- [ ] `poolSize`를 올려도 평가기의 provider 동시 호출이 `providerPermits`를 안 넘는다(단위 시험)
- [ ] provider 실패 시 유예 안에서는 직전 판정이 유지되고 평가 번호가 안 오른다(IT)
- [ ] 유예를 넘기면 UNKNOWN으로 바뀐다(IT)
- [ ] 잘못된 설정 조합에서 부팅이 실패한다
- [ ] 마이그레이션 없음

## 6. 이 PR이 하지 않는 것

- 일일 호출 예산 분리 — 같은 외부 API를 쓰는 소비자끼리 나누면 굶는 쪽이 생긴다. 범위 제외(2026-08-06)
- 부하 실험·`poolSize` 실제 조정 — ⑧ `journey-load-harness`
- 검색 경로의 Semaphore permit 수(8) 변경 — 총 동시 호출량은 이 PR에서 안 늘린다
- 지하철 경로 예산 — 호출이 하나뿐이라 나눌 것이 없다
