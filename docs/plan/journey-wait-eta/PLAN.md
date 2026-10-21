# #480 대기 단계 도착 예정 — 실행 기록 (journey-wait-eta)

초안은 Plan 에이전트(2026-10-22)가 썼고, 구현하며 아래처럼 줄였다(초안의 `BoardingPredictionRule` 클래스·`assessAndStore` 반환형 변경·provider-slot.held 타이머는 넣지 않음).

## PR B (#503, 머지 67ce5db3)
- `SubwayBoardingTimeEstimator`: 남은 초 없는 열차 — 진입·도착 = 지금(GOOD), N 번째 전역 = `SubwayLineOrder.secondsFromBehind`(APPROXIMATE), 거리 모름 = 제외
- `SubwayLineOrder.secondsFromBehind(lineId, direction, stationId, n)`: `StationsAheadCalculator.behind(...).secondsToHere().get(0)`(0번 = n 역 앞), n 역 못 채우면 empty. 계약 시험은 PR A 의 `SubwayLineOrderTest.secondsFromBehind`
- `JourneyEvaluationScheduleWriter`: 새 예측이 모름이고 직전 예측이 앞날 + 근거 90초 안이면 덮지 않음(`UNKNOWN_GRACE`)

## PR A
1. `TransferRiskEvaluator.assess`
   - 환승이 없어도 지금 이후 대기가 있으면 진행(스위치 `journey.transfer-risk.predict-all-waits`, env `JOURNEY_PREDICT_ALL_WAITS`, 기본 true)
   - 단계 도착은 단계마다 한 번만 계산(`arrivalOf` 메모) — 판정과 굴림이 같은 값, 열차 대기 규칙 한 번
   - `predictWaitsAhead`: 지금 단계부터 굴린다. 이동·도보 = 지금 단계 도착 또는 계획 소요 누적, 대기 = 그 대기의 예측. 덮인 대기(앞 단계가 진행 중·지난 환승의 대기, `covered`)는 판정 답을 그대로 쓰고 다시 묻지 않음. 나머지는 앵커(지금 대기면 now, 아니면 굴린 시각)로 `predictBoarding`. 앞 대기를 모르면 멈춤
   - 판정(`assessments`)은 그대로 — 시작 전 환승은 UNKNOWN 유지
   - 지표 `journey.evaluation.wait-ahead{position=current|later, result=known|unknown|blocked}`
2. `JourneyEvaluationContextLoader`: 단계 목록을 한 번 읽어 `usesBus` 와 `previousBoardings`(대기 순번 → 저장된 predictedEndAt)를 만든다(쿼리 수 그대로)
3. `JourneyEvaluationService`
   - `outsideAssessments` = 지금 이후 대기 중 덮이지 않은 것
   - `changed` = 판정 변화 ∨ 판정 밖 대기 변화(`sameBoarding`: 모름은 같음, 30초 폭, 저장값이 지금+폭 안이면 폭 안도 다름)
   - `stabilizeBoardings`: 폭 안이면 저장값 시각 유지(근거만 새것) — 기준 끌림 방지
   - `waitsAhead`(환승 대상이 아닌 대기만) → 간격 정책
4. `JourneyEvaluationIntervalPolicy`: `WaitsAhead(present, earliestBoardingAt)`. 환승 쪽이 IDLE 이고 대기가 있으면 `BOARDING`(기본 30초, 비긴급, 등급 CAUTION). 고른 간격이 저장할 예측보다 늦으면 예측 시각까지로 자름(최소 10초)
5. `JourneySnapshotEtaComposer.journeyEta`: 대기마다 `boardingAt` — 평가기 켜짐이면 저장된 단계 예측(낡음 규칙 같음), 없으면 그 대기가 다음 환승의 도착 쪽일 때만 그 차량 도착. `cursor = max(cursor, 예측)`. 대기가 둘이면 각자(잠재 버그 수정)

## 범위 밖
- 평가기 꺼진 경로(되돌리기 전용)의 첫 대기 — 지금처럼 판정 기준
- 검색 스냅숏 `transferArrivals[].waitSeconds` 초기값, 지난 환승 대기 재조회(F13), 경기·TAGO 첫 대기(평가기 경로 미지원)

## 측정 (배포 뒤, 사용자 결정)
- 전후 비교: `journey_evaluation_provider_slot_total{gate,result}` 거절률, `journey_evaluation_interval_count{reason}`(boarding 비중), `journey_evaluation_wait_ahead_total{position,result}`, 버스 실시간 캐시 hit/miss·외부 호출 수, `journey_evaluation_start_lag` p95, `journey_query_conditional_total{result}`
- 끄는 기준(제안): bus 슬롯 거절률 > 5% 지속, start-lag p95 > 10초, 버스 일일 호출 몫 80% 근접 → `JOURNEY_PREDICT_ALL_WAITS=false`
