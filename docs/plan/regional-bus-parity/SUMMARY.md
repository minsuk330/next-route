# 지역 버스 여정 기능 서울과 맞추기

## 뭘 하나
경기·지방(TAGO) 버스도 데이터가 있으면 서울과 같은 여정 기능을 켠다. PR-1 은 재탐색 후보의 승차 시각을 지역 실시간으로 계산해, 경기 환승에서도 재탐색 제안·환승 놓침 푸시가 실제로 나가게 한다. PR-2 는 여정에 지역 노선 순번 저장, PR-3 은 탑승 중 하차 창 보강.

## 왜
테스트 여정 555 에서 경기 후보 20개가 전부 "서울 노선 아님"으로 버려졌다. 경기 노선 정류장 표(최근 여정 노선 68/71 매칭)와 지역 실시간 조회는 이미 있는데 재탐색 계산에 연결만 안 돼 있다.

## 핵심 결정
- 지역 승차 시각은 기존 `RegionalArrivalLookup` 재사용. 단 **후보 계산 전용 한도**(경기·TAGO 각 1,500/일)를 따로 두고 회차당 조회 4건 상한 — 대기 화면·환승 판정 몫을 지킨다.
- 경기 초 단위 예측이 있으면 GOOD, 분 단위만 있으면 APPROXIMATE. 푸시 규칙은 기존 그대로.
- "지금 경로와 같은 후보" 거르기에 지역 노선 id 도 넣는다(안 하면 계획 그대로인 경로가 제안 자리를 차지).
- 몫은 실제 API 호출일 때만 깎는다(순번 없음·캐시 적중은 안 깎음), 실시간 창 밖 시각은 묻지 않는다.
- 차량 위치·ML·사건 감시자·여정 밖 기능(도착 알림 등)은 범위 밖 — 지역 위치 API 없음 또는 별건 결정.

## 건드는 곳
BoardingQuery, BusBoardingTimeEstimator, RegionalArrivalLookup, JourneyRerouteProperties, RemainingPathEtaComposer, RemainingPathCandidateProvider, RerouteAcceptTxService, WaitBusArrivalPredictor, BusTransferTargetArrivalPredictor, RegionalRideLocator / (PR-2) V79, JourneyTransitIdEnricher, JourneyRouteOptionDraft, JourneyLegRouteOption, RerouteOriginResolver / (PR-3) UpcomingStopWindowResolver, RideTruncationResolver

## 리스크
- 경기 환승 사용자에게 처음으로 재탐색 푸시가 나간다. 분 단위 예측만 있는 경우 개선폭 오차 ±1분.
- 후보 한도(1,500/일)를 다 쓰면 그날 남은 경기 후보는 빠진다(대기 화면·서울은 영향 없음).

## 검증법
PR-1: 관련 단위·IT 통과, 배포 후 경기 환승 실여정에서 `unresolved-route` 제외가 멈추고 보드 후보가 채워지는지, 후보·기존 지역 한도 사용량 확인.
