# standalone-track — PLAN v2 (L 티어, 에이전트 실행용)

> **실행 결과 (2026-10-07, 이 플랜은 기록용으로 머지)** — 이 문서는 구현 전 v2 그대로다. 실제와 다른 점:
> - PR-1 #287(V59)·PR-2 #290·PR-3 #293(V60) 은 이 플랜대로 머지·VPS 배포됐다. 플래그 `journey.track.enabled`·`journey.subway.selection.enabled` 운영 ON.
> - **PR-4(버스 차량 시각 대조) 는 #296 으로 머지 후 #300 으로 revert.** issue #265 결정 5차: 단독 버스 추적은 차량을 특정하지 않는다(사용자가 탄 뒤 한참 있다 시작을 누르면 시각 대조가 엉뚱한 차를 고른다). 버스 위치는 앱 GPS 를 노선 선에 투영해 앱 엔진(M5/M6)이 잡는다. V61 마이그레이션 파일은 main 에 남아 있고 VPS 에도 적용됐다(칼럼만 있고 쓰지 않음).
> - **PR-5(busGuidance·서버 버스 안내) 는 폐기.** 아래 버스 차량·관찰·busGuidance 절은 실행되지 않았다.
> - 앱 연결은 이 플랜의 추적 조회 API 대신 기존 화면 API 에 OTP GTFS id 를 싣는 쪽으로 갔다(#302, #298 A안). 방금 떠난 열차 #301·#303 추가.
> - 현재 계약의 원본 = `HANDOFF.md`(#297). 이 문서와 어긋나면 HANDOFF 가 맞다.

issue #265 · 기준 main `21371b27` · 관련 #264(보드, 머지 완료), #267/#268(지역 노선 정류장 표), #282(지역 탑승 위치 수정)

결정 이력은 issue #265 댓글(2026-10-06~07)이 원본이다. 이 문서는 그 결정을 구현 단위로 옮긴 것이다.

**v2(2026-10-07)**: 리뷰 에이전트 지적 12건 반영 — 경로 합성은 기존 OTP→레거시 매퍼를 거친다(id·방향 보강 필수), 선택 고정 행은 열차 식별 4칸 필수, 링은 이동 전 선택에 안 켠다, 리셋 후 M7 복귀, 계승 시 출처 덮어씀 수정, 추적 API 는 `/api/journeys/track/**`(인증), GTFS 정류장 id 매핑, OTP 쿼리 크기·코드 노후·전송 규칙, 버스 매핑 훅·잠금·순번·시계, 버스 안내 위치·폴링 계약, 제외는 SQL 에서.

## 0. 목표

경로 검색을 거치지 않고 **노선·방향·승차·하차만 골라** 지하철·버스를 추적하는 "단독 추적"을 만든다. 내부적으로는 **구간 1개짜리 여정(`kind=TRACK`)** 이라 지하철 열차 고정(M7)·진행 링·Live Activity·여정 수명주기를 그대로 탄다. 덧붙여, 지하철은 역에 도착하면 **사용자가 탈 열차를 고르게** 하고(일반 여정에도 적용), 버스는 **이동 시작 시각으로 탄 차량을 맞춘다**.

## 1. 확정 규칙 (issue #265 결정)

| 항목 | 규칙 |
|---|---|
| 서버 모델 | (a) 1구간 여정 재사용. `journey_session.kind` = `NAVIGATION`(기존) / `TRACK` |
| TRACK 와 재탐색 | **없음.** 보드 갱신·말풍선·재탐색 push·사건 기반 방아쇠에서 전부 제외 |
| 하차역/정류장 | 필수 |
| 경로 뼈대 | **OTP 노선 패턴에서 오려낸다**(검색 아님). 패턴 구간을 OTP 검색 결과와 같은 `RoutingLeg` 로 만들고 **기존 `LegacyRouteResponseMapper.toSubPath` 로 `SubPathResult` 를 만든다** — 그래야 `startLocalStationID`·`busLocalBlID`(서울·GBIS·TAGO id)와 지하철 `wayCode`(`route.longName` 에서)가 채워진다. → `JourneyCompiler.compile` → `JourneyTransitIdEnricher.enrich` → `JourneyStartTxService.start` |
| 선택 데이터 | 추적 전용 조회 API 신설(`/api/journeys/track/**` — 인증 경로 안): 정류장/역 → 지나는 노선·방향 → 그 방향 정류장 목록 |
| 방향 표시 | `headsign` 이 없으면(TAGO 는 `directionId=-1`, `headsign=null` 실측) **패턴 종점 정류장 이름 + "방면"** |
| 역·정류장 도착 확인 | **앱이 판단**(여정=도보 단계 종료, 단독=고른 역 지오펜스). 서버 GPS 미수집(2026-08-12 결정) 유지 |
| 선택 알림 | 앱 로컬 알림 + Live Activity 버튼(포그라운드면 인앱 시트). 서버 push 아님 |
| 지하철 열차 선택 | 역 도착 시 후보(도착 API 방향별 다음 2대) 중 사용자가 고르면 **곧바로 CONFIRMED**(매처 검증 없음). 고르지 않고 이동이 시작되면 기존 M7 자동 판정. **일반 여정에도 적용** |
| 지하철 교정 | 기존 `USER_RESET`("이 열차 아님") 통로. **이동 전 리셋이면 M7 자동 판정으로 돌아갈 수 있어야 한다**(PR-3) |
| 버스 사용자 선택 | **노선만**(+ 승·하차 정류장). 차량은 고르지 않는다 |
| 버스 차량 매핑 | 서버 **시각 대조**: 앱의 `WAIT_BUS → RIDE_BUS` 전환 시각(`clientOccurredAt`, 기기 시계가 의심스러우면 서버 수신 시각) ±60초 안에 승차 정류장을 출발한 그 노선 차량. 1대면 고정, 0대면 고정 안 함, 2대 이상은 시각 차가 확실히 갈릴 때만 |
| 버스 매핑 적용 범위 | **단독 추적(TRACK) 버스만.** 일반 여정 버스는 붙이지 않는다(#265 2026-10-07 결정 — #264 배포 후 기점 실패 지표를 보고 다시 판단) |
| 버스 저장 | `journey_leg.vehicle_id`(현재 legacy 미사용) + 종류(`VEH_ID`/`PLAIN_NO`) + 출처(`AUTO_MATCH`) |
| 버스 교정 | "이 버스 아님" 수동 리셋. 자동 해제(선택 차량 통과 + 이동 없음)는 후속 |
| 버스 정류장 도착 시 | 안내만("곧 도착 ○○번"). 선택 요구 없음 |
| 버스 Live Activity | **1차 앱 폴링.** 서버는 스냅샷에 버스 안내(내 버스 n 정거장 전·도착 예정)를 싣는다. 서버 push 는 후속. 폴링이라 앱이 살아 있는 동안만 갱신된다 |
| lease | 기존 그대로(앱 소식 끊기면 접기). 지하철 탑승은 기존 오프라인 보호가 덮는다. 만료 지표에 `kind` 태그 |

### 실측 근거 (issue #265 댓글)
- 버스 위치 원천: 서울 `getBusPosByRtid`(`vehId`·`plainNo`·`sectOrd`·`stopFlag`, 갱신 p50 18초), 경기 `getBusLocationListv2`(`vehId`·`plateNo`·`stationSeq`·`stateCd` 2=정류소 출발, 변화 간격 p50 26초), TAGO `getRouteAcctoBusLcList`(`vehicleno`·`nodeord`, 상태 없음, p50 27초). 배차 5~15분 대비 20~30초 해상도 → **승차 순간 단독 확정**, 후행 확정 단계 없음.
- 한도: 경기 위치 일 1만 / TAGO 위치 일 50만 / 서울 검색용 위치 예산 `SEARCH_QUOTA_POSITION=5000`.
- 순번 공간: 경기·TAGO 모두 실시간 순번 = #267 표 `seq`. 앱 정류장 목록(GTFS)과는 정류장 id 로 조인(#282 규칙).

## 2. PR 분할 · 순서

| 순서 | PR | 내용 | 마이그레이션 |
|---|---|---|---|
| 1 | PR-1 | `kind` 칼럼 + TRACK 제외 규칙 | V59 |
| 2 | PR-2 | 노선 패턴 조회 API + TRACK 시작 API(경로 합성) | 없음 |
| 3 | PR-3 | 지하철 열차 후보·선택 API(일반 여정 포함) | V60(고정 출처 값) |
| 4 | PR-4 | 경기·TAGO 버스 위치 어댑터 + 버스 시각 대조 매퍼 + 리셋 | V61(`journey_leg` 차량 종류·출처) |
| 5 | PR-5 | 스냅샷 버스 안내(`busGuidance`) + HANDOFF | 없음 |

- 플래그 `journey.track.enabled`(기본 false) 하나가 TRACK 조회·시작 API 를 연다(조회도 OTP 를 부르므로 막는다). PR-3 의 지하철 선택은 별도 플래그 `journey.subway.selection.enabled`(기본 false) — 일반 여정에도 영향이 있어 따로 켠다.
- PR-1 은 동작 변화가 없다(기존 여정은 전부 `NAVIGATION`). 먼저 배포해도 된다.
- **마이그레이션 번호는 머지 순서대로 다시 매긴다**(운영은 v58 까지). 각 PR 노트 `### 배포` 에 "V__ VPS 수동배치 필요".

---

## PR-1 `kind` 칼럼 + TRACK 제외

### 마이그레이션 `V59__journey_session_kind.sql`
```sql
ALTER TABLE journey_session ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'NAVIGATION';
ALTER TABLE journey_session ADD CONSTRAINT journey_session_kind_check CHECK (kind IN ('NAVIGATION', 'TRACK'));
```
- 기존 행은 기본값으로 전부 `NAVIGATION`. 백필 없음.

### 엔티티
- `domain/journey/entity/JourneyKind.java`(신규 enum) + `JourneySession.kind`(`@Enumerated(STRING)`, builder 기본 `NAVIGATION`).
- `JourneySession` 이 `@Builder` 면 `NAVIGATION` 기본값은 생성자에서 정한다(빌더 기본값 함정).
- `JourneySnapshot` 에 `kind` 를 싣는다(앱이 화면을 가른다). `JourneySnapshot.of` 에 인자 추가 — 유일 호출자 `JourneyQueryService:73`. **`withEta`·`withSubwayGuidance` 가 새 필드를 같이 옮기는지 시험**(M7 함정: 레코드 재조립 메서드가 새 필드를 조용히 떨어뜨린다).
- 운영은 `ddl-auto: validate` — V59 는 칼럼을 매핑하는 앱 배포 **전에** 배치.

### 제외 지점 — **SQL 에서 막는다**(세션을 먼저 읽고 거르면 경쟁 창이 생긴다)
| 지점 | 파일 | 방법 |
|---|---|---|
| 보드 갱신 선점 | `JourneySessionRepository.claimRerouteRefreshBatch`(:283 부근) | `AND s.kind = 'NAVIGATION'` |
| 보드 예약 생성 | 같은 파일 `requestRerouteRefresh`(:338-356, 예약이 없으면 만든다), `scheduleRerouteRefreshIfAbsent`(:365), `markRerouteAccepted`(:373) | 각 UPDATE 에 `AND kind = 'NAVIGATION'` |
| 보드 첫 예약 | `JourneyStartTxService.start` 의 `rerouteRefreshSignal.scheduleInitial` | TRACK 이면 부르지 않음(위 SQL 조건과 이중) |
| 보드 직접 갱신 | `RerouteProposalService.request` 가 보드 모드에서 `boardRefreshService.refresh` 를 선점 없이 부름(:195) | `RerouteBoardRefreshService.refresh` 첫머리에 kind 확인(방어) |
| 사건 기반 방아쇠 | `RerouteTriggerEvaluator` | 1구간이라 이미 `batch.isEmpty()` 로 0 — 시험으로만 고정 |
| 재탐색 알림 | `RerouteAlertPublisher`, `RerouteBoardPushService` | 보드가 안 생기면 0 — 시험으로 고정 |
| 사용자 요청 제안 | `POST /reroute-proposals` | 이미 "환승 없음" 검사(`WindowCooldownEligibilityPolicy:58`)로 409 `REROUTE_NOT_ELIGIBLE` — 새 오류 코드 만들지 않는다 |

- 환승 위험(`TransferRiskEvaluator`)은 1구간이라 자연히 0 — 별도 조건 불필요. 대기 단계 도착 예측(`WaitBusArrivalPredictor`·`WaitSubwayArrivalPredictor`)은 **TRACK 에도 그대로 쓴다**(곧 도착 안내의 근거).

### 테스트
- 보드 선점·예약 쿼리 IT: TRACK 세션은 기한이 지나도 안 잡히고, `requestRerouteRefresh` 가 예약을 만들지 않는다.
- 사용자 요청 제안: TRACK → 409 `REROUTE_NOT_ELIGIBLE`.
- `withEta`·`withSubwayGuidance` 후에도 `kind` 가 남는다.
- 플래그·kind 기본값 회귀: 기존 시작 경로는 `NAVIGATION`.

---

## PR-2 노선 패턴 조회 + TRACK 시작

### OTP 포트 확장
`application/route/port/out/TransitPatternPort.java`(신규) — OTP GraphQL 구현 `infrastructure/adapter/out/api/otp/OtpTransitPatternAdapter.java`.
```java
List<StopPattern> patternsAtStop(String gtfsStopId);   // stop(id){patterns{code headsign directionId route{gtfsId shortName longName mode color agency{gtfsId}} stops{gtfsId name lat lon}}}
Optional<PatternDetail> pattern(String patternCode);    // pattern(id){stops{...} patternGeometry{points} trips{gtfsId} route{gtfsId shortName longName mode color agency{gtfsId}}}
List<StopTime> tripStopTimes(String tripGtfsId);        // trip(id){stoptimes{scheduledArrival scheduledDeparture stop{gtfsId}}}
```
- **`pattern{trips{stoptimes}}` 를 한 번에 받지 않는다** — 제한 인자가 없어 2호선이면 2.5만 행 수준이고 OTP 는 힙에 민감하다. `trips{gtfsId}` 만 받고, 지금 시각에 가까운 오늘 trip 하나의 `stoptimes` 를 따로 받는다(`tripsForDate` 가 있으면 그것).
- `route.longName` 필수 — 지하철 방향(`wayCode`)이 여기서 나온다(`OtpPlanConnectionMapper:136-137`).
- 지하철 정류장이 OTP 에서 자식 stop 이면 `station(id){stops{patterns}}` 로 받아야 한다 — 구현 첫 단계에서 실측해 노트에 기록.
- 전송: `SimpleClientHttpRequestFactory`(JDK 팩토리는 POST 본문에서 멈춘다, `OtpRoutingAdapter:37-45`). 호출자가 `OtpConcurrencyGate.tryAcquire(deadline, role)` 를 잡는다(어댑터는 안 잡는다).
- 쿼리 파일 `src/main/resources/otp/trackPatterns.graphql`. OTP 2.9 스키마 필드만(`planConnection.graphql` 머리말 규칙과 같게).
- 2026-10-07 실측: `route(id:"1:BR_TAGO_DJB30300151"){patterns{...}}` 가 패턴 3개·정류장·형상 길이·trips stoptimes 를 모두 준다. TAGO 는 `directionId=-1`, `headsign=null`.
- 캐시: 패턴 단위 10분. **패턴 코드는 그래프를 다시 빌드하면 바뀐다**(최근 P3 형상·GBIS 그래프로 자주 바뀜) → 캐시 키에 그래프 버전 포함, 시작 API 는 패턴 코드 대신 `(routeGtfsId, boardStopId, alightStopId)` 를 받아 서버가 패턴을 다시 찾는다(코드가 낡아도 동작).

### 조회 API (`JourneyTrackController` 신규)
**경로는 `/api/journeys/track/**`** — `SecurityConfig` 는 적어 둔 경로만 인증하고 나머지는 `permitAll`(:60-67)이라 `/api/track` 으로 두면 익명에 열린다.

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | `/api/journeys/track/stops/near?lat&lon&mode` | 좌표 주변 OTP 정류장/역(`stopsByRadius`) `[{gtfsStopId, name, lat, lon, mode}]` |
| GET | `/api/journeys/track/stops/{gtfsStopId}/patterns` | `[{routeGtfsId, mode(BUS/SUBWAY), routeName, color, directionLabel, terminalName, patternCode}]` — `directionLabel` = headsign 또는 `"{종점} 방면"` |
| GET | `/api/journeys/track/routes/{routeGtfsId}/stops?from={gtfsStopId}&pattern={code}` | 그 정류장 **다음부터** 종점까지 `[{gtfsStopId, name, lat, lon, index}]` — 하차 후보 |

- **앱은 지금 GTFS 정류장 id 를 가진 API 가 없다**(지도 스냅샷은 서울 버스·서울 `stop_id` 만, 지하철은 없음). 그래서 좌표 주변 조회를 하나 둔다. 서울 버스 정류장은 `1:BS_TAGO_SEB<9자리>`, 경기 `GGB`, 지방 TAGO 접두사 규칙으로 기존 id 에서 바꿀 수도 있다(`OtpSeoulIdentityResolver:43` 규칙) — HANDOFF 에 둘 다 적는다.

### 시작 API
`POST /api/journeys/track` `{routeGtfsId, boardStopId, alightStopId, patternCode?, replace}` → 기존 시작과 같은 `JourneySnapshot`(201/409 규칙 동일). `replace=true` 면 진행 중인 일반 여정도 접힌다 — HANDOFF 에 명시.

`application/journey/service/TrackJourneyStartService.java`(신규)
1. `journey.track.enabled` 확인(꺼짐이면 404).
2. 패턴 결정: `patternCode` 가 있고 승하차를 순서대로 포함하면 그것, 아니면 그 노선의 패턴 중 승차 다음에 하차가 오는 것(여럿이면 정류장 수가 가장 적은 것). 없으면 422 `TRACK_STOPS_INVALID`.
3. `TrackLegFactory`(신규) — 패턴 구간으로 **OTP 검색 결과와 같은 `RoutingLeg`** 를 만든다(모드·노선 `gtfsId`/`shortName`/`longName`/`color`/`agency`, 승하차 `stop.gtfsId`·좌표, 중간 정류장, 잘라 낸 형상, 소요).
   - 소요 = 지금 시각에 가까운 오늘 trip 의 stoptimes 에서 승차 출발~하차 도착. **없으면 422 + 지표**(근사하지 않는다 — 소요가 지하철 오프라인 보호 시간 `SubwayOfflineProtectionPolicy` 에도 들어가 틀리면 일찍 만료된다).
4. `LegacyRouteResponseMapper.toSubPath`(가시성 조정)로 `SubPathResult` 를 만든다 — `OtpSeoulIdentityResolver` 가 서울·경기·TAGO id 를, `SubwayDirectionNormalizer` 가 `wayCode` 를 채운다. `stations` 는 **승차·하차를 포함**해야 한다(`JourneySubwayStationChainReader:82` 가 `stations[0]` 을 승차역으로 본다). 이 `SubPathResult` 하나로 `PathResult`(도보 없음, `info.lastEndStation` = 하차 이름)를 만든다.
5. 이후는 일반 시작과 같다: `compiler.compile` → `transitIdEnricher.enrich` → `startTxService.start(..., kind=TRACK)`.
   - `JourneyStartTxService.start` 에 `JourneyKind` 인자 추가(기존 호출부는 `NAVIGATION`).
   - 동시 시작 경쟁(`uq_journey_active_per_user`)은 `JourneyStartService.translate`(:106-119)와 같은 변환을 재사용 — 공통 메서드로 뺀다.
   - `routeSnapshot` 은 합성한 `PathResult` — 지오메트리·계획 정류장 읽기(#282)가 같은 원천을 본다.
6. 여정 이력(검색 기록)은 남기지 않는다.

### 테스트
- `TrackLegFactoryTest`: 승하차 자르기(승하차 포함), 소요 계산·없으면 실패, TAGO 방향 라벨.
- 합성 → `toSubPath` → `compile` → `enrich` 통합 단위: 서울 버스·경기 버스·TAGO 버스·지하철 각각 `startLocalStationID`·`busLocalBlID`·`wayCode`·`directionKey` 가 채워진다(지하철 `wayCode` 가 비면 반대 방향 열차가 후보에 섞인다).
- `OtpTransitPatternAdapterTest`(MockRestServiceServer, 실측 응답 축약).
- `TrackJourneyStartServiceTest`: 플래그 off 404, 순서 역전 422, 낡은 패턴 코드 → 노선으로 재해결, 정상 → `kind=TRACK`·단계 = 대기+타기 2개, `scheduleInitial` 미호출.
- 보안: `/api/journeys/track/**` 익명 401.
- IT 1개: 시작 → `/active` 스냅샷이 1구간·`kind=TRACK`.

---

## PR-3 지하철 열차 후보·선택 (일반 여정 포함)

### 후보 API
`GET /api/journeys/{journeyId}/subway/train-candidates` → 지금 단계가 `WAIT_SUBWAY` 이고 다음이 `RIDE_SUBWAY` 일 때, 그 역·방향의 도착 후보(`SubwayRealtimeArrivalReader.arrivals(lineId, stationId, wayCode)`) 최대 2대.
- 응답 `[{trainNo, destination, express, arrivalSeconds, observedAt}]`. 대기 단계가 아니면 409 `NOT_WAITING_FOR_SUBWAY`.
- 행선지·급행은 앱이 사용자에게 보여 준다(급행·다른 지선 오판을 사용자가 거른다).

### 선택 API
`POST /api/journeys/{journeyId}/subway/train-selection` `{trainNo, routeRevision}`
- 대기 단계의 다음 `RIDE_SUBWAY` 구간에 고정 행을 **CONFIRMED · source `USER_SELECTED`** 로 만든다.
  - **열차 식별 4칸을 모두 채운다** — `subway_leg_binding_train_identity_check`(V44:88-93)가 `train_no`·`train_service_date`·`train_line_id`·`train_direction` 을 함께 요구하고, 링도 하나라도 비면 식별을 못 만든다(`SubwayRingUpdateService.identityOf`). 지금 빌더에는 열차 칸이 없다(`SubwayLegBinding:107-120`) → `SubwayLegBinding.selected(...)` 팩토리 신설. 운행일 = `ServiceDateResolver`, 방향 = `SubwayRealtimeArrivalReader.toDirection(lineId, wayCode)`(:147).
  - 기존 행이 있으면(PENDING 등) 행 잠금(`SubwayBindingLockGuard`) 후 버전 비교로 갱신. 감사 이벤트 `USER_SELECTED`(DECISION 종류, V45:36 규칙).
  - 후보 목록에 없는 열차번호면 422 `TRAIN_NOT_IN_CANDIDATES`(낡은 화면 방어).
- **링은 이동 전 선택에 켜지 않는다**: `findRingTargets`(`SubwayLegBindingRepository:80-92`)는 탑승 여부를 안 본다. 링 계산기는 승차 다음 역을 목표로 잡아(목표 순번 ≥ 1) 승강장 대기용 "접근 중" 상태가 없다. 그래서 `AND NOT (b.source = 'USER_SELECTED' AND b.last_movement_seq IS NULL)` 를 추가하고, `JourneySubwayGuidanceComposer`(:62-80, 대기 단계에서도 바인딩이 있으면 링을 보여 줌)에도 같은 조건. 지금 고정된 행은 전부 이동 순번이 있다(`findMatchable` :60 조건) — 기존 동작 무영향.
- 이후 앱의 `boarding-evidence`(이동 시작)는 **새 코드**로 CONFIRMED·USER_SELECTED 행에 이동 순번(`last_movement_seq`)과 `boardedDetectedAt` 을 채운다(지금은 기존 행에 이벤트만 쌓고 순번만 올린다, `SubwayBoardingEvidenceService:92-107`). 이 순간부터 링이 켜진다. 매처는 CONFIRMED 행을 건너뛴다(시험으로 고정).
- **이동 전 "이 열차 아님" 후 M7 복귀**: `resetByUser` 는 PENDING + `lastMovementSeq=null`(:182-192)로 만들고, `sweepStale` 이 `match-window`(PT6M) 뒤 ABANDONED 로 접는다(`SubwayBindingMatchService:114-117`). 버려진 행의 증거는 무시된다(`SubwayBoardingEvidenceService:97`) → 자동 판정이 영영 안 돌아온다. **탑승 단계가 아직 PENDING 인 행은 sweep 에서 뺀다**(이동 전이면 기다리는 게 정상).
- **재탐색 계승은 지금 `source` 를 `INHERITED_ON_REROUTE` 로 덮는다**(`SubwayLegBinding:161`). `USER_SELECTED` 는 보존하도록 `inheritTo` 를 바꾼다(오판율 지표가 출처를 본다).
- `journey.subway.selection.enabled` 가 꺼져 있으면 두 API 모두 404.

### 마이그레이션 `V60__subway_binding_user_selected.sql`
- `subway_leg_binding_source_check`(V44:61) 에 `'USER_SELECTED'` 추가 — `SubwayBindingSource` enum 에도.
- `subway_binding_event_type_check`(V45:30) 에 `'USER_SELECTED'` 추가 — `SubwayBindingEventType` enum 에도, DECISION 종류.
- `subway_leg_binding` 의 `train_no` 필수 조건(`status not in ('PROVISIONAL','CONFIRMED') or train_no is not null`)은 그대로 — 선택은 열차번호가 있다.

### 테스트
- 선택 → CONFIRMED·USER_SELECTED·식별 4칸 채움(제약 IT), 이후 evidence 가 상태를 안 바꾸고 이동 순번·탑승 시각만 채운다.
- 링 대상: 선택만 하고 이동 전 → 안 잡힘 / evidence 후 → 잡힘. 기존 M7 행 → 그대로 잡힘.
- 매처가 CONFIRMED 행을 건너뛴다.
- 이동 전 리셋 → 6분 지나도 sweep 에 안 접히고, 이후 이동 증거로 M7 이 다시 고른다.
- 후보 밖 열차 422, 대기 단계 아님 409, 플래그 off 404.
- 계승 IT: 선택 고정이 재탐색 수락 후에도 `USER_SELECTED` 로 남는다.

---

## PR-4 버스 위치 + 시각 대조 (TRACK 버스만)

### 위치 포트
`application/bus/port/out/BusPositionPort.java`(신규) — `List<BusVehiclePosition> positions(provider, routeId)`
- `BusVehiclePosition(vehicleId, plateNo, seq, departedFromSeq?, stopped?, observedAt)`
- 서울: 기존 `SearchTimeBusQueryPort.getBusPosByRtid` 감싸기(예산 공유).
- 경기: `GyeonggiBusLocationAdapter`(신규, `buslocationservice/v2/getBusLocationListv2`, `stateCd` 2 = 출발).
- TAGO: `TagoBusLocationAdapter`(신규, `BusLcInfoInqireService/getRouteAcctoBusLcList`, 도시코드는 `TagoCityCodeLookup`).
- 노선 단위 15초 캐시 + single-flight(같은 노선 사용자끼리 공유). 포털 동시 세션 30 공유 → 어댑터당 permits.
- 일일 예산: 경기 1만은 빠듯 → `journey.track.bus.position-budget.{seoul,gyeonggi,tago}` 로 전용 상한, 넘으면 매핑을 포기(추적은 계속, 차량 없이).

### 관찰과 대조 — `BusVehicleMatcher`(신규) + `BusVehicleWatchScheduler`(**worker 프로필 전용** — 공용이면 api 부팅에서 죽는 M7 선례)
0. **승차 순번**: 경기·TAGO 버스는 `boardingSeq` 가 비어 있다(보강기는 서울 `bus_route_stop` 만 본다, `JourneyTransitIdEnricher:90-100`). #267 표(`RegionalBusRouteStopStore`)에서 `RegionalRideLocator` 와 같은 조인·회차 규칙(:35-50)으로 승차 정류장 실시간 순번을 얻는다. 서울은 기존 `boardingSeq`.
1. TRACK 여정이 `WAIT_BUS` 면 **시작 즉시부터** 그 노선을 관찰 대상으로 등록(Redis 집합, TTL) — 대기 예측 "5분 안"을 조건으로 쓰면 실시간이 없거나 할당량이 바닥날 때(`RegionalArrivalLookup` QUOTA/NO_REALTIME) 영영 관찰되지 않는다. **60초 간격**으로 보다가, 위치상 승차 정류장 2정거장 안에 차가 들어오면 **15초 간격**.
2. 관찰 스케줄러가 대상 노선 위치를 받아 **승차 정류장 출발 사건**을 기록(Redis, 노선·정류장별 최근 10분): 서울 = 승차 순번에서 `stopFlag=1`→다음 관측에서 순번 증가, 경기 = 승차 순번 `stateCd=2` 또는 순번 증가, TAGO = 순번 증가(관측 시각 = 처음 본 조회 시각). 노선 단위 single-flight, 예산은 기존 `BusApiQuotaPort.tryAcquire` 재사용.
3. 대조 요청 지점: 앱의 `WAIT_BUS → RIDE_BUS` 전환. **`JourneyCommandService.apply` 안(세션 행 잠금 중, :62-87)이나 재전송 경로(`replayIfSeen`)에서 부르지 않는다** → APPLIED 결과에만 AFTER_COMMIT 이벤트를 내고 worker 가 받아 대조. t0 = `clientOccurredAt`, 기기 시계가 의심스러우면(`SubwayBoardingEvidenceService` 의 `isClockSuspect` 규칙) 서버 수신 시각. `BusVehicleMatcher.match(t0, events)`:
   - 창 ±60초 안 출발 사건 1대 → 고정
   - 2대 이상 → t0 와 가장 가까운 것과 두 번째의 차가 45초 이상일 때만 고정
   - 0대 → 30초 뒤 한 번 더(관측 지연 흡수), 그래도 없으면 포기
4. 고정: `journey_leg`(RIDE_BUS) 에 `vehicle_id`, `vehicle_id_type`, `vehicle_source='AUTO_MATCH'`, `vehicle_matched_at` — **엔티티 저장이 아니라 대상 칼럼만 바꾸는 `@Modifying UPDATE`**. `JourneyLeg` 는 `@DynamicUpdate` 가 없어 저장할 때 모든 칼럼을 다시 쓰므로, api 의 진행·명령 저장이 worker 가 쓴 차량 칼럼을 덮을 수 있다(그 반대도).
5. 관찰 해제: 고정·포기·여정 종료 시.

### 마이그레이션 `V61__journey_leg_vehicle.sql`
```sql
ALTER TABLE journey_leg
    ADD COLUMN vehicle_id_type varchar(16),
    ADD COLUMN vehicle_source varchar(16),
    ADD COLUMN vehicle_matched_at timestamptz;
ALTER TABLE journey_leg ADD CONSTRAINT journey_leg_vehicle_id_type_check
    CHECK (vehicle_id_type IS NULL OR vehicle_id_type IN ('VEH_ID', 'PLAIN_NO'));
ALTER TABLE journey_leg ADD CONSTRAINT journey_leg_vehicle_source_check
    CHECK (vehicle_source IS NULL OR vehicle_source IN ('AUTO_MATCH'));
```
- `vehicle_id` 칼럼은 이미 있다(legacy). `JourneyLeg` 의 "legacy 미사용" 주석을 갱신.
- 재탐색이 없으니 계승은 필요 없다. `JourneyLeg` 복제 메서드(:197 `copy.vehicleId`)에 새 칼럼도 함께 복제.

### 리셋 API
`POST /api/journeys/{journeyId}/bus/vehicle/reset` → 고정을 지운다(관찰 재개는 하지 않음 — 이미 타 있다). 감사는 로그·지표로.

### 테스트
- 매처 단위: 1대·0대·2대(갈림/안 갈림)·창 밖·기기 시계 의심.
- 동시 갱신 IT: 차량 칼럼 UPDATE 후 진행 저장이 그것을 지우지 않는다.
- 재전송된 전환 명령은 대조를 다시 요청하지 않는다.
- 경기·TAGO 승차 순번을 #267 표로 얻는다(회차 노선 포함).
- 원천별 출발 사건 추출(서울 stopFlag, 경기 stateCd, TAGO 순번).
- 어댑터 파싱(경기·TAGO 실측 응답 축약: 단일 객체, `items:""`, 포털 오류 30).
- 예산 초과 → 매핑 포기·추적 계속.
- NAVIGATION 여정은 관찰 대상이 되지 않는다.

---

## PR-5 버스 안내 + HANDOFF

### 스냅샷 `busGuidance`(최상위 nullable, `subwayGuidance` 와 같은 자리 규칙)
- TRACK 버스 단계에서만.
- `WAIT_BUS`: `{routeName, arrivalSeconds, stopsAway, observedAt}` — 기존 대기 예측 재사용("곧 도착 ○○번" 안내 근거).
- `RIDE_BUS` + 차량 고정: `{vehiclePlate?, stopsToAlight, nextStopName, alightEtaAt?, observedAt, quality}` — 고정 차량의 위치(PR-4 포트, 캐시)로 계산. 고정이 없으면 앱 진행(남은 정류장 수) 기반으로 같은 칸을 채우고 `quality=APPROXIMATE`.
- **계산 위치**: `snapshotOf` 는 시작·명령 트랜잭션 안에서도 불린다(`JourneyStartTxService:112`, `JourneyCommandService:86`) → 외부 호출 금지. `JourneySnapshotEtaComposer` 에서 **캐시만 읽어** 조립(위치 캐시는 PR-4 관찰 스케줄러가 채운다).
- `JourneySnapshot.with*` 재조립 메서드가 `busGuidance` 를 옮기는지 시험.

### HANDOFF `docs/plan/standalone-track/HANDOFF.md`
- 선택 흐름: 정류장 → `/api/track/stops/{id}/patterns` → `/api/track/patterns/{code}/stops` → `POST /api/journeys/track`.
- 지하철 역 도착(앱 지오펜스/도보 종료) → `train-candidates` → 로컬 알림·LA 버튼 → `train-selection`. 무응답이면 아무것도 안 보냄(M7 자동).
- 버스: 정류장 도착 시 안내만, "이 버스 아님" → `bus/vehicle/reset`.
- LA: 1차 폴링. **`/active` 에는 ETag 가 없고(`JourneyController:70-76`), `/{id}` 는 버전이 같으면 204 를 준다(:93-96)** — 버스 안내만 바뀌면 204 에 묻힌다. 그래서 버스 안내가 바뀌면 평가 버전을 올리거나(`evaluationVersion`), 폴링은 `/active` 로 한다 — 구현 시 둘 중 하나로 정하고 HANDOFF 에 적는다. push 없음. 기존 지하철 링 push 계약은 그대로.
- `kind=TRACK` 이면 재탐색 UI(말풍선·제안) 숨김.

### 테스트
- `busGuidance` 조립(대기/탑승·고정/미고정), TRACK 이 아니면 null.

---

## 3. 횡단

- **플래그**: `journey.track.enabled`, `journey.subway.selection.enabled`, `journey.track.bus.{watch-lead-seconds=300, match-window-seconds=60, ambiguity-gap-seconds=45, retry-after-seconds=30, position-budget.*}` — 전부 기본 꺼짐/보수값.
- **지표**: `track.start{mode,result}`, `subway.selection{result}`, `bus.match{provider,result}`(matched/none/ambiguous/budget), `bus.position{provider,result}`. 태그에 id 금지.
- **구현 노트**: PR 마다 `implementation_note.md` 에 `### 확정/함정/좌표/검증/배포`.
- **IT 환경**: 스크래치 clone 에는 `env/*.env` 가 없다 → 일회용 postgis(로컬 개발 DB `--schema-only` + `flyway_schema_history` 복원) + redis + 더미 env(`GONG_API`·`KAKAO_REST_API_KEY`·`SEOUL_*`). `env/*.env` 통째 source 금지(`fcm.env` 가 FCM 을 켜 부팅이 죽는다). 기존 무관 실패: `JourneyEvalReadPathIT`·`JourneyEvaluationIT`·`JourneyTransferAssessmentIT`(+ 가끔 `BusAlertWakeupBurstIT`).

## 4. 남은 위험 (수용)

- 지하철 선택 = 즉시 확정이라, 붐벼서 다음 열차를 타면 사용자가 리셋하기 전까지 링이 틀린 열차를 따라간다.
- 버스 2대가 붙어 오면(벌크) 고정을 포기한다 — 그 탑승은 차량 없이 추적.
- TAGO 출발 시각은 "처음 본 조회 시각"이라 폴링 주기(15초)만큼 늦다 — 창 60초로 흡수.
- 버스 안내는 앱 폴링이라 앱이 꺼져 있으면 Live Activity 가 멈춘다.
- 경기 위치 일 1만 — TRACK 사용량이 늘면 운영계정 증액 필요.

## 5. 리뷰에서 정한 답 (2026-10-07, 리뷰 에이전트)

1. **이동 전 CONFIRMED 고정**: 그대로는 안 맞는다. 종료·갈아타기·만료 경로는 PENDING/PROVISIONAL 만 건드려(Repository:230-271) CONFIRMED 행이 남지만, 링은 `s.status='ACTIVE'` 로 걸러 무해. 필요한 것 = 식별 4칸 팩토리 + 링 게이트 + 리셋·sweep 수정 + 계승 출처 보존(PR-3 에 반영).
2. **버스 관찰 시작**: TRACK 시작 즉시(대기 단계). 60초 → 근접 시 15초. 노선 단위 공유 + 예산(PR-4 에 반영).
3. **소요 근사**: GTFS 는 모든 trip 에 stop_times 가 필수이고 OTP 가 빈 시각을 보간한다 → 지하철도 있다. 근사 대신 실패 + 지표(PR-2 에 반영).
4. **lease**: 그대로(180초 + 유예 120초). 만료 지표에 `kind` 태그.
