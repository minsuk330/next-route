# 평가기 선점 순서: 급한 환승 먼저 — PLAN (티어 M)

브랜치: `feat/journey-evaluator-urgent-first` (fresh main `55990ddc` 기준)

## 구현 중 바뀐 결정 (2026-10-14, 측정 근거는 implementation_note.md)
- D1 개정: 순서 = **세 줄** 급함 → 기다림 상한 넘긴 여정 → 나머지. 같은 줄에 두면 계속 넘치는 동안 밀린 비급 여정이 급한 여정을 앞질렀다
- D8 추가: URGENT_FIRST 모드의 스케줄러는 **빈자리만큼만 선점하고 기다리지 않는다**(`JourneyDueEvaluatorScheduler.refill`). 배치 200을 통째로 잡으면 작업 큐 FIFO 가 순서를 정해 순서 효과가 0이었다

## 배경
- 평가기 처리량 = min(워커 `poolSize`, 호출 몫 `providerPermits`) ÷ 외부 지연이다. PR #409 에서 측정했다: 4/4·500ms 면 약 8회/s, 한 명당 약 2회라 약 4명/s.
- 넘치면 선점 줄이 밀린다. 그런데 `claimDueBatch` 는 `ORDER BY next_evaluation_at` 뿐이다(`JourneySessionRepository.java:209-226`). 2분 뒤 환승하는 여정도 40분 뒤 환승하는 여정과 같은 줄에서 기다린다.
- 처리량(사용자 검색 몫 보호를 위해 4로 묶음)은 그대로 둔다. 같은 처리량 안에서 **누구를 먼저 계산할지**만 바꾼다.

## 결정
| # | 결정 | 이유 / 기각한 대안 |
|---|---|---|
| D1 | 순서 = (급함 OR 기다림이 guard 초과) 먼저, 그다음 `next_evaluation_at` | 제약 ③(급한 정도가 여정마다 다름)을 밀릴 때의 순서에도 반영한다 |
| D2 | 급함 = `JourneyEvaluationIntervalPolicy.Decision.urgent()` (AT_RISK·MISSED 또는 NEAR, `JourneyEvaluationIntervalPolicy.java:141`) + 시작·단계 전이·경로 교체 수락 | 간격 정책이 이미 쓰는 판단을 그대로 재사용한다. 기준이 두 벌이 되지 않는다 |
| D3 | 기다림 상한 `starvationGuardSeconds`(기본 60): 예정 시각에서 이만큼 지난 비급 여정은 급한 줄로 올린다 | 기아 방지. 비급 판정 나이 ≤ maxInterval(180) + guard(60) < staleAfter(300) 이면 낡음 모름이 생기지 않는다 → 부팅 검증으로 강제 |
| D4 | 저장 = `journey_session.evaluation_urgent boolean NOT NULL DEFAULT false` (V66) | 기각: 매번 계산(transfer_connection 조인으로 직전 간격 되읽기) — 선점 쿼리에 조인이 붙고, 단계 전이 직후 값이 틀어진다 |
| D5 | 기각: EDF(마감 = next + interval 순) | 과부하에서 이미 마감을 넘긴 비급 여정이 가장 이른 마감이 되어 급한 여정이 다시 밀린다(도미노) |
| D6 | 스위치 `claimOrder` = `DUE_TIME`\|`URGENT_FIRST`(기본 URGENT_FIRST) | 롤백은 env 하나로 한다. 같은 빌드에서 전후를 측정한다 |
| D7 | 손대지 않음: lease 비교·저장 직전 확인·알림 순서·간격 정책·호출 몫 | 기존 정합성 장치(PR #396 시험)가 회귀를 잡는다 |

## 변경 파일

### 1. 마이그레이션 `src/main/resources/db/migration/V66__journey_session_evaluation_urgent.sql`
```sql
-- 평가기 선점 순서(급한 환승 먼저). 마지막 평가가 "급함"(위험·임박)으로 본 여정, 그리고 시작·단계 전이·
-- 경로 교체 직후의 여정이 true. 평가기가 밀릴 때 이 여정을 먼저 선점한다.
ALTER TABLE journey_session
    ADD COLUMN evaluation_urgent boolean NOT NULL DEFAULT false;
```
- 인덱스는 추가하지 않는다. 선점은 `idx_journey_due`(next_evaluation_at, partial)로 만기 행을 고른 뒤 정렬한다. 정렬 대상 = 적체 크기라 평상시 작다. 1만 행에서 `EXPLAIN ANALYZE` 로 확인한다.
- **V66 VPS 수동배치 필요** (V18+ untracked convention).

### 2. `domain/journey/entity/JourneySession.java`
- 필드 `private boolean evaluationUrgent;` 를 `nextEvaluationAt`(L98) 옆에 둔다. 주석: 다음 선점에서 먼저 잡을지.
- `scheduleEvaluationAt(Instant)`(L197)에서 `this.evaluationUrgent = true;` 도 함께 심는다. 주석: 시작·단계 전이·경로 교체는 상황이 크게 바뀐 순간이라 급함.
  - 호출 3곳은 그대로 둔다: `JourneyStartTxService:108`, `JourneyCommandService:143`, `RerouteAcceptTxService:338`.

### 3. `domain/journey/repository/JourneySessionRepository.java`
- `claimDueBatch`(L209-226)의 정렬을 바꾸고 인자 2개를 추가한다.
  ```sql
  ORDER BY CASE WHEN :urgentFirst
                     AND (s.evaluation_urgent OR s.next_evaluation_at <= :starvedBefore)
                THEN 0 ELSE 1 END,
           s.next_evaluation_at
  ```
  - 시그니처: `claimDueBatch(Instant now, Instant leaseUntil, int batchSize, boolean urgentFirst, Instant starvedBefore)`
  - javadoc 의 "정렬 단계 없음" 문장(L197)을 고친다.
- `finishEvaluation`(L252-265): `SET` 에 `evaluation_urgent = :urgent` 를 추가한다. `@Param("urgent") boolean urgent` 를 마지막 인자로 둔다. WHERE 는 그대로다.

### 4. `application/journey/config/JourneyEvaluatorProperties.java`
- `public enum ClaimOrder { DUE_TIME, URGENT_FIRST }` 와 `private ClaimOrder claimOrder = ClaimOrder.URGENT_FIRST;` 를 추가한다. 주석: DUE_TIME = 롤백.
- `private long starvationGuardSeconds = 60;` 와 `public Duration starvationGuard()` 를 추가한다.
- `validateAdaptiveInterval()` 에 추가한다:
  - `starvationGuardSeconds <= 0` 이면 거부.
  - `maxIntervalSeconds + starvationGuardSeconds >= staleAfterSeconds` 이면 거부. 메시지: 비급 여정이 최대 간격만큼 미뤄진 뒤 상한만큼 더 기다리면 낡음 기준에 걸려 모름이 된다.

### 5. `src/main/resources/application.yaml` (evaluator 블록 L455-505)
```yaml
      # 밀릴 때 선점 순서. URGENT_FIRST = 위험·임박 환승 먼저(기다림 상한 넘긴 여정도 같은 줄). DUE_TIME = 예정 시각 순(롤백).
      claim-order: ${JOURNEY_EVALUATOR_CLAIM_ORDER:URGENT_FIRST}
      # 급하지 않은 여정이 이만큼 밀리면 급한 줄로 올린다. max-interval + 이 값 < stale-after 를 부팅 검증이 강제.
      starvation-guard-seconds: ${JOURNEY_EVALUATOR_STARVATION_GUARD_SECONDS:60}
```

### 6. `application/journey/service/JourneyDueClaimService.java`(L45)
- `sessionRepository.claimDueBatch(now, leaseUntil, batchSize, properties.getClaimOrder() == URGENT_FIRST, now.minus(properties.starvationGuard()))`

### 7. `application/journey/service/JourneyEvaluationService.java`
- `nextEvaluationAt`(L239-250)가 `private record Schedule(Instant nextAt, boolean urgent)` 를 돌려주게 바꾼다. `urgent = decision.urgent()` 이다(Decision.urgent 가 package-private 이면 같은 패키지라 그대로 쓴다).
- `evaluate`(L107-108): `scheduleWriter.finish(claim, outcome, schedule.nextAt(), schedule.urgent(), notificationContext(...))`

### 8. `application/journey/service/JourneyEvaluationScheduleWriter.java`(L59-63)
- `finish(JourneyDueClaim claim, JourneyEvaluationOutcome outcome, Instant nextEvaluationAt, boolean urgent, NotificationContext notificationContext)` 로 바꾸고 `finishEvaluation(..., urgent)` 에 넘긴다.
- 테스트 호출부를 고친다: `AbstractJourneyNotificationIT:206`, `JourneyNotificationLostClaimIT:36,54,60` (`false` 를 넘긴다).

## 테스트
- [ ] `JourneyDueEvaluatorIT` 에 추가한다. 배치 크기를 1로 줄여 순서를 단언한다(`respectsBatchSize` L168 패턴 참고). 시각은 JDBC 로 직접 심는다.
  1. `urgentJourneyIsClaimedFirst`: 비급 A(예정 −30초), 급함 B(예정 −5초) → B 가 먼저.
  2. `dueTimeOrderWhenSwitchedOff`: `claimOrder=DUE_TIME` 이면 A 가 먼저. 프로퍼티 빈을 실행 중에 바꾸고 finally 로 복원한다.
  3. `starvedJourneyJoinsUrgentLine`: 비급 A(예정 −90초 > guard 60) vs 급함 B(예정 −5초) → A 가 먼저(같은 줄, 예정 시각 순).
  4. `legTransitionMarksUrgent`: 단계 전이 명령 뒤 `evaluation_urgent=true`.
- [ ] `JourneyEvaluationIT` 에 추가한다: 위험 판정이면 저장 후 `evaluation_urgent=true`, 여유·먼 환승이면 false. 기존 `givenFixedRealtimeArrival` 과 `ridingLegTakes` 를 재사용한다. 노선 id 가 비어 예측이 안 되는 기존 함정 때문에 `resolveStopIdentity` 에 route_id 를 채워야 할 수 있다(그 경우 이 IT 의 기존 7 실패도 함께 풀리는지 기록).
- [ ] `JourneyEvaluatorPropertiesTest`: guard ≤ 0 거부, `max + guard >= staleAfter` 거부, 기본값 통과.
- [ ] 회귀:
  ```
  ./gradlew test --tests '*JourneyDueEvaluatorIT' --tests '*JourneyEvaluationIT' \
    --tests '*JourneyNotificationLostClaimIT' --tests '*JourneyNotification*IT' \
    --tests '*JourneyEvaluatorPropertiesTest' --tests '*JourneyDueEvaluatorSchedulerTest' \
    --tests '*JourneyEvaluationIntervalPolicyTest'
  ```
  - 일회용 DB: `lr-pg`(DB lr2, 55440), `lr-redis`(56390). env 는 `$CLAUDE_JOB_DIR/tmp/testenv.sh`. V66 은 Flyway 가 lr2 에 적용한다.

## 측정 (개선 전후)
- 하네스 `JourneyQueryLoadIT` 는 PR #409 에 있다(미머지). 기능 PR 에 섞지 않는다. 로컬 임시 브랜치 `tmp/measure-urgent-first` = main + 이 기능 + #409 머지로 측정하고, 푸시하지 않는다.
- #409 에 추가 커밋한다(하네스 보강):
  - `JourneyLoadFixture.Spec` 에 비임박 타는 시간 `farRideSeconds`(기본 600 유지)를 추가한다. env 는 `JQL_FAR_RIDE_SEC`.
  - 보고에 비임박 판단 나이 p50/p95 를 추가한다(`Report.ageSeconds(..., nearOnly)` 확장).
  - `conditions` 에 claimOrder 를 기록한다.
- 절차(지연 500ms, 4/4, 정류장 전부 다름, 임박 20%, 비임박 1,800초):
  1. `DUE_TIME` 으로 200 / 400 / 800건 → 급한 여정 판단 나이 p95 가 20초를 넘는 첫 N 을 찾는다.
  2. 그 N 에서 `DUE_TIME` vs `URGENT_FIRST` 를 각 2회 잰다(env `JOURNEY_NAVIGATION_EVALUATOR_CLAIM_ORDER`).
- 지표: 급한 여정 p95(기준 ≤ 20초), 비급 여정 p95·낡음 모름(guard 확인), 외부 호출/s(처리량은 같아야 함).
- 선점 쿼리 비용: 1만 행·적체 상태에서 `EXPLAIN ANALYZE` 1회(정렬 시간). 결과를 노트에 남긴다.
- 원자료는 `docs/portfolio/data/b-evaluator/urgent-first-*.tsv`.

## 작업 순서
- [ ] 0. 착수 전 질문(사용자 규칙)
- [ ] 1. V66 + 엔티티 + 저장소(선점·저장) + 프로퍼티·yaml → 컴파일
- [ ] 2. 서비스·writer 배선 + 테스트 호출부 수정 → 컴파일
- [ ] 3. 테스트 추가 → 회귀 묶음 통과
- [ ] 4. 구현 노트 append(`### 확정`/`### 배포`: V66 수동배치/`### 검증`). 커밋 → PR(main 기준)
- [ ] 5. #409 하네스 보강 커밋 → 임시 브랜치에서 측정 → 노트·PR 댓글·포폴 문서(CANDIDATES.md B "개선" 단락) 반영

## 롤백
- env `JOURNEY_EVALUATOR_CLAIM_ORDER=DUE_TIME` + api·worker 재생성. 컬럼은 남겨도 무해하다(DEFAULT false).
