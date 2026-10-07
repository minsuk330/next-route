# 재탐색 주기 보드 (#264) — v2

## 뭘 하나
진행 중인 모든 여정에서 정류장·역을 지날 때마다(신호가 없으면 60초마다) 서버가 조용히 더 나은 경로를 찾는다. 찾은 결과는 하차 자리별 "보드"에 갱신하고, 앱은 이를 지도 말풍선으로 그린다. push 는 확실한 이득이 있을 때만 보낸다. PR 은 6개이고 플래그를 끈 채 배포한다. 플래그가 꺼져 있으면 기존 동작은 그대로다.

## 왜
"다른 경로 찾기" 버튼이 사라지면서 지금의 사건 기반 방아쇠로는 제안이 거의 뜨지 않는다. 환승이 없는 여정, 판정이 나빠지지 않은 여정은 제안이 0개다.

## 핵심 결정
- 보드는 개정본마다 한 행을 제자리에서 갱신한다. 저장할 때 세션을 확인하고 명령 번호를 다시 찍는다. 그래야 승하차 뒤에도 수락이 된다.
- 같은 자리에 다른 경로가 나오면, 60초 이상 빠를 때만 교체하고 아니면 기존을 유지한다. 이번 회차에 못 구한 자리도 유지한다.
- 수락 후에는 3분만 push 를 막는다. 거절하면 push 만 멈추고 말풍선 갱신은 계속된다. 무시는 push 단위로 센다.
- 위험 알림은 20초 늦춰 넣는다. 그 사이 재탐색 push 가 나가면 위험 알림을 취소한다.
- 경기·지방 노선 정류장 표는 #267 이 만든다. 여기서는 그 표를 읽기만 하고, PR 순서상 맨 뒤에 둔다.

## 건드는 곳
마이그레이션 1개(#267 다음 번호), RerouteCandidateEnvelope·Reader·TxService, RerouteBoardMerger/Service/PushPolicy(신규), RerouteBoardRefreshScheduler(신규), JourneyProgressService, JourneyCommandService, SubwayRingUpdateService, RemainingPathCandidateProvider(보드 진입점 신규), UpcomingStopWindowResolver, BoardingChainEtaCalculator, RerouteOrigin(Kind), WalkTruncationResolver(신규), BusRideLocator, RideTruncationResolver, JourneyNotificationEnqueuer, FcmMessageFactory, RerouteAcceptTxService, RerouteProposalService, SearchTimeBusAdapter, 컨트롤러, HANDOFF.md

## 리스크
- 전 여정을 1분마다 찾는 OTP 부하. 전용 permits 로 막고, 켜기 전에 지표를 본다.
- 20초 지연 창 안에서 위험 알림이 이미 발송 중이면 위험 알림과 재탐색 push 가 둘 다 나간다(수용).
- 걷기 위치를 보간으로 추정하므로, 사용자가 경로를 벗어나면 위치가 틀린다(수용).

## 검증법
PR 마다 컴파일, 계약별 테스트, **플래그 off 회귀 테스트**를 통과해야 한다. 핵심 테스트는 셋이다: "구간 전이 후 수락 200", "같은 보드 push 2회 → 2행", "버전이 같아도 지나간 말풍선은 200". 운영 on 은 PR-5 이후, 서울만으로 판단한다.
