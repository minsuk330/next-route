# 여정 주기 평가기 (L)

## 뭘 하나

`next_evaluation_at` 이 지난 여정을 30초마다 배치로 골라, 요청과 무관하게 환승 위험을 다시 계산하고 결과를 DB 에 남긴다.
같이 고치는 것 — 지금은 **다가오는 환승의 대기 ETA 가 항상 UNKNOWN** 이다. 앞으로는 "내가 그 정류장에 도착할 시점"을 기준으로 버스 도착을 예측한다(실시간 우선, 미포착 시 모델).

## 왜

아무도 묻지 않으면 아무 일도 일어나지 않는다. 사용자가 화면을 보지 않는 동안 환승 위험이 올라가도 서버는 모르고, 알림을 보낼 주체도 없다.

## 핵심 결정

- **예측 앵커를 `now` → `userReadyAt` 로 바꾼다.** 사용자 관심사는 "정류장에 도착할 때 버스가 언제 오나"다. 실시간·모델 판정은 기존 `SingleTransferPredictor` 를 그대로 쓴다 — 앵커 기준 규칙이 이미 그 안에 있다. 앵커가 15분(지하철 8분)보다 멀면 호출하지 않고 UNKNOWN
- **claim 한 lease 시각을 소유권 증서로 쓴다.** 저장 전에 `next_evaluation_at = 내가 심은 값` 을 조건으로 확인하고, 확인된 뒤에만 판정을 쓴다. lease 를 잃은 느린 워커가 최신 결과를 덮는 것을 막는다. 컬럼 추가 없음
- **`evaluationVersion` 은 시각·여유·출처가 바뀌어도 올린다.** 조회 API 가 이 번호로 204 를 판단한다. 상태만 보면 도착 시각이 바뀌어도 앱이 새 ETA 를 못 받는다
- **쓰기 주체는 평가기 하나.** 평가기가 켜져 있으면 조회 경로는 계산만 하고 저장하지 않는다(한 줄 분기, 응답 계약 불변). ④b 에서 그 분기도 사라진다
- **실패는 종류로 나눠 재예약한다.** provider 차단은 간격 4배, 일시 오류는 2배, "근거 없음"은 실패로 세지 않는다(정상적인 UNKNOWN). 재시도 몰림을 막는 jitter 포함
- **기본 flag off 로 머지한다.** 되돌리기 비용을 낮춘다. 마이그레이션 없음(V35 `idx_journey_due` 사용)

## 건드는 곳

`JourneySessionRepository`(claimDueBatch) · `JourneySession` · `JourneyLeg` · `JourneyStartTxService` · `JourneyCommandService` · `TransferRiskEvaluator`(assess/assessAndStore 분리) · `TransferAssessmentWriter` · `TransferRiskAssessment` · `JourneySnapshotEtaComposer`
신규 — `TransferTargetArrivalPredictor`(버스·지하철) · `TransferTargetArrivalService` · `JourneyEvaluationService` · `JourneyEvaluationScheduleWriter` · `JourneyDueEvaluatorScheduler` · `JourneyEvaluatorProperties` · `JourneyEvaluatorPoolConfig` · `JourneyEvaluationMetrics` · `application.yaml`

## 리스크

- **평가가 lease(60초)를 넘기면 매번 소유권을 잃어 아무것도 저장되지 않는다** — `journey.evaluation.lost-claim` 카운터로 감시하고 배치 deadline 을 lease 보다 훨씬 짧게 둔다
- **호출량 일시 증가** — ④a 기간에는 조회 경로도 계산은 계속한다(저장만 안 함). 기본 off 로 머지하고 ④b 를 바로 뒤에 낸다
- **claim·계산 트랜잭션 경계** — 같이 묶으면 외부 API 대기 중 커넥션 풀이 마른다(#105 재발). claim → 밖에서 계산 → 소유권 확인 → 짧은 저장

## 검증법

두 워커 동시 실행에서 중복 claim 0, **소유권을 잃은 워커의 저장이 거부됨**, lease 만료 후 재시도. **다가오는 환승이 UNKNOWN 이 아닌 판정을 받고**, 도착 시각만 바뀐 경우에도 앱이 204 대신 200 을 받는다. `source`·`model_version` 이 실제 DB 에 남는다(`varchar(20)` 초과 시 실패 — #111 가드). 전체 스위트 회귀 없음(기준선 991 tests).
