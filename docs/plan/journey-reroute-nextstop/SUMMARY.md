# 주행 중 다음-정류장 재탐색 통일 (A′, rev4)

## 뭘 하나
주행 중 재탐색을 하나로 통일한다. ③(같은 정류장 다른 버스)을 폐기하고 ④(남은 경로 재탐색)를 "다음 정류장들에서 출발하는 OTP 재탐색 + 정류장별 후보 리스트 + 알림/선택 + 진행 중 버스를 중간 정류장에서 끊고 새 경로 이식"으로 일반화한다. 초기 스코프는 버스.

## 왜
OTP 자가호스팅이라 ③의 호출 절약 이유가 사라졌고 ④가 뼈대다. 필요한 정류장별 시각은 OTP 응답에 이미 있고, 마침 OTP 전환이 진행 중이라 그 작업에 합류해 정면 해결(A′).

## 핵심 결정
- 정류장별 시각은 OTP stopCalls에 있으나 legacy 변환 경계에서 유실 → PR-A로 보존(national OTP 마이그레이션 P2-B/P2-G에 합류, SubPathResult 확장 금지·중립 StopCall 모델).
- geometry 게이트·투영 폐기 → truncated ride 지도선은 정류장 좌표로, 시각 분할만 정확.
- 원점 주소를 transferIndex → RerouteOrigin으로 일반화(PR-B, expand→backfill→dual-read/write→cutover). ACTIVE 유니크는 여정당 1개.
- overlay = 모든 탑승점 실시간→ML→없으면 제외, 정류장당 최선 1개에만. baseline도 동일.
- 원점 = 리드타임 90초 창, 계획 하차점은 재검색 않고 현재 계획(기준선). 재탐색은 현재시각 아닌 origin 도달시각으로.
- 알림 = 서버 능동 push / 열 때 validate. 응답 3갈래(수락·거절±체크·무시). 무응답 집계는 durable, Redis TTL로 불가.
- apply = 진행 중 RIDE 절단 + Y 이식. progress는 현재까지 보존, X가 아직 안 지난 정차인지 재확인.
- stop 식별 = (trip, sourceStopSequence, occurrenceIndex). RIDE_INTERIOR 초기 버스만.

## 건드는 곳
planConnection.graphql·OtpPlanConnectionMapper·LegacyRouteResponseMapper·StopCall 모델(PR-A) · RerouteOrigin·reroute_proposal·request API·RerouteProposalTxService(PR-B) · JourneyRevisionComposer(PR-C) · RemainingPathEtaComposer+rank()(PR-1) · UpcomingStopWindowResolver·RemainingRouteSearchService·RemainingPathCandidateProvider(PR-2) · RerouteProposalService·알림 엔드포인트(PR-3) · RerouteAcceptTxService(PR-4) · RouteOptionCandidateProvider 제거(PR-5). 마이그레이션 2건.

## 리스크
- PR-A를 진행 중 OTP 전환 경로 모델과 조율 못 하면 병렬 중복(구현 전 필수).
- PR-B 34파일 원자 경로 동시 이동 — 기존 WAIT 소비처 무회귀 광범위.
- overlay A 제외율 폭주 → 다중환승·광역 빈 리스트(지표 관측).
- 전환 전 시작 여정은 정류장별 데이터 없어 중간하차 불가(leg-경계 폴백).

## 검증법
PR-0 스파이크(절단 표현·progress 보존) 먼저. 이후 8개 PR 각 컴파일 + 계약당 테스트(정류장별 시각·중간절단·origin 왕복·overlay 체인·rank·정류장 창·알림 3갈래·절단 apply) 그린 + ④/⑤ 무회귀 + RerouteAcceptIT·마이그레이션 IT 부팅. overlay 제외율은 배포 후 실측. 마이그레이션 VPS 수동배치.
