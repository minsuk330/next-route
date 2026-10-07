# HANDOFF — 모바일: 역 ID 도착 API 로 전환 (#277)

## 무엇을 바꾸나
홈 "근처에서 타기" 지하철 줄과 역 시트의 도착 조회를 좌표 API 에서 역 ID API 로 바꾼다.

```
GET /api/subway/stations/{stationId}/arrivals
```
`stationId` = `GET /api/nearby/subway-stations` 응답의 `stationId` 그대로(노선별로 다르다: 강남 2호선 `1002000222`, 신분당선 `1077000687`).

## 응답
```jsonc
{
  "stationId": "1002000222",
  "lineId": "1002",
  "directions": [
    { "direction": "내선", "label": "내선",
      "trains": [ { "trainNo": "2231", "destinationName": "성수", "trainType": "일반",
                    "arrivalSeconds": 96, "stationsAway": 1, "currentMessage": "전역 출발" } ] }
  ]
}
```
- `directions` 순서·`label` 은 서버가 정한다. 앱은 그대로 탭으로 그린다.
  - 순서 = `direction` 한국어 사전순(빈 방향은 맨 뒤) → 새로고침해도 탭 자리 고정
  - label = 행선지 최빈값 + " 방면", 두 방향이 같으면 `direction` 원문(2호선 내선/외선)
  - `direction` 이 `null` 일 수 있다(원천이 비워 보낼 때)
- 방향 안 `trains` 는 도착 순. `arrivalSeconds` 가 `null` 인 열차가 흔하다 → `stationsAway`·`currentMessage` 로 표시하고 감추지 말 것.
- 없는 역 → `404` `{ "code": "SUBWAY_STATION_NOT_FOUND" }`. 운행 정보 없음 → `200` + `directions: []`.

## 지울 것
- `use-subway-line-arrivals.ts` 의 노선별 승강장 좌표 조회 + `lineId` 재필터 우회
- `ride-tracking-model.ts` `groupTrainsByDirection`·`directionLabel` (서버가 같은 규칙으로 내려 준다. 테스트 `ride-tracking-model.test.ts` 해당 케이스도)

## 그대로 둘 것
- 경로 검색·안내의 지하철 도착은 역 ID 가 없어 좌표 API(`/api/arrivals/subway`) 유지. 좌표 API 환승역 버그는 #276(PR #280) 으로 따로 고쳤다.

## 아직 없는 것
- `departed`(방금 떠난 열차) → #265 에서 같은 엔드포인트 방향별로 추가 예정.
