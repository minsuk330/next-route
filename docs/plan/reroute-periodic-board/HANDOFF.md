# 모바일 인계 — 재탐색 주기 보드 (#264)

서버가 진행 중인 여정마다 **보드**(하차 자리별 더 빠른 경로 목록)를 1분 안팎마다 조용히 갱신합니다. 앱은 이 보드를 지도 말풍선으로 그립니다. push 는 확실한 이득이 있을 때만 옵니다.

서버 플래그 `JOURNEY_REROUTE_BOARD_ENABLED` 가 켜진 뒤부터 아래 계약이 적용됩니다. 꺼져 있으면 응답은 예전과 같습니다.

관련: [#259 후보 경로 조회 HANDOFF](../reroute-candidate-route/HANDOFF.md)

## 1. `GET /api/journeys/{journeyId}/reroute-proposals/active`

### 보드 응답 (신규 칸)

```jsonc
{
  "proposal": {
    "proposalId": 812,
    "routeRevision": 1,
    "kind": "REMAINING_PATH",
    "expiresAt": "…",              // 마지막 갱신 + 3분. 지나면 서버가 보드를 없는 것으로 봅니다
    "boardVersion": 17,            // 신규. 보드면 항상 있음
    "refreshedAt": "2026-10-07T09:12:00Z", // 신규. 마지막으로 찾아본 시각
    "candidates": [
      {
        "candidateId": "…",
        "kind": "REMAINING_PATH",
        "originType": "RIDE_STOP",   // 신규. PLANNED_ALIGHT | RIDE_STOP | CURRENT_POSITION | WAITING_STOP
        "display": {
          "etaGainSec": 420,
          "alight": { "stopId": "…", "name": "성수역", "alightAt": "…", "planned": false,
                      "x": 127.0559, "y": 37.5446 }
        }
      }
    ]
  }
}
```

- 사건 기반 제안(플래그 off)에는 `boardVersion`·`refreshedAt`·`originType` 칸이 **없습니다**.
- 서버가 이미 걸러서 보냅니다. 앱이 다시 거를 필요가 없습니다.
  - 숨긴 자리(이득이 문턱 아래)는 오지 않습니다.
  - 도착까지 60초 미만인 자리는 오지 않습니다.

### 말풍선 그리기 (`originType`)

| originType | 말풍선 위치 | 뜻 |
|---|---|---|
| `RIDE_STOP` | `alight.x/y` (내릴 정류장) | 지금 타는 버스를 이 정류장에서 내려 갈아탐 |
| `PLANNED_ALIGHT` | `alight.x/y` (계획 하차점) | 원래 내리려던 곳에서 다른 경로로 감 |
| `CURRENT_POSITION` | 내 위치 | 걷는 중, 지금 위치에서 다른 경로 |
| `WAITING_STOP` | 기다리는 정류장 | 기다리는 곳에서 다른 노선·경로 |

`CURRENT_POSITION`·`WAITING_STOP` 은 PR-3 이후에 내려옵니다. 모르는 값이 오면 하차 정류장형처럼 `alight.x/y` 를 쓰고, 그것도 없으면 그리지 않습니다.

### 같은 말풍선 = 같은 `candidateId`

- 같은 경로가 다시 찾아지면 숫자(`etaGainSec`, 도착 시각)만 바뀌고 `candidateId` 는 그대로입니다. 말풍선을 다시 만들지 말고 내용만 갱신하세요.
- 다른 경로로 바뀌면 `candidateId` 가 바뀝니다. 기존보다 60초 이상 빨라야 바뀌므로 자주 일어나지 않습니다.

## 2. 갱신 받는 법 — 15초 폴링 + ETag

- 화면이 켜져 있는 동안 **15초마다** `/active` 를 부릅니다.
  - 응답의 `ETag` 를 저장하고, 다음 요청에 `If-None-Match` 로 보냅니다.
  - 바뀐 게 없으면 `304 Not Modified`(본문 없음)가 옵니다. 기존 화면을 유지하면 됩니다.
- 아래 순간에는 15초를 기다리지 말고 **즉시** 다시 부릅니다.
  - 앱이 포그라운드로 돌아옴
  - push 를 탭함
  - 수락·경로 조회가 `404`/`409` 를 받음
  - 구간이 넘어감(탑승·하차 이벤트를 보낸 직후)
- 백그라운드에서는 부르지 않습니다. 그동안은 push 가 알려 줍니다.

## 3. 빈 상태 문구

| 상황 | 판정 | 문구 |
|---|---|---|
| 최근 찾았고 보이는 후보가 0개 | `proposal != null && candidates == []` | "지금 경로가 가장 빨라요" + `refreshedAt` 기준 "n분 전 확인" |
| push 로 들어왔는데 후보가 사라짐 | push 탭 직후 조회에서 위와 같음 | "방금 찾은 더 빠른 경로가 사라졌어요. 지금 경로가 가장 빨라요" |
| 보드가 없음(서버 갱신 멈춤 등) | `proposal == null` | 아무것도 표시하지 않음 |

## 4. push 진입

push payload 의 후보를 그대로 쓰지 말고, 탭하면 `/active` 를 다시 불러 **최신 보드**를 보여 줍니다. push 문구와 숫자가 다를 수 있습니다(그 사이 갱신됨).

## 5. 진행 보고 — 지하철도 남은 역 수 (변경)

`POST /api/journeys/{id}/progress` 의 `remainingStops` 를 **지하철 탑승 구간에서도** 보내 주세요(남은 역 수).

- 서버는 이 값이 줄어드는 순간을 "역 하나를 지났다"로 보고 보드를 바로 다시 찾습니다.
- 버스는 지금처럼 남은 정류장 수를 보냅니다.

## 6. 수락 · 경로 조회

#259 와 같습니다. 보드에서는 **지금 보이는 후보**만 고를 수 있습니다. 숨겨졌거나 교체된 `candidateId` 는 `404 REROUTE_CANDIDATE_NOT_FOUND` 이고, 그때는 `/active` 를 다시 받습니다.
