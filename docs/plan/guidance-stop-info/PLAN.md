# 안내 스냅샷에 정류장 번호·지하철 출구·방면·빠른 하차 (#266) — PLAN

티어 **L** (스키마 변경 2건·정적 데이터셋 2종 신설·PR 4개). 내리는 문 방향은 데이터 확보 대기로 #335 에 분리.

## 0. 결론

여정 단계(`journey_leg`)에 **계획 시점에 정해지는 값**을 null 허용 컬럼으로 붙이고, 스냅샷(`JourneyLegView`)으로 그대로 내보낸다. 값은 여정을 만들 때(시작·재탐색 공통 경로 `JourneyCompiler` → `JourneyLegDraft` → `JourneyTransitIdEnricher`) 한 번 채운다 — 요청마다 다시 찾지 않는다.

| PR | 내용 | 원천 | 스키마 | 배포 |
|---|---|---|---|---|
| PR-1 | `journey_leg` 컬럼 신설 + 서울 버스 정류장 번호 + 지하철 방면 | `bus_stop.ars_id`(99%), 승차 구간 정차 목록 두 번째 역 | **V63** | api+worker, V63 수동배치 |
| PR-2 | 경기·지방 버스 정류장 번호 | GBIS `mobileNo`(92%), TAGO `nodeno`(22/25 도시) → `regional_bus_route_stop.stop_no` | **V64** | worker(재적재)+api, V64 수동배치 |
| PR-3 | 지하철 출구 번호 | OSM `railway=subway_entrance` `ref`(전국 4,500개 99%) → 리소스 CSV, 메모리 색인 | 없음 | api+worker |
| PR-4 | 빠른 하차 칸·문 | 서울교통공사 `B553766/inout/getFstExit`(2,358행, 1~8호선) → 리소스 CSV, 메모리 색인 | 없음 | api+worker |

순서: PR-1 먼저(컬럼·DTO 계약). PR-2·3·4 는 PR-1 머지 후 서로 독립(fresh main 각자 브랜치, 스택 금지).

## 1. 확정 결정

1. **저장 시점 = 여정 생성 시**. 시작(`JourneyStartTxService`)과 재탐색(`JourneyRevisionComposer`)이 둘 다 draft 를 거치므로 draft 에 필드를 더하면 두 경로가 함께 채워진다. 요청마다 계산하지 않는 이유: 출구 선택은 다음 도보 목적지가 필요한데 그건 계획 시점에만 확실하다.
2. **컬럼은 PR-1 에서 한 번에**(V63) — 나머지 PR 은 값만 채운다. 마이그레이션 수동배치를 한 번으로 줄인다.
3. **출구·빠른 하차는 DB 표가 아니라 리소스 CSV + 메모리 색인**. 각 4,500·2,358행으로 작고, 원천 갱신이 드물다(빠른 하차 기준일 2024-12-31). 갱신은 `tools/` 스크립트로 CSV 재생성 → 커밋. DB 표·적재 잡을 새로 만드는 비용 대비 이득 없음.
4. **OTP 그래프의 출입구 기능은 쓰지 않는다**. 운영 포크(8dbad55)는 `step.feature{Entrance}`를 지원하지만 실측(선릉→역삼) 도보 단계에 출입구가 안 나온다 — 정류장이 역 중심→최근접 도로로 붙어 출입구 노드를 안 지나는 것으로 추정. 그래프 재빌드 없이 자체 선택한다.
5. 값이 없으면 null — 앱이 줄을 숨긴다(#266 앱 쪽 합의). 추정값을 지어내지 않는다.

## 2. 현재 코드 좌표 (origin/main, V62 까지)

- 컴파일 `application/journey/service/JourneyCompiler.java` — 걷기 `emitWalk` :119, 교통 `emitTransit` :178(대기 :188, 탑승 :208 `new JourneyLegDraft`), `Compiled.subPathIndexByLegIndex`(단계 → 원본 구간)
- draft `application/journey/dto/JourneyLegDraft.java` (필드 :23~41, `withResolvedStops` :53)
- ID 해석 `application/journey/service/JourneyTransitIdEnricher.java` (:59·:78·:86 에서 `withResolvedStops`)
- 저장 `JourneyStartTxService.java:135-151` (draft → `JourneyLeg.builder()`), 재탐색 `JourneyRevisionComposer`
- 엔티티 `domain/journey/entity/JourneyLeg.java` (journey_leg, V35 생성, V61 vehicle 추가)
- 응답 `application/journey/dto/JourneyLegView.java:14`, 조립 `JourneyQueryService.java:110`
- 기하 `JourneyNavigationGeometry.StopGeometry(seq, stopId, stopName, x, y)` :164, 조립 `JourneyNavigationGeometryService`
- 검색 구간 `application/route/dto/SubPathResult` — `stations: List<StationResult(index, stationID, stationName, x, y)>`, `startArsID/endArsID/startExitNo/endExitNo` 는 OTP 에서 늘 null(`LegacyRouteResponseMapper.java:165`)
- OTP 정류장 → 원천 ID `application/route/mapping/OtpSeoulIdentityResolver.java` (`resolveBus` :83 SEB, `resolveGyeonggiBus` :99 GGB, `resolveTagoBus` :112)
- 서울 정류장 `domain/bus/entity/BusStop.java:22-26` (stopId, arsId)
- 지역 정류장 표 `regional_bus_route_stop`(V56), 파서 `RegionalBusRouteSourceAdapter.toGyeonggiStop`/TAGO, 저장 `JdbcRegionalBusRouteStopStore.replaceRoute` :35-53, 적재 `RegionalBusCatalogScheduler`(worker, 월 1회 + 표 비면 부팅 직후)

## 3. PR-1 — 컬럼 + 서울 정류장 번호 + 방면

브랜치 `feat/guidance-stop-info`. **이 PR 에 `docs/plan/guidance-stop-info/{PLAN,SUMMARY}.md` 포함.**

### V63 `V63__journey_leg_guidance_info.sql`
```sql
ALTER TABLE journey_leg
    ADD COLUMN start_stop_no  varchar(16),   -- 버스: 승차(대기·탑승 단계) 정류장 번호
    ADD COLUMN end_stop_no    varchar(16),   -- 버스: 하차 정류장 번호
    ADD COLUMN direction_name varchar(64),   -- 지하철 탑승: 다음 역 이름("충무로" → 앱이 "충무로 방면")
    ADD COLUMN start_exit_no  varchar(8),    -- 지하철 대기·탑승: 들어가는 출구(PR-3)
    ADD COLUMN end_exit_no    varchar(8),    -- 지하철 탑승: 나가는 출구(PR-3)
    ADD COLUMN fast_alight    jsonb;         -- 지하철 탑승: [{carDoor:"2-3", facility:"STAIRS"}] (PR-4)
```
모두 null 허용, 기본값 없음 → 기존 행·구버전 앱 무해.

### 계약 (`JourneyLegView` 추가, 전부 nullable)
```java
String startStopNo, String endStopNo,      // WAIT_BUS 는 둘 다 승차 정류장
String directionName,                       // RIDE_SUBWAY·WAIT_SUBWAY
String startExitNo, String endExitNo,       // PR-3 전까지 null
List<FastAlight> fastAlight                 // PR-4 전까지 null. record FastAlight(String carDoor, String facility)
```
`StopGeometry` 에 `String stopNo` 추가(버스 정류장 목록 펼치기용, 서울만 PR-1).

### 채우는 곳
- `JourneyLegDraft` 에 위 6필드 + `withGuidance(...)` 복사 메서드.
- 신규 `JourneyGuidanceInfoEnricher`(application/journey/service): `JourneyTransitIdEnricher` 다음 단계로 draft 목록 전체를 받아 채운다(출구는 앞뒤 걷기 단계가 필요해 목록 단위).
  - 버스 정류장 번호: draft 의 원천 정류장 ID(`sourceStartStopId`/`sourceEndStopId`, OTP `…SEB104000136`) → `OtpSeoulIdentityResolver` 의 서울 접두사 규칙으로 노드 ID → `BusStopRepository` 일괄 조회(`findAllByStopIdIn`)로 `arsId`. 서울 외는 PR-2.
  - 방면: 지하철 탑승 구간의 `SubPathResult.stations` 두 번째 역 이름(첫 번째 = 승차역). 대기 단계에도 같은 값. 역 이름 정규화는 `SubwayPatternStationMatcher.displayName`(괄호 노선명 제거).
- `JourneyStartTxService`·`JourneyRevisionComposer` 의 `JourneyLeg.builder()` 에 6필드, `JourneyLeg` 엔티티 컬럼(`fast_alight` 는 `@JdbcTypeCode(SqlTypes.JSON)` 로 `List<FastAlight>`).
- `JourneyQueryService.java:110` 에서 뷰로 복사. 지오메트리 `StopGeometry.stopNo` 는 같은 일괄 조회.

### 작업
- [ ] V63 + 엔티티 + draft + 뷰 + 저장 두 곳
- [ ] `JourneyGuidanceInfoEnricher` + 테스트(서울 버스 번호, 서울 외 null, 지하철 방면, 정차 1개뿐이면 null)
- [ ] 시작·재탐색 경로 각각 통합 테스트 1개(값이 저장되는지) — `JourneyStartTxServiceTest`/`JourneyRevisionComposerTest` 패턴
- [ ] 스냅샷 컨트롤러 JSON 필드 이름 + null 직렬화 테스트
- [ ] 노트 `### 배포`: **V63 VPS 수동배치 필요**

검증: `./gradlew test --tests '*Journey*Test' --tests '*GuidanceInfo*'` + 전체 `*Test`.

## 4. PR-2 — 경기·지방 정류장 번호

브랜치 `feat/guidance-regional-stop-no` (PR-1 머지 후).
- V64 `ALTER TABLE regional_bus_route_stop ADD COLUMN stop_no varchar(16);`
- `RegionalRouteStop` 에 `stopNo`. 경기 `mobileNo` trim(빈 값 null), TAGO `nodeno` **문자열 그대로**(JSON 숫자면 `asText`, 앞자리 0 유실 확인 — 유실되면 `_type=xml` 로 받거나 0 패딩 규칙 실측 후 결정).
- `JdbcRegionalBusRouteStopStore` INSERT 에 `stop_no`.
- 적재: V64 배포 후 worker 적재 1회 수동 트리거(기존 "표 비면 부팅 직후" 조건으로는 안 돈다 → 운영 절차에 명시, 또는 `stop_no` 전부 null 이면 1회 돌리는 조건 추가).
- 조회: `JourneyGuidanceInfoEnricher` 에 경기(`GGB` → GBIS stationId) · TAGO(`nodeid`) 분기 → `regional_bus_route_stop` (provider, stop_id) 로 `stop_no` 한 건(같은 정류장이 노선마다 여러 행 — 값이 같다고 보고 첫 값, 다르면 null).
- 실측 확인: 군 단위 TAGO 7자리 값(`TSB…` `3370262`)이 표지 번호인지 — 아니면 그 도시 묶음은 null 처리.
- **V64 VPS 수동배치 필요.**

## 5. PR-3 — 지하철 출구

브랜치 `feat/guidance-subway-exit` (PR-1 머지 후).
- 도구 `tools/subway-entrances/build_entrances.sh`: `osmium tags-filter <south-korea-*.osm.pbf> n/railway=subway_entrance` → `osmium export geojsonseq` → Python 으로 `src/main/resources/subway/entrances.csv`(`osm_id,lat,lon,ref,name`) 생성. 입력 PBF 경로·날짜·sha 를 CSV 머리 주석에.
- `SubwayEntranceIndex`(application/subway/service): 부팅 시 CSV 로드, 격자 색인. `nearestExit(stationLat, stationLon, targetLat, targetLon)` = 역 좌표 350m 안 출입구 중 target 에 가장 가까운 것의 `ref`.
- 채우기(`JourneyGuidanceInfoEnricher`):
  - `end_exit_no`(지하철 탑승): 하차역 좌표 + **바로 다음 걷기 단계의 끝 좌표**. 다음이 환승(같은 역 다른 노선)이면 null.
  - `start_exit_no`(지하철 대기·탑승): 승차역 좌표 + **바로 앞 걷기 단계의 시작 좌표**. 앞이 환승이면 null.
- 역 좌표는 draft 의 `startX/Y`·`endX/Y`(OTP 정류장 좌표).
- OSM 출처 표기(ODbL): 앱 설정 화면 "지도 데이터 © OpenStreetMap" 문구 — HANDOFF 에 요청.
- 테스트: 강남역 출구 CSV 일부를 픽스처로, 목적지 방향에 따라 다른 출구 선택·350m 밖 무시·환승 null.
- 실측: 역 마스터 696역 중 692역이 350m 안 출구 보유(연천·광명·도농·인천공항2터미널 없음 → null).

## 6. PR-4 — 빠른 하차

브랜치 `feat/guidance-fast-alight` (PR-1 머지 후).
- 도구 `tools/subway-fast-alight/fetch.sh`: `getFstExit` 전 페이지(100행 × 24) → `src/main/resources/subway/fast-alight.csv`(`line_nm,stn_nm,updn,next_stn,car_door,facility`). 키는 VPS env `TAGO_BUS_API`(data.go.kr 공용 키, 활용신청 완료) — 출력·커밋 금지. `stinNm` 필터는 무시되므로 전체를 받는다.
- `SubwayFastAlightIndex`: (호선 이름, 역 이름, 다음 역 이름) → 목록. **방향은 `drtnInfo`(다음 역)로 맞춘다** — 2호선도 원천이 상행/하행이라 내선/외선으로 매칭 불가.
- 채우기: 지하철 탑승 구간의 하차역 + 하차역 다음 역(= 하차역에서 계속 갔을 때의 다음 역, `SubwayLineOrder.nextOf`) — **원천의 "하차역 기준 진행 방향 다음 역"이 이 값이다**. 시설 순서 계단 → 에스컬레이터 → 엘리베이터, 최대 3개.
- 범위: 서울교통공사 1~8호선만(276역). 그 밖은 null.
- facility 값: `STAIRS` | `ESCALATOR` | `ELEVATOR`.

## 7. 배포
- PR-1: **V63 수동배치** → api·worker. PR-2: **V64 수동배치** → worker(재적재 1회) → api. PR-3·4: 리소스만, api·worker.
- 기존 진행 중 여정은 컬럼이 null — 새 여정부터 채워진다.

## 8. 리뷰 포인트
1. 저장 시점(생성 시) vs 조회 시 계산 — 재탐색 경로 누락 위험.
2. 출구 선택 규칙(350m·다음 걷기 끝 좌표)이 환승·출구 없는 역에서 틀린 값을 내지 않는지.
3. 빠른 하차 방향 매칭에 `nextOf` 를 쓰는 근거(원천 `drtnInfo` 의미) — 표본 대조 필요.
4. TAGO `nodeno` 형식(앞자리 0·7자리).
5. 리소스 CSV 갱신 절차(누가 언제) — 빠른 하차 원천 갱신 주기 미상.

## 9. Opus 리뷰 반영 (2026-10-10, "보류 → 수정 후 진행")

| # | 지적 | 반영 (위 절보다 이 표가 우선) |
|---|---|---|
| 🔴1 | 재탐색 저장은 `JourneyRevisionComposer` 가 아니라 `RerouteAcceptTxService:378`. 앞 구간 복제 `JourneyLeg.copyToRevision`(:179-212)·자르기 `copyToRevisionTruncated`(:227)+`Truncation`(:252) 누락 → 수락 순간 null | 저장 지점 = `JourneyStartTxService:153` + `RerouteAcceptTxService:378`. `copyToRevision` 은 6필드 복사, `Truncation` 에 end 쪽 3필드(새 draft 값) 추가. 수락 경로 테스트 신설 |
| 🔴2 | enricher 시점 draft·plan 에 SubPathResult 없음 | **Compiler `emitTransit` 이 draft 에 직접 담는다**: `directionName` = stations[1] 이름. 호출 3곳(start·revision·**track #265**) 무수정. PR-4 용 하차 직전 역 이름도 같은 자리에서 |
| 🟡3 | 버스 번호는 접두사 규칙 재구현 금지 | 서울 = enricher 가 채운 `startStopId/endStopId`(bus_stop 존재 확인된 값) → `BusStopRepository.findByStopIdIn`(:21). PR-2 는 `sourceStartStopId`(두 모양 혼재: 떼어진 값 / `1:BS_TAGO_…` 폴백) 정규화 필요 |
| 🔵4 | stopCalls 비면 from/to 2점 폴백 → stations[1] = 하차역 | stations.size()==2 면 direction null(1정거장 구간도 null — 앱은 방면 줄 숨김) |
| 🟡5 | 빠른 하차 `nextOf` 의존 약함(캐시·ID·갈래) | 원천 두 방향 행 중 **`drtnInfo` ≠ 하차 직전 역** 인 행. 종착역은 행 1개면 그것. 호선 `(?<!\d)([1-9])호선`, 역 이름 `displayName` 정규화, 이름 차이 픽스처 |
| 🟡6 | 코드베이스에 `@JdbcTypeCode` 없음 | `fast_alight` = `String` + `@Column(columnDefinition="jsonb") @ColumnTransformer(write="?::jsonb")`(TmapWalkCacheEntity 패턴), ObjectMapper 직렬화 |
| 🟡7 | 출구: 같은 정류장 환승은 걷기 단계 없음, 직선 최근접 오차, 큰 환승역 타 역 출구 혼입 | 다음/앞 **단계 type** 으로: RIDE_SUBWAY 다음이 WAIT_SUBWAY 거나 TRANSFER_WALK→WAIT_SUBWAY 면 null. 목표점 = 걷기 polyline 100~150m 지점(폴백 끝 좌표). OSM 출입구 이름/설명에 역명 포함으로 거름 |
| 🔵8 | V62 최신 맞음, 마이그레이션 추적 중(.gitignore allowlist) | 노트 `### 배포` 에 수동배치 표기 유지 + 머지 직전 번호 재확인 |
| 🟡9 | 지오메트리 stopNo: 서울 노선표 경로는 `findStopsByRouteId` projection, 검색 경로는 혼재 id | PR-1: 노선표 projection 에 ars 추가(서울), 검색 경로 null |
| 🟡10 | 위치 인자 record 파급 | 6필드를 `GuidanceInfo` record 하나로(`GuidanceInfo.EMPTY`). 엔티티 복사·`Truncation` 도 같은 묶음 |
| 🔵11 | 지선(성수·신정) 원천 유무 실측 | PR-4 에서 확인, 없으면 null |
| 🔵12 | null 직렬화 | 컨트롤러 테스트로 고정 |
