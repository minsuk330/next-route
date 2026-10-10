# 안내 스냅샷 정류장 번호·출구·방면·빠른 하차 (#266)

## 뭘 하나
여정 단계마다 버스 정류장 번호(서울·경기·지방), 지하철 방면(다음 역), 들어가고 나가는 출구 번호, 빠른 하차 칸·문을 싣는다. 여정을 만들 때 한 번 채워 `journey_leg` 에 저장하고, 스냅샷으로 그대로 내보낸다. 내리는 문 방향은 #335 로 분리했다.

## 왜
OTP 로 바꾼 뒤 검색 결과의 정류장 번호·출구 값이 늘 비어 있다. 그래서 앱 안내 시트의 해당 줄이 자리만 있고 비어 있다.

## 핵심 결정
- 저장은 여정 생성 시 1회. 방면은 Compiler 가 draft 에 직접 담아 시작·재탐색·단독 추적 세 경로가 함께 채워진다. 재탐색 수락 시 앞 구간 복제·자르기 경로도 값을 넘긴다. 요청마다 다시 계산하는 방식은 기각
- 컬럼 6개는 PR-1 에서 한 번에 추가함(V63). 나머지 PR 은 값만 채운다
- 출구(OSM)와 빠른 하차(서울교통공사)는 DB 표 대신 리소스 CSV + 메모리 색인. 각각 수천 행이고 원천 갱신이 드물다
- OTP 의 출입구 기능은 쓰지 않음. 실측에서 운영 그래프 경로에 출구가 나오지 않았다
- 값이 없으면 null 이고 앱이 줄을 숨긴다. 추정값은 만들지 않는다

## 건드는 곳
V63·V64 마이그레이션, JourneyLeg, JourneyLegDraft, JourneyLegView, JourneyQueryService, JourneyStartTxService, RerouteAcceptTxService, JourneyCompiler, GuidanceInfo(신규), JourneyGuidanceInfoEnricher(신규), JourneyNavigationGeometry, RegionalRouteStop, RegionalBusRouteSourceAdapter, JdbcRegionalBusRouteStopStore, SubwayEntranceIndex(신규), SubwayFastAlightIndex(신규), tools/subway-entrances, tools/subway-fast-alight

## 리스크
- 마이그레이션 2건(V63·V64)은 VPS 에 수동배치해야 한다. 경기·지방 번호는 배포 후 정류장 표 재적재가 1회 필요하다
- 출구 선택은 "하차역 350m 안 출구 중 다음 도보 목적지에 가장 가까운 것"이라, 실제 안내와 다를 수 있다
- 지방 TAGO 번호 형식이 문제일 수 있다(앞자리 0 유실, 군 단위 7자리 값)

## 검증법
PR 마다 필드 null 조건 계약 테스트와 시작·재탐색 경로 저장 테스트를 통과해야 한다. 배포 후 서울 버스·경기 버스·지하철(강남 하차) 여정 1건씩 스냅샷에서 번호·방면·출구·빠른 하차를 확인한다.
