# 지하철 진행 링 — 정차 포함·TYPICAL 직선·STALE 축소 (v2)

## 뭘 하나
링이 전역 정차 중 0 에 얼고 구간마다 회색 "확인 중"이 뜨는 문제를 고친다. 실시간 예정이 없으면 GTFS 전형 시간(×1.2)으로 직선을 그리고 `etaSource=TYPICAL` 로 추정임을 알린다. 전역 정차부터 다음 역 도착까지 한 직선으로 그리고, STALE 은 서버가 새 관측을 60초 못 볼 때만 낸다.

## 왜
실측(journey 202)에서 정차 73초 정지·전환마다 STALE·원천 지연으로 인한 가짜 LATE(0→0.9 점프)가 났다. 제품 결정: 멈추는 것보다 느리게라도 움직이는 게 낫다.

## 핵심 결정
- "모르면 멈춘다"를 뒤집되 `etaSource`(LIVE/MEASURED/TYPICAL)로 정직하게 표시 — 되감김·근거 없는 도착 금지는 유지
- 직선 시작 시각 = 서버가 그 사건을 처음 본 시각(늦은 recptnDt 아님), 한 번 정하면 tick 마다 다시 계산하지 않음 — 리뷰 R1·R2
- 정차+운행 한 직선(요청서의 15% 고정 분할 대신) — 꺾임·push 1건 감소, 하차 예정 계산 그대로 — 리뷰 Y2·R3
- 같은 구간에서 단계(정차<운행<늦음<진입<도착)는 되돌아가지 않음, 같은 관측 반복은 갱신 안 함 — 리뷰 R4·Y1
- 실측 정차 수집 제외(GTFS 차분/30초), 계산기 순수 유지, 새 enum 은 새 필드로만, PR 4개 독립 배포

## 건드는 곳
SubwayRingCalculator, SubwayRingState, SubwayRingContext, SubwayRingObservation, SubwayRingSignalReader, SubwayRingUpdateService, SubwayGuidance, SubwayRingPushPayloadFactory, SubwayRingPushDecider, JourneySubwayRingProperties, application.yaml, 신규 SubwayRingEtaSource·SubwayRingStage

## 리스크
- TYPICAL 이 실제보다 빠르면 cap 에서 LATE 로 멈춤 — factor 실측 후 조정
- 링이 실제보다 최대 ~20초+원천 지연만큼 늦게 출발(진입/도착 코드와 같은 지연이라 일관)
- PR-1 배포 직후 진행 중 링마다 일회성 push 1건

## 검증법
REQUEST 수용 기준 15개 + 리뷰가 찾은 빠진 시험 8개를 PR 별로 고정(역당 push ≤ 6 포함), PR 마다 전체 테스트 기존 무관 12건 외 신규 실패 0, 배포 후 실여정 1회 링 시계열·push 건수 기록.
