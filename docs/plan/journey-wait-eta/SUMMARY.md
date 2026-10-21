# 대기 단계 도착 예정(journeyEta) 채우기 (#480)

## 뭘 하나
첫 승차 대기, 열차 고르기 전 지하철 대기, 안내 시작 직후에도 도착 예정이 나오게 한다. 평가기가 환승 대기뿐 아니라 남은 대기를 앞 단계 끝 시각으로 굴린 앵커로 예측해 저장하고, 조회는 대기마다 자기 예측으로 시각을 이어 붙인다. 지하철은 남은 초가 없는 열차도 몇 번째 전역인지로 시각을 어림한다(PR #503). 스키마·API 변경 없음(null → 값).

## 왜
지금은 환승 대상 대기만 예측해서 그 밖의 대기가 하나라도 있으면 도착 예정이 통째로 비고 앱이 '시간 확인 중'을 띄운다. 환승 없는 여정은 예측이 생겨도 평가 번호가 안 올라 앱이 받을 길이 없다.

## 핵심 결정
- 판정기에 남은 대기 굴림을 더한다 — 앞 단계가 시작된 환승의 대기는 판정 답을 그대로 쓰고 다시 묻지 않는다(BoardingChainEtaCalculator 재사용 기각: 실패 의미가 다름)
- 시작 전 환승은 대기 예측만 채우고 판정은 UNKNOWN — 탑승 전 위험 알림이 생기지 않게
- 판정 밖 대기 예측 변화도 평가 번호를 올린다(30초 폭, 폭 안이면 저장값 유지, 임박이면 폭 안도 바뀜) — 204 고착과 기준 끌림을 함께 막음
- 환승 아닌 대기가 남으면 30초 비긴급(BOARDING) + 예측이 지나기 전 재평가(10초 긴급 기각: 위험 알림 줄 잠식)
- 스위치 JOURNEY_PREDICT_ALL_WAITS(기본 켬) — 측정에서 버거우면 끈다

## 건드는 곳
TransferRiskEvaluator, JourneySnapshotEtaComposer, JourneyEvaluationService, JourneyEvaluationIntervalPolicy, JourneyEvaluationContextLoader, JourneyEvaluationMetrics, JourneyTransferRiskProperties, application.yaml
(PR #503: SubwayBoardingTimeEstimator, SubwayLineOrder, JourneyEvaluationScheduleWriter)

## 리스크
- 걷기·첫 대기 구간 여정의 평가가 120초 → 30초 간격이 되고 매번 외부 조회를 해 버스 슬롯(4)을 다툴 수 있다 — 배포 뒤 측정(PLAN §측정)
- 탑승 전 굴린 예측(주로 모델)이 흔들려 도착 예정이 오르내릴 수 있다(30초 폭 안정화로 완화)

## 검증법
판정기·조립기·간격 단위 시험과 탑승 전 IT(첫 대기 예측 저장, 변화 시 평가 번호 상승·그대로면 유지, 30초 비긴급 간격)가 통과하고 기존 여정 IT 가 통과하면 완료. 배포 뒤 슬롯 거절률·start-lag p95·버스 호출이 기준 안이면 유지.
