# journey-due-evaluator-claim — 실행 계획 (L)

- 상위: [PHASE-4.md](../journey-navigation/PHASE-4.md) ④
- 근거: PLAN §6.4 평가 트리거, SC-9(대기 ETA 근거), SC-13(`snapshot p95 500ms`), REQUIREMENTS T·G
- 선행: ① #108, ② #109, ③ #110, 그리고 판정 저장 버그 수정 #111 (모두 main)
- 사람용 요약: [SUMMARY.md](./SUMMARY.md)
- 작성일: 2026-08-04
- 티어: **L** — 조회·평가가 같은 행을 동시에 만지는 경계 + 예측 앵커 변경 + 실시간 호출 주체 이동이 얽힌다

이 PR 은 **④a** 다. 조회 경로 전환은 후속 PR **④b `journey-eval-read-path`** 로 분리한다(§8).

---

## 0. 이 PR 이 바꾸는 한 가지

지금은 **아무도 묻지 않으면 아무 일도 없다.** 앱이 조회할 때 그 요청 안에서 실시간 도착을 부르고 환승 위험을 판정한다. 이 PR 은 그 계산을 **요청과 무관한 주기 작업**으로 옮긴다. 조회 경로는 이 PR 에서 건드리지 않는다 — 계약을 그대로 둔 채 평가기만 세워, 되돌리기 비용을 낮춘다.

함께 고치는 결함이 하나 있다. **다가오는 환승의 대기 ETA 가 지금은 항상 UNKNOWN 이다.** `WaitBusArrivalPredictor:53` 이 아직 시작하지 않은 단계에 대해 즉시 `unknown()` 을 돌려주기 때문이다. 사용자가 정말 알고 싶은 값 — "내가 그 정류장에 도착할 때 그 버스가 언제 오나" — 을 이 PR 에서 계산하게 만든다.

## 1. 확정된 설계 결정

| # | 결정 | 이유 |
|---|---|---|
| D1 | 예측 앵커는 `now` 가 아니라 **`userReadyAt`** | 사용자 관심사는 "정류장에 도착할 시점의 버스". 앵커가 `now` 면 이미 지나갈 버스를 고른다 |
| D2 | 실시간 우선, 미포착 시 모델. **기존 `SingleTransferPredictor` 를 그대로 쓴다** | 그 규칙(`pickRealtime` → `modelFallback`)이 이미 앵커 기준으로 구현돼 있다. 여정 쪽에서 다시 만들면 두 판정이 어긋난다 |
| D3 | 무엇으로 계산했는지 **DB 에 남기고 앱에는 노출하지 않는다** | 사용자는 실시간인지 모델인지 궁금하지 않다. 운영은 알아야 한다 → `transfer_connection.source`·`model_version` |
| D4 | claim 은 **`next_evaluation_at` 을 미래로 밀어** 선점한다 | 컬럼 추가 없이 lease 효과. 워커가 죽으면 lease 만료 후 자연 재시도 |
| D5 | tick 당 배치 claim → **worker pool 병렬 처리 → 배치 완료 후 tick 종료** | backlog 관측이 가능하고, 느린 provider 하나가 claim 자체를 막지 않는다 |
| D6 | 평가 간격은 **고정 30초** | 상황별 간격은 ⑥ 의 몫. 여기서 둘을 섞으면 측정 기준이 흔들린다 |
| D7 | 조회 경로 **무변경**, flag 로 평가기를 끌 수 있다 | ④b 와 분리해 되돌리기 쉽게 |
| D8 | 마이그레이션 **없음이 목표** | V35 `idx_journey_due` 가 claim 조건을 덮는다. 실행계획 확인 후 필요하면 V39 |
| D9 | **claim 한 lease 값 자체를 소유권 토큰으로 쓴다** | lease 가 만료된 뒤 느렸던 이전 워커가 낡은 판정을 최신 위에 덮는 것을 막는다. 컬럼 추가 없음 (codex 리뷰 1) |
| D10 | `evaluationVersion` 은 **상태뿐 아니라 시각·출처가 바뀌어도** 올린다 | 조회 API 가 이 번호로 204 를 판단한다. 상태 그대로 도착 시각만 바뀌면 앱이 새 ETA 를 영원히 못 본다 (codex 리뷰 5) |
| D11 | 버스도 **앵커 horizon** 을 둔다(기본 15분) | 실시간은 다음 1~2대만, 모델은 현재 운행 차량만 본다. 먼 미래 환승은 어차피 UNKNOWN 이므로 호출 자체를 하지 않는다 (codex 리뷰 3) |
| D12 | 평가기가 켜져 있으면 **조회 경로는 저장하지 않는다** | 쓰기 주체를 하나로 만든다. 켜진 상태에서 둘이 같은 행을 쓰면 낡은 계산이 최신을 덮는다 (codex 리뷰 2) |

## 2. 신규·변경 파일

### 2.1 신규

```
src/main/java/watoo/grd/nextroute/application/journey/config/JourneyEvaluatorProperties.java
src/main/java/watoo/grd/nextroute/application/journey/port/out/TransferTargetArrivalPredictor.java
src/main/java/watoo/grd/nextroute/application/journey/service/BusTransferTargetArrivalPredictor.java
src/main/java/watoo/grd/nextroute/application/journey/service/SubwayTransferTargetArrivalPredictor.java
src/main/java/watoo/grd/nextroute/application/journey/service/TransferTargetArrivalService.java
src/main/java/watoo/grd/nextroute/application/journey/service/JourneyEvaluationService.java
src/main/java/watoo/grd/nextroute/application/journey/service/JourneyEvaluationScheduleWriter.java
src/main/java/watoo/grd/nextroute/application/journey/observability/JourneyEvaluationMetrics.java
src/main/java/watoo/grd/nextroute/infrastructure/adapter/in/scheduler/journey/JourneyDueEvaluatorScheduler.java
src/main/java/watoo/grd/nextroute/common/config/JourneyEvaluatorPoolConfig.java
```

### 2.2 변경

| 파일 | 변경 |
|---|---|
| `domain/journey/repository/JourneySessionRepository.java` | `claimDueBatch` 추가 |
| `domain/journey/entity/JourneySession.java` | `scheduleEvaluationAt(Instant)` 추가 |
| `domain/journey/entity/JourneyLeg.java` | `applyBoardingPrediction(...)` 추가 (대기 leg 예측 저장) |
| `application/journey/service/JourneyStartTxService.java` | 여정 생성 시 `nextEvaluationAt = now` |
| `application/journey/service/JourneyCommandService.java` | 단계 전이 시 `nextEvaluationAt = now` |
| `application/journey/service/TransferRiskEvaluator.java` | 앵커 기반 대기 예측으로 전환, `source` 를 예측 출처로 기록, **계산과 저장을 메서드로 분리**(D12) |
| `application/journey/service/JourneySnapshotEtaComposer.java` | 평가기가 켜져 있으면 **저장하지 않는 계산 메서드**를 부른다(D12) |
| `application/journey/service/TransferAssessmentWriter.java` | `source`·`modelVersion`·`dataObservedAt` 을 판정에서 받아 저장 |
| `application/journey/dto/TransferRiskAssessment.java` | `source`·`modelVersion` 필드 추가 |
| `application.yaml` | `journey.navigation.evaluator.*` |

## 3. 구현 상세

### 3.1 claim 쿼리 — `JourneySessionRepository.claimDueBatch`

`②` 의 `claimExpiredBatch` 와 같은 모양이다. native SQL 한 문장으로 고르고 밀어 놓는다.

```sql
WITH picked AS (
    SELECT s.id FROM journey_session s
    WHERE s.status = 'ACTIVE' AND s.deleted_at IS NULL
      AND s.next_evaluation_at IS NOT NULL
      AND s.next_evaluation_at <= :now
    ORDER BY s.next_evaluation_at
    LIMIT :batchSize
    FOR UPDATE OF s SKIP LOCKED)
UPDATE journey_session j
   SET next_evaluation_at = :leaseUntil
FROM picked
WHERE j.id = picked.id
RETURNING j.id
```

```java
@Query(value = "...", nativeQuery = true)
List<Long> claimDueBatch(@Param("now") Instant now,
                         @Param("leaseUntil") Instant leaseUntil,
                         @Param("batchSize") int batchSize);
```

- `@Modifying` 을 붙이지 않는다(`RETURNING`). `@Transactional` 안에서만 부른다
- `leaseUntil = now + evaluationLeaseSeconds`(기본 60). 평가가 끝나면 실제 다음 시각으로 덮어쓴다. 워커가 죽으면 60초 뒤 다시 잡힌다
- V35 `idx_journey_due` = `(next_evaluation_at) WHERE status='ACTIVE' AND deleted_at IS NULL` 를 그대로 쓴다. 조건에 칼럼 연산을 넣지 말 것
- 착수 시 `EXPLAIN` 으로 인덱스 사용을 확인하고 PLAN 에 결과를 덧붙인다. 인덱스가 안 붙으면 V39 로 부분 인덱스 추가

**`leaseUntil` 은 소유권 증서다(D9).** 여정마다 값이 다르게 나오도록 **여정별로 계산한 값을 그대로 되받아** 저장 조건에 쓴다. 그래서 claim 은 여정 번호와 함께 그 번호에 심은 lease 시각을 함께 돌려줘야 한다.

```java
public record DueClaim(Long journeyId, Instant leaseUntil) {}
```

배치 claim 은 한 문장이지만 `leaseUntil` 은 배치 안에서 여정마다 조금씩 다르게 만든다 — `now + lease + (journeyId % jitterSeconds)`. 이유가 둘이다.

1. 같은 값이면 두 워커가 우연히 같은 토큰을 들 수 있다(같은 초에 같은 lease 를 계산한 경우). 값이 갈리면 토큰이 실제로 소유권을 가른다
2. 다음 평가가 한 tick 에 몰리지 않고 흩어진다(재시도 몰림 방지, codex 리뷰 5)

구현은 SQL 안에서 계산한다 — `:now + :leaseInterval + (j.id % :jitterSeconds) * interval '1 second'` 를 `SET` 과 `RETURNING` 에 함께 쓴다. `RETURNING j.id, j.next_evaluation_at` 로 심은 값을 되받는다.

### 3.2 평가 대상이 되게 만들기 (seeding)

`next_evaluation_at` 은 지금 아무도 채우지 않는다. 채우는 자리는 둘뿐이다.

- `JourneyStartTxService.start` — 여정 저장 시 `session.scheduleEvaluationAt(now)`. 시작 직후 첫 평가가 돈다
- `JourneyCommandService.applyTransition` — `transition.newCurrentLegIndex() != null` 일 때 `journey.scheduleEvaluationAt(appliedAt)`. 단계가 바뀌면 상황이 급변하므로 다음 tick 을 기다리지 않는다
- 종료 시 비우는 것은 이미 있다 — `claimExpiredBatch`(②)와 `JourneySession.end` 경로. **`end()` 에도 `nextEvaluationAt = null` 을 추가한다**(사용자 취소·완료 경로가 평가 대상에 남지 않게)

progress 는 건드리지 않는다. 15초마다 다음 평가 시각을 앞당기면 평가기가 사실상 progress 주기로 돌아 호출량이 폭증한다. 상황별 앞당김은 ⑥.

### 3.3 앵커 기반 대기 leg 예측 (이 PR 의 핵심)

**문제.** `CurrentLegArrivalPredictor` 는 "지금 진행 중인 단계가 언제 끝나나"용이다. 그래서 두 구현이 모두 `state != ACTIVE → unknown` 가드를 갖고(`WaitBusArrivalPredictor:53`, `WaitSubwayArrivalPredictor:52`), 예측 앵커로 `now` 를 쓴다. 다가오는 환승은 둘 다 틀린 전제다.

**해결.** 용도를 타입으로 분리한다. 기존 인터페이스는 그대로 두고 새 포트를 추가한다.

```java
// application/journey/port/out/TransferTargetArrivalPredictor.java
public interface TransferTargetArrivalPredictor {
    boolean supports(JourneyLegType type);

    /**
     * @param boardingAnchorAt 사용자가 그 정류장에서 탈 준비를 마치는 시각(userReadyAt)
     */
    CurrentLegArrival predictBoarding(JourneyLeg waitLeg, Instant boardingAnchorAt, Instant now);
}
```

- `BusTransferTargetArrivalPredictor` (`WAIT_BUS`) — `predictTransferUseCase.predict(startStopId, routeId, boardingSeq, boardingAnchorAt)`. **`state` 가드 없음.** 결과 `source` 를 `CurrentLegArrival.Source` 로 매핑: `REALTIME → REALTIME_ARRIVAL`, `MODEL → MODEL_ARRIVAL`(신규 enum 값)
- `SubwayTransferTargetArrivalPredictor` (`WAIT_SUBWAY`) — 지하철 실시간은 "지금 들어오는 열차"만 말한다. `boardingAnchorAt - now > subwayRealtimeHorizonSeconds`(기본 480)이면 `unknown()`. 지하철은 모델이 없다(SC-6) — 없는 근거를 만들지 않는다

**버스에도 horizon 을 둔다(D11).** `boardingAnchorAt - now > transferRealtimeHorizonSeconds`(기본 900)이면 외부 호출을 **하지 않고** `unknown()` 을 돌려준다. 근거는 두 예측 방법의 시야다 — 실시간 도착 API 는 다음 1~2대만 알려 주고, 모델은 **지금 운행 중인 차량**의 위치로만 추정한다. 20분 뒤에 올 버스는 아직 차고에 있거나 노선 앞쪽에 없어서 어느 쪽도 답을 못 준다. 그때 호출하면 `NO_VEHICLE` 을 받으려고 외부 API 를 쓰는 셈이다.

**앵커가 과거인 경우는 그대로 넘긴다.** `pickRealtime`(`SingleTransferPredictor.java:222`)이 `!t.isBefore(userAt)` 로 고르므로, 앵커가 과거면 자연히 "다음에 오는 차"가 선택된다. 사용자가 이미 늦은 상황에서 원하는 동작이 그것이다. 앵커를 `now` 로 올려 잡지 말 것 — 그러면 이미 놓친 사실(`MISSED`)이 지워진다.
- `TransferTargetArrivalService` — 단계 종류로 구현을 고르는 얇은 층. `CurrentLegArrivalService` 와 같은 모양

`TransferRiskEvaluator.assess` 의 순서를 뒤집는다.

```
현재: fromArrival = predict(fromLeg)          // 타고 있는 것
      toArrival   = predict(toLeg)            // ← state 가드에 걸려 항상 UNKNOWN
      userReadyAt = readiness(fromArrival, walk)

변경: fromArrival = predict(fromLeg)
      userReadyAt = readiness(fromArrival, walk)
      toArrival   = targetArrivalService.predictBoarding(toLeg, userReadyAt, now)
      slack       = readiness.slack(userReadyAt, toArrival.arrivalAt())
```

`userReadyAt` 이 null 이면(타고 있는 단계 ETA 를 모르면) 앵커가 없다 → `toArrival = unknown`, 판정 UNKNOWN. 앵커를 `now` 로 대체하지 않는다. 그것이 지금의 버그다.

**실시간 승격은 자동이다.** 평가기가 tick 마다 다시 부르고, 앵커가 가까워지면 `SingleTransferPredictor` 가 실시간을 잡는다. 저장은 늘 최신 결과로 덮어쓰므로 모델→실시간 전환이 그대로 반영된다. 반대 방향(실시간→모델 역행)은 predictor 가 실시간을 먼저 보므로 같은 조건에서 생기지 않는다.

### 3.4 판정에 출처를 담아 저장

`TransferRiskAssessment` 에 두 필드를 추가한다.

```java
CurrentLegArrival.Source targetSource,   // REALTIME_ARRIVAL | MODEL_ARRIVAL | null
String modelVersion                      // MODEL 일 때만
```

`TransferAssessmentWriter.write` 는 `source` 자리에 **예측 출처**를 넣는다.

| 저장 값 | 뜻 | 길이 |
|---|---|---|
| `REALTIME` | 실시간 도착으로 다음 차량을 잡음 | 8 |
| `MODEL` | 실시간 미포착 → ML 추정 | 5 |
| `NONE` | 둘 다 실패 → UNKNOWN 판정 | 4 |

`transfer_connection.source` 는 **varchar(20)** 이다(#111 에서 같은 이유로 500 이 났다). 20자 넘는 값을 넣지 말 것. `model_version`(varchar 50)과 `data_observed_at`(예측의 `calculatedAt`)도 함께 저장한다.

대기 leg 자체의 예측도 남긴다 — `JourneyLeg.applyBoardingPrediction(predictedEndAt, source, quality, observedAt)`. `journey_leg` 의 `predicted_end_at`·`evidence_source`(varchar 30)·`evidence_quality`(20)·`evidence_observed_at` 컬럼이 이미 있다(V35). ④b 의 조회 경로가 이 값을 읽는다.

### 3.5 평가 본체 — `JourneyEvaluationService`

```java
public EvaluationOutcome evaluate(Long journeyId);   // 트랜잭션 밖
```

순서:

```java
public EvaluationOutcome evaluate(DueClaim claim);   // 트랜잭션 밖
```

1. 여정·단계·환승 로드(짧은 readOnly 트랜잭션). `ACTIVE` 가 아니면 즉시 종료(다음 시각도 비운다)
2. 좌표 로드 — `GetJourneyNavigationGeometryUseCase`. 실패해도 계속(계획 소요시간으로 내려감)
3. 현재 단계 ETA — `CurrentLegArrivalService` (기존, 외부 호출 없음)
4. 환승 판정 계산 — `TransferRiskEvaluator.assess(...)` (3.3 의 앵커 규칙, **저장하지 않는다**)
5. **소유권 확인 겸 다음 시각 기록** — `JourneyEvaluationScheduleWriter.finish(claim, nextAt, bump)`
6. 5가 **성공했을 때만** 저장 — `TransferAssessmentWriter` + 대기 leg 예측(짧은 트랜잭션)

### 순서가 왜 5 → 6 인가 (D9)

거꾸로 하면 소유권을 잃은 워커가 낡은 판정을 먼저 저장한다. 그래서 **"내가 아직 이 여정의 주인인가"를 먼저 묻고**, 확인된 뒤에 판정을 쓴다.

```sql
-- JourneyEvaluationScheduleWriter.finish
UPDATE journey_session
   SET next_evaluation_at = :nextAt,
       evaluation_version = evaluation_version + CASE WHEN :bump THEN 1 ELSE 0 END
 WHERE id = :journeyId
   AND status = 'ACTIVE'
   AND deleted_at IS NULL
   AND next_evaluation_at = :claimedLeaseUntil      -- ← 소유권 증서
```

- 0건이 돌아오면 **내 lease 가 아니다.** 다른 워커가 이미 가져갔거나, 여정이 끝났거나, 단계 전이가 다음 시각을 앞당겼다. 계산 결과를 **버리고** `journey.evaluation.lost-claim` 카운터만 올린다. 로그는 debug — 정상적으로 일어나는 일이다
- `ACTIVE` 조건이 죽은 여정을 되살리는 것을 막는다
- 5가 성공하고 6이 실패하면 판정은 한 주기 낡은 값으로 남는다. `next_evaluation_at` 은 이미 밀렸으므로 다음 tick 이 다시 계산한다 — 데이터가 사라지지 않는다. 반대 순서에서는 낡은 판정이 최신을 덮은 채 남는다

### `bump` 판정 기준 (D10)

조회 API 는 `afterEvaluationVersion` 으로 "바뀐 것 없음 → 204" 를 판단한다(`JourneyController.java:96-113`). 그래서 **상태만 보고 올리면 앱이 새 ETA 를 못 받는다** — 예를 들어 `CATCHABLE` 그대로인데 다음 버스가 3분 뒤에서 1분 뒤로 바뀐 경우다.

직전 저장값과 비교해 하나라도 다르면 올린다.

- `status`
- `nextBusArrivalAt`, `currentLegArrivalAt`, `userReadyAt` (초 단위 비교)
- `transferSlackSeconds`
- `source` (실시간 ↔ 모델 전환)

모두 같으면 올리지 않는다. 값이 안 변했는데 번호가 오르면 앱이 30초마다 같은 응답을 전부 다시 받는다 — 지하철에서 데이터를 먹는 그 상황을 피하려고 204 를 만든 것이다.

**절대 지킬 것.** claim 과 계산을 같은 트랜잭션에 넣지 않는다. 외부 API 를 기다리는 동안 DB 커넥션을 붙잡으면 커넥션 풀이 먼저 마른다 — #105 에서 이미 겪었고, `TransferAssessmentWriter` 가 별도 빈인 이유도 그것이다. `@Transactional` 자기 호출은 프록시를 지나지 않으므로 저장 빈을 분리한 형태를 유지한다.

트랜잭션은 넷으로 끊어진다: **claim(짧음) → 로드(readOnly) → 소유권 확인+다음 시각(짧음) → 판정 저장(짧음)**. 외부 호출은 두 번째와 세 번째 사이, 어떤 트랜잭션에도 들어가지 않는다.

### 3.5.1 쓰기 주체를 하나로 (D12)

평가기가 켜져 있는 동안 조회 요청도 같은 `transfer_connection` 행을 쓰면, 서로 다른 시점에 계산한 두 결과가 마지막 커밋 순서로 승부가 난다(엔티티에 `@Version` 이 없고 저장소에 `@Lock` 도 없다). 그래서 **쓰기는 평가기만 한다.**

`TransferRiskEvaluator` 를 두 메서드로 가른다.

```java
List<TransferRiskAssessment> assess(Long journeyId, int routeRevision, Map<Integer, LegGeometry> geometry);
List<TransferRiskAssessment> assessAndStore(Long journeyId, int routeRevision, Map<Integer, LegGeometry> geometry);
```

- 평가기 → `assess` 를 부르고, 소유권 확인 뒤 직접 저장(§3.5)
- 조회 경로(`JourneySnapshotEtaComposer`) → 평가기가 **꺼져 있으면** `assessAndStore`(지금 동작 유지), **켜져 있으면** `assess`(계산만)

```java
// JourneySnapshotEtaComposer
List<TransferRiskAssessment> assessments = evaluatorProperties.isEnabled()
        ? transferRiskEvaluator.assess(...)          // 쓰기는 평가기의 몫
        : transferRiskEvaluator.assessAndStore(...); // 평가기가 없으면 지금처럼
```

한 줄이 조회 경로에 들어가는 것이 D7("조회 경로 무변경")의 유일한 예외다. 그래도 **응답 계약은 그대로**다 — 앱이 보는 필드와 값의 의미가 바뀌지 않는다. ④b 에서 이 분기 자체가 사라지고 조회는 저장된 값만 읽는다.

### 3.6 tick — `JourneyDueEvaluatorScheduler`

```java
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.navigation.evaluator", name = "enabled",
        havingValue = "true", matchIfMissing = false)   // ← 기본 off
@Scheduled(fixedDelayString = "${journey.navigation.evaluator.tick-ms:5000}")
public void run()
```

- **기본 off.** Phase 8 의 점진 배포가 이 스위치를 쓴다(⑦ 이 이어받는다)
- claim → `journeyEvaluatorExecutor` 에 여정별 작업 제출 → `CompletableFuture.allOf(...).get(batchDeadline)` 로 배치 완료 대기 → 다음 tick
- 배치 deadline 초과 시 남은 작업은 lease 만료로 자연 회수된다(D4). 소유권 토큰이 있으므로 늦게 끝난 작업이 새 워커의 결과를 덮지 못한다

### 실패를 종류로 나눈다 (codex 리뷰 5)

여정 하나의 실패는 그 여정만 격리한다. 배치를 실패시키지 않는다. 다만 **재예약 간격을 실패 종류에 따라 다르게** 둔다 — 전부 같은 간격으로 재시도하면 provider 가 막혀 있는 동안 같은 호출을 계속 두드린다.

| 종류 | 예 | 다음 평가 | 메트릭 tag |
|---|---|---|---|
| provider 차단·한도 | `BLOCKED`, `LIMITED` | `now + interval * 4` | `reason=provider_blocked` |
| 근거 없음 | `NO_VEHICLE`, `STOP_MAPPING_FAILED` | `now + interval`(정상 주기) | 실패 아님 — UNKNOWN 판정으로 저장 |
| 일시 오류 | 타임아웃, `ERROR` | `now + interval * 2` | `reason=transient` |
| DB 실패 | 저장 예외 | `now + interval` | `reason=storage` |
| 소유권 없음 | lease 토큰 불일치 | 재예약하지 않음 | `reason=lost_claim`(debug) |

재예약 시각에는 claim 과 같은 방식의 jitter(`journeyId % jitterSeconds`)를 더한다. 실패한 여정들이 다음 tick 에 한꺼번에 몰리지 않게 한다.

`NO_VEHICLE` 같은 "근거 없음"을 실패로 세지 않는 것이 중요하다. 그것은 정상적인 답이고(모른다고 말하는 것), 실패로 세면 대시보드가 늘 붉게 보여 진짜 실패를 못 알아본다.

영구 실패(dead-letter)는 두지 않는다. 여정은 길어도 한두 시간이고 lease 만료로 끝나므로, 무한 재시도가 쌓이는 구조가 아니다.

### 3.7 pool — `JourneyEvaluatorPoolConfig`

`ThreadPoolTaskExecutor` 하나. 이름 `journey-eval-`, 기본 core 4 / max 4 / queue 200, `CallerRunsPolicy` 금지(tick 스레드가 막힌다) → 포화 시 거부하고 다음 tick 으로 넘긴다.

`SchedulingConfig` 의 주의사항이 그대로 적용된다 — `TaskScheduler` 빈을 새로 만들지 않는다. tick 은 공용 `taskScheduler` 를 쓴다. 전용 scheduler pool·deadline·bulkhead 심화는 ⑦.

### 3.8 설정 — `JourneyEvaluatorProperties` (`journey.navigation.evaluator`)

| 키 | 기본값 | 뜻 |
|---|---|---|
| `enabled` | `false` | 평가기 스위치. 배포 후 수동으로 켠다 |
| `tick-ms` | `5000` | claim 주기 |
| `batch-size` | `100` | 한 번에 claim 하는 여정 수 |
| `interval-seconds` | `30` | 다음 평가까지(고정. 상황별은 ⑥) |
| `lease-seconds` | `60` | claim 선점 시간. `interval` 보다 커야 한다 |
| `jitter-seconds` | `7` | claim·재예약 시각을 여정별로 흩뜨리는 폭. 소유권 토큰을 갈라 놓는 역할도 한다 |
| `batch-deadline-ms` | `4000` | 한 배치 처리 상한. `tick-ms` 보다 작게 |
| `transfer-realtime-horizon-seconds` | `900` | 이 시간보다 먼 **버스** 환승은 호출 없이 UNKNOWN (D11) |
| `subway-realtime-horizon-seconds` | `480` | 이 시간보다 먼 **지하철** 환승은 UNKNOWN |
| `pool-size` | `4` | worker 스레드 수 |

`@PostConstruct` 검증: `lease > interval`, `batchDeadline < tick*2`, `jitter < interval`, 전부 양수.

**`lease > interval` 이 왜 필수인가.** lease 가 다음 평가 간격보다 짧으면, 평가가 끝나기 전에 다른 워커가 같은 여정을 잡을 수 있다. 그러면 소유권 토큰이 있어도 두 워커가 번갈아 서로의 결과를 버리며 아무도 저장하지 못하는 상태가 된다. 또한 **한 여정의 평가가 lease 안에 끝나야 한다** — 평가가 60초를 넘으면 항상 소유권을 잃는다. 그래서 `batch-deadline-ms` 를 lease 보다 훨씬 짧게 둔다.

### 3.9 계측 — `JourneyEvaluationMetrics`

| 이름 | tag | 뜻 |
|---|---|---|
| `journey.evaluation.duration` | `outcome` | 여정 1건 평가 소요 |
| `journey.evaluation.claimed` | — | claim 한 여정 수 |
| `journey.evaluation.failures` | `reason` | 격리된 실패(`provider_blocked`·`transient`·`storage`) |
| `journey.evaluation.lost-claim` | — | 소유권을 잃어 결과를 버린 횟수. 늘어나면 lease·deadline 설정이 잘못됐다는 신호다 |
| `journey.transfer.prediction` | `source=realtime\|model\|none` | **실시간 대 모델 비율** |
| `journey.evaluation.backlog` | — | `next_evaluation_at <= now` 인 ACTIVE 여정 수(gauge, 30초 주기 조회) |

`RouteSearchMetrics` 의 관례를 따른다 — tag 는 저카디널리티 고정 집합. 여정 번호·정류장 ID 를 tag 로 쓰지 않는다.

## 4. 작업 순서

- [ ] 1. `EXPLAIN` 으로 claim 조건이 `idx_journey_due` 를 쓰는지 확인, 결과를 이 문서에 기록
- [ ] 2. `JourneyEvaluatorProperties` + `application.yaml` (기본 off)
- [ ] 3. `JourneySessionRepository.claimDueBatch`(jitter 포함, `DueClaim` 반환) + `JourneySession.scheduleEvaluationAt` / `end()` 에서 비우기
- [ ] 4. seeding — `JourneyStartTxService`, `JourneyCommandService`
- [ ] 5. `CurrentLegArrival.Source.MODEL_ARRIVAL` 추가
- [ ] 6. `TransferTargetArrivalPredictor` 포트 + 버스·지하철 구현(horizon 포함) + `TransferTargetArrivalService`
- [ ] 7. `TransferRiskAssessment` 에 `targetSource`·`modelVersion` 추가 → `TransferRiskEvaluator` 를 `assess`/`assessAndStore` 로 분리 + 앵커 전환 → `TransferAssessmentWriter` 저장 확장
- [ ] 8. `JourneyLeg.applyBoardingPrediction` + 평가 시 대기 leg 예측 저장
- [ ] 9. `JourneyEvaluationScheduleWriter`(소유권 CAS + `bump` 판정) → `JourneyEvaluationService`(§3.5 의 5→6 순서)
- [ ] 10. `JourneySnapshotEtaComposer` 에 D12 분기 한 줄
- [ ] 11. `JourneyEvaluatorPoolConfig` + `JourneyDueEvaluatorScheduler`(실패 종류별 재예약)
- [ ] 12. `JourneyEvaluationMetrics` 배선
- [ ] 13. 테스트(§5) → 전체 스위트 → PR

3~4 는 서로 의존한다(seeding 이 없으면 claim 대상이 0). 6~8 은 4 와 독립이라 병행 가능. 9 는 3(claim 이 lease 값을 돌려줘야 CAS 조건을 만들 수 있다)에 의존한다.

## 5. 검증

### 5.1 새 테스트

| 파일 | 확인 |
|---|---|
| `JourneyDueEvaluatorIT` | 대상만 claim 된다 / 두 워커 동시 실행 시 **중복 claim 0** / claim 이 `next_evaluation_at` 을 lease 만큼 밀어 둔다 / lease 만료 뒤 다시 잡힌다 / 종료된 여정은 claim 되지 않는다 / 평가 후 `next_evaluation_at` 이 `interval` 뒤로 재기록된다 / 평가 중 종료된 여정에는 다시 심지 않는다 |
| `JourneyEvaluationScheduleWriterIT` | **소유권을 잃은 워커의 저장이 거부된다** — claim 한 lease 를 다른 값으로 바꿔 놓고 `finish` 를 부르면 0건, 판정이 저장되지 않는다 / `bump=true` 면 `evaluationVersion` 이 하나 오른다 / `bump=false` 면 그대로다 / 종료된 여정에는 다시 심지 않는다 |
| `JourneyEvaluationBumpRuleTest` | 상태가 같고 **도착 시각만 바뀌어도 bump** / `source` 가 실시간↔모델로 바뀌면 bump / 모든 값이 같으면 bump 하지 않는다 |
| `TransferTargetArrivalPredictorTest` | 앵커를 그대로 `predict(...)` 에 넘긴다(ArgumentCaptor) / 실시간이 잡히면 `REALTIME_ARRIVAL` / 실시간 미포착이면 `MODEL_ARRIVAL` / **버스 앵커가 horizon 밖이면 외부 호출 없이 UNKNOWN**(mock 이 호출되지 않았음을 검증) / 지하철도 horizon 밖이면 UNKNOWN / `state != ACTIVE` 여도 예측한다 / **앵커가 과거면 다음 차량을 고른다** |
| `TransferRiskEvaluatorTest` (기존 확장) | `userReadyAt` 을 앵커로 대기 예측을 부른다 / 앵커가 없으면(`userReadyAt == null`) `now` 로 대체하지 않고 UNKNOWN / `assess` 는 저장하지 않고 `assessAndStore` 만 저장한다 |
| `JourneyEvaluationServiceIT` | 다가오는 환승이 **UNKNOWN 이 아닌 판정**을 받는다(회귀의 핵심) / `source`·`model_version`·`data_observed_at` 이 DB 에 남는다 / 대기 leg `predicted_end_at` 이 채워진다 / 여정 하나가 실패해도 다른 여정이 평가된다 / provider 차단은 재예약 간격이 길다 |
| `JourneySnapshotEtaComposerTest` (기존 확장) | 평가기 flag on 이면 `assess`(저장 없음), off 면 `assessAndStore` 를 부른다 |
| `JourneyDueEvaluatorSchedulerTest` | flag off 면 빈이 없다 / tick 실패를 삼킨다 / 실패 종류별 재예약 간격이 다르다 |

**204 회귀 시험을 잊지 말 것.** 기존 `JourneyControllerTest` 의 `afterEvaluationVersion` 케이스와 함께, "평가가 도착 시각만 바꿨을 때 앱이 200 을 받는다"를 `JourneyEvaluationServiceIT` 수준에서 확인한다. 이 조합이 codex 리뷰가 찾은 가장 실제적인 결함이었다.

`source` 값은 20자를 넘을 수 없다 — IT 가 실제 DB 에 쓰므로 넘기면 실패한다(#111 과 같은 가드).

### 5.2 명령

```bash
./gradlew compileJava compileTestJava
./gradlew test --tests "*Journey*" --tests "*Transfer*"
./gradlew test                      # 전체. 기준선 991 tests, 0 failures (2026-08-04)
```

### 5.3 손으로 확인

flag 를 켠 뒤 로컬에서 여정 하나를 시작하고 30초 기다린다. `transfer_connection` 의 `calculated_at` 이 갱신되고 `source` 가 `REALTIME` 또는 `MODEL` 로 남는지, `journey_session.next_evaluation_at` 이 30초 간격으로 밀리는지 본다.

## 6. 함정

- **claim 과 계산을 같은 트랜잭션에 넣지 마라.** #105 에서 겪었다. claim → 트랜잭션 밖 계산 → 짧은 트랜잭션 저장
- **`@Transactional` 자기 호출은 프록시를 지나지 않는다.** 저장은 별도 빈(`TransferAssessmentWriter`·`JourneyEvaluationScheduleWriter`)
- **평가가 `commandVersion` 을 올리면 안 된다.** 낙관적 락의 기준이다. `evaluationVersion` 만
- **`transfer_connection.source` 는 varchar(20).** #111 의 재발 금지
- **앵커가 없을 때 `now` 로 대체하지 마라.** 그것이 이 PR 이 고치는 버그다
- **저장 전에 소유권을 먼저 확인하라.** 순서를 뒤집으면 lease 를 잃은 워커가 낡은 판정을 최신 위에 덮는다(§3.5)
- **`evaluationVersion` 을 상태 변화만 보고 올리지 마라.** 조회 API 가 그 번호로 204 를 판단한다. 도착 시각만 바뀐 경우가 실제로 가장 흔하다
- **평가기가 켜진 동안 조회 경로가 저장하면 안 된다.** 쓰기 주체가 둘이 되는 순간 마지막 커밋이 이기는 경합이 생긴다(D12)
- **한 여정의 평가는 lease 안에 끝나야 한다.** 넘으면 매번 소유권을 잃어 아무것도 저장되지 않는다. `journey.evaluation.lost-claim` 이 그 신호다
- **claim 조건에 칼럼 연산을 넣지 마라.** `idx_journey_due` 를 못 쓴다
- **api 프로필에서 돌게 하지 마라.** VPS 는 `prod,api`·`prod,worker` 두 컨테이너다
- **호출량이 일시적으로 는다.** ④a 기간에는 조회 경로도 여전히 실시간을 부르므로 같은 API 를 두 곳에서 부른다. 그래서 기본 off 로 머지하고, ④b 를 바로 뒤에 낸다
- **`JourneySchemaIT` 의 `@Sql` 목록** — 마이그레이션을 추가하면 그 목록에도 넣어야 한다(V38 을 빠뜨려 한 번 깨졌다)
- **`.gitignore` 파일별 whitelist** — 새 SQL 마다 `!` 라인 추가

## 7. ⑤ dedupe 에 남기는 제약

같은 `(stopId, routeId, targetSeq)` 를 여러 여정이 물어도 **`userReadyAt` 이 달라 답이 다르다.** 그래서 ⑤ 가 합칠 수 있는 것은 **원시 조회 응답**(도착 API·위치 API·ML 입력)까지고, "어느 차량을 탈 수 있나"(`pickRealtime`·`chooseBoardable`)는 여정별로 계산해야 한다. 응답 단위로 캐시하고 선택은 뒤에 두는 구조가 필요하다 — ⑤ 착수 시 이 문장을 먼저 읽을 것.

## 8. 다음 PR — ④b `journey-eval-read-path`

조회 경로를 저장값 읽기로 전환한다.

- `JourneySnapshotEtaComposer` 가 `TransferRiskEvaluator.evaluate` 를 부르지 않고 `transfer_connection` 의 마지막 판정을 읽는다
- 대기 leg 이 현재 단계일 때의 ETA 는 `journey_leg.predicted_end_at` 을 읽는다
- **낡음 판정은 도착 시각 기준이다.** `next_bus_arrival_at` 이 이미 지났으면 `UNKNOWN`, 아직 미래면 그대로 쓴다. 계산 시각 기준 상한(예: 5분)은 안전망으로만 둔다
- 이동 leg ETA 는 요청 시점 계산을 유지한다(외부 호출이 없고, 앱이 방금 올린 progress 가 즉시 반영된다)
- 응답에 `eta.evaluatedAt` 추가 — 앱이 신선도를 판단할 수 있게
- 이 PR 이 머지되면 실시간 호출이 요청 경로에서 완전히 빠지고 `snapshot p95 500ms`(SC-13)를 provider 지연이 직접 깨지 못한다
- §3.5.1 의 `assess`/`assessAndStore` 분기도 여기서 사라진다 — 조회는 계산을 아예 하지 않는다

## 9. codex 설계 리뷰 (2026-08-04)

**반영한 지적**

| 지적 | 반영 |
|---|---|
| lease 만료 후 이전 워커의 stale write, fencing token 부재 | D9 — claim 한 lease 값을 소유권 증서로 쓰고, 저장 전에 CAS 로 확인(§3.5). jitter 로 토큰 값을 갈라 놓는다 |
| `evaluationVersion` 을 상태 변화만으로 올리면 조회 API 가 204 를 돌려 앱이 새 ETA 를 못 본다 | D10 — 시각·여유·출처 변화도 bump 대상(§3.5 `bump` 기준). 회귀 시험 명시 |
| 조회 경로와 평가기가 같은 `transfer_connection` 행에 동시 쓰기(`@Version`·`@Lock` 둘 다 없음) | D12 — 평가기가 켜져 있으면 조회는 계산만 한다(§3.5.1) |
| 버스 앵커 horizon 이 없다(내부 호출에 한도 미적용) | D11 — `transfer-realtime-horizon-seconds` 기본 900. 넘으면 호출하지 않는다 |
| 실패 분류·backoff·jitter 부재 | §3.6 실패 종류 표 + jitter. 근거 없음(`NO_VEHICLE`)은 실패로 세지 않는다 |
| 트랜잭션 경계와 중간 실패 복구 규칙 | §3.5 에 네 트랜잭션과 5→6 순서, 각 실패 시 무엇이 남는지 명시 |

**반영하지 않은 지적과 이유**

- **"앵커가 과거면 잘못된 차량을 고른다"** — `pickRealtime` 이 `!t.isBefore(userAt)` 이라 앵커가 과거면 자연히 다음 차를 고른다. 사용자가 이미 늦은 상황에서 원하는 동작이다. 앵커를 `now` 로 올려 잡으면 오히려 놓친 사실이 지워진다
- **DST 포함 타임존 테스트** — `Asia/Seoul` 은 서머타임을 쓰지 않는다
- **`lastNotifiedStatus`·알림 중복 방지 원자화** — 맞는 지적이지만 여정 알림 자체가 Phase 5 다. 알림을 만들 때 같은 트랜잭션에서 상태 변화 감지 + outbox insert 를 묶는다는 사실만 Phase 5 로 넘긴다
- **dead-letter** — 여정은 길어도 한두 시간이고 lease 만료로 끝난다. 무한 재시도가 쌓이는 구조가 아니다
