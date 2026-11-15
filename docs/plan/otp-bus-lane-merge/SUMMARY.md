# OTP 버스 합치기 (A+B)

## 뭘 하나
같은 정류장에서 타서 같은 정류장에 내리는 버스들을 한 경로로 묶고, 그 구간 lane 에 노선 여러 개를 싣는다.
OTP 후보끼리 묶기(A)에 더해 OTP `nextLegs` 로 대체 노선을 채운다(B).

## 왜
네이버, 카카오는 묶어서 보여주는데 우리는 노선마다 따로 보여 후보 칸을 낭비한다.

## 핵심 결정
- 버스 지문 = 승하차 정류장 id. 정류장만 다른 같은 노선은 비용 낮은 쪽 하나만(반복으로 보임).
- 대표 선택 규칙 유지(이른 도착 우선). 비용 우선은 늦은 출발편을 뽑아 기각.
- lane 상한 6. 서울 실시간 조회가 lane 마다 순차라 응답 시간 보호.
- `(예약)` 노선, 탑승시간 1.3배+5분 초과 노선은 뺌(실측 노이즈).
- 응답 구조 그대로(`lanes[]` 배열). 재탐색 질의엔 nextLegs 안 실음.

## 건드는 곳
planConnection.graphql, RoutingLeg, AlternativeRoute(신규), RoutingQuery, OtpRoutingProperties, OtpRoutingAdapter, OtpPlanConnectionMapper, ItineraryRanker, BusLaneMerger(신규), RouteSearchService, LegacyRouteResponseMapper

## 리스크
- OTP 응답 +0.1~0.2초(검색 질의마다).
- lane 이 늘어 여정 routeOption, 알림 소요 추정이 대체 노선을 쓰기 시작함(ODsay 시절 동작).
- 모바일 카드는 첫 노선 번호만 보여서, 묶음 표시는 앱 후속 작업 필요.

## 검증법
관련 단위 테스트 + 전체 단위 통과. 운영 OTP 로 서울역→잠실 검색 시 5번승강장 구간 lane 6개, `(예약)` 없음.
