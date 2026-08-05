# 조회 경로를 저장된 평가 결과 읽기로 전환 (M)

## 뭘 하나

평가기가 켜져 있으면 여정 조회가 실시간 도착 API 를 **부르지 않는다.** 평가기가 미리 계산해 둔 환승 판정과 대기 단계 도착 예측을 DB 에서 읽는다. 응답에 `eta.evaluatedAt`(계산 시각)을 함께 실어 앱이 신선도를 판단할 수 있게 한다.

## 왜

`snapshot p95 500ms`(SC-13)를 provider 지연이 직접 깨뜨리는 구조를 끝낸다. 지금까지는 앱이 조회할 때마다 그 요청 안에서 외부 API 를 불렀다 — 기다리는 구간은 앱이 10초마다 묻는 가장 잦은 조회라서, 호출량이 사용자 수에 그대로 비례했다.

## 핵심 결정

- **낡음의 기준은 시간이 아니라 가리키는 시각이다.** 저장된 도착 시각이 이미 지났으면 `UNKNOWN` 으로 내린다 — 이미 떠난 버스 시각을 주면 사용자는 오지 않을 차를 기다린다. 5분 계산 상한은 평가기가 멈춘 채 먼 미래를 가리키는 값이 최신인 척하는 것을 막는 **안전망**으로만 둔다
- **이동·도보 단계 ETA 는 요청 시점 계산을 유지한다.** 외부 호출이 없고(좌표 + 앱 진행도), 방금 올라온 진행도가 즉시 반영돼 화면이 부드럽다. 대기 단계만 저장값을 읽는다
- **낡음으로 판정하면 근거 시각도 함께 비운다.** 하나만 남기면 앱이 그것으로 남은 시간을 계산한다
- **분기를 남긴다.** 평가기 flag 하나가 "누가 계산하고 누가 읽는지"를 함께 뒤집는다. 끄면 즉시 이전 동작으로 돌아온다. 분기 제거는 평가기를 항상 켜기로 정하는 Phase 8 의 몫이다
- 저장 행 → 판정 변환을 `TransferAssessmentMapper` 로 모았다. 평가기(직전 판정 비교)와 조회(앱 응답)가 같은 변환을 쓴다

## 건드는 곳

`JourneySnapshotEtaComposer` · `JourneyEtaView`(evaluatedAt 추가) · `TransferRiskAssessment`(asUnknown) · `JourneyEvaluatorProperties`(stale-after-seconds) · `JourneyEvaluationContextLoader` · `application.yaml`
신규 — `StoredJourneyEvaluationReader` · `TransferAssessmentMapper`

## 리스크

- **평가기가 꺼져 있으면 이 경로는 동작하지 않는다.** 그때는 조회가 계산·저장하는 기존 동작이다. 켤 때 두 동작이 함께 뒤집히므로 순서를 헷갈릴 여지가 없다
- 평가기가 멈추면 조회는 5분 안에 전부 `UNKNOWN` 이 된다. 화면이 비지만 틀린 값을 주지는 않는다 — `journey.evaluation.claimed`·`lost-claim` 으로 감시한다

## 검증법

평가기를 켠 통합시험에서 **조회가 실시간 mock 을 한 번도 부르지 않고**, 저장된 판정이 응답에 실리고, 도착 시각을 과거로 밀면 `UNKNOWN` 으로 내려간다. 평가 전에는 `UNKNOWN` 이며 그때도 외부 호출이 0이다. 전체 스위트 1047 tests 회귀 없음.
