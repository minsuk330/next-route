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

## PR-2 `feat/journey-wait-lane-reselect` (#614 ③) — 2026-11-17 설계
### 결정
- 대상: **지금 단계가 WAIT_BUS(ACTIVE)** 이고 주인 DEFAULT 이고 후보 ≥2. USER 면 절대 안 건드림. 아직 안 온 대기 단계는 대상 아님 —
  사용자가 그 정류장에 닿아 그 단계가 ACTIVE 가 되면 그때 고른다(두 번째 이후 버스 구간 기본 선택도 이걸로 해결, onwardWaits 잇기 불필요).
- 규칙: 후보마다 다음 차 도착 예측(WaitBusArrivalPredictor 와 같은 경로, 서울 predictTransfer / 지역 lookup). 가장 이른 후보가
  지금 선택보다 **60초 이상 먼저** 오거나 지금 선택 예측이 없고 그 후보는 있으면 옮긴다(깜박임 방지 이력). 동률은 앞 순번.
- 옮기는 곳: 평가기 계산 단계(트랜잭션 밖)에서 고르고, 저장은 `JourneyEvaluationScheduleWriter.finish` 의 소유권 확인 **뒤 같은 트랜잭션**에서.
  저장 직전 다시 읽어 `선택 == 계산 때 선택 && 주인 DEFAULT` 일 때만 적용(사용자 선택 #598 은 평가 번호를 올려 소유권을 뺏으므로 이중 보호).
  적용 = 대기·짝 탑승 `reselectRouteOption(new, route, DEFAULT)` + 탑승 `replanDurationSec(option.rideSeconds)`.
- 이번 평가 안의 나머지 계산(대기 도착, 환승 판정, 대기 예측 저장)은 **새 노선 기준**: 계산 단계에서 읽어 온 단계 엔티티(트랜잭션 밖, 분리 상태)의
  선택을 메모리에서 바꾼 뒤 예측기를 돌린다. 평가기 경로에서만 켠다(조회 경로 assessAndStore 는 끔 — 트랜잭션 안 dirty checking 위험 차단).
- 바뀌면 `changed=true`(평가 번호 +1 → 앱 갱신). INFO 로그 + 지표 `journey.lane.reselect`(result=applied|skipped_user|stale).
### 변경
- `WaitBusArrivalPredictor`: `predictOption(leg, option, now)` 로 쪼갬(기존 predict 는 선택 후보로 이걸 부름, 동작 불변).
- 신규 `MergedBusLaneChooser`: `Optional<LaneReselection> choose(JourneyLeg wait, now)` — 후보 로드, 후보별 predictOption, 이력 규칙.
  `LaneReselection(waitLegIndex, fromOptionId, toOptionId, toRouteId, toRideSeconds, fromArrival, toArrival)`.
- `TransferRiskEvaluator.assess(..., boolean reselectLanes)`: 지금 단계가 대상이면 chooser → 메모리 반영(대기·짝 탑승) → 이후 계산.
  `TransferAssessmentBatch` 에 `LaneReselection laneReselection`(nullable) 추가(옛 생성자 유지).
- `JourneyEvaluationService`: assess 를 reselectLanes=true 로, `changed |= laneReselection != null`, outcome 에 실음.
- `JourneyEvaluationScheduleWriter.finish`: 소유권 OK 뒤 `applyLaneReselection`(단계 재조회 → 조건 확인 → 적용). 조건 불일치면 skip 로그.
- 비용: 대상 여정(묶인 DEFAULT 대기 중)만 후보 수(≤6)만큼 도착 조회. 버스 실시간 Redis 15s 공유 캐시라 같은 정류장·노선은 재호출 안 함. 버스 공급자 슬롯 점유 시간 증가는 지표로 본다.
### 시험
- `MergedBusLaneChooserTest`: 60초 이상 먼저면 옮김 / 59초면 그대로 / 지금 선택 예측 없음 → 옮김 / USER·후보 1개·대기 아님·ACTIVE 아님 → 없음 / 모두 예측 없음 → 없음.
- `WaitBusArrivalPredictorTest` 기존 통과(리팩터).
- `JourneyEvaluationIT`: DEFAULT 대기 중 다른 노선이 먼저 오면 평가 뒤 선택·routeId·탑승 소요가 바뀌고 주인 DEFAULT, 평가 번호 +1, 저장된 대기 예측이 새 노선 값 /
  USER 선택이면 안 바뀜 / 사용자 선택이 평가 도중 끼면 결과 버림(기존 소유권 규칙).
### 배포
- 마이그레이션 없음. api·worker·worker2(평가기는 worker). 진행 여정 0.

### PR-2 리뷰 반영 (2026-11-17, 에이전트 리뷰 8건) — 티어 L 로 승격
- 고르기 재료 = **정류장 단위 도착(#577 BusStopArrivalQueryService, Redis 캐시, 호출 1번에 전 노선, 서울·경기·TAGO)**. 후보별 predictBoarding/WaitBus 조회 안 함
  (서울 캐시 키가 노선별이라 후보 수만큼 호출, 버스 슬롯 최대 9~15초 점유, 경기 일일 5000 몫 소진 위험). 후보 ↔ 행 대조는 노선 번호(정규화).
- 옮긴 뒤 저장되는 대기·판정은 지금처럼 `predictBoarding`(선택 노선 1개) — 단 선택은 엔티티 변경 대신 `Map<legIndex, option>` 덮어쓰기로 예측기에 넘김.
- 이력: 60초 이상 먼저일 때만. 지금 선택이 "차 없음"으로 **확정**일 때만 예측 없음 → 옮김(막힘·예산 초과·조회 실패는 안 옮김).
  지금 선택 버스가 [now−120s, now+60s] 안(도착 중·막 떠남 = 사용자가 탔는데 탑승 전이 전)이면 안 옮김. 노선·순번 못 푼 후보는 제외.
- 배치 전달: applyGrace·toStore 재구성에서 laneReselection 유지. 옮긴 대기 단계는 stabilizeBoardings·grace 에서 제외(직전 값은 옛 노선).
- finish 순서: 소유권 확인 → **applyLaneReselection**(재조회: 선택==계산 때 && DEFAULT && 대기 ACTIVE) → 판정·알림 → storeBoardingPredictions.
  재확인 어긋나면 그 대기 단계 boarding 과 그 단계로 들어오는 판정은 버림.
- `BusRouteSelectionService` 확인 경로(DEFAULT→USER)도 `scheduleEvaluationAt(now)` — 평가 도중 끼면 소유권 확인에서 결과 버림(클래스 주석 약속과 일치).
- 옮길 때: 그 환승의 대기 중 접근 알림 취소(키가 환승 번호 단위라 옛 노선 알림이 새 알림을 막음), 메모리·저장 모두 짝 탑승 replanDurationSec,
  재탐색 보드 `rerouteRefreshSignal.requestNow` 는 커밋 뒤 트랜잭션 밖.
- 조회 경로(평가기 꺼짐)는 옛 선택 기준 — 운영은 평가기 켜짐, 노트 기록.
- 추가 시험: 확인 경로가 평가 도중 끼는 IT, 배치 재구성 뒤 laneReselection 유지, finish 순서(새 예측 유지·위험 알림 노선 이름), 막힘·실패로 안 옮김,
  도착 중 창, 접근 알림 취소, 탑승 소요가 두 번째 대기 앵커에 반영.
