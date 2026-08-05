# journey-eval-read-path — 실행 계획 (M)

- 상위: [PHASE-4.md](../journey-navigation/PHASE-4.md) ④ 의 후반부(④b)
- 앞 PR: [journey-due-evaluator-claim](../journey-due-evaluator-claim/PLAN.md) (④a, #113)
- 근거: SC-13(`snapshot p95 500ms`), PLAN §6.4
- 사람용 요약: [SUMMARY.md](./SUMMARY.md)
- 작성일: 2026-08-05

## 1. 이 PR 이 끝내는 것

④a 는 평가기를 세웠지만 **조회 경로는 그대로 두었다.** 그래서 평가기를 켜면 같은 외부 API 를 두 곳에서 부르는 상태가 된다. 이 PR 이 조회 쪽 호출을 없앤다.

```
전:  앱 조회 ──▶ 실시간 도착 API          (평가기와 중복)
후:  앱 조회 ──▶ DB 읽기                  (평가기만 외부 호출)
```

## 2. 무엇을 읽고 무엇을 계산하는가

| 값 | 평가기 켜짐 | 평가기 꺼짐 |
|---|---|---|
| 환승 판정(`activeTransfer`) | **저장된 행을 읽는다** | 요청 때 계산 + 저장(기존) |
| 기다리는 단계 ETA(`currentLeg`) | **저장된 `journey_leg.predicted_end_at` 을 읽는다** | 요청 때 실시간 조회(기존) |
| 이동·도보 단계 ETA | 요청 때 계산 | 요청 때 계산 |
| 목적지 ETA·다음 할 일 | 위 값들로 합성 | 같음 |
| `eta.evaluatedAt` | 판정 계산 시각 | `null`(방금 계산한 값이다) |

**이동 단계를 계산으로 남긴 이유.** 외부 호출이 없고(얼려 둔 좌표 + 앱이 올린 진행도), 앱이 15초마다 올리는 진행도가 즉시 반영돼야 화면의 countdown 이 부드럽다. 저장값으로 바꾸면 최대 평가 간격만큼 늦게 움직인다.

## 3. 낡음 판정 — `StoredJourneyEvaluationReader`

```
쓸 수 있다 ⇔ next_bus_arrival_at 이 아직 미래
            AND calculated_at 이 staleAfter(기본 5분) 안
```

첫 조건이 본질이다. 5분 전에 계산했어도 "10분 뒤 도착"이면 유효하고, 30초 전에 계산했어도 "이미 도착"이면 무효다. 두 번째 조건은 안전망이다 — 평가기가 멈춰 있는데 도착 시각만 먼 미래인 값(잘못된 예측)이 계속 최신인 척하는 것을 막는다.

낡았으면 `TransferRiskAssessment.asUnknown()` 으로 바꾼다. **근거 시각을 모두 비운다** — `nextBusArrivalAt` 만 지우고 `userReadyAt` 을 남기면 앱이 그것으로 남은 시간을 계산한다.

기다리는 단계도 같은 규칙이다. `predicted_end_at` 이 지났거나 `evidence_observed_at` 이 상한을 넘으면 `unknown()`.

## 4. 변경 파일

| 파일 | 변경 |
|---|---|
| `application/journey/service/StoredJourneyEvaluationReader.java` | **신규** — 저장된 판정·대기 예측 읽기 + 낡음 판정 |
| `application/journey/service/TransferAssessmentMapper.java` | **신규** — 저장 행 → 판정 변환(평가기·조회 공용) |
| `application/journey/service/JourneySnapshotEtaComposer.java` | flag 에 따라 읽기/계산 분기, `evaluatedAt` 채움, 대기 단계는 저장값 |
| `application/journey/dto/JourneyEtaView.java` | `evaluatedAt` 추가 |
| `application/journey/dto/TransferRiskAssessment.java` | `asUnknown()` 추가 |
| `application/journey/config/JourneyEvaluatorProperties.java` | `stale-after-seconds`(기본 300) |
| `application/journey/service/JourneyEvaluationContextLoader.java` | 변환을 mapper 로 위임(중복 제거) |
| `application.yaml` | `stale-after-seconds` |

마이그레이션 없음. 새 컬럼 없음 — ④a 가 채워 둔 `transfer_connection` 과 `journey_leg` 의 기존 컬럼을 읽는다.

## 5. 앱 계약 변화

`eta.evaluatedAt` 한 필드가 늘었다. 기존 필드의 의미와 형식은 그대로다. 늘어나는 상황은 **`UNKNOWN` 의 빈도**다 — 평가기가 아직 한 번도 돌지 않은 여정, 평가가 밀린 여정, 도착 시각이 지난 판정에서 `UNKNOWN` 이 온다. 앱은 이미 모든 예측 필드에서 `UNKNOWN`·null 을 다루도록 만들어져 있다(Phase 3 계약).

## 6. 계획과 달라진 것

④a 의 PLAN §8 은 "조회 경로에서 계산을 아예 없애고 분기를 지운다"고 썼다. **분기는 남겼다.** 평가기 기본값이 꺼짐이라, 분기를 지우면 켜기 전까지 아무도 값을 만들지 않아 모든 예측이 `UNKNOWN` 이 된다. 대신 flag 하나가 두 동작을 함께 뒤집게 했다.

```java
if (evaluatorProperties.isEnabled()) {  // 평가기가 쓰고, 조회는 읽는다
    return storedEvaluationReader.assessments(journeyId, revision, now);
}
return transferRiskEvaluator.assessAndStore(journeyId, revision, geometry);  // 조회가 쓴다
```

분기 제거는 평가기를 항상 켜기로 정하는 **Phase 8** 의 몫이다.

## 7. 검증

`JourneyEvalReadPathIT`(평가기 켠 `@SpringBootTest`)

| 확인 | 방법 |
|---|---|
| 저장된 판정이 응답에 실린다 | 평가 1회 → 조회 → `activeTransfer` 가 `UNKNOWN` 아님, `evaluatedAt` 있음 |
| **조회가 실시간을 부르지 않는다** | 평가 후 mock 호출 수 기록 → 조회 2회 → 호출 수 불변 |
| 평가 전에는 모름 | 조회 → `UNKNOWN`, 외부 호출 0 |
| 낡으면 모름 | `next_bus_arrival_at` 을 과거로 → 조회 → `UNKNOWN`, 시각 null |
| 이동 단계는 요청 계산 | `currentLeg.source` 가 요청 시점 계산 계열 |

`StoredJourneyEvaluationReaderTest`(8) — 미래 도착은 사용, 오래된 계산 + 미래 도착도 사용, 지난 도착은 모름, 상한 초과 계산은 모름, 평가 이력 없으면 모름, 대기 단계 예측 읽기·지난 예측·예측 없음.

`JourneySnapshotEtaComposerTest` 확장 — 평가기 켜짐이면 저장 읽기(계산기 미호출), 대기 단계는 저장값, 이동 단계는 계산, 꺼짐이면 `evaluatedAt` null.

```bash
./gradlew test    # 1047 tests, 0 failures (직전 1031)
```

## 8. 배포 순서

1. 이 PR 머지 — 동작 변화 없음(평가기가 꺼져 있다)
2. `JOURNEY_EVALUATOR_ENABLED=true` — 이 순간 평가기가 계산·저장하고 조회는 읽기로 바뀐다. **두 동작이 함께 뒤집힌다**
3. 확인 지표: `journey.transfer.prediction{source}` 비율, `journey.evaluation.lost-claim`(0 근처여야 한다), 여정 조회 p95, 외부 API 호출 수
4. 문제가 있으면 flag 를 끈다 — 조회가 다시 계산·저장한다

## 9. 남은 것

- **⑤ dedupe** — 지금은 여정마다 실시간을 따로 부른다. 같은 정류장·노선을 여러 여정이 물어도 `userReadyAt` 이 달라 응답까지만 합칠 수 있다(④a PLAN §7)
- **⑦ resilience** — 평가기 전용 deadline·bulkhead, flag 를 Phase 8 점진 배포에 연결
- 분기 제거(§6) — Phase 8
