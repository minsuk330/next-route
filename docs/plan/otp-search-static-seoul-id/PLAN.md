# OTP 검색 응답 정적화 + 서울 id 채우기 (national-otp correction)

브랜치: `fix/otp-search-static-seoul-id` (fresh main, parent 4e0898a = #187 M1 포함)
티어: M. 배경 = 이번 세션이 #1 초기검색에 실시간·composer·재정렬을 박은 게 과했음(사용자 지시 2026-09-09).
원칙: [[feedback-server-static-search-front-realtime]] — #1 응답은 정적 ODsay 드롭인만. 실시간은 프론트가 별도 API. 검색응답이 할 일 = 프론트가 실시간 호출하게 **서울 provider id 채우기**.

## 확정 계약 (구현 근거)

기존 프론트 실시간 호출 규약:
- **버스 = id 기반**: `GET /api/arrivals/bus/{stopId}/{routeId}` (BusArrivalController:23). stopId=bus_stop.stop_id, routeId=bus_route.route_id.
- **지하철 = 좌표 기반**: `GET /api/arrivals/subway?lat&lon&line_id(optional)` (SubwayArrivalController:24 → SubwayArrivalQueryService.resolveStation:64 = findNearby 50→300m + lineId 필터). 서버가 좌표로 역 확정. → **지하철 leg 은 legacy 에 id 불필요**, 매퍼가 이미 채우는 좌표로 충분.

resolver 산출(OtpSeoulIdentityResolver): 버스=SeoulBusIdentity{stopId, routeId, routeName}, 지하철=SeoulSubwayIdentity{stationId, lineId, dist}.

PR3 필드 규칙(project-pr3-odsay-resolver): `startLocalStationID`→stop_id, `busLocalBlID`→route_id.

## 변경 파일

### ① `application/route/service/RouteSearchService.java` (runOtpSearch:160-192)
- 삭제: `reorderByEffectiveTotal(...)` 호출 + 감싼 BUS_REALTIME 계측 블록(현 183-189).
- 대체: 성공 outcome 을 바로 `return legacyRouteResponseMapper.toLegacy((RoutingResult) outcome);`
- 유지: port 호출 감싼 ODSAY stage 계측(174-181), handleOtpFailure 분기.
- 필드 제거: `RouteTimelineService routeTimelineService` (runOtpSearch 가 유일 사용처 확인됨) + 생성자 인자. `RouteTimelineService` 클래스/빈은 **존치**(#2 P2-F 용). import 정리.

### ② `application/route/service/LegacyRouteResponseMapper.java`
- 생성자에 `OtpSeoulIdentityResolver resolver` 주입(@Component). PAYMENT_UNKNOWN 등 기존 유지.
- `toSubPath(RoutingLeg leg)` 분기 추가:
  - **BUS**: `resolver.resolveBus(boarding.sourceStopId(), leg.sourceRouteId(), leg.sourceRouteName())`
    - 성공 → `startLocalStationID = id.stopId()`, LaneResult 생성 = `new LaneResult(routeName, routeName, null, null, null, id.routeId())` → `lanes = List.of(lane)`.
    - 실패(미매핑/스코프밖) → id null, 단 LaneResult 는 `busNo=leg.sourceRouteName()` 로 표시용 생성(busLocalBlID=null). 실시간 불가·표시 유지.
    - 하차: `resolver.resolveBus(lastStop.sourceStopId(), leg.sourceRouteId(), leg.sourceRouteName())` 성공 시 `endLocalStationID = id.stopId()`, 아니면 null.
    - `startArsID`/`endArsID`/`endID` = null 유지(원천 ARS 없음, 가짜값 금지).
  - **SUBWAY/RAIL**: 변경 없음(좌표 기반). startX/Y, StationResult x/y 이미 채워짐.
  - **WALK/UNSUPPORTED**: 변경 없음.
- toSubPath 의 SubPathResult 생성에서 lanes 위치를 null→계산된 `lanes` 로 교체(버스만 non-null, 그 외 null).

### ③ `infrastructure/adapter/out/api/otp/OtpRoutingAdapter.java`
- 필드 `coverageResolver` + 생성자 인자 + import 제거.
- search():108-112 `if (outcome instanceof RoutingResult result) return coverageResolver.applyCoverage(result);` → `return outcome;` (실패/성공 그대로).
- `OtpLegCoverageResolver`/`RouteTimelineService`/composer/timing/budget = **폐기 아님**, #2 소비지점으로 이동은 P2-F(PR4)에서. 지금은 #1 배선만 해제.

## 작업 순서
- [ ] ③ OtpRoutingAdapter coverage 배선 해제 (컴파일 격리 쉬움)
- [ ] ① runOtpSearch reorder 제거 + RouteTimelineService 필드 제거
- [ ] ② LegacyRouteResponseMapper resolver 주입 + 버스 id 채우기
- [ ] 테스트 갱신·추가 (아래)
- [ ] 컴파일 + 관련 테스트 그린 확인 → implementation_note `### 검증` 기록
- [ ] PLAN/SUMMARY 커밋 포함 후 PR

## 테스트
- **RouteResponseMapperTest** (신규 계약 ②): mapper 를 mock 화된 BusStop/BusRoute/Subway repo 로 만든 resolver 로 생성.
  - 버스 leg 매핑성공 → `startLocalStationID`==stop_id, `lanes[0].busLocalBlID`==route_id, `endLocalStationID`==하차 stop_id.
  - 버스 미매핑(repo empty) → id null, lanes[0].busNo==routeName(표시 유지).
  - 지하철 leg → startLocalStationID/startId null, startX/Y·stations 좌표 present.
- **RouteSearchServiceTest**:42-52 — RouteTimelineService 인자 제거, `new LegacyRouteResponseMapper(resolver)` 로 갱신. 8 ODSAY 회귀 유지.
- **RouteSearchServiceOtpWiringTest**:64 — RouteTimelineService 제거, mapper 에 resolver. 단언 = 엔진 순서 그대로 legacy 로(재정렬 없음).
- **OtpRoutingAdapterHttpTest / OtpRoutingAdapterBudgetTest** — 생성자에서 coverageResolver 인자 제거. 성공 결과 leg.enrichment 가 coverage 미설정(null/NONE)임을 확인(더 이상 REALTIME_CAPABLE 부착 안 함).
- **OtpLegCoverageResolverTest** — 변경 없음(클래스 존치).

## 검증 명령
```
./gradlew compileJava compileTestJava
./gradlew test --tests "*RouteResponseMapperTest" --tests "*RouteSearchServiceTest" \
  --tests "*RouteSearchServiceOtpWiringTest" --tests "*OtpRoutingAdapter*Test" \
  --tests "*OtpLegCoverageResolverTest"
```

## 마이그레이션
없음.

## 리스크
- 프로덕션 ODSAY 경로 무변경(OTP branch 는 routing.provider.otp-enabled 기본 off). 회귀면은 ODSAY 8테스트 + OtpWiring.
- resolver DB 조회가 매퍼(응답 경로)로 들어옴 = 버스 leg 당 2회(boarding/alighting) 존재검증 쿼리. OTP off 라 프로덕션 영향 0, 켤 때 leg 수만큼. m4 이중 resolve 는 #2 에서 정리.
