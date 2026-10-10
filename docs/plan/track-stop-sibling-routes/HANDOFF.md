# 모바일 인계 — 정류장 노선 전체 표기 + 근처 목록 한 줄 합치기

## 한 줄 요약
같은 정류장이 서울·경기·인천 데이터로 쪼개져 있어서 노선이 일부만 나오던 문제를 서버에서 고쳤다. 앱이 바꿀 것은 **노선 행의 실시간 도착 조회 id** 하나, 그리고 권장 사항 하나다.

## 무엇이 바뀌었나 (서버)
1. `GET /api/journeys/track/stops/near?mode=BUS`
   - 같은 정류장이 한 줄로 온다. 예전에는 `신분당선강남역`(서울) / `신분당선강남역(중)`(경기) / (인천) 이 따로 왔다.
   - 대표는 서울 → 경기 → 인천 순으로 고른다. `gtfsStopId`·`name`·좌표·`arrivalStopId`·`arsId` 가 모두 대표 것이다.
   - `distanceMeters` 는 묶인 정류장 중 가장 가까운 것이다.
   - 응답 필드는 그대로다.
2. `GET /api/journeys/track/stops/{gtfsStopId}/patterns`
   - 묶인 정류장의 노선까지 전부 온다. 신분당선강남역은 21개에서 55개가 됐다.
   - `Direction` 에 **필드 2개가 추가**됐다.

| 필드 | 뜻 |
|---|---|
| `boardStopGtfsId` | 이 노선을 실제로 타는 정류장의 gtfsId. 누른 정류장이거나, 묶인 다른 피드 정류장이다 |
| `arrivalStopId` | **이 노선의** 버스 도착 조회 정류장 id. 지하철이거나 못 풀면 null |

## 앱이 할 일
1. **(필수)** 노선 행의 실시간 도착은 `GET /api/arrivals/bus/{Direction.arrivalStopId}/{Direction.arrivalRouteId}` 로 부른다.
   - `Direction.arrivalStopId` 가 null 이면 지금처럼 정류장의 `arrivalStopId` 를 쓴다.
   - 이걸 안 바꾸면 경기 노선이 서울 정류장 id 로 조회돼서 도착 정보가 비어 온다.
2. **(권장)** 하차 목록 `/routes/{id}/stops`, 노선 선 `/routes/{id}/shape`, 추적 시작 `POST /api/journeys/track` 의 `from`(boardStopId)에 `Direction.boardStopGtfsId` 를 넘긴다.
   - 지금처럼 누른 정류장 id 를 넘겨도 서버가 묶인 정류장에서 찾아 주므로 깨지지는 않는다. 다만 OTP 조회가 한 번 더 든다.

## 지도
- 지도 스냅샷(`/api/map/bus-stops`)은 서울 정류장만 있어서 이미 한 점이다. 바뀐 것은 없다.
- 지도 마커를 눌러서 서울 gtfsStopId 로 노선을 조회해도 묶음 전체 노선이 나온다.
- 전국 정류장 지도는 이슈 #326 에서 다룬다.
