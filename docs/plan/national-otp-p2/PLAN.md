# PLAN — Phase 2: 공통 라우팅·시간·ID·Journey 계약 (national-otp-p2, L)

기준: `docs/plan/national-otp-map-migration/` 의 intent.md·PLAN.md·PHASE-2.md, 그리고 handoffs/PHASE-0.md·PHASE-1.md.
호환성 실측: `docs/plan/national-otp-p2/COMPAT-OTP-vs-ODSAY.md` (매핑 가능 확정, 갭 6종).
결정: **모델=A(중립 RoutingResult + legacy 매퍼)**, **payment=legacy `-1` sentinel + v2 nullable**, **provider 기본=ODSAY(전환은 P7)**, **워크플로우=세션분리 PR(A~H)**.

목적: OTP 결과를 최초 검색·재탐색에서 동일 규칙으로 처리한다. ODsay·OTP 를 공통 `RoutingPort` 뒤에 두고, 기존 앱은 legacy 응답을, 신규는 v2 capability 응답을 받는다. 실시간/ML·기존 여정은 보존.

## 확정 결정 (코드만 봐선 안 나오는 계약)
- **중립 모델**: `RoutingPort.search(RoutingQuery, Deadline) → RoutingResult`. ODsay·OTP adapter 둘 다 여기에 담고, `LegacyRouteResponseMapper` 가 `RoutingResult → RouteSearchResult/PathResult` 로 번역(기존 앱·JourneyCompiler·snapshot·reroute 무변경). v2 필드는 additive.
- **OUT_OF_SCOPE**: OTP `routingErrors[].code == OUTSIDE_BOUNDS` → `RoutingFailure(OUT_OF_SCOPE)`. bbox 안 무경로(NO_ROUTE)·timeout·engine error 와 구분(P1 실측).
- **payment(불변식, codex 반영)**: fare availability 3-상태 **`KNOWN` / `KNOWN_ZERO`(도보전용 진짜 무료) / `UNKNOWN`**. v2 DTO = nullable `amount` + `availability`. legacy `PathInfo.payment`(primitive int): OTP 경로는 UNKNOWN→**`-1`**, KNOWN_ZERO→`0`. **ODsay 경로는 기존 동작 유지**(`OdSayApiAdapter.java:338-341,351` 이미 unknown→`0` 생성) — provider 비대칭을 문서화하고 parity 입증 전 ODsay 값 안 건드림. 구형 앱 `-1` 렌더는 P5 실기기 확인 전까지 미검증.
- **갭 6종(COMPAT 문서)**: 출구·방향/급행·요금·색상·mapObj·실시간ID. "원천 부재"라 가짜값 금지 → coverage/quality/`STATIC_SCHEDULE` 로 노출. 실시간 ID(ArsID/localStationID)는 P2-D 매핑된 leg 만 실시간/ML.
- **도보**: OTP `steps` 사용(TMAP walkSteps 대체). 대중교통 형상은 P3 OSM relation(P2 는 결측 품질 상태 반환).
- **시간**: departureAt 은 요청 경계에서 주입 Clock 으로 1회 확정(offset 필수, 없으면 입력오류). 내부 Instant/초, serviceDate=feed timezone(Asia/Seoul).
- **캐시 키**: provider + graphVersion + schema/options/capability + OD + 초단위 departureAt. ODsay 는 graphVersion 없음 sentinel.
- **마이그레이션**: V49(ID 매핑), V50(snapshot schema). **V18+ untracked convention → VPS 수동배치 필수**(CI 로 안 나감).

## 재사용 좌표 (기존 코드)
- 진입: `infrastructure/adapter/in/api/route/search/RouteSearchController.java` (`GET /api/route/search`, 좌표만, optional auth).
- 검색: `application/route/service/RouteSearchService.java` (ODsay 직접, deadline ctx, enricher 체인).
- 재탐색: `application/journey/service/RemainingRouteSearchService.java` (OdSayApiPort 직접 호출, 캐시+SingleFlight).
- 포트: `application/route/port/out/OdSayApiPort.java` (`searchPath(...)→RouteSearchResult`, `loadLane`).
- adapter: `infrastructure/adapter/out/api/odsay/OdSayApiAdapter.java` (주입 `RestClient.Builder`, per-call read timeout — OTP adapter 동일 패턴).
- 캐시: `application/journey/port/out/RemainingRouteCachePort.java`, `infrastructure/adapter/out/cache/journey/RedisRemainingRouteCacheAdapter.java`; 검색측 `port/out/{WalkSegmentCachePort,MlPredictionCachePort,RouteSearchSnapshotPort}.java`.
- ID: `application/route/service/TransferStopResolver.java` (`resolveStop/Route/Seq`, ODsay id 기반 — P2-D 가 일반화).
- 시간보강: `application/route/service/TransferArrivalEnricher.java`, `TransferPredictProperties`, `config/TransferArrivalProperties.java`.
- 여정: `application/journey/service/{JourneyCompiler,JourneyStartService,JourneyStartTxService}.java`, `dto/JourneySnapshot.java`, reroute providers(`RouteOptionCandidateProvider`,`RemainingPathCandidateProvider`), `config/JourneyRerouteProperties.java`.
- 정책: `docs/plan/national-otp-map-migration/policy-inventory.md` (REVIEW 항목 #1/#2/#4/#6).

## PR 분할 (세션분리, 각 fresh main 브랜치, per-task-pr)

### PR1a — P2-A 중립 모델 타입 (행위변화 없음, 아무도 호출 안 함)
신규 `application/route/routing/`:
- [ ] `RoutingPort` { `RoutingResult search(RoutingQuery, Deadline)` }
- [ ] `RoutingQuery`(record): origin/dest(**명시 lon/lat 필드 — x/y 혼동 방지, codex 놓친위험**), `Instant departureAt`, `Set<RoutingMode> modes`, walk/transfer/accessibility opts, `String schemaVersion`, provider context(선택 provider, optional graphVersion).
- [ ] `RoutingResult`(record): provider, schemaVersion, **networkDataVersion, graphVersion, mappingVersion, geometryVersion**(codex Q5: 캐시/pinning 에 4버전 필요), `Instant departureAt`, warnings, `List<RoutingItinerary>`.
- [ ] `RoutingItinerary`: staticArrivalAt, mov/wait/walk **seconds(초 단위 보존; 분 반올림은 legacy mapper 최종단계만 — codex Q3 초분규칙)**, `boolean navigationSupported`+reasons, `Fare`(availability KNOWN/KNOWN_ZERO/UNKNOWN + nullable amount), `List<RoutingLeg>`.
- [ ] `RoutingLeg`: `RoutingMode mode`(WALK/BUS/SUBWAY/RAIL/UNSUPPORTED)+sourceDetailType, feedId, agencyId, sourceRouteId, tripId, serviceDate, static board/alight(Instant), rideDurationSeconds, `List<StopOccurrence>`(sourceStopId, **tripPattern, sourceStopSequence**(codex Q3 놓친갭 — PR2 OTP query 에 stop.gtfsId+stopPosition 추가해 채움), occurrenceIndex, lon/lat), `List<WalkStep>`(도보 leg — OTP steps 구조 매핑, codex Q3), `LegGeometry`(source, quality, reason, version, display/progress ref), `Enrichment`(coverage, actualSource, observedAt, calculatedAt, freshness/status).
- [ ] `RoutingFailure`(enum kind: OUT_OF_SCOPE/NO_ROUTE/TIMEOUT/ENGINE_ERROR/INPUT_ERROR) — **first-class 반환값**(빈 목록으로 삼키지 않음; codex 놓친위험: RemainingRouteSearchService 가 실패를 empty 로 흡수). 도메인 예외 연결.
- [ ] enum 세분화(codex Q3 — 단일 STATIC_SCHEDULE 로 뭉치지 말 것):
  - `RoutingMode`, `ActualSource`(STATIC/REALTIME/MODEL), `RealtimeCoverage`(상위 계획 enum 유지).
  - `GeometryQuality`: `OSM_RELATION_OK` / `STREET_FALLBACK`(P3 전 OTP street — **정상 품질 아님**, handoffs/PHASE-1.md:3) / `MISSING` / `REVIEW`.
  - `MissingAttribute` reason 집합: EXIT / DIRECTION / EXPRESS_TYPE / COLOR / FARE / REALTIME_ID (갭 6종을 개별 사유로 — 뭉치지 않음).
- 검증: `./gradlew compileJava`, 신규 record 단위테스트(mode 미상·ID 충돌·fare 3-상태·JSON 직렬화·failure 반환·enum 매핑). 호출부 없어 기존 회귀 영향 0.

### PR1b — departureAt 시간 확정 + 요청 검증
- [ ] departureAt: 요청 경계 주입 `Clock` 1회 확정. offset 없는 문자열=INPUT_ERROR(무경로 아님). 내부 Instant/초, serviceDate=feed tz.
- [ ] `RouteSearchRequest` 확장: optional departureAt(생략=now). 좌표 검증(범위/NaN). 공개 endpoint 유지.
- 검증: Clock 고정 단위테스트, offset/좌표 입력 검증, 기존 RouteSearch 회귀 그린(departureAt 생략 시 동작 동일).

### PR2 — P2-B adapter + 진입점 연결
- [ ] `OtpRoutingAdapter`(신규, `infrastructure/adapter/out/api/otp/`): OTP GraphQL planConnection 호출(주입 RestClient.Builder, per-call timeout), 검증된 fixture 필드로 `RoutingResult` 매핑. query 파일 버전관리(`resources/otp/planConnection.graphql`). fixture=`tools/transit-routing-benchmark/results/otp-seoul-p1/*.json`. HTTP 200+GraphQL errors/부분응답 분류. `OUTSIDE_BOUNDS→OUT_OF_SCOPE`.
- [ ] `OdsayRoutingAdapter`(신규): 기존 `OdSayApiPort` 감싸 `RouteSearchResult→RoutingResult` 역매핑. -98 도보 fallback 은 ODsay 내부 유지.
- [ ] `RoutingProviderSelector`: 상위 서비스가 provider 선택(기본 ODSAY, OTP 는 내부/승인 범위만). 일반 파라미터로 OTP 못 열게.
- [ ] `LegacyRouteResponseMapper`: `RoutingResult→RouteSearchResult/PathResult/SubPathResult`(COMPAT 표대로). payment unknown→`-1`. mapObj 없음→leg별 legGeometry.
- [ ] `RouteSearchService`·`RemainingRouteSearchService` 를 `RoutingPort` 경유로 전환(기본 ODSAY 라 서울 응답 회귀 동일). 재탐색은 검색이력 미기록 유지.
- [ ] v2 응답 DTO(신규, nullable fare 등) — capability 경로. 구형 앱=legacy.
- 검증: 정적 bus/subway/rail/walk, partial GraphQL error, no route, timeout, ODsay -98, 최초/남은검색 port 호출, **기존 RouteSearch 회귀 그린**. `api-contract.md` 초안.

### PR3 — P2-C 시간의존 캐시 + graph pinning
- [ ] `RoutingCacheKey`(공통): provider+graphVersion+schema/options/capability+OD+초 departureAt. sub-second 올림, 옵션 정렬 직렬화 hash.
- [ ] versioned cache envelope(유효 serviceDate/출발편/요청시각 보존; TTL 남아도 못 탈 편 반환 금지).
- [ ] `RemainingRouteCachePort`/`RedisRemainingRouteCacheAdapter` 키 교체 + namespace 분리(구형 JSON 을 새 DTO 로 안 읽음). ODsay graphVersion sentinel.
- [ ] graph pinning: lookup 전 대상 인스턴스 고정, 응답 버전 불일치=재시도/실패. Redis 장애 fail-open 유지, OTP 동시성/timeout 한도 유지.
- 검증: 출발시각·옵션·graph별 분리, 동시요청 공유, 자정/막차, Redis 장애, blue/green 교체 경합.

### PR4 — P2-D ID 매핑 + 기존 실시간 coverage (**V49**)
- [ ] `TransitIdentityMappingPort`(신규) + 버전별 mapping adapter. key=feed/agency/source route+trip pattern+stop occurrence. canonical↔provider(route/stop/direction/seq)+근거·검증상태·dataVersion.
- [ ] **V49**: mapping 테이블. (version, feed, agency, route, pattern, occurrence, provider) 복합 유일 + lookup index. 활성 snapshot 참조 버전 불변.
- [ ] `TransferStopResolver` 연계 재작성: OTP GTFS stop → 서울 ArsID/localStationID/지하철 station 매핑된 leg 만 실시간/ML. 번호·이름만 같으면 자동승인 금지(지역/기관/승하차/순서/방향 검증).
- [ ] 수도권 내 미매핑=`STATIC_SCHEDULE`+사유(검색 실패 아님). 수도권 밖=OUT_OF_SCOPE. 기존 지하철 도착/열차매칭 ID·snapshot 계약 보존(조용히 삭제 금지).
- 검증: 동명 502, 회차/급행 pattern, 같은 stop 재방문, 모호 매핑, 모델 미지원, 이전버전 조회.

### PR5 — P2-E 하나의 시간축 ETA
- [ ] `RouteTimelineComposer` + `ConnectionFeasibilityEvaluator`(신규): departureAt→userReadyAt→편 선택(userReadyAt+buffer)→정적 WAIT/RIDE 분리(waitSeconds=boardAt-userReadyAt, buffer 이 대기에 포함, 중복가산 금지)→하차+환승도보 누적→다음편 재평가→불가시 OTP 남은검색.
- [ ] effective total = 최종도착 - 요청출발(정적에 실시간 대기 재가산 금지, 분은 마지막 반올림). 초기대기 포함.
- [ ] 실시간 horizon/freshness 설정, 취소편 정적 fallback 금지, staticArrivalAt+실시간 시각 모두 보존. buffer 는 기존 `TransferArrivalProperties`/`TransferPredictProperties` 재사용.
- 검증: 10:05→10:12 도착변경으로 10:10편 제외, 도보증가, 버스→지하철→버스, 0초 대기, 초기대기, 25시, stale fallback, buffer 중복없음, 재검색 상한. Clock 고정.

### PR6 — P2-F 동일 leg 대안 + 남은검색 + 정책복원
- [ ] routeOptions: OTP 후보/검증 pattern 에서 같은 승·하차·방향 + 하차 stop 실제정차만. 각 option 자체 route/trip/occurrence/승차시간/geometry/version.
- [ ] 각 대안 P2-E 로 재평가(laneIndex 로 trip identity 대체 금지). 동일 leg 우선, 없으면 공통 port 남은검색. 저장 직전 ACTIVE/current revision 재검증.
- [ ] **policy-inventory 반영**: `journey.reroute.remaining-path.max-paths`(#1, ODsay 유료근거) 비용 상한 해제 → 자원(deadline·동시성)으로 재설정. `route-option`(#4) 비용 라벨 제거·UX/자원 유지. trigger shadow(#6) 완화 판단. 변경 값·좌표·시험결과를 policy-inventory 갱신.
- 검증: 느린 먼저 버스, 다른 승차시간, 하차역 통과, 이후 환승 실패, 대안 없음, 검색 중 종료/재탐색.

### PR7 — P2-G Journey compile + snapshot (**V50**)
- [ ] `JourneyCompiler`: mode switch 명시, rail/unknown→bus 변환 금지. `navigationSupported`=mode+ID매핑+geometry OK+진행엔진 계약 서버 판정(클라 true 불신).
- [ ] OTP 정적 WAIT/RIDE 분리 저장(ETA composer 재가산 방지). revision 에 provider/schema/network/graph/version·선택편·occurrence·routeOptions·표시/진행 geometry 결합.
- [ ] snapshot reader: 기존=legacy reader, 신규=schemaVersion reader(ODsay metadata 를 OTP 값으로 임의 채움 금지).
- [ ] **V50**: snapshot column/JSON payload/제약 추가. 기존 V35 등 배포완료 migration 불변. Tx 경계: 외부 routing/geometry 준비는 잠금 밖 → 시작/수락 Tx 에서 유효성·capability 재확인 → revision·legs·connections·geometry·알림 원자반영.
- 검증: KTX 시작/수락 차단, forged capability, 중복수락/동시 progress, 구형 snapshot, graph 교체·ODsay rollback 후 활성 OTP 여정 조회/진행/종료.

### PR8 — P2-H flag·오류·관측성 마무리
- [ ] flag: 기본 ODSAY, OTP=내부/승인만. 검색 provider 와 navigationSupported 정책 분리.
- [ ] 계측: route stage/provider/outcome/source/mapping failure/time infeasible/재검색 횟수. 고카디널리티(graphVersion/routeId/tripId)는 metric label 금지 → 구조화 로그/trace.
- [ ] 배포순서 문서화: reader/guard 선배포 → writer/새 응답 → 앱 → flag. 전체 요청 예산 안에 OTP·보강·재검색 포함(단계별 독립 3초 금지).
- [ ] `api-contract.md` 확정(endpoint/요청·응답 JSON/오류/단위·null/버전·capability/geometry + fixture 경로).
- 검증: A~G 회귀, 전환 전 기존경로 호환, OTP 내부 검색/재탐색, 미지원 기능/범위 응답. P3 미완 형상은 네비 승인 제외.

## 산출/인계
- `docs/plan/national-otp-map-migration/handoffs/PHASE-2.md`: A~H 상태, `api-contract.md`, JSON fixture, migration(V49/V50)/설정/배포순서, 대상 테스트 결과, 정책 변경.
- 실제 배포는 P6. V49/V50 은 배포 시 VPS 수동배치.

## 리스크
- OTP GraphQL 스키마 세부(fareProducts 등) 이미지 버전 의존 — PR2 에서 실행 이미지로 재확인(임의 필드 금지).
- P2-D 매핑 규모/정확도 — 매핑 전 STATIC 로 안전. 잘못된 매핑이 실시간 오염시키지 않게 검증필수.
- legacy `-1` payment 를 구형 앱이 어떻게 그리는지 — 모바일(P5)에서 unknown 표시 확인 필요(0원 렌더 금지).
- snapshot schema(V50) 배포 순서 어기면 활성 여정 손상 — reader 선배포 강제.

## Codex 반영 (2026-09-07, thread 01a07bec)
codex 조건부 진행 승인. 반영 사항:
- **PR 재분할**: PR1→PR1a(모델)/PR1b(시간·요청). PR2→PR2a(OtpRoutingAdapter+stable GraphQL query+fixture 재고정)/PR2b(OdsayRoutingAdapter+provider selector, 기본 ODSAY 유지)/PR2c(LegacyRouteResponseMapper+v2 DTO+오류계약). PR7→PR7a(reader/guard 선배포: schemaVersion snapshot reader, 3개 reader 무조건 역직렬화 지점 `JourneySubwayStationChainReader:55-75`·`JourneyNavigationGeometryService:382-391`·`RerouteAcceptTxService:482-496` 방어)/PR7b(V50 + writer)/PR7c(JourneyCompiler mode·navigationSupported·routeOptions 결합). **flag/guard/reader 를 writer 보다 앞 배포.**
- **canonical 은 RoutingResult**, LegacyMapper 는 API 경계에서만. **ODsay 는 parity 입증 전 기존 경로 유지**(강제 전환 금지).
- **fixture 재고정(PR2a 선행)**: 현재 `otp_fixture_query.py` 가 deprecated 필드(`startTime/endTime`, `intermediatePlaces`) 사용 — 실행 이미지 `schema.graphqls` 로 stable 필드 재확인 후 query 버전관리. `fareProducts` leg단위 nullable list 특성 반영. 오류 enum: `OUTSIDE_BOUNDS` 외 `OUTSIDE_SERVICE_PERIOD` 등도 분류(창 만료=미지원).
- **stop occurrence**: PR2a OTP query 에 `intermediatePlaces{stop{gtfsId}}`+stopPosition(stop_sequence) 추가해 RoutingLeg.StopOccurrence 채움.
- **캐시(PR3)**: 키에 mappingVersion+geometryVersion 추가. `RedisRouteSearchSnapshotAdapter:19-25` old/new schema 전환을 PR3 범위에 포함(누락 보완). graph pinning 은 **응답에 graphVersion 필드**가 있어야 성립 → PR2a 에서 OTP `serverInfo`/graph build id 조회 경로 확인, 없으면 build 주입 버전을 응답 envelope 에 부착.
- **마이그레이션 재현성(codex Q5 최대구멍)**: `.gitignore` 가 migration 전역 무시 + whitelist V43 까지만 → V44~V48 untracked. ~~V49/V50 은 whitelist 에 추가(버전관리)~~ **결정 번복(2026-09-08, P2-D 구현 시)**: 실제 저장소 관행(V44~V48 전부 untracked)과 일관되게 **V49 도 untracked(VPS 수동배치)** 로 간다. codex 재현성 우려는 인정하되, whitelist 만 V49 부터 하면 관행이 갈려 더 혼란스럽고 `.gitignore` 는 사용자 WIP 가 얹혀 있어 분리 커밋도 어렵다. 대신 CLAUDE.md 규칙대로 **implementation_note `### 배포` 에 "V49 VPS 수동배치 필요" 명시**로 재현성을 문서로 보전한다. whitelist 정책 전면 재정비는 별건.
- **searchType/busCount/laneGraphics 파생규칙(codex Q3)**: LegacyRouteResponseMapper 에서 mode 집계로 명시 정의(PR2c). laneGraphics 는 P3 형상 전 빈 값+quality.
- **record copy 유실(codex 놓친위험)**: `JourneySnapshot:75-107` 류 전체 재생성 copy 는 신규 필드 추가 시 유실 위험 — PR7 에서 builder/withX 패턴 또는 테스트로 가드.
