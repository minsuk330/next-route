# route-option 후보를 노선별 실주행 소요로 재평가 (national-otp P2-F PR4c)

티어: **L** (새 OTP 쿼리 + 신규 port/adapter + EtaCalculator 계약 확장 + provider 재배선 + 실제 제안 게이트). codex 논의 전제. fresh main 독립 브랜치([[feedback-per-task-pr]]).

## 결론 먼저
주행 중 재탐색 ③(같은 정류장 다른 노선)이 "이 버스 타면 목적지 언제 도착"을 계산할 때, **타고 가는 시간(승차→하차)을 원래 타려던 노선 값으로** 쓴다. 다른 버스는 노선이 달라 실제 소요가 다른데 그 차이를 잃는다. 이 소요만 **노선별 OTP 실값**으로 바꾼다. 나머지 계산은 그대로 둔다.

## 왜 이 조각이 필요한가
PR-4(=PR #193)가 OTP pattern 에서 후보 노선을 넓혔다. 하지만 후보끼리 우열을 재는 ETA 가 노선별 실주행을 반영하지 못해, 후보를 늘려도 "어느 게 더 빠른지"가 부정확하다(codex 리뷰 B2). 그래서 **PR4c 전에는 OTP 후보 실제 제안 ON 금지**였다. PR4c 가 이 정확도를 채워야 shadow OFF(실제 제안)가 가능해진다.

## 핵심 관찰 (설계 근거)
- 목적지까지 = `[승차] → [그 버스로 하차정류장] → [하차 후 나머지 leg]`.
- **하차 후 나머지 leg 은 baseline·모든 후보가 동일**(같은 정류장 하차) → 개선폭 뺄셈에서 상쇄된다(현 `RouteOptionEtaCalculator` R1 주석이 이미 인정).
- 따라서 후보마다 실제로 다른 값은 **board→alight 소요 하나뿐**. 그 하나만 노선별 실값으로 바꾸면 개선폭이 정확해진다.
- **B안(RouteTimelineComposer 통짜 재평가) 기각**: composer 는 모든 leg 에 절대시각(OTP planConnection)을 전제한다. 하차 후 leg 은 절대시각이 없고 다음 환승 대기시간을 모른다 → composer 에 넣으려면 절대시각을 위조해야 하고 "이후 대기 모름" 문제가 되살아난다. 실시간 주행보정 이득도 작다(predict() 가 이미 승차시각을 실시간으로 준다). → A안(EtaCalculator RIDE 소요만 교체) 채택.

## 확정 계약 (codex 리뷰 반영 2026-09-10)
- **데이터 출처 = OTP `Trip.stoptimes`(시간표)**: 스키마 실측(schema.graphqls: `type Stoptime` 2470 의 `scheduledArrival`/`scheduledDeparture`(초, serviceDay 기준, **실시간 실주행 아님 — 시간표값**), `type Trip` 의 `stoptimes` 88, root `trip(id)` 1941, `Stoptime.trip` 2543). `rideSec = alight.scheduledArrival − board.scheduledDeparture`. serviceDay 무관 delta. pattern 별 소요는 trip 간 거의 일정 → 대표 trip 하나. **명칭 정직화**: 필드·문서에 "시간표 소요(scheduled)"로 표기, 실주행/실시간으로 오인 금지.
- **공정성 게이트 `rideDurationComparable`(codex ②, 강화)**: baseline·후보가 **둘 다 OTP rideSec OK + 양수 delta**일 때만 그 후보를 **실제 제안으로 발행**한다. 한쪽이라도 폴백(미매칭·timeout·off)이면 그 후보는 승격하지 않고 **shadow 지표로만** 남긴다(개선폭 계산·로그는 종전대로, 불공정 비교를 제안으로 내보내지 않음). 화면 `confidence`/`sourceTag` 재사용 금지 — 그건 이후환승 누락·예측출처(REALTIME/ML) 뜻이라 의미 오염(`RouteOptionCandidateProvider.java:332,:381`).
- **override 대상 leg = 대기 직후 정확한 `RIDE_BUS`(codex ②)**: waitLeg 다음이 `RIDE_BUS`일 때만 그 leg 소요를 override. "첫 non-wait leg" 탐색 금지 — WALK 를 잘못 대체할 여지. RIDE_BUS 가 없거나 다른 타입이면 그 후보 **폐기**(override 불가=승격 불가).
- **baseline 의 OTP 식별 (codex ①)**: 저장 `journey_leg_route_option.sourceRouteId` 는 **OTP gtfsId 가 아니다**(`JourneyCompiler.java:257` 가 lane busLocalBlID 저장, `JourneyRouteOptionDraft.java:12` 정의; OTP 동적 후보만 `pattern.routeGtfsId` 채움 `OtpPatternRouteOptionSource.java:152`). `source_route_id` 는 출처 구분 없는 범용 칼럼(`V35__create_journey.sql:90`). → **MVP: baseline 의 OTP route gtfsId 를 board 정류장 stopPatterns 재조회 + resolveBus 매핑으로 되찾는다**(후보와 동일 파이프 — 저장 컬럼에 의존 안 함). 되찾기 실패면 baseline rideSec 폴백 → 그 대기 leg 전체가 shadow(승격 없음). **온전 운영(별건 후속)**: `otp_route_gtfs_id`+출처 컬럼 마이그레이션으로 저장 시 보존.
- **④ RemainingPath 무변경**: EtaCalculator 의 `compute(List<JourneyLegDraft>,...)` 경로는 rideSecOverride 를 쓰지 않는다(기본 null=기존 합산). ③ 저장경로만 override 채움 → ④ 회귀 0.
- **게이트 조합**: 실제 제안 ON = PR-4 게이트(`journey.reroute.route-option.otp-pattern-enabled`) + 마스터 `journey.reroute.enabled` + 후보별 `rideDurationComparable`. PR4c 는 별도 새 플래그 없이 이 comparable 게이트로 승격을 가른다.

## 변경 파일

### 신규 — OTP 노선 소요 조회
- `application/route/routing/RouteRideDurationPort.java` (interface):
  ```java
  public interface RouteRideDurationPort {
      /** 이 노선이 board→alight 를 타고 가는 데 걸리는 초. 미매칭·장애면 empty(예외 금지). */
      RideDurationResult rideDurationSeconds(String routeGtfsId, String boardGtfsId,
                                             String alightGtfsId, RoutingDeadline deadline);
  }
  ```
  - 결과 타입 `RideDurationResult(Status{OK,NOT_FOUND,TIMEOUT,ERROR}, Integer seconds)` — 정상 미매칭 vs 장애 구분(StopPatternResult 선례와 동일 철학).
- `resources/otp/routeRideDuration.graphql`: board 정류장의 pattern 중 routeGtfsId 매칭 → 대표 trip 의 stoptimes(정차별 gtfsId + scheduledArrival/scheduledDeparture). 후보 안: 기존 `stopPatterns.graphql` 확장 대신 **전용 쿼리**(PR4c 독립성). stable 필드만.
  - **쿼리 2단(codex ③, `pattern.trips` 확장 금지 — limit 없음 `schema.graphqls:1067`)**:
    1. board 정류장 stopPatterns 조회(PR-4 것 재사용 or 확장)에서 각 pattern 의 **대표 출발편 `trip.gtfsId`** 를 얻는다(`stoptimesForPatterns` 이미 pattern 당 1 출발편, `stopPatterns.graphql:14`; `Stoptime.trip` `schema.graphqls:2543`). route gtfsId 매칭으로 후보/baseline 노선의 tripGtfsId 확정.
    2. 선택된 **≤11개 tripGtfsId 만** root `trip(id)`(`schema.graphqls:1941`) 로 **alias 일괄(batch) 조회** → 각 trip.stoptimes 에서 board/alight gtfsId delta.
    ```graphql
    query RouteRideDurations {
      t0: trip(id: "...") { gtfsId stoptimes { stop { gtfsId } scheduledArrival scheduledDeparture } }
      t1: trip(id: "...") { ... }
      # ... 최대 baseline1+대안10
    }
    ```
    → 응답 크기는 fixture 로 측정 후 numberOfDepartures/limit 확정.
- `infrastructure/adapter/out/api/otp/OtpRouteRideDurationAdapter.java`: OtpStopPatternAdapter 미러(RestClient.Builder clone + per-call readTimeout=deadline, GRAPHQL_PATH, sendOnce/OtpCallException). 파싱=`OtpRouteRideDurationMapper`(@Component 순수). 실패→ERROR/TIMEOUT, 미매칭→NOT_FOUND.

### 변경 — EtaCalculator 계약 확장
- `application/journey/service/RouteOptionEtaCalculator.java`:
  - `compute(List<JourneyLeg> legs, JourneyLeg waitLeg, Instant boardingAt, Integer rideSecOverride)` 오버로드 추가(기존 3-arg 는 override=null 위임 → 하위호환).
  - `computeFrom` 에 `Integer rideSecOverride, int rideLegIndex` 전달: `segment.legIndex() == rideLegIndex`(=waitLeg.legIndex+1, 즉 승차 직후 RIDE leg)이고 override != null 이면 그 leg 소요를 override 로 대체, 아니면 기존 plannedDurationSec. **하나의 합산 규칙 유지** — override 지점만 분기.
  - rideLegIndex = 대기 leg 다음 RIDE_* leg 의 legIndex. `computeFrom` 이 이미 fromLegIndexExclusive(=waitLeg.legIndex) 뒤부터 도므로, 그 첫 비대기 RIDE leg 을 rideLegIndex 로 잡는다.

### 변경 — provider 재배선
- `application/journey/service/RouteOptionCandidateProvider.java`:
  - 새 의존성 `RouteRideDurationPort rideDurationPort`.
  - `evaluate(...)` 와 `evaluateBaseline(...)` 가 predict 후 rideSec 을 조회해 `etaCalculator.compute(legs, waitLeg, boardingAt, rideSecOverride)` 에 전달.
    - rideSec 조회 입력: `waitLeg.getSourceStartStopId()`(board), `waitLeg.getSourceEndStopId()`(alight), 노선 OTP id. **노선 OTP id 출처**: 저장 후보는 `option.getSourceRouteId()`(OTP route gtfsId, PR-4 가 채움) — 저장 후보에 sourceRouteId 가 OTP 원천이 아닐 수 있어(과거 ODsay 원천) 구현 시 확인; 없으면 rideSec 조회 skip=폴백.
    - baseline 도 동일 조회(공정성).
  - rideSec 조회는 **deadline 예산 안**: 남은 evaluationBudgetMs 를 RoutingDeadline 으로 넘긴다. baseline 1 + 후보 N 회 조회가 예산 초과하면 조회 실패=폴백.
  - `toCandidate`: rideSec 반영 여부를 신뢰도/게이트에 반영(실주행 미반영 폴백 후보는 실제 제안 대상에서 제외 — display quality 또는 게이트 필드로 표시, codex 와 확정).

## 작업 순서 (체크박스, 의존성)
- [ ] PR4c-1: RouteRideDurationPort + RideDurationResult(OK/NOT_FOUND/TIMEOUT/ERROR) + OtpRouteRideDurationMapper + routeRideDuration.graphql(2단: 대표 trip.gtfsId → trip(id) ≤11 alias batch) + fixture. **미배선**(회귀0). Mapper 단위테스트(delta / 미매칭 NOT_FOUND / board·alight 순서). OTP HTTP 테스트(OK/미매칭/5xx/예산0). 응답크기 fixture 측정.
- [ ] PR4c-2: RouteOptionEtaCalculator override 오버로드(rideSecOverride+rideLegIndex). **대기 직후 RIDE_BUS 만 override**(첫 non-wait 금지, 다른 타입=후보 폐기). 기존 테스트 무회귀 + 교체/null 하위호환 테스트.
- [ ] PR4c-3: provider 재배선 — baseline OTP gtfsId 되찾기(board stopPatterns+resolveBus) + baseline·후보 rideSec 조회(**남은 deadline 공유**, 예산 이중소비 방지) + **rideDurationComparable 게이트**(양측 OK+양수 delta 일 때만 승격, 아니면 shadow). RouteOptionCandidateProviderTest 무회귀 + 승격/shadow폴백/공정성/RIDE_BUS 없음 폐기 테스트.
- [ ] 예산 계약: predict+duration 총 대기가 evaluationBudgetMs 넘지 않게(단계별 남은 deadline 공유). 계약 테스트로 가드.
- [ ] 각 단계 fresh main 독립 브랜치 or 단일 PR(구현 세션에서 분할 판단).

## 검증
- 컴파일 `./gradlew compileJava compileTestJava`.
- 신규: OtpRouteRideDurationMapper(delta/미매칭), 어댑터 HTTP(상태 분류), EtaCalculator override(교체/하위호환), provider(rideSec 반영 시 개선폭 변화 / 폴백 시 종전 / baseline·후보 동일 방식).
- 무회귀: RouteOptionCandidateProviderTest(현 21), RemainingPathCandidateProviderTest(④ 무변경), RouteTimelineService/Composer(안 건드림). RerouteAcceptIT 컨텍스트 부팅.
- 계약당 테스트 1개(경계 리뷰 대상 = RouteRideDurationPort DTO, EtaCalculator override 분기, provider 공정성).

## 마이그레이션
없음.

## 예산 계약 (codex ③ — 이중소비 주의)
현행 계약: 요청 최악 대기 = `predict-deadline(baseline 1회) + evaluationBudgetMs(대안 누적)`. baseline predict 는 누적예산에서 제외(`JourneyRerouteProperties.java:136`). rideSec 조회를 **같은 budgetMs 로 별도 추가하면 predict+duration 이중소비** → 예산 계약 깨짐. 해결(구현 시 확정, codex 지적): **단계별 남은 deadline 을 공유**(RoutingDeadline 하나로 predict+duration 이 남은 예산 나눠 씀) 또는 예산 계약을 "요청당 총 deadline" 으로 명시 변경. 어느 쪽이든 총 대기가 예산을 넘지 않아야.

## 캐시 (codex ③)
rideSec 캐시 키 = `graphVersion + tripGtfsId + serviceDate + boardGtfsId + alightGtfsId`. graphVersion 은 `OtpRoutingProperties.java:22` 제공. OTP off 라 프로덕션 영향 0 — 캐시는 on 이후 부하 관측 시. 시간표값이라 TTL 길게 가능(serviceDate 로 이미 분리).

## 미결/리스크 (구현 중 확정)
- **baseline OTP 되찾기 커버리지**: baseline 서울 routeId → board stopPatterns 에서 resolveBus 매핑되는 pattern 재확정. 매핑 실패율(=승격 불가율) 실측 필요. 낮으면 온전 운영에 `otp_route_gtfs_id` 마이그레이션 검토(별건).
- **PR-4(#193) 머지 의존**: 코드상 독립(stored 후보에도 override 파이프 적용) but 실제 승격은 baseline+후보 둘 다 OTP OK 필요 → PR-4 머지 후 실효 커짐. PR4c 는 fresh main 독립 브랜치.
- **응답 크기**: trip(id) batch ≤11 의 stoptimes 총량 fixture 측정 후 한도 확정.

## 관련
[[project-national-otp-migration]] [[feedback-server-static-search-front-realtime]] [[feedback-per-task-pr]] [[feedback-implementation-note]]. 선행=PR-4(#193). 이후=shadow OFF(실제 제안 ON) 배포 결정.
