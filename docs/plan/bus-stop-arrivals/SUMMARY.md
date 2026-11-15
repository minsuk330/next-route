# 근처 버스 도착을 한 번에 (#577)

## 뭘 하나
정류장 하나(`/api/bus/stops/{stopId}/arrivals`)와 정류장 여러 곳(`/api/bus/stops/arrivals?stopIds=`, 최대 10)의 노선별 도착을 한 번의 요청으로 주는 두 경로를 연다. 원본 공급자는 정류장마다 한 번만 부른다.

## 왜
지금은 정류장 × 노선마다 따로 불러 근처 화면 한 번에 서울 원본 32~63회가 든다. 정류장 단위 API 로 바꾸면 8회다(강남·마포·노원·잠실 실측).

## 핵심 결정
- 원본은 세 지역 모두 정류장 단위 API(서울 `getStationByUid`, 경기 `getBusArrivalListv2`, TAGO `getSttnAcctoArvlPrearngeInfoList`). 여러 정류장을 한 번에 주는 원본 API 는 없다.
- 9자리 id 는 서울과 경기가 같이 쓴다(`121000009` = 서울 20노선 + 경기 35노선) → 둘 다 부르고 `routeId` 로 합친다. 어느 카탈로그에도 없는 id 는 부르지 않는다.
- 서울은 검색용 어댑터에 넣고 새 몫 `SEARCH_QUOTA_STATION`(5,000), 경기·TAGO 는 정류장 단위 버킷과 차단 이름을 따로 둬서 노선 단위 도착과 안내를 막지 않는다.
- 노선 유형은 원본 코드 + 출처 + 이름. 정류장 캐시 30초(빈 결과도 저장, 실패는 안 함). 버스 없는 행의 남은 시간은 null.
- 뺀 것(사용자 결정): 공개 근처 정류장 확장, 좌표 경로, 표시색 — 앱이 추적 API 로 이미 받거나 직접 한다.

## 건드는 곳
BusStopArrivalController(새), GetBusStopArrivalsUseCase(새), BusStopArrivalQueryService(새), BusStopArrivalMappers(새), BusRouteTypeNames(새), SeoulArrivalRules(새), 응답 DTO 2개(새), GyeonggiStopArrivalAdapter(새), TagoStopArrivalAdapter(새), JdbcBusStopCatalog(새), RedisBusStopArrivalCache(새), SearchTimeBusAdapter, BusArrivalQueryService, BusApiQuotaPort/Properties, RedisBusApiQuota, RegionalBusSharedProperties, application.yaml, RealtimeArrivalLiveIT

## 리스크
- 서울 `stationinfo`, 경기 `getBusArrivalListv2` 의 우리 키 일일 한도를 아직 모른다 → 구현 전 0단계로 확인. 넘으면 그 몫만 자정까지 막힌다(노선 단위 도착과 안내는 영향 없음).
- 서울 몫 5,000 은 근처 화면 약 10 사용자·시간이다. 사용자가 늘면 공급자 한도 상향이 먼저다.

## 검증법
`./gradlew test` 전체와 계약 테스트(칸, 정렬, 운행종료, 두 공급자 합치기, 모르는 id, 묶음 순서·status, 기한 부분 응답, 한도·차단 분리, 캐시). 배포 뒤 실데이터 IT 로 서울·경기·TAGO·두 공급자 정류장(`121000009`, `124000360`)에서 원본과 맞는지 확인.
