# 모바일 인계 — 재탐색 주기 보드 (#264)

> **상태: 운영 켜짐(2026-10-07 13:05 KST).** 서버 이미지 `nextroute:20261007-125927-21371b27`, `JOURNEY_REROUTE_BOARD_ENABLED=true`(api·worker).
> 관련 인계: [#259 후보 경로 조회·하차 좌표](../reroute-candidate-route/HANDOFF.md)

## 0. 한 줄 요약

서버가 진행 중인 여정마다 **"지금 더 빠른 길" 보드**를 정류장·역을 지날 때마다(신호가 없으면 60초마다) 조용히 갱신합니다. 앱은 화면이 켜져 있는 동안 이 보드를 **15초마다 받아(ETag) 지도 말풍선**으로 그립니다. 사용자를 깨울 만큼 확실할 때만 서버가 push 를 보냅니다.

"다른 경로 찾기" 버튼은 필요 없습니다. 개발 빌드에서만 `POST /reroute-proposals` 를 남겨 두세요. 보드 모드에서 이 요청은 보드를 즉시 1회 갱신한 결과를 돌려줍니다.

---

## 1. 앱이 해야 할 일 (체크리스트)

- [ ] 여정 화면이 켜져 있는 동안 `/active` 15초 폴링 + `If-None-Match` (§2)
- [ ] 아래 순간에는 즉시 재조회 (§2.3)
- [ ] `candidates[]` 를 `originType` 별로 지도 말풍선으로 그리기 (§3)
- [ ] 같은 `candidateId` 는 말풍선을 다시 만들지 말고 내용만 갱신 (§3.3)
- [ ] 빈 상태 문구 3종 (§4)
- [ ] 말풍선 탭 → 후보 경로 조회 → 상세(`RouteDetail`) + 지도 겹쳐 그리기 (§5)
- [ ] 수락 / 거절 / 무시 처리와 응답 해석 (§6)
- [ ] push 수신·탭 처리 (§7)
- [ ] **진행 보고에 지하철 남은 역 수 보내기 (§8, 서버 신호에 필요)**

---

## 2. 보드 받기 — `GET /api/journeys/{journeyId}/reroute-proposals/active`

### 2.1 요청

```
GET /api/journeys/{journeyId}/reroute-proposals/active
Authorization: Bearer <token>
If-None-Match: "<직전 응답의 ETag>"     // 두 번째 요청부터
```

### 2.2 응답

- **200**: 본문 + `ETag` 헤더(따옴표 포함 문자열 그대로 저장).
- **304 Not Modified**: 본문 없음. 내보낸 후보와 숫자가 직전과 같다는 뜻이므로 화면을 그대로 둡니다.
- ETag 는 서버가 실제로 내보낸 본문으로 만듭니다. `boardVersion` 이 같아도 시간이 지나 자리가 빠지면 200 이 옵니다.

```jsonc
{
  "proposal": {
    "proposalId": 812,
    "routeRevision": 1,
    "origin": null,                 // 보드에서는 항상 null (무시)
    "kind": "REMAINING_PATH",
    "expiresAt": "2026-10-07T04:12:00Z",   // 마지막 갱신 + 3분. 지나면 서버가 보드를 없는 것으로 봄
    "snapshotObservedAt": "2026-10-07T04:08:40Z",
    "boardVersion": 17,             // 보드면 항상 있음
    "refreshedAt": "2026-10-07T04:09:00Z", // 마지막으로 찾아본 시각
    "candidates": [
      {
        "candidateId": "6f1c…",     // 말풍선의 정체성 (§3.3)
        "kind": "REMAINING_PATH",
        "originType": "RIDE_STOP",  // §3
        "display": {
          "destinationEtaAt": "2026-10-07T04:41:00Z",
          "totalDurationSec": 1920,
          "etaGainSec": 420,        // 지금 경로보다 몇 초 빠른가
          "transferCount": 1,
          "walkingSec": 240,
          "source": "REALTIME",     // REALTIME | ML | STATIC
          "confidence": "GOOD",
          "dataObservedAt": "2026-10-07T04:08:40Z",
          "legSummaries": ["2412 승차", "성수역 → 건대입구"],
          "alight": {
            "stopId": "121000123",  // 현재 위치형은 null
            "name": "성수역",        // 현재 위치형은 "현재 위치"
            "alightAt": "2026-10-07T04:15:30Z", // 하차형만. 현재 위치·대기형은 null
            "planned": false,
            "x": 127.0559,          // 말풍선 경도
            "y": 37.5446            // 말풍선 위도
          }
        }
      }
    ]
  }
}
```

- 서버가 이미 거르기 때문에 앱이 다시 거를 필요는 없습니다.
  - 숨긴 자리(이득이 문턱 아래)는 오지 않습니다.
  - 도착까지 60초 미만인 자리는 오지 않습니다.
  - 개정본이 바뀐 보드, 유효시간이 지난 보드는 `proposal: null` 입니다.
- 정렬은 보장하지 않습니다. 필요하면 `etaGainSec` 내림차순으로 정렬하세요.

### 2.3 언제 부르나

| 상황 | 동작 |
|---|---|
| 여정 화면이 포그라운드 | **15초마다**(ETag) |
| 앱이 포그라운드로 돌아옴 | 즉시 |
| push(`REROUTE_SUGGESTED`) 탭 | 즉시 |
| 수락·후보 경로 조회가 `404`/`409` | 즉시 |
| 탑승·하차 등 구간 전이 이벤트를 보낸 직후 | 즉시 |
| 백그라운드 | 부르지 않음 (push 가 알림) |

---

## 3. 말풍선 그리기

### 3.1 `originType` 별 위치·문구

| originType | 말풍선 위치 | 뜻 | 상세 첫 줄 |
|---|---|---|---|
| `RIDE_STOP` | `alight.x/y` (내릴 정류장) | 지금 탄 버스를 이 정류장에서 내려 갈아탐 | "{alight.name} 하차" |
| `PLANNED_ALIGHT` | `alight.x/y` (원래 내리려던 곳) | 원래 하차점에서 다른 경로로 감 | "{alight.name}에서 다른 경로" |
| `CURRENT_POSITION` | `alight.x/y` (서버가 추정한 내 위치) 또는 앱의 현재 위치 | 걷는 중, 지금 위치에서 다른 경로 | "지금 위치에서" |
| `WAITING_STOP` | `alight.x/y` (기다리는 정류장) | 기다리는 곳에서 다른 노선·경로 | "{alight.name}에서 다른 노선" |

- 모르는 값이 오면 `alight.x/y` 에 그리고, 그것도 없으면 그리지 않습니다.
- 말풍선 내용 예: "**7분 빨라요**" (`round(etaGainSec/60)`) + `legSummaries[0]`.

### 3.2 언제 나타나고 사라지나 (서버 규칙 — 참고용)

| 조건 | 값 |
|---|---|
| 처음 뜨는 문턱 | 실시간 근거 120초 / ML 추정이 섞이면 180초 / 환승 1회 늘 때마다 +60초 |
| 사라짐 | 도착 60초 미만, 지나감, 이득이 문턱 아래로 떨어짐 |
| 다시 뜸 | 문턱 + 30초 |

### 3.3 같은 말풍선 = 같은 `candidateId`

- 같은 경로가 다시 찾아지면 숫자(`etaGainSec`·시각)만 바뀌고 **`candidateId` 는 그대로**입니다. 말풍선을 지우고 다시 만들지 말고 내용만 바꾸세요(깜빡임 방지).
- 다른 경로로 바뀌면 `candidateId` 가 바뀝니다. 기존보다 60초 이상 빨라야 바뀌므로 자주 일어나지 않습니다.
- 응답에서 사라진 `candidateId` 의 말풍선은 지웁니다.

---

## 4. 빈 상태

| 상황 | 판정 | 문구 |
|---|---|---|
| 최근 찾았고 보이는 후보가 0개 | `proposal != null && candidates == []` | "지금 경로가 가장 빨라요" + `refreshedAt` 기준 "n분 전 확인" |
| push 를 탭했는데 후보가 사라짐 | push 탭 직후 조회에서 위와 같음 | "방금 찾은 더 빠른 경로가 사라졌어요. 지금 경로가 가장 빨라요" |
| 보드가 없음 | `proposal == null` | 아무것도 표시하지 않음 |

참고: 여정 시작 후 첫 갱신은 **60초 뒤**입니다. 그 전에는 `proposal: null` 이 정상입니다.

---

## 5. 말풍선 탭 → 후보 경로 — `GET …/candidates/{candidateId}/route`

#259 와 같습니다. [#259 HANDOFF](../reroute-candidate-route/HANDOFF.md) 를 참고하세요.

```
GET /api/journeys/{journeyId}/reroute-proposals/{proposalId}/candidates/{candidateId}/route
→ { proposalId, candidateId, ride | null, path }
```

- `path` 는 `/api/route/search` 의 `paths[]` 원소와 같은 모양입니다. `toRouteOptions` → `RouteDetail` 에 그대로 넣고, `subPaths[].polyline` 으로 지도에 겹쳐 그립니다.
- `ride` 는 지금 타고 있는 구간입니다(`RIDE_STOP`·`PLANNED_ALIGHT` 일 때). 경기·지방 버스는 `ride.stopCount` 가 null 일 수 있습니다.
- 이 조회는 서버에서 **push 에 답한 것**으로 셉니다(§7.3).
- 오류
  - `404 REROUTE_CANDIDATE_NOT_FOUND`: 그 사이 숨겨지거나 교체됐음. `/active` 를 다시 받습니다.
  - `409 REROUTE_PROPOSAL_STALE`: 여정이 움직였음. `/active` 를 다시 받습니다.

---

## 6. 수락 · 거절 · 무시

| 동작 | 요청 | 응답 / 뜻 |
|---|---|---|
| 수락 | `POST …/reroute-proposals/{proposalId}/accept` `{"candidateId": "…"}` | 새 개정본이 반영된 여정 스냅샷. `routeRevision` 으로 geometry 를 다시 받습니다 |
| 거절 | `POST …/{proposalId}/reject` `{"keepAlerts": false}` | `{closed:false, rerouteTerminated:true, rerouteAlertOptIn:false}` — **push 만 멈춤**. 말풍선(보드)은 계속 갱신됨. `keepAlerts:true` 면 push 도 계속 |
| 무시(시트 닫기) | `POST …/{proposalId}/dismiss` | `{closed:false, …}` — 보드는 그대로, 무시 1회로 셈 |

- 보드에서는 거절·무시가 **보드를 닫지 않습니다**(`closed: false`). 말풍선을 숨기고 싶으면 앱 쪽 UI 상태로만 처리하세요.
- 수락 뒤 3분 동안은 "새 기회" push 가 오지 않습니다. 수락해도 push 가 영구히 꺼지지 않습니다.

수락 오류

| 코드 | 앱 동작 |
|---|---|
| `409 REROUTE_PROPOSAL_STALE` | `/active` 재조회 |
| `409 REROUTE_ALIGHT_UNAVAILABLE` | 그 정류장은 이미 못 내림. `/active` 재조회 |
| `404 REROUTE_CANDIDATE_NOT_FOUND` / `REROUTE_PROPOSAL_NOT_FOUND` | `/active` 재조회 |

---

## 7. push

### 7.1 언제 오나

- **위험 + 대안**: 다음 환승이 위험해졌고 300초 이상 빠른 경로가 있을 때. 이 경우 기존 "환승 시간이 빠듯해요" 알림 대신 **이 push 하나만** 옵니다.
- **새 기회**: 300초 이상 빠른 경로가 새로 생겼을 때. 여정 시작·수락 뒤 3분 동안은 오지 않습니다.
- 공통 제한
  - 하차형은 도착까지 2분 이상 남아야 합니다.
  - 같은 여정에는 5분에 한 번입니다.
  - 답 없는 push 가 2번 쌓이면 더는 오지 않습니다(거절하면서 "계속 받기"를 켰으면 예외).

### 7.2 수신 형태 (FCM)

- 문구는 서버가 만듭니다.
  - title: "더 빠른 길이 있어요"
  - body 예: "성수역에서 내려 2412 타면 7분 빨라요" / "360(으)로 가면 7분 빨라요" (재료가 없으면 "앱에서 새 경로를 확인하세요.")
- `data`

```jsonc
{
  "checkpoint": "REROUTE_SUGGESTED",
  "messageKey": "journey.reroute.suggested",
  "journeyId": "123",
  "routeRevision": "1",
  "scopeType": "JOURNEY",
  "scopeId": "812",                 // = proposalId
  "notificationId": "…", "commandVersion": "…", "evaluationVersion": "…",
  "deepLink": "nextroute://journey/123?alertId=…&scope=JOURNEY&scopeId=812"
}
```

- **후보·숫자는 `data` 에 없습니다.** 탭하면 딥링크로 여정 화면을 열고 `/active` 를 즉시 다시 불러 **최신 보드**를 보여 주세요. push 문구의 숫자와 화면 숫자가 다를 수 있습니다(그 사이 갱신됨).

### 7.3 무엇이 "답"인가

- 서버는 다음 push 를 보낼 때 직전 push 에 답이 없었으면 무시 1회로 셉니다.
- 답으로 치는 것: 후보 경로 조회(§5), 수락, 거절, 무시.
- `/active` 폴링은 답이 아닙니다.

---

## 8. 진행 보고 변경 — 지하철 남은 역 수 (필수)

`POST /api/journeys/{journeyId}/progress` 의 `remainingStops` 를 **지하철 탑승 구간에서도** 보내 주세요(남은 역 수).

- 서버는 이 값이 줄어드는 순간을 "역 하나를 지났다"로 보고 보드를 바로 다시 찾습니다. 안 보내면 지하철에서는 60초 주기로만 갱신됩니다(열차가 고정된 여정은 서버가 열차 위치로도 압니다).
- 버스는 지금처럼 남은 정류장 수를 보냅니다.
- 걷기 구간은 지금처럼 `geometrySegmentIndex`·`segmentProgress` 를 보내면 됩니다. 서버가 이 값으로 현재 위치를 추정해 `CURRENT_POSITION` 후보를 만듭니다.

---

## 9. 서버 쪽 참고 (질문할 때)

- 갱신 기점
  - 걷기: 추정 현재 위치
  - 기다리기: 그 정류장
  - 버스: 중간 정류장들(서울·경기·지방) + 다음 환승이 있으면 계획 하차점
  - 지하철: 계획 하차점만
- 끄기: 서버 플래그 `JOURNEY_REROUTE_BOARD_ENABLED` 를 지우면 응답이 예전 모양으로 돌아갑니다. 예전 모양은 `boardVersion`·`refreshedAt`·`originType` 칸이 없고 ETag 도 없습니다. 앱은 칸이 없을 때도 동작해야 합니다.
- 설계 원문: 이슈 #264, `docs/plan/reroute-periodic-board/PLAN.md`
