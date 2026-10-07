# 경기·TAGO 노선 정류장 표 + TAGO 노선 단위 도시코드 (#267)

## 뭘 하나
경기·지방 버스 노선별 정류장 순서를 실시간 API 순번 그대로 담는 표(`regional_bus_route_stop`)와 월 1회 적재 배치를 만든다. TAGO 도시코드를 접두사 표(38개) 대신 이 표의 "노선 → 도시코드"로 찾게 바꾼다.

## 왜
접두사 하나가 여러 도시에 걸쳐(TSB 49개 시군, ADB·YDB 포함) 지방 실시간 커버율이 41%에 묶여 있고, #264 경기·지방 재탐색은 실시간 순번과 맞는 정류장 표가 있어야 한다.

## 핵심 결정
- 원천: TAGO 노선 목록(138개 도시 → 21,393노선, 도시 간 중복 0) + 노선별 경유정류소. 경기(GGB)만 경기 노선 API — 경기 실시간과 같은 순번 체계.
- 저장: 노선 단위로 통째 교체(한 트랜잭션). 중간에 죽어도 다른 노선은 옛 값. 엔티티 없이 JdbcTemplate.
- 조회: 표 우선, 없으면 기존 접두사 표로 폴백 — 표가 비어도 지금과 같은 동작.
- 갱신: 월 1회(매월 1일 03:30) + 표가 비어 있을 때만 부팅 직후 1회. 플래그 기본 꺼짐.
- 폐선 정리: 노선 목록에 더는 없는 노선만 삭제. 이번에 0건·실패였던 노선은 옛 값 유지.

## 건드는 곳
V56 마이그레이션, application/regionalbus(포트·적재 서비스), TagoCityCodeLookup, RegionalBusRouteSourceAdapter, JdbcRegionalBusRouteStopStore, RegionalBusCatalogScheduler, BusArrivalQueryService, OtpSeoulIdentityResolver, BusRealtimeStopIds(→빈), JourneyNavigationGeometryService, application.yaml

## 리스크
- 적재 약 2.15만 호출(병렬 3, 약 2시간)이 같은 포털 키의 동시 세션 30을 사용 중인 도착 조회와 나눠 씀 → 새벽 실행 + 병렬 3.
- V56 VPS 수동 배치 누락 시 적재가 실패(조회는 폴백이라 서비스 영향 없음).

## 검증법
파싱·적재·조회 단위 시험 + 표 IT 통과, `./gradlew build` 통과(기존 무관 실패 라벨). 배포 후 TSB·ADB 두 번째 도시 정류장 도착 실호출 성공.
