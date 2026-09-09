# OTP 검색 응답 정적화 + 서울 id 채우기

## 뭘 하나
OTP 초기검색(#1) 응답에서 이번 세션이 박은 실시간 재정렬·coverage 배선을 걷어내 기존 ODsay 와 같은 정적 응답으로 되돌린다. 대신 응답에 서울 버스 식별자(정류장·노선 id)를 채워 프론트가 실시간을 별도 API 로 호출할 수 있게 한다.

## 왜
검색 응답에 서버가 실시간·재정렬을 박으면 "가장 빠른 경로도 그대로 제시" 원칙이 깨진다. 실시간은 프론트 몫. 검색은 정적 드롭인 + id 채우기만.

## 핵심 결정
- 지하철은 id 안 채운다 — 프론트 지하철 실시간이 **좌표 기반**(서버가 findNearby 로 역 확정)이라 매퍼가 이미 넣는 좌표로 충분. (startId 타입불일치 문제 폐기)
- 버스만 id 채운다 — 버스 실시간은 `/{stopId}/{routeId}` **id 기반**. startLocalStationID←stop_id, LaneResult.busLocalBlID←route_id.
- runOtpSearch 에서 reorderByEffectiveTotal 호출 제거, 엔진 순서(빠른순) 그대로 매핑.
- coverage 배선(OtpRoutingAdapter)은 #1 미사용 → 제거. RouteTimelineService/composer/coverage 코드는 폐기 아님, #2(주행중 재탐색 P2-F)용으로 main 존치.
- #187(M1) 은 먼저 머지 완료, 그 위 fresh main 에서 correction.

## 건드는 곳
RouteSearchService · LegacyRouteResponseMapper · OtpRoutingAdapter · (테스트) RouteResponseMapperTest · RouteSearchServiceTest · RouteSearchServiceOtpWiringTest · OtpRoutingAdapter*Test

## 리스크
- 프로덕션 ODSAY 경로 무변경(OTP branch 기본 off). 회귀 방어 = ODSAY 8테스트 + OtpWiring.
- resolver DB 조회가 응답 경로로 들어옴(버스 leg 당 2쿼리). OTP off 라 프로덕션 영향 0.

## 검증법
컴파일 그린 + 버스 id 채움/지하철 id null·좌표 present/미매핑 null 계약테스트 통과 + ODSAY 8 회귀 + coverage 미부착 확인.
