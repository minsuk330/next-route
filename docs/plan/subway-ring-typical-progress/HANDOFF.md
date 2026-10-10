# 앱 인계 — 지하철 진행 링 TYPICAL (서버 #360·#363·#364·#365, 2026-10-11 00:10 KST 운영 배포)

서버가 링을 **멈추지 않고 느리게라도 움직이게** 바꿨다. 실시간·실측 예정이 없으면 GTFS 계획 시간 × 1.2 로 직선을 그리고 그 사실을 `etaSource=TYPICAL` 로 알린다. 스냅샷 `subwayGuidance` 와 Live Activity push `props` 에 **같은 이름**으로 실린다. 모든 새 칸은 선택이라 모르는 앱은 무시해도 된다.

## 1. 앱 main(`e9d140a`) 기준 이미 맞는 것 — 확인만

| 서버 동작 | 앱 코드 | 상태 |
|---|---|---|
| `etaSource=TYPICAL` 이면 다음 역 도착 시각은 추정 | `navigation-live-activity.tsx` `etaDate` 가 `etaSource !== 'TYPICAL'` 일 때만 시각 표시 | ✅ |
| 전역 정차 `holdReason=PREV_STATION_STOPPED` + `holdStationName` | `holdCopy` "○○ 정차 중" | ✅ |
| 늦음 `holdReason=LATE` | "조금 늦어지고 있어요" | ✅ |
| 회색 STALE 은 서버가 새 관측을 못 받을 때만 | `qualityCopy` 는 `isStale` 일 때만 | ✅ |
| 정차 중 보간 상한이 0.90 보다 낮다(`progressTarget` = 정차 지점) | `shownSubwayProgress` 가 `progressTarget ?? 0.9` 사용 | ✅ (하드코딩 0.9 를 새로 쓰지 말 것) |
| 예정이 없을 때 출처 이어 쓰기 | `JourneyGuidanceLiveActivity.swift` `etaSource: estimatedNextStationAt == nil ? carried?.etaSource : …` | ✅ |

## 2. 남은 작업 — `alightingEtaSource`

서버는 이제 **하차 예정(`estimatedAlightingAt`)을 거의 항상 채운다**(GTFS 를 모를 때만 null). 출처는 `alightingEtaSource` 로 따로 온다.

| 값 | 언제 | 뜻 |
|---|---|---|
| `LIVE` / `MEASURED` | **다음 역이 하차역**이고 그 예정이 실시간·실측일 때 | 믿을 만한 시각 |
| `TYPICAL` | 그 밖 전부 — 남은 구간이 GTFS 계획값이거나, 다음 역 예정이 없어 추정했을 때(늦음·진입·도착·정차 초과·멈춤) | 대략적인 시각 |
| null | 하차 예정 없음 | — |

**제안(앱 결정)**: 컴팩트 Dynamic Island 의 하차 카운트다운은 그대로 두되(시스템 타이머라 "약"을 못 붙인다), 확장 화면·잠금화면의 하차 시각 문구는 `alightingEtaSource === 'TYPICAL'` 이면 "약 HH:mm 도착" 처럼 완곡하게. 타입 추가:

```ts
// navigation-live-activity.tsx props, journey/types.ts SubwayGuidance
alightingEtaSource?: 'LIVE' | 'MEASURED' | 'TYPICAL' | null;
```

Swift 쪽 `JourneyGuidanceLiveActivity.swift` 도 `etaSource` 와 같은 방식으로 디코드·이어 쓰기(`estimatedAlightingAt == nil ? carried?.alightingEtaSource : guidance.alightingEtaSource`).

## 3. 의미가 바뀐 칸 — 코드가 기대는 곳 점검

- **`holdReason=PREV_STATION_STOPPED` 중에도 링이 움직일 수 있다.** 전역 정차를 "정차 지점(`상한 × 정차/(정차+운행)`)까지 느리게 차는 직선"으로 그린다. 정차가 계획보다 길어지면 그 지점에서 멈춘다. 문구는 그대로 "○○ 정차 중". `holdReason != null` 을 "링이 멈췄다"로 해석하는 코드가 있으면 고칠 것(`navigation-live-activity.tsx` 의 주석 "A ring standing still says why" 정도)
- **`estimatedNextStationAt` = 보간 종점 시각.** 보통 다음 역 도착 예정이지만, 전역 정차 직선 동안은 **정차 끝**이다. 이때 `etaSource=TYPICAL` 이라 앱은 시각을 안 보이므로 화면 문제는 없다. 이 값을 "다음 역 도착"으로 다른 계산(예: 자체 하차 추정 `perStationMs` 폴백)에 쓰는 곳은 `estimatedAlightingAt` 을 우선 쓰면 된다 — 서버 값이 거의 항상 온다
- **STALE 이 크게 줄었다.** 구간이 넘어갈 때마다 뜨던 회색 "확인 중"은 없어졌다. STALE = 도착 스냅샷 기준 60초 동안 우리 열차 행을 못 받았거나, 행의 원천 시각이 240초 넘게 묵었을 때. 위치 폴백만 있을 때는 종전(원천 시각 120초)
- **가짜 LATE 가 없어졌다.** 원천 지연(1~2분)으로 첫 관측에 바로 "늦어지고 있어요"가 뜨던 문제(journey 202)는 서버가 관측을 처음 본 시각으로 직선을 앵커해 해결. LATE 는 예정 + 30초가 지나야 뜬다

## 4. 링 모양 — 한 구간 예시(2호선 교대→강남, GTFS 정차 60초·운행 90초)

```
시각   서버 관측            segmentProgress  progressTarget  estimatedNextStationAt  etaSource  holdReason
t0     교대 도착(→ 강남 구간)  0.00             0.36            t0+72s (정차 끝)        TYPICAL    PREV_STATION_STOPPED(교대)
t0+72  아직 교대(정차 초과)    0.36             0.36            null                    null       PREV_STATION_STOPPED(교대)
t0+87  교대 출발(전역 출발 3)  0.36             0.90            t0+87+108s              TYPICAL    null   (실측 표본 있으면 MEASURED)
…      강남 진입(0)           0.95             0.95            null                    null       null
…      강남 도착(1)           1.00             1.00            null                    null       null
```

정시면 정차 직선(0→0.36, 72초)과 운행 직선(0.36→0.90, 108초)의 기울기가 같아 한 속도로 보인다.

## 5. push 양

실녹화 재생(2호선 7399 서초→선릉 4구간)에서 16건, 역당 약 4건. 정차→운행 전환, 출처 전환(TYPICAL↔MEASURED), 진입·도착 때 나간다. 배포 직후 진행 중이던 링마다 지문 변화로 1건이 더 나갔다(일회성).

## 6. 확인 방법

- 실기기 여정 1회: 전역 정차 중 링이 천천히 차는지, 구간 전환 때 회색이 안 뜨는지, 하차 카운트다운이 늘 보이는지
- 서버 재생 도구로 같은 장면 재현: `SubwayRingReplayIT`(저장소 녹화 조각) — `./gradlew test --tests '*SubwayRingReplayIT'` → `build/replay/line2-7399.csv`, `tools/subway-replay/render.py` 로 그래프
