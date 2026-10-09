# 역·정류장 응답에 GTFS id 싣기 (#298 A안)

## 뭘 하나
앱이 이미 쓰는 근처 정류장·정류장 노선·노선 정류장·근처 지하철역·남은 역(stations-ahead) 응답에 OTP GTFS id(`gtfsStopId`·`routeGtfsId`)를 더한다. 앱은 그 값을 그대로 단독 추적 시작에 넘긴다.

## 왜
시작 API 는 GTFS id 만 받는데 앱 화면은 서울 id 로 만들어져 있어 추적을 시작할 방법이 없다(#298).

## 핵심 결정
- 시작 API 는 그대로, 응답에 칸만 추가 — B안(시작이 앱 id 수용) 기각(사용자 결정)
- 버스는 규칙 변환(`1:BS_TAGO_SEB{id}`·`1:BR_TAGO_SEB{id}`) — 기존 역방향 규칙과 같다
- 지하철은 #283 이 이미 하는 "OTP 패턴 정차 ↔ 역" 대응에서 gtfsId 를 꺼낸다. 근처 역은 캐시만 읽어 응답 지연 0(워밍업 전엔 null)
- 서울만. 경기·지방은 이 API 들에 정류장이 없어 범위 밖
- 공항버스 `_U/_D` 는 시작 쪽 노선 비교가 흡수

## 건드는 곳
OtpGtfsIds(신규), SubwayGtfsStopIndex(신규), 버스 DTO 3개, NearbySubwayStationResult, SubwayStationsAheadResponse, StationsAheadCalculator, SubwayStationsAheadService, SubwayLinePatternService, OtpSubwayRoutePatternAdapter, RailPattern, TrackPatternSelector, HANDOFF

## 리스크
- 갈래 노선은 역마다 routeGtfsId 가 다를 수 있다 — 앱은 하차역 값을 써야 한다(HANDOFF 에 명시)
- 기동 직후 워밍업 전에는 근처 지하철역 gtfsStopId 가 null

## 검증법
단위·컨트롤러 계약 시험 통과 + 운영 OTP 라이브 프로브에서 stations-ahead 의 id 로 시작 패턴이 실제로 잡힘 + 전체 시험에서 새 실패 0.
