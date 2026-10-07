# HANDOFF — 모바일: 홈 근처 목록을 track/stops/near 로 (#313)

## 응답에 생긴 필드
`GET /api/journeys/track/stops/near?lat&lon[&mode=BUS|SUBWAY]` 의 각 정류장:

| 필드 | 뜻 | 앱이 부를 API |
|---|---|---|
| `distanceMeters` | 좌표에서 거리 | 정렬·표시 |
| `arrivalStopId` (버스) | 서울 9자리 / 경기 GBIS 9자리 / 그 밖 TAGO(`ICB…`, `BSB…`) | `GET /api/arrivals/bus/{arrivalStopId}/{arrivalRouteId}` |
| `arsId` (버스) | 서울 정류장 번호. 서울 밖 null | 표시 |
| `arrivalStationId` (지하철) | 서울 실시간 역 id(`1002000222`) | `GET /api/subway/stations/{id}/arrivals`, `.../stations-ahead` |
| `lineId`, `lineName` (지하철) | `1002`, `2호선` | 표시·노선 색 |

`GET /api/journeys/track/stops/{gtfsStopId}/patterns` 의 각 방향에 `arrivalRouteId`(버스 노선 실시간 id)가 생겼다. 지하철은 null 이다.

## null 규칙
- 못 푼 id 는 null 이다. 가짜 값을 주지 않는다. null 이면 도착 정보 없이 노선 선택·추적 시작만 보여 준다.
- 지하철: 역 마스터에 없는 노선(지방 도시철도·인천2호선 등)은 `arrivalStationId·lineId·lineName` 이 모두 null 이다. 호선은 아는데 역이 없으면(4호선 진접 연장 등) `arrivalStationId` 만 null 이다.
- 한 정류장을 지나는 지하철 호선이 둘 이상이면 호선마다 한 줄이 온다(`gtfsStopId` 는 같다).

## 🔴 옮기기 전에
- `track/*` 는 **로그인 필요**(익명 401)이고, 운영 플래그 `JOURNEY_TRACK_ENABLED` 가 꺼져 있다(404). 홈을 옮기려면 서버 플래그를 켜야 하고, 익명 사용자 홈을 어떻게 할지 정해야 한다.
- 같은 물리 정류장이 서울(SEB)·경기(GGB)·인천(ICB) feed 로 여러 줄 올 수 있다(GTFS 원천 그대로). 이름·좌표가 같은 줄을 합칠지는 앱 표시 결정이다. 도착은 `arrivalStopId` 가 다른 원천으로 각각 된다.
- 근처 조회도 OTP 를 부른다. `/api/nearby/*` 는 DB 만 봤다.
