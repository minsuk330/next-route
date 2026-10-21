# HANDOFF — 모바일: 하차역 선택 지하철 데이터 (#283)

## 무엇을 바꾸나
`src/features/ride-tracking/use-alight-stops.ts` 의 `loadAlightStops` 지하철 분기(지금 `placeholderSubwayStops`)를 아래 API 호출로 바꾼다.

```
GET /api/subway/stations/{stationId}/stations-ahead?direction=내선[&destination=성수][&trainNo=2390]
```
- `stationId`: 승차역 서울 실시간 역 ID(`/api/nearby/subway-stations`·#277 와 같은 값). `AlightRequest.boardStopId`.
- `direction`: **#277 응답 `directions[].direction` 원문**(`상행`·`하행`·`내선`·`외선`).
  - 🔴 `AlightRequest` 엔 표시용 `directionLabel`("잠실 방면")만 있다. 원문 `direction` 필드를 추가해 넘겨야 한다. 라벨을 보내면 400.
- `destination`(선택): 탄 열차의 `destinationName`(#277 `trains[].destinationName`). 열차를 골랐으면 꼭 넣는다 — 분기 노선에서 그 갈래 끝까지 받는다.

## 응답 → `AlightStop`
```jsonc
{
  "lineId": "1005", "boardStationId": "1005000533", "boardStationName": "방화", "direction": "하행",
  "destinationApplied": false,
  "endReason": "BRANCH",                     // TERMINAL | BRANCH | LOOP
  "branches": [ { "destinationName": "마천", "nextStationName": "둔촌동", "kind": "TERMINAL" },
                { "destinationName": "하남검단산", "nextStationName": "길동", "kind": "TERMINAL" } ],
  "stations": [ { "stationId": "1005000534", "stationName": "개화산", "stopsAway": 1, "minutes": 2,
                  "transfers": [] } ]
}
```
| AlightStop | 응답 |
|---|---|
| `id` | `stationId` — **null 가능**(역 마스터에 없는 역, 예: 4호선 진접 연장). null 이면 `stationName` 으로 키를 만든다. 빼지 말 것 |
| `name` | `stationName` |
| `stopsAway` | `stopsAway` |
| `minutes` | `minutes`(시간표 근사, null 가능) |
| `transfers` | `transfers` 그대로(`{lineId, lineName}`) |
| `code` | `null` |
| `frequent` | 앱이 추적 기록으로 붙인다(서버 필드 아님) |

## 급행 열차 정차(subway-express PR-S3)
`&trainNo=<탄 열차 번호>`(#277 `trains[].trainNo`)를 붙이면 그 열차 기준 정차가 함께 온다. 안 붙이면 아래 칸은 전부 null(예전과 같음).
- 최상위 `train`: `{kind, label, serviceId, stopsAtBoard}`. `kind` = `NORMAL`·`EXPRESS`·`DIRECT`·`ITX`·`UNKNOWN`(문자열로 받을 것).
- `stations[].stopsHere`: 급행으로 해석된 열차만 `true`/`false`(그 열차 종착 이후 역도 `false`). 일반 열차·모름은 null. **서지 않는 역도 목록에 남는다** — 흐리게, 고를 수 없게.
- `stations[].expressStopsAway`: 급행이 서는 역만 센 정거장 수(서는 역에만). `stopsAway` 는 완행 기준 그대로다.

## endReason 별 화면
- `TERMINAL`: 목록 끝 = 종착(또는 받은 행선지).
- `LOOP`(2호선): 한 바퀴, 승차역 바로 앞 역까지. 2호선은 행선지 "성수"를 보내도 자르지 않는다.
- `BRANCH`: 목록 끝 = 분기점. 그 아래에 `branches` 를 갈래 선택지로 보여 주고, 고르면 `destination=<branches[].destinationName>` 로 다시 부른다.
  - 갈래 표시는 **`nextStationName` 기준**("둔촌동 방면")으로 한다. `destinationName` 은 다시 부를 값이다.
  - `kind: "LOOP"` 갈래 = 2호선 순환 본선(#292). `destinationName` 이 `"순환"` 이다 — "문래 방면 · 순환"처럼 보여 주고, 고르면 `destination=순환` 으로 다시 부르면 본선 한 바퀴가 온다. (예전 응답은 한 바퀴 끝 역 "대림"을 줬고 "대림 방면"이 반대 방향으로 읽혔다.)
  - **승차역이 곧 분기점이면 `stations: []`** 이고 `branches` 만 있다(2호선 신도림·성수, 1호선 구로, 5호선 강동, 경의중앙 가좌 등). 빈 목록을 "역 없음"으로 보이지 말 것.
- `destinationApplied=false` 인데 destination 을 보냈다면 행선지를 갈래에서 못 찾은 것이다(공통 구간만 왔다).

## 오류
| HTTP | code | 앱 처리 |
|---|---|---|
| 404 | `SUBWAY_STATION_NOT_FOUND` | 역 ID 오류 |
| 400 | `SUBWAY_DIRECTION_INVALID` | 방향 값이 틀렸거나 그 노선에 없는 방향 |
| 422 | `SUBWAY_STATION_NOT_ON_ROUTE` | 이 역은 운행 데이터가 없다 → 직접 입력/자리표시로 |
| 503 | `SUBWAY_ROUTE_UNAVAILABLE` | 잠시 뒤 재시도 |

## 캐시
서버가 노선 데이터를 하루 캐시한다. 앱 `staleTime` 10분 그대로 둬도 된다. queryKey 에 `direction` 원문과 `destination` 을 넣을 것.

## 범위 밖
- 경춘선 용산 직결 열차(상봉·청량리까지만)
