# Dynamic Island 3기능 정상화

## 뭘 하나
지하철 단독·버스 단독·여정 전체 추적이 실기기에서 끝까지 돌게 만든다. 앱이 GPS로 버스 탑승·하차·도보 도착을 판정해 넘기고, 지하철은 열차 선택 UI(대기·탑승 중 재선택·지하 환승)로 연결하며, 그 사이 Dynamic Island가 꺼지지 않게 한다.

## 왜
Opus 리뷰 결과 세 기능 모두 단계 전환·하차 종료 주체가 없고, 여정 지하철은 열차가 안 묶여 push 0, 지하철 대기에서 Activity가 꺼진다(기존 "정상"은 시뮬레이터 확인).

## 핵심 결정
- 하차·종료 = 앱 GPS, 마지막 구간은 네이티브가 직접 COMPLETE(409 재동기화·재시도·세션 부활 방지 포함) (서버 링 100% 기각)
- 버스 탑승 = GPS 속도 6m/s 15초 + 노선 위 100m 전진, 실시간 없어도 동일 (버튼 기각)
- 지하철 = 열차 선택만(알림 탭 → 앱 시트), 고르는 즉시 탑승, 지하 환승은 다음 열차 선택으로 넘김, 실시간 없는 노선은 추적 안 함 / 모션 자동·"모르겠어요"·"탔어요" 버튼 기각
- 걷는 중엔 Dynamic Island 안 바뀜: 역 입구까지 도보 화면 유지, 역 안 진입/도착 시에만 "열차를 골라주세요", 지하철→도보는 지상 출구에서 한 번만 (end·역행 기각)

## 건드는 곳
서버: SubwayTrainSelectionService, SubwayTrainSelectionRequest, SubwayTrainCandidate, trackability API(신규), SubwayLegBindingRepository, SubwayRingPushRelayTxService, SubwayOfflineProtectionPolicy, SubwayRingPushPayloadFactory, FcmLiveActivityPushAdapter
앱: JourneyGuidanceEngine, JourneyGuidanceNetwork, JourneyGuidanceProjection, JourneyGuidanceTransitionPolicy(신규), JourneyGuidanceSubwayMotion, JourneyGuidanceLiveActivity, JourneyGuidanceModels, navigation-live-activity, navigation-screen, journey-train-picker-sheet(신규), journey-api, journey-provider, active-journey-banner, subway-station-sheet, use-start-ride, tracking-progress-card, ride-history-model, alight-model

## 리스크
- GPS 임계값(속도·하차 반경 300m·저속 유지)이 실제와 어긋나면 조기/지연 전환 — fixture 고정 후 실기기 보정
- 지하 환승 연속 전이(최대 3회 전송)가 버전 충돌로 중간에 멈출 수 있음 — outbox 경유·서버 응답 버전 기준 재전송
- 앱 출시 전에 S1 서버 배포가 안 되면 탑승 중·환승 선택이 409

## 검증법
서버 IT/단위(선택 허용 구간·링 가드·END·보호시간) + 앱 tsc·node test·Swift fixture 통과, 그리고 실기기 5시나리오(버스 단독 / 지하철 단독 / 여정 도보→지하철→도보 / 지하철→지하철 환승 / 도보→버스) 기록.
