# Dynamic Island 3기능 정상화 (L 티어) — rev2 (Opus 플랜 리뷰 반영)

## Context
2026-10-10 Opus 리뷰 3건 결과: 지하철 단독·버스 단독·여정 전체 모두 **실기기에서 끝까지 동작하지 않는다**. "여정 안 버스·도보 정상"은 시뮬레이터 확인이었다.
공통 원인 3개:
- **A. 다음 단계로 넘기는 주체가 없다** — 엔진 전이는 WALK·RIDE_BUS(+main 의 도보→역 100m, 모션 지하철 탑승)만, 마지막 구간은 대상 아님. 서버는 자동 완료 없음(`JourneyStateMachine.advanceLeg` 마지막=409). → 하차해도 안 끝나고, 버스 대기·환승 도보·지하철 탑승 중에서 멈춤
- **B. 여정 안 지하철 열차 연결 경로가 없다** — `train-selection` 은 단독 추적만 호출. binding 없음 → `findRingTargets` 0행 → push 0
- **C. 지하철 대기 진입 시 Live Activity 를 끈다**(`JourneyGuidanceLiveActivity.swift` WAIT_SUBWAY 미연결 guard·`synchronizeSubway` 미연결 guard) + 백그라운드 시작 실패를 조용히 삼킴

비유: 지금 앱은 "출발"만 누를 수 있는 내비다. 다음 갈림길·도착을 스스로 알아채는 눈(GPS)과, 지하철에서 "이 열차"라고 짚는 손(선택 UI)을 달고, 그 사이 화면이 꺼지지 않게 한다.

## 사용자 결정 (2026-10-10, 확정 — 재논의 금지)
1. 하차·여정 종료 = **앱 GPS** 판정. 마지막 구간은 네이티브가 직접 COMPLETE
2. 여정 안 지하철 열차 연결 = **열차 선택 UI**. "모르겠어요" **제거**(jette-app#12)
3. 버스 대기→탑승 = **GPS 속도** 자동 판정
4. 지하철 열차 고르면 **즉시 탑승** 전환(링은 승차역 0 부터, #304 와 동일)
5. 열차 고르기 전 지하철 대기 Dynamic Island = **"열차를 골라주세요" 대기 화면**
6. 리뷰 = **Opus**(플랜·PR)
7. main 에 이미 있는 **모션 기반 지하철 대기→탑승 자동 전환은 끈다**(보내는 주체 = 선택 UI 하나)
8. **지하 환승(지하철→지하철)** = 다음 열차를 고르면 넘긴다. 환승역 도착(링 하차역 도달) 시 다음 열차 선택 시트를 띄우고, 고르면 앱이 `RIDE→(TRANSFER_WALK)→WAIT→RIDE` 를 연속 전송
9. **실시간 없는 지하철 노선**(후보 목록 비는 경우: 지방·인천1·2·김포골드 등) = **추적 안 함**. "탔어요" 버튼 없음(서버가 추적 못 해 무의미). 단독 추적은 시작 차단, 여정에선 그 구간 링/DI 추적 없음. **버스는 실시간 없어도 기존처럼 GPS 판정**
   - 여정 진행: 그 지하철 구간은 지상에 나와 GPS 가 하차역 근처를 잡으면 다음 구간으로(아래 A2 규칙). 사용자가 이견 시 수정
10. **열차 선택 = 앱 진입**. 역 근처에서 로컬 알림 "○○역 열차를 골라주세요" → 탭하면 앱이 열리며 선택 시트 바로 노출(딥링크). 고른 뒤 앱 닫아도 DI 는 링으로 이어짐. 잠금화면/DI 버튼 선택(LiveActivityIntent)은 후속
11. **걷는 중 DI 가 바뀌면 안 된다.**
    - 도보→지하철: 서버 단계는 기존대로 역 100m 전 WAIT_SUBWAY 로 넘기되, **DI 는 도보 화면 유지**. 바뀌는 시점 = (a) 열차 선택 → 바로 지하철 링 화면(대기 화면 생략) (b) 역 지점 30m 도착 또는 역 근처에서 GPS 30초 이상 끊김(역 안 진입) → "열차를 골라주세요"
    - 지하철→도보: 전환은 **한 방향·한 번**. 지하철 push 는 서버 가드(S1)+앱 버전 비교로 도보 화면을 덮지 못함. GPS 가 안 잡히면 "○○역에서 내려요"에 머묾(엉뚱한 화면으로 안 바뀜)

## 기준 코드
- 서버 `/Users/wa/golbob/개인/nextroute` origin/main **8bb1ca6a**. 로컬 트리는 옛 브랜치+미커밋 다수 → **스크래치 git clone** 에서 작업
- 앱 `/Users/wa/golbob/nextroute-mobile` origin/**main 6dc8dbc** (ui-revamp 원격 없음). 0c058ff(fix/1.0-field-bugs)는 **PR #5(fec7960)로 이미 main 머지** — 이식 불필요
- 라인 번호는 위 커밋 기준, 착수 시 재확인. 작업마다 fresh 기본 브랜치에서 독립 브랜치+PR, 커밋 직후 `git log -2`, `git diff --cached --name-only`

## PR 구성과 순서
`S1 → (VPS 배포) → A1·A3` / `S2·A2·A4` 는 병렬 가능. **A1·A3 출시 전 S1 VPS 배포 필수**(옛 서버는 탑승 중·이전 탑승 단계 선택에 409). 새 서버+옛 앱은 안전(필드 추가뿐, 범위 밖 push 만 빠짐).

---

### S1 서버 `fix/subway-selection-window` — 선택 허용 구간 확장 + 링 대상 단계 가드
**계약 변경(HANDOFF 필수)**
- `S/application/journey/service/SubwayTrainSelectionService.java` `waiting()`(:208-228 부근, :216-222 조건) 을 "선택 대상 RIDE_SUBWAY 구간 찾기"로 일반화. 대상 RIDE 구간 r(그 앞 WAIT_SUBWAY = r-1) 에 대해 **현재 단계 c 가 아래면 허용**:
  - (a) c = r-1 (WAIT_SUBWAY) — 기존
  - (b) c = r (RIDE_SUBWAY, 탑승 중 재선택 "다른 열차예요")
  - (c) **지하 환승**: c 가 직전 RIDE_SUBWAY 이고 c 와 r-1 사이가 TRANSFER_WALK/WALK 0~1개뿐 → 다음 RIDE 구간 r 의 선택 허용
  - 요청에 대상 지정이 필요 → `SubwayTrainSelectionRequest` 에 선택 필드 `legIndex`(대상 RIDE_SUBWAY index, 없으면 기존 규칙: 현재 단계 기준 가장 가까운 대상) 추가. candidates 도 `?legIndex=` 쿼리
  - 그 외 409 `NOT_WAITING_FOR_SUBWAY` 유지
- candidates 원천(:178-196 부근 승차역 도착 목록):
  - c = r-1 또는 (c) 환승: 기존 다가오는 열차 + 막 떠난 열차(DEPARTED)
  - c = r (탑승 중): **DEPARTED 만**, 창 = `max(DEPARTED_WINDOW(:60, 10분), now - rideLeg.activatedAt + 2분)`
  - DEPARTED 의 `destination`: 위치 원천은 null(`DepartureLookup.java:46-51`) → 노선·방향 종착역 이름으로 보강, 못 풀면 null 허용
  - 응답 `SubwayTrainCandidate` 에 `kind` 추가
    ```java
    public record SubwayTrainCandidate(String trainNo, String destination, boolean express, String trainType,
                                       Integer arrivalSeconds, Instant observedAt, Kind kind) {
        public enum Kind { UPCOMING, DEPARTED }
    }
    ```
  - 실시간 없는 노선/방향 모름 = 빈 목록(현행 :74-86, :198-202) 유지. "지금 열차가 없음"과 "노선 자체가 실시간 미지원"을 앱이 구분해야 하므로(결정 9), 기존 리스트 응답은 그대로 두고(옛 앱 호환) **`GET /api/journeys/{id}/subway/trackability?legIndex=` 신설** → `{trackable: boolean, reason: "NO_REALTIME_LINE"|"UNKNOWN_DIRECTION"|null}`. 판정 = 노선이 실시간 지원 노선표에 있고 방향을 풀 수 있는가(`SubwayDirectionNormalizer.fromWayCode` != null)
- `SubwayTrainSelectionTxService.bind`(:39-60): 재선택은 기존 `selectByUser()` 덮어쓰기(version++). 링은 새 version 키로 처음부터, 옛 version push 는 stale_generation 으로 걸러짐 → **테스트로 고정**
- 링 대상 가드 `S/domain/journey/repository/SubwayLegBindingRepository.java` `findRingTargets`: `AND s.current_leg_index IN (b.leg_index - 1, b.leg_index)` (대기 중 링 #304 유지, 다음 단계 이후 제외)
  - END 프레임은 ring targets 가 아니라 `SubwayRingPushEnqueueService.enqueueTerminal`(:113-131) 경로 → 가드 영향 없음. 테스트로 고정
- `SubwayRingPushRelayTxService.reasonToSkip`(:75-99): END 아닌 프레임은 세션 `current_leg_index ∉ {leg-1, leg}` 이면 `LEG_LEFT` SKIP
- 오프라인 보호(리뷰 11): 즉시 탑승이면 RIDE `activatedAt` = 승강장 시각이라 대기 시간이 여유분을 깎음 → `SubwayOfflineProtectionPolicy` 의 예상 종료를 **링 `estimatedAlightingAt` 이 있으면 그것 + 5분**(상한 activatedAt+60분 유지)
- 테스트(계약당 1개 이상)
  - `SubwayTrainSelectionIT`: (a) 대기 선택 / (b) 탑승 중 재선택 200 + version++ / (c) 지하 환승 이전 RIDE 에서 다음 RIDE 선택 200 / 도보 등 그 외 409 / 탑승 중 candidates = DEPARTED 만 / 대기 candidates 에 DEPARTED 포함
  - `SubwayRingPushIT`: WAIT(leg-1)·RIDE(leg) 는 발송, leg+1 이후 SKIP(`LEG_LEFT`) / END 프레임은 가드와 무관하게 발송 / 재선택 후 옛 version 프레임 stale
  - `SubwayOfflineProtectionPolicyTest`: estimatedAlightingAt 기준 보호
  - trackability 엔드포인트 단위/IT
- HANDOFF: `docs/plan/dynamic-island-tracking/HANDOFF.md` — legIndex 선택 필드, 허용 구간, candidates kind, trackability
- 검증: `./gradlew compileJava && ./gradlew test --tests '*SubwayTrainSelection*' --tests '*SubwayRingPush*' --tests '*SubwayLegBinding*' --tests '*SubwayOfflineProtection*'` (스크래치는 일회용 DB/Redis 필요. main 기존 IT 25건 실패=#337 V63 은 "기존 무관")

### S2 서버 `fix/live-activity-push-content` — push 내용
- `SubwayRingPushPayloadFactory.java`(:83-103) props 에 `alightingExitNo`(원천 RIDE 구간 `endExitNo`, `JourneyGuidanceInfoEnricher.java:115` 경로), 없으면 키 생략
- `FcmLiveActivityPushAdapter.java` END(:109-133): `aps.dismissal-date = now + 15s`(epoch 초). END 화면은 마지막 지하철 push 복사본(`PayloadFactory.java:70-81`)이라 오래 남기지 않음
- 테스트 `SubwayRingPushPayloadFactoryTest`(exitNo 유/무), `FcmLiveActivityPushAdapterTest`(END dismissal-date)

---

### A1 앱 `fix/subway-no-unknown-train` (jette-app#12) — S1 배포 후 출시
- `F/ride-tracking/subway-station-sheet.tsx` `TrainChoice.kind` 에서 `unknown` 제거, `onUnknown` 전달·`DirectionStrip` unknown 버튼·스타일 제거
- `alight-model.ts` choice 타입 `'departed'|'upcoming'`, 분기 정리 / `alight-select-screen.tsx` 기본값 처리
- `ride-history-model.ts` 지하철 기록 칩 → 바로 시작 금지, 역 시트 열기(`nearby-transit-section.tsx` 호출부)
- `use-start-ride.ts`: 선택 성공 후 choice 무관 `reportBoarded()` (결정 4)
- **실시간 없는 노선(결정 9)**: 역 시트에서 `trackability=false`(또는 도착 정보 없음 `hasArrivals===false`) 면 열차 목록·추적 시작 숨기고 "이 노선은 실시간 추적을 지원하지 않아요" 안내
- `tracking-progress-card.tsx` "다른 열차예요": `resetSubwayBinding` 제거 → A3 선택 시트(탑승 중 모드: DEPARTED 목록) → `selectSubwayTrain`
- 테스트 `alight-model.test.ts`, `ride-tracking-model.test.ts`, 신규 trackability 분기 테스트
- 검증: `npx tsc --noEmit && node --experimental-strip-types --test src/features/ride-tracking/*.test.ts`

### A2 앱 엔진 `feat/guidance-gps-transitions`
**판정 순수 함수 분리** — 신규 `M/JourneyGuidanceTransitionPolicy.swift`(Foundation 만, fixture 로 swiftc 실행 가능)
```swift
struct TransitionSample {
  let legType: String; let isLastLeg: Bool; let nextLegType: String?
  let timestamp: TimeInterval; let accuracyMeters: Double
  let speedMps: Double?; let speedAccuracyMps: Double?
  let legProgress: Double?; let remainingDistanceMeters: Double?
  let distanceToBoardMeters: Double?; let alongRouteFromBoardMeters: Double?   // WAIT_BUS: 다음 RIDE_BUS 선 위 투영
  let distanceToAlightMeters: Double?; let alightCheckpointFired: Bool        // RIDE_BUS ALIGHT_NOW 이후
  let subwayTrackable: Bool }
enum TransitionDecision { case none, advance, complete }
struct TransitionState { var hits = 0; var firstHitAt: TimeInterval?; var misses = 0 }
enum JourneyGuidanceTransitionPolicy {
  static let busBoardSpeedMps = 6.0, busBoardHoldSec = 15.0, busBoardAlongMinM = 100.0
  static let busAlightLeaveM = 60.0, busAlightSlowMps = 2.0, busAlightSlowHoldSec = 20.0
  static let subwayAlightRadiusM = 300.0, walkArriveRadiusM = 30.0, requiredHits = 3, maxGraceMisses = 2
  static func evaluate(_ s: TransitionSample, state: inout TransitionState) -> TransitionDecision
}
```
규칙:
- WALK/TRANSFER_WALK: 기존 progress ≥ 0.985(+main 의 다음 WAIT_SUBWAY 면 remaining ≤ 100m, `Engine.swift:750-753` 유지) → advance / 마지막이면 complete(목적지 30m 도 허용)
- **WAIT_BUS → RIDE_BUS**: speed ≥ 6 m/s (speedAccuracy 0<x≤2) 가 **15초 이상** 유지 + 다음 RIDE_BUS 선 위 투영이 승차 정류장보다 **100m 이상 전진** → advance. 짧은 정차(신호) miss ≤ 2 허용
- RIDE_BUS 중간: 기존 유지
- **RIDE_BUS 마지막**: ALIGHT_NOW 발화 이후, (하차 정류장에서 60m 이상 멀어짐) 또는 (속도 ≤ 2 m/s 가 20초 유지 & 정류장 100m 내) → complete. 시간 폴백: 계획 하차 시각 + 10분 & 정류장 150m 내 → complete
- **RIDE_SUBWAY 하차 판정**(실시간 추적 중이든 아니든). 흐름:
  1. 지하 탑승 중: GPS 무시, DI = 서버 링(push+15초 poll)
  2. 링 하차역 도달(`subwayGuidance.phase == SUBWAY_ALIGHT` 또는 remainingStations == 0): DI 는 지하철 화면 그대로 "○○역에서 내려요 · n번 출구". 아직 전이 안 함
  3. 역 안 이동: GPS 없음 → 판정 없음, DI 유지
  4. 지상: 아래 **모두** 만족 3회 연속 → 중간 advance / 마지막 complete
     ① 정확도 ≤ 80m ② 하차역 300m 내 ③ 속도 ≤ 2 m/s ④ **링이 하차역 도달을 이미 알림**(실시간 없는 노선은 계획 도착 시각 경과). ④는 2호선 등 지상 구간에서 열차가 하차역 근처 감속할 때 조기 전이 방지
  5. 서버 스냅샷 도보 전환 → DI 도보 화면으로 한 번 전환 하차역 좌표 = geometry leg `stops.max(seq).x/y` → 없으면 `points.last`(Swift 모델엔 `subwayStations`·`endX/Y` 없음, `Models.swift:41-75`). #266 출구 좌표가 스냅샷에 있으면 우선
- **WAIT_SUBWAY**: none. 탑승 전환은 선택 UI 만(결정 7)
- 역 도착 신호(결정 11b) 계산도 policy 에: `stationArrived = 역 지점 30m 내 || (100m 전 전환 이후 GPS 공백 ≥ 30초)` → 엔진이 LiveActivity 에 전달
- 단계 역행 금지: `applyNetworkSnapshot` 은 currentLegIndex 가 줄어드는 스냅샷 무시(같은 routeRevision 내)
- **모션 자동 전환 제거**(결정 7): `Engine.swift:864-900, 915-925` 의 `sendSubwayBoardingTransitionIfNeeded`·armSubwayMotion 흐름 삭제(모션 evidence emit 은 JS 무수신이므로 같이 정리). `JourneyGuidanceSubwayMotion.swift` 는 미사용 → 삭제 또는 보존 결정은 구현 시 노트
- **호출 지점 수정(리뷰 5)**: `evaluateLegTransition` 은 투영 accepted & 비 ESTIMATED 에서만 돌고 WAIT_BUS 는 :371 에서 먼저 return(`Engine.swift:364-401`). → `didUpdateLocations` 초입(지하철 분기 :339 처럼) 에 **WAIT_BUS·RIDE_SUBWAY·마지막 RIDE_BUS 전용 분기**를 두고 policy 호출

**네이티브 COMPLETE 신뢰성(리뷰 4)**
- `M/JourneyGuidanceNetwork.swift`: `complete(journeyId, baseCommandVersion, routeRevision, eventId)` → `POST /api/journeys/{id}/complete`(JS `journey-api.ts` 와 동일 body)
- `send()`(:170-202) 409 응답 본문의 `journey` 를 버리지 말고 반환 → 엔진이 `applyNetworkSnapshot` 후 **새 eventId** 로 재전송(서버 sameCommand 가 baseCommandVersion 까지 비교하므로 버전 바뀌면 eventId 재사용 금지)
- `requestInFlight` 단일 슬롯: complete/transition 은 heartbeat·poll 과 겹쳐 false 나면 1~3초 내 재시도 큐
- pending complete 를 `GuidancePersistedState` 에 저장 → 앱 재기동·백그라운드 재개 시 재시도
- **세션 부활 방지**: 완료 성공 시 네이티브가 ①`onTerminal(journeyId)` 이벤트 emit ②자체 `stop("terminal")`(위치 중지·Activity end) ③끝낸 journeyId 를 persisted 에 기록 → `Engine.start` 가 그 id 를 거부. JS `journey-provider.tsx` 는 `onTerminal` 수신 시 `stopSession('terminal')`·`clearStoredJourney`, cold restore(:488-497)·동기화 effect(:701-730)에서 terminal id 면 세션 시작 안 함
- JS outbox COMPLETE 중복은 dispatcher 가 이미 terminal ack(:42-47) → 추가 수정 불필요(테스트만)
- 버스 단독 "중간 시작 시 멈춤": `M/JourneyGuidanceProjection.swift` :172-173 승차 뒤 2구간 창에서 매칭 실패 시 구간 전체 재탐색 폴백

**테스트/검증**
- 신규 `modules/journey-guidance/fixtures/JourneyGuidanceTransitionPolicyFixture.swift`: 규칙별 성공/실패, 버스 탑승(달리기 4m/s 미발화·6m/s 10초 미발화·15초+100m 발화), 신호 정차 miss 2 유지/3 리셋, 버스 마지막(정차 중 미완료·이탈 시 완료·시간 폴백), 지하철 하차역 300m+저속
- Projection fixture 에 중간 시작 케이스 추가
- `swiftc JourneyGuidanceModels.swift JourneyGuidanceProjection.swift JourneyGuidanceTransitionPolicy.swift fixtures/JourneyGuidanceTransitionPolicyFixture.swift -o /tmp/f && /tmp/f`, 기존 fixture 재실행, `npx tsc --noEmit`, iOS 빌드 성공

### A3 앱 `feat/journey-train-picker` — S1 배포 필요
- `F/journey/journey-api.ts`: `fetchTrainCandidates(journeyId, legIndex?)`, `fetchSubwayTrackability(journeyId, legIndex)`, `selectSubwayTrain(journeyId, {routeRevision, trainNo, legIndex?})`
- 신규 `F/journey/journey-train-picker-sheet.tsx`: 모드 3개
  - 대기(WAIT_SUBWAY): 다가오는 열차 + DEPARTED("이미 탔어요")
  - 탑승 중 재선택(RIDE_SUBWAY): DEPARTED 만
  - **지하 환승**: 이전 RIDE_SUBWAY 에서 다음 RIDE 대상 → 선택 성공 후 앱이 `LEG_TRANSITION` 을 c→…→r-1 까지 연속, 이어서 r-1→r(boarded). 각 전송은 outbox 경유, 서버 응답 버전으로 다음 전송
  - **진입 경로(결정 10)**: WAIT_SUBWAY 진입(역 100m 전) 시 로컬 알림 "○○역 열차를 골라주세요" — 탭 딥링크 `nextroute://navigation?picker=subway&legIndex=r` → 안내 화면 + 시트 자동 노출. 지하 환승도 동일(링 하차역 도달 시 "다음 열차를 골라주세요"). 앱 포그라운드면 알림 대신 시트 바로
  - 확인 → `selectSubwayTrain` → (대기면) `reportBoarded()` → `refreshJourney()`. `SUBWAY_BINDING_BUSY` 1회 재시도, 422 `TRAIN_NOT_IN_CANDIDATES` → 목록 새로고침
- `F/transit/navigation-screen.tsx` `renderLive`(:368,554 부근):
  - WAIT_SUBWAY 미연결 → 시트 자동 노출 + "열차 고르기"
  - RIDE_SUBWAY → "다른 열차예요"
  - RIDE_SUBWAY 이고 다음이 지하 환승(…→WAIT_SUBWAY→RIDE_SUBWAY) → 링 하차역 도달(`subwayGuidance.phase == SUBWAY_ALIGHT` 또는 remainingStations==0) 시 다음 열차 시트 자동 노출 + 로컬 알림 "다음 열차를 골라주세요"
  - trackability=false 구간 → 열차 선택·링 없음, "실시간 추적 미지원 구간" 표시(결정 9). 진행은 A2 GPS 규칙
- `active-journey-banner.tsx` WAIT_SUBWAY 미연결 문구 "열차를 골라주세요"
- 테스트: 연속 전이 계산(현재 c, 대상 r → 보낼 전이 목록) 순수 함수 + node test
- 검증: `npx tsc --noEmit`, node test, 시뮬레이터 도보→지하철, 지하철→지하철 여정 시트 노출

### A4 앱 `fix/live-activity-lifecycle`
- **DI 전환 규칙(결정 11)**: WAIT_SUBWAY 미연결 동안
  - `stationArrived == false` → 직전 도보 Activity 그대로 유지(WAIT_BUS 와 같은 `return`). 도보 payload 마지막 값("○○역 n번 출구로") 유지
  - `stationArrived == true` → 대기 payload 로 update
  - 연결(선택) 즉시 → 지하철 링 payload(대기 화면 생략 가능)
- `M/JourneyGuidanceLiveActivity.swift`: WAIT_SUBWAY 미연결 guard·`synchronizeSubway` 미연결 guard·phase guard(:196) → `end()` 금지, 위 규칙대로 유지 또는 **앱 로컬 대기 payload**
  ```swift
  ["phase": "WAIT_SUBWAY", "routeName": line, "nextStationName": boardStationName,
   "alightingStationName": alight, "quality": "NORMAL"]   // 서버 SubwayRingPhase 는 2값, WAIT 는 앱 로컬 전용
  ```
  - trackability=false 지하철 구간도 이 화면(문구만 "실시간 추적 미지원")
- 위젯 `src/features/live-activity/navigation-live-activity.tsx`: TS phase union(:64)에 `WAIT_SUBWAY` 추가, eyebrow(:134) 분기 "열차를 골라주세요", 링 숨김. STALE 문구("실시간 정보 확인 중" :131, :249)와 섞이지 않게 phase 분기 우선
- WAIT_BUS 는 레이아웃 없음 → 직전 도보 화면 유지(:130 return), 현행 유지를 명시
- **단독 추적 포그라운드 시작**: `journey-provider.tsx` 세션 시작(:701-728)에서 형상 대기 전에 네이티브 `startPlaceholder(snapshot)`(브리지 함수 신설, 패치 `ensure` 재사용) 호출 → 화면 잠금 전 Activity 확보. 여정 Activity 는 도보 단계에서 포그라운드로 이미 시작되므로 `end()` 만 안 하면 이어짐
- `ensure` 실패 catch → `os_log` + JS 이벤트 `onLiveActivityError`
- `observePushToken`: activity id 바뀔 때만 등록(패치 :45-66 이 ensure 마다 재부착), 동일 토큰 재등록 POST 생략(Engine 토큰 캐시)
- `M/JourneyGuidanceModels.swift`(:132-148) `estimatedAlightingAt` 디코딩 → LiveActivity 하차 시각 서버 값 우선, 자체 추정 폴백
- 검증: `npx tsc --noEmit`, iOS 빌드, 실기기 시나리오 2·3

---

## 범위 밖 (후속 이슈)
- 재탐색 수락 시 Activity 끄고 새로 만듦(`journey-provider.tsx:242`)
- push 프레임 링 되감김(앱 가드 미적용) — 서버 단조성 보장으로 별건
- 버스 단독: 하차 정류장 선택화·대기 화면, `patternCode` 미전송, 같은 정류장 두 번 노선
- 서버 `boarding-evidence` 자동 판정 코드 정리, push-to-start
- **잠금화면/DI 버튼으로 열차 선택**(iOS 17 LiveActivityIntent) — expo-widgets 버튼 지원 조사 후 별건. 알림 액션 버튼 선택은 후보가 낡는 문제로 보류
- 미커버 5노선 실시간(project-subway-realtime-full-coverage)

## 배포
- 서버: 마이그레이션 **없음**. S1·S2 머지 후 api+worker 교체(선택 API=api, 링 가드·relay·push=worker)
- 플래그 실효값은 VPS compose env 로 확인 후 노트 기록: `JOURNEY_SUBWAY_SELECTION_ENABLED`, `JOURNEY_SUBWAY_RING_PUSH_ENABLED`, `JOURNEY_TRACK_ENABLED`, sender=fcm. 앱 `EXPO_PUBLIC_RIDE_TRACKING_ENABLED` 는 EAS
- 순서: S1 배포 → A1·A3 출시

## 검증 (완료 기준)
- 서버·앱 위 명령 통과, 결과는 `implementation_note.md ### 검증`
- 실기기:
  1. 버스 단독: 탑승 3정거장 뒤 시작 → 진행 움직임 / 신호 정차 중 안 끝남 / 하차 후 1분 내 Activity·위치 표시 사라지고 서버 COMPLETED
  2. 지하철 단독: 다가오는 열차 선택 직후 화면 잠금 → Activity 뜸 / 지하 10분 백그라운드 → 만료 없음 / 하차역 지상 → 자동 종료 / 실시간 없는 노선 → 추적 시작 불가 안내
  3. 여정 도보→지하철→도보: 역 100m 전~입구까지 **DI 도보 화면 유지**, 알림 탭 → 시트 → 선택 → 링 / 선택 안 하고 역 안 진입 → "열차를 골라주세요" / 하차역 도달 "내려요" 유지 → 지상 출구 → 도보 1회 전환, 이후 지하철 화면으로 되돌아가지 않음
  4. 여정 지하철→지하철 환승: 환승역 도달 시 다음 열차 시트 → 선택 → 다음 링
  5. 여정 도보→버스: 버스 대기 → 속도로 탑승 전환(정류장까지 뛰어도 미발화) → 하차 → 다음/완료

## 진행 절차
승인 → (rev2 반영 완료) → PR 단위 새 세션 구현(이 파일만 읽고 시작 가능) → PR 마다 Opus 리뷰(서브에이전트 model=opus) → /verify. 구현 발견은 `implementation_note.md` append-only.

## 실행 체크리스트
- [x] 앱 이슈 → minsuk330/jette-app#12
- [ ] S1 (서버) — 이 PLAN/SUMMARY/HANDOFF 함께 커밋
- [ ] S2 (서버)
- [ ] S1·S2 VPS 배포 + 플래그 확인
- [ ] A2 (앱 엔진)
- [ ] A4 (앱 Live Activity)
- [ ] A1 (앱, #12) — S1 배포 후
- [ ] A3 (앱 열차 선택) — S1 배포 후
- [ ] 실기기 시나리오 1~5 기록
- [ ] 후속 이슈 등록
