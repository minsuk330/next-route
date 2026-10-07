# 역 ID 로 묻는 지하철 도착 API (#277)

## 뭘 하나
`GET /api/subway/stations/{stationId}/arrivals` 신설. 역 ID 로 바로 도착 열차를 방향별로 묶어 준다.
방향 표시 이름(label)과 방향 순서도 서버가 정해 내려 준다.

## 왜
앱은 역 ID 를 이미 아는데 좌표로 다시 물어서, 환승역에서 다른 노선 열차가 오는 버그(#276)가 났다.

## 핵심 결정
- 열차 소스 = 기존 좌표 API 와 같은 캐시 reader → 키 소비 0, 지나간 열차 규칙 공유
- label = 행선지 최빈값 + " 방면", 두 방향이 같으면 방향 원문(2호선 내선/외선) — 앱 규칙 그대로 이전
- 방향 순서 = 방향 이름 한국어 사전순 → 새로고침해도 탭 자리 고정
- 없는 역 404 `SUBWAY_STATION_NOT_FOUND`, 운행 없음 200 + 빈 목록
- `departed`(방금 떠난 열차)는 제외 → #265

## 건드는 곳
SubwayStationController, SubwayStationArrivalQueryService, GetSubwayStationArrivalsUseCase, SubwayStationArrivalsResponse, SubwayErrorCode, HANDOFF.md(모바일)

## 리스크
- 수집기 캐시가 비면 항상 빈 목록(좌표 API 와 같은 한계)

## 검증법
서비스 테스트(묶기·label·순서·404·빈 목록·stationsAway) + 컨트롤러 테스트(200/404) 통과.
배포 후 강남 2호선(1002000222) 실호출로 내선/외선 2방향 확인.
