# route-option 대안을 OTP pattern 에서 생성 (national-otp P2-F PR4b)

티어: **L** (새 OTP GraphQL 쿼리 + 신규 port/adapter + source 추상화 + provider 재배선 + 폴백 + **동적 후보 수락 계약**).
브랜치: fresh main 기준. 아래 PR 들은 **stacked**(순차 의존) — 독립 아님(codex 지적).

## ⚠ codex 설계 리뷰 반영 (2026-09-09, gpt-5.6-sol) — 이 블록이 아래 원안보다 우선
codex 가 실 코드·GTFS 실데이터로 검토해 원안의 결함 여럿을 잡았다. 아래 결정으로 갱신한다(원안의 충돌 부분은 무효).

- **B1 🔴 수락 계약 결함 (최우선 블로커)**: `RerouteAcceptTxService:293` 은 `RouteOptionApply.routeId/directionKey` 를 안 쓰고 **저장 `journey_leg_route_option` 을 optionIndex 로 선택, 없으면 첫 후보 fallback**. → 저장소에 없던 OTP 후보를 수락하면 **엉뚱한 노선이 선택된다.** 따라서 OTP 후보를 붙이기 **전에** 동적 후보 수락 계약을 먼저 설계해야 한다(제안 저장/수락 시 OTP 후보를 journey_leg_route_option 에 새 행으로 넣고 그 행을 선택, 또는 RouteOptionApply 에 후보 snapshot 을 담기). **동적 후보 수락 IT 필수.**
- **B2 🔴 ETA 가 노선별 실주행시간 미반영**: 목적지 ETA 는 후보 노선 운행시간이 아니라 기존 leg `plannedDurationSec` 을 공통 가산한다(`RouteOptionEtaCalculator:57,104`). OTP 가 저장보다 폭넓은 노선을 추가해도 **노선별 실제 소요 차이를 잃는다.** → **PR4c(RouteTimelineComposer 재평가) 전에는 OTP 후보의 실제 제안 ON 금지** 를 명문화. PR4b 는 후보 집합 확장까지만, 우열 정확도는 PR4c 가 책임.
- **B3 🔴 순수 대체 불가 → Stored ∪ OTP (union)**: resolver 는 9자리 route 만 허용하나 feed 에 방향 suffix `BR_TAGO_SEB100100373_D/_U` 존재(routes.txt). resolveBus + bus_route_stop 매핑까지 요구 → 저장보다 후보 감소 불가피. **초기: OTP 성공결과 ∪ Stored. OTP off/장애/deadline 소진: Stored fallback.** 커버리지 검증 후 union 재검토.
- **B4 boardingSeq 단건 규칙 일치**: 저장은 `resolveSeq()` 가 **정확히 1개**일 때만 boardingSeq 채움(`JourneyTransitIdEnricher:142`). OTP 경로도 `findByRouteIdAndStopId` 다건을 임의선택 말고 **동일 단건 규칙**(distinct seq==1) 적용, 아니면 제외.
- **B5 순환/지선 occurrence**: 01A 순환은 동일정류장이 한 trip 에서 seq 1·25 반복(stop_times). "첫 승차 뒤 하차 존재"는 도달만 증명, **올바른 boardingSeq occurrence 확정 못함** → 반복 정류장은 OTP occurrence↔DB seq 결정규칙 생기기 전 **제외**. directionId(-1)를 directionKey 로 쓰지 말 것 — dedup 키는 `(routeId, boardingSeq)`.
- **B6 승하차 가능성·service 활성 검증**: `Stop.patterns`+`Pattern.stops` 는 topology 만. drop_off_type=1(하차전용)·pickup_type=1(승차금지) 정류장 존재 → 승차 stop pickup 허용·하차 stop dropoff 허용 + 현재 service 활성 검증 필요. `stoptimesForPatterns` 또는 별도 stoptime 검증 병행.
- **B7 source 결과 타입 + deadline**: source 반환을 단순 `List` 아니라 `SourceOutcome{SUCCESS/UNAVAILABLE/TIMEOUT/ERROR, candidates}` 로(정상 0건 vs 장애 구분). `optionsFor(JourneyLeg, RoutingDeadline)` — OTP HTTP+DB매핑+평가를 **한 예산**에. N+1(pattern당 resolveBus 2회+seq 1회) 방지: topology 필터·route dedup 먼저 → route 존재·`(routeId,stopId)→seq` **bulk 조회**. graphVersion 캐시 검토.
- **B8 baseline 제외 키**: OTP 후보에서 baseline 을 ID 아니라 `(routeId, boardingSeq)` 로 제외(안 그러면 OTP 가 현재 노선을 대안으로 재출력).
- **B9 CandidateInput 필드 보강**: `RouteOptionCandidateInput` 에 `optionIndex`(기존 수락 계약 보존), `sourceKind`, `patternCode/semanticHash`, `routeId`, `sourceRouteId`, `routeName`, `boardingSeq`. Stored 구현은 기존 index·정렬 보존(PR-2 무행위변경 성립 조건).
- **B10 전용 게이트**: `journey.reroute.route-option.otp-pattern-enabled`. `RoutingProviderSelector`(경로검색용, "내부 OTP 요청 여부") 재사용 금지 — 의미 다름.

### 갱신된 PR 순서 (stacked)
1. **PR-1**: StopPatternPort + OtpStopPatternAdapter + Mapper + graphql + fixture. `SourceOutcome` 타입 포함. 미배선.
2. **PR-2**: `RerouteRouteOptionSource` 추상화 + `StoredRouteOptionSource`(현행 이관, optionIndex/정렬 보존 → **무행위변경**, 기존 20테스트 그린). deadline 인자.
3. **PR-3**: 🔴 **동적 후보 수락·저장 계약**(B1) + IT. OTP 후보 없이도 이 계약 먼저 성립. **상세 설계 아래**.
4. **PR-4**: `OtpPatternRouteOptionSource`(B3~B8) + Stored∪OTP union + 전용 게이트(B10). OTP on 계약 테스트.
5. **명문화**: PR4c 전 OTP 후보 실제 제안 ON 금지(B2).

### PR-3 상세 설계 (동적 후보 수락 계약)
**문제**: 제안 생성 시 candidate.apply(RouteOptionApply) 가 `RerouteProposal.candidates` jsonb 에 저장 → 수락 시 `saveRouteOptions`(RerouteAcceptTxService:288~) 가 **routeOptionIndex 로 복제된 저장옵션에서 선택, 불일치=첫 후보 fallback**(orElse saved.get(0)). OTP 동적 후보는 저장 journey_leg_route_option 에 없어 → 엉뚱 노선.
**해법(선례 = RemainingPathApply 가 full PathResult 담는 것과 동일 철학)**: RouteOptionApply 를 **self-sufficient snapshot** 으로 확장 + 수락 시 없으면 새 행 insert.
- **RouteOptionApply 필드 추가(전부 nullable, 하위호환)**: 기존 `waitLegIndex, routeOptionIndex, routeId, directionKey` 유지 + `sourceRouteId, routeName, Integer wayCode, Integer boardingSeq, Integer alightingSeq, Integer laneIndex`. 옛 저장 제안은 새 필드 null → optionIndex 경로(현행).
- **saveRouteOptions 선택 로직 교체**: fromLegIndex leg 에서 (RouteOptionApply 일 때)
  1. copied = copyOptions(old leg) (현행 유지).
  2. **copied 에서 `(routeId, directionKey)` 매칭 탐색** → 있으면 그걸 select(index 아닌 identity — 더 견고, codex B8).
  3. 없고 **snapshot 있으면**(boardingSeq/routeName non-null) → `JourneyLegRouteOption.builder()` 로 새 행 insert(journeyLegId=새 leg, optionIndex=copied max+1, laneIndex=snapshot|sentinel, routeId/sourceRouteId/routeName/directionKey/wayCode/boardingSeq/alightingSeq) → saved 에 추가 → select.
  4. 없고 snapshot 없으면(legacy) → 현행 optionIndex 경로.
- **toCandidate**: RouteOptionApply 에 option 의 snapshot 필드 전부 채움(현 stored source 는 저장옵션 값 = copied 와 매칭되어 무해; PR-4 OTP source 는 OTP 유도 snapshot). baseline 제외도 `(routeId, boardingSeq)` 로(B8).
- **마이그레이션**: 없음(jsonb 필드 additive + journey_leg_route_option 기존 테이블 재사용).
- **IT(필수)**: 저장 옵션에 없는 `(routeId, directionKey)` 후보 수락 → 새 journey_leg_route_option 행 insert + selectedRouteOptionId 가 **그 routeId 를 가리킴**(첫 후보 fallback 아님) 검증. + 기존 stored 후보 수락 무회귀.

## 배경/현행 (구현자 컨텍스트)
주행 중 재탐색 ③ "같은 정류장 다른 노선"은 `RouteOptionCandidateProvider` 가 담당한다. 지금은 **journey start 시 저장된** `journey_leg_route_option`(원래 검색이 준 laneIndex 대안, ODsay-shaped)을 읽어 각각 live ETA 를 재고 baseline 대비 빠른 것을 제안한다.
- 저장 시점: `JourneyStartTxService.toEntity` (draft.routeOptions → JourneyLegRouteOption).
- 소비: `RouteOptionCandidateProvider.collect` → `routeOptionRepository.findByJourneyLegIdIn` → `alternatives()` → `evaluate()`.
- **핵심**: `evaluate()` 는 `option.getRouteId()`(서울) + `option.getBoardingSeq()` 만 있으면 `predictTransferUseCase.predict(stopId, routeId, boardingSeq, anchor, mode, budget)` 로 실시간 예측·목적지 ETA 를 낸다. 대안 후보에 필요한 최소 = **routeId + boardingSeq**.

**문제(4b)**: 저장 대안은 ①시작 시점 고정 ②원래 검색이 준 것만 = 같은 정류장의 다른 노선을 다 못 볼 수 있음.

**해결(B안, 사용자 확정)**: 대안을 저장값 대신 **OTP pattern** 에서 생성 — 승차정류장을 지나는 노선 pattern 중 **하차정류장에 실제 정차 + 같은 방향**인 것을 전부. 방향 정합성은 pattern 정차 순서(승차 index < 하차 index)로 확보 → 버스 상/하행 unknown 우회.

## 확정 계약
- **evaluate/baseline 흐름 무변경**: 대안의 **소스만** 교체. baseline 은 저장된 selected option 그대로(사용자가 현재 기다리는 노선).
- **direction (조사로 확정 — A안 강제)**: KTDB GTFS `trips.txt` 에 `direction_id` **컬럼 자체가 없음**(seoul-bbox 154,887 / normalized 74,685 trip 전부, 헤더=route_id,service_id,trip_id). → OTP `pattern.directionId` 는 전 노선 -1(스키마 "direction irrelevant"). **directionId 로는 방향 판정 불가.** 대신 OTP 는 direction_id 없어도 **정차 순서가 다르면 별개 pattern 으로 분리**(왕복=상행 pattern+하행 pattern, stop 순서 반대). → 방향 = **pattern.stops 에서 승차 stop index < 하차 stop index**. wayCode/directionId 불필요·불가.
  - **순환/지선 규칙**: 한 정류장이 pattern 에 여러 번 나오면 **첫 승차 index 뒤에 하차 index 가 하나라도 있으면 OK**, 없으면 그 pattern 제외("모름>틀림").
- **boardingSeq**: OTP pattern 위치 아님 → 서울 `busRouteStopRepository.findByRouteIdAndStopId(routeId, boardingStopId)` 의 seq (BusArrivalQueryService 와 동일 규칙). 매핑 없으면 후보 제외.
- **routeId**: OTP pattern.route.gtfsId → `OtpSeoulIdentityResolver.resolveBus` → bus_route.route_id. 미매핑(스코프 밖)이면 제외.
- **OTP off 폴백**: OTP 비활성이면 기존 StoredRouteOptionSource(저장값) 사용. B 는 OTP on 일 때만.
- **occurrence/승차시간 미사용**: predict() 가 anchor(userReadyAt)로 실시간 재계산하므로 OTP departure time 불필요. pattern 의 route+stops 만 필요.

## 변경 파일

### 신규 — OTP stop-pattern 조회
- `resources/otp/stopPatterns.graphql`: `query($id:String!){ stop(id:$id){ gtfsId patterns{ route{ gtfsId shortName } directionId stops{ gtfsId } } } }` (stable 필드만, deprecated 회피). 필요시 `stoptimesForPatterns` 대신 `patterns` 사용(departure 불요).
- `application/route/routing/StopPatternPort.java`: `List<StopPattern> patternsThroughStop(String stopGtfsId, RoutingDeadline deadline)`. 도메인 `StopPattern(routeGtfsId, routeShortName, Integer directionId, List<String> stopGtfsIds)`.
- `infrastructure/adapter/out/api/otp/OtpStopPatternAdapter.java`: OtpRoutingAdapter 패턴 복제(RestClient.Builder 주입 빌더, per-call timeout=deadline, GRAPHQL_PATH). 순수 JSON→StopPattern 매핑은 `OtpStopPatternMapper`(fixture 고정). 실패는 빈 목록(재탐색 계약: 후보 0 = 조용히 폴백/빈손, 예외로 검색 안 죽임).

### 신규 — 대안 소스 추상화
- `application/journey/service/RerouteRouteOptionSource.java` (interface): `List<RouteOptionCandidateInput> optionsFor(JourneyLeg waitLeg)` 반환. `RouteOptionCandidateInput(routeId, sourceRouteId, routeName, directionKey, boardingSeq)` — evaluate 가 쓰는 최소 필드.
- `StoredRouteOptionSource`: 현행 로직 이관(`routeOptionRepository.findByJourneyLegIdIn` → JourneyLegRouteOption → input). 폴백/기본.
- `OtpPatternRouteOptionSource`:
  1. 승차 gtfsId = `waitLeg.getSourceStartStopId()`, 하차 gtfsId = `waitLeg.getSourceEndStopId()` (leg 에 이미 존재, `1:BS_TAGO_SEB<id>` 재구성 불요).
  2. `stopPatternPort.patternsThroughStop(boardingGtfsId, deadline)`.
  3. 필터: pattern.stopGtfsIds 에서 승차 index < 하차 index (실제 정차 + 방향).
  4. `resolveBus(pattern.routeGtfsId, ...)` → 서울 routeId (미매핑 제외).
  5. `busRouteStopRepository.findByRouteIdAndStopId(routeId, boardingSeoulStopId)` → boardingSeq (없으면 제외). boardingSeoulStopId = `waitLeg.getStartStopId()`.
  6. directionKey = routeId+"|"+directionId (dedup 용). RouteOptionCandidateInput emit.

### 변경 — provider 재배선
- `RouteOptionCandidateProvider`:
  - `alternatives()`/`collect()` 가 `JourneyLegRouteOption` 대신 `RerouteRouteOptionSource.optionsFor(waitLeg)` 결과(RouteOptionCandidateInput)를 순회.
  - **baseline 은 계속 저장 selected** 에서(`waitLeg.getSelectedRouteOptionId()` → JourneyLegRouteOption). baseline 과 대안이 다른 소스여도 evaluate 는 routeId+boardingSeq 동일 계약이라 무방(같은 계산기·상수항 유지).
  - 소스 선택: `RoutingProviderSelector`/otp-enabled → OtpPatternRouteOptionSource, else StoredRouteOptionSource. 주입은 List<Source> + 선택기 or @Primary/Qualifier.
  - dedup(routeId|directionKey)·maxEvaluations·budget·gain 필터 로직 **그대로**.

## 작업 순서 (체크박스)
- [ ] PR-1: StopPatternPort + OtpStopPatternAdapter + OtpStopPatternMapper + stopPatterns.graphql + fixture. **미배선**(회귀0). OTP HTTP 테스트(MockWebServer).
- [ ] PR-2: RerouteRouteOptionSource 추상화 + StoredRouteOptionSource(현행 이관, 동작 동일). provider 를 source 경유로 리팩터(행위 무변경, 저장 소스만). 기존 RouteOptionCandidateProviderTest 그린 유지.
- [ ] PR-3: OtpPatternRouteOptionSource + provider 소스 선택 배선(otp-enabled 게이트). OTP on 계약 테스트(pattern→routeId→boardingSeq→input) + 방향/미매핑/미정차 제외 + OTP off 폴백 테스트.
- [ ] 각 PR fresh main 독립 브랜치([[feedback-per-task-pr]]).

## 검증
- 컴파일 + `RouteOptionCandidateProviderTest`(현행 20) 무회귀(PR-2 후).
- 신규: OtpStopPatternMapper(JSON→StopPattern), OtpPatternRouteOptionSource(정차순서 방향 필터/미매핑 제외/boardingSeq 매핑실패 제외/OTP off 폴백), provider 소스선택.
- 계약당 테스트 최소 1개(경계 리뷰 대상=StopPatternPort DTO, source 선택 게이트).

## 마이그레이션
없음.

## 미결/리스크 (구현 중 확정)
- **boardingSeq 매핑 실패율**: OTP pattern route ↔ 서울 route_id ↔ bus_route_stop seq 3단 매핑. 하나라도 없으면 후보 제외 → 저장값보다 후보가 **적어질** 위험. PR-3 에서 실 데이터로 매핑 커버리지 측정, 낮으면 Stored 와 union 검토.
- **direction 판정 = 확정**: direction_id 부재 조사 완료 → pattern 정차순서로 강제(위 확정 계약). 잔여 = 순환/지선 pattern 에서 첫 승차 뒤 하차 규칙의 실데이터 확인만(오판 시 제외라 안전측).
- **stop.patterns vs stoptimesForPatterns**: patterns 가 stops 리스트를 stable 하게 주는지 스키마 재확인(안 주면 stoptimesForPatterns 로).
- **성능**: 정류장당 pattern 다수 → resolveBus/busRouteStop 조회가 pattern 수만큼. maxEvaluations(10) 로 잘리지만 소스 단계는 전부 조회 → OTP off 라 프로덕션 영향 0, on 시 캐시 검토.
- **⚠ OTP 전제 계속**: OTP off 면 이 경로 미동작(Stored 폴백). shadow OFF(실제 제안 ON)는 PR4c 후.

## 관련
[[project-national-otp-migration]] [[feedback-server-static-search-front-realtime]] [[feedback-per-task-pr]] [[feedback-implementation-note]]. 다음=PR4c(대안 우열 RouteTimelineComposer 재평가 브리지).
