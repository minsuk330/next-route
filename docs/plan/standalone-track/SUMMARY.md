# 단독 지하철·버스 추적 (#265) — v2

## 뭘 하나
경로 검색 없이 노선·방향·승하차만 골라 지하철·버스를 추적한다. 속은 구간 1개짜리 여정(`kind=TRACK`)이라 열차 고정·링·Live Activity 를 그대로 쓴다. 지하철은 역 도착 시 사용자가 탈 열차를 고르고(일반 여정에도), 버스는 이동 시작 시각으로 탄 차를 맞춘다.

## 왜
지하철 추적 기능이 이미 있지만 경로 검색에 묶여 있어 "타기만 할 때" 쓰기 무겁다. 버스는 서버가 탄 차를 모른다.

## 핵심 결정
- 경로 뼈대는 OTP 노선 패턴에서 승차~하차를 오려, 기존 OTP 결과 변환기를 거쳐 만든다(그래야 실시간 id·지하철 방향이 채워진다). 검색 기각 — 결과가 흔들림.
- TRACK 여정은 재탐색(보드·말풍선·push·제안)에서 전부 빠진다. 제외는 SQL 조건으로.
- 지하철 열차 선택은 곧바로 확정하되 링은 실제로 움직인 뒤에 켠다. 무응답이면 기존 자동 판정, 탑승 전 "이 열차 아님"이면 자동 판정으로 돌아간다.
- 버스 차량은 사용자가 안 고르고 서버가 시각 대조로 맞춘다(TRACK 버스만, 시작 즉시 관찰). 틀리면 "이 버스 아님".
- 버스 Live Activity 는 1차 앱 폴링, 서버 push 는 후속. 추적 API 는 인증 경로(`/api/journeys/track`) 아래. 플래그 2개 기본 꺼짐.

## 건드는 곳
V59~V61 마이그레이션, JourneySession(kind), JourneyStartTxService·StartService, JourneySessionRepository(보드 쿼리 4곳), RerouteBoardRefreshService, OTP 패턴 포트·어댑터, TrackLegFactory·TrackJourneyStartService·JourneyTrackController(신규), LegacyRouteResponseMapper, 지하철 후보·선택 API, SubwayLegBinding·Repository(링 대상)·MatchService(sweep)·BoardingEvidenceService, JourneySubwayGuidanceComposer, 경기·TAGO 버스 위치 어댑터·BusVehicleMatcher·관찰 스케줄러(신규), JourneyLeg(차량 칼럼), JourneySnapshot(kind·busGuidance), JourneySnapshotEtaComposer, HANDOFF.md

## 리스크
- 지하철 선택 즉시 확정 — 다른 열차를 타면 리셋 전까지 링이 틀린다.
- 경기 버스 위치 일 1만 회 — 사용량이 늘면 증액 필요(전용 예산으로 막음).
- 버스 안내만 바뀌면 지금 조회 API 가 204 로 묻는다 — 폴링 계약을 구현 때 정해야 한다.

## 검증법
PR 마다 계약별 시험 + 플래그 off 회귀 + 전체 빌드(기존 무관 3클래스 라벨). 마지막에 실기기로 단독 지하철 1건(선택→링)·단독 버스 1건(차량 고정→버스 안내) 확인.
