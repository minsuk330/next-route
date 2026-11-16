# 묶인 버스 구간의 기본 선택 = 먼저 오는 노선 (#614)

티어 M. PR 2개. PR-1 은 지금 구현, PR-2 는 PR-1 배포 뒤 평가기 설계를 보고 구현.

## Context
- 여정 시작은 묶인 버스 구간(#605)의 선택 노선을 항상 `lanes[0]`(시간표 대표)로 저장한다(`JourneyStartTxService.saveRouteOptions` :197-216).
  운영 journey 516: 140 3분 / 741 7분 / 402 19분인데 402 선택 → 상단 "402 19분 뒤", 도착 예정 01:15.
- 검색은 같은 구간에서 가장 먼저 오는 lane 을 탄다고 본다(`TransferArrivalEnricher.updatePrevWave` :1021-1045, AVAILABLE/ARRIVAL_TIME_APPROXIMATE 이고
  predictedArrivalAt 있는 lane 중 최소).
- 여정 시작 스냅샷은 검색 enrich 뒤 저장된다(`RouteSearchController` → `RouteSearchSnapshotRecorder.attach`) → `SubPathResult.transferArrivals` 있음(TTL 600s).
- 사용자 선택 API(#598, PR #609 `BusRouteSelectionService`)는 있으나 "누가 골랐나" 칸이 없다.

## PR-1 `feat/journey-default-earliest-lane`
(리뷰 에이전트 반영 2026-11-17: 도보 도달 기준, 출처 인자화, 첫 버스 구간만, 재탐색 규칙 제외, 같은 후보 재전송 USER, 탑승 소요)
### 규칙 (한 곳: `TransferArrival` 메서드)
- `TransferArrival.isBoardable()` = predictedArrivalAt != null && status ∈ {AVAILABLE, ARRIVAL_TIME_APPROXIMATE}. `updatePrevWave` 도 이걸 쓴다(동작 불변).
- **적용 구간 = 조건부가 아닌 구간만**: 그 구간 transferArrivals 가 `basisLaneIndex == null && conditional != TRUE`(앞 버스 가정 없음 = 첫 버스 구간).
  두 번째 이후 버스 구간은 앞 구간 bestLane 가정의 조건부 값이라 쓰지 않는다 → 0번(지금 동작). onwardWaits 로 잇는 건 PR-2.
- **도보 도달 기준**: lane 이 후보가 되려면 `predictedArrivalAt >= userArrivalAt + (now − calculatedAt)`(검색 뒤 늦게 시작한 만큼 정류장 도착도 늦다).
  userArrivalAt 이 null 이면 `predictedArrivalAt >= now`. 후보 중 predictedArrivalAt 최소, 동률은 앞 순번. 없으면 0번.
### 변경
- `JourneyRouteOptionDraft`: 저장 안 하는 칸 `TransferArrival searchArrival`(그 lane 의 값, 적용 구간만) + `Integer rideSeconds`(LaneResult.rideSeconds) 추가. 옛 생성자 유지, `resolved()` 유지.
  정적 `defaultIndex(List<Draft>, Instant now)`.
- `JourneyCompiler.routeOptions(sub)`: 위 두 칸 채움(버스 구간만).
- `JourneyStartTxService`: 저장 전에 구간별 기본 k 계산 → (1) 대기·짝 탑승 단계 선택 = k 행, 출처 DEFAULT
  (2) k≠0 이고 그 lane rideSeconds 가 있으면 짝 탑승 단계 `plannedDurationSec` = rideSeconds(대표 sectionTime 대신). now = session.startedAt.
  k≠0 이면 INFO 로그 `[JourneyStart] journeyId legIndex default 0->k route waitSec`.
- `RerouteAcceptTxService`: 새 구간 규칙 **변경 없음**(재탐색 후보는 lane 1개·실시간 없음 → firstOption 유지, 노트에 기록). 출처만 처리:
  복제 구간은 옛 출처 이어받고, carriedOption 이 첫 후보로 떨어지면 DEFAULT. 새 구간 DEFAULT.
- 출처 칸: `journey_leg.route_option_selected_by varchar(16)` + `CHECK (IN ('DEFAULT','USER'))` (V78). 엔티티 `@Enumerated(STRING) RouteOptionSelectedBy`.
  - `selectRouteOption(id, routeId, selectedBy)` / `reselectRouteOption(id, routeId, selectedBy)` — 출처는 인자(메서드 이름에 묶지 않음, PR-2 자동 재선택 대비).
  - `BusRouteSelectionService`: USER. **같은 후보 재전송이어도 출처가 DEFAULT 면 USER 로 바꾸고 evaluationVersion +1**(버전·평가 시각 규칙은 일반 선택과 같게).
  - 응답 `JourneyLegView.routeOptionSelectedBy`: 선택이 있는 구간에서 null(옛 행) → "DEFAULT". 선택 없는 구간은 null.
  - V77 을 쓰는 journey IT 15개 `@Sql` 목록에 V78 추가(JourneyStartIT·RerouteAcceptIT·BusRouteSelectionIT 는 Flyway).
### 시험
- defaultIndex 단위: 먼저 오는 lane / 도보 도달 경계(검색 T, 도보 5분, 140 T+6, 741 T+10, 시작 T+3 → 741) / NONE·NO_BUS 제외 / 조건부 구간 → 0 / 없으면 0 / 동률 앞 순번.
- `JourneyCompilerTest`: searchArrival·rideSeconds 대응(#613 lane 빠진 스냅샷 포함), 지하철 null.
- `JourneyStartIT`: 140/741/402 → 140, 대기·탑승 둘 다, 출처 DEFAULT, 탑승 plannedDurationSec = 140 rideSeconds. 단독 추적 시작 = 0번 + DEFAULT.
- `RerouteAcceptIT`: 복제 구간 USER 유지, 첫 후보 대체 시 DEFAULT, 새 구간 DEFAULT.
- `BusRouteSelectionIT`: 선택 후 USER, 같은 후보 재전송 시 DEFAULT→USER + 버전 +1, 이미 USER 재전송은 무변화.
- `JourneySchemaIT` V78 왕복·CHECK. `JourneyQueryService` null→DEFAULT. `JourneyControllerTest` `$.legs[i].routeOptionSelectedBy`.
- `TransferArrivalEnricherTest` 기존 통과.
### 범위 밖(노트·이슈 기록)
- #598 USER 선택 때 탑승 소요가 여전히 대표 값(옵션에 rideSeconds 저장 안 함) — 기존 결함, 별건.
- RemainingPathCandidateProvider "같은 경로" 거름이 선택 노선 하나만 봄(기본 140 이면 402 경로가 후보로 뜰 수 있음) — 기존 USER-k 와 같음, 별건.
### 배포
- V78(Flyway 자동). api + worker + worker2. 진행 여정 0 확인(배포 중 #598 USER 선택이 DEFAULT 로 읽히는 것 방지).

## PR-2 (#614 ③, PR-1 배포 뒤)
- 두 번째 이후 버스 구간 기본 선택: 앞 구간 선택 lane 의 onwardWaits(#610)로.
- 대기 중(WAIT_BUS ACTIVE, selectedBy=DEFAULT, 후보 ≥2): 평가 때 후보마다 대기 예측 → 가장 먼저 탈 수 있는 노선으로 선택을 옮긴다(USER 면 안 건드림).
  선택을 옮기면 ETA·탑승 위치·환승 판정·지도가 한꺼번에 따라온다(이미 selected 기준).
- 설계 확인 필요: 평가기 저장 경로의 소유 검사 안에서 선택을 바꿔야 사용자 선택(#598)과 경쟁하지 않는다. 후보별 실시간 호출 비용(최대 6, Redis 15s 캐시)과 목표 부하.
- PR-1 배포 후 평가기(`CurrentLegArrivalService`, 저장 경로) 읽고 별도 SUMMARY 로 확인받는다.
