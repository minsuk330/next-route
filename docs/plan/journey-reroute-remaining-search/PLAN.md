# Phase 6 ④ — 남은 구간 재검색 (`journey-reroute-remaining-search`)

- 설계 근거: [DESIGN.md](./DESIGN.md) — 결정의 *이유*는 전부 그쪽에 있다. 이 문서는 *무엇을 어떻게*만 적는다
- 상위: [PHASE-6.md §④](../journey-navigation/PHASE-6.md) · 선행 PR: ①(#135) ②(#133) ③(#136) 모두 머지됨
- 마이그레이션: **없음**
- 작성일: 2026-08-12

## PR 두 개로 나눈다

| | 슬러그 | 티어 | 내용 | 선행 |
|---|---|---|---|---|
| **PR-1** | `feat/journey-boarding-time-estimator` | **M** | `BoardingTimeEstimator` 포트 + 버스·지하철 구현 + **기존 예측기 둘이 그것에 위임** | — |
| **PR-2** | `feat/journey-reroute-remaining-search` | **L** | ④의 나머지 전부(공급자·검색·캐시·ETA 합성) | PR-1 |

**PR-1 에 위임 리팩터링을 함께 넣는 이유** — 포트와 구현만 떼면 **소비자가 하나도 없는 죽은 코드**가 머지된다. 기존 예측기(`BusTransferTargetArrivalPredictor`·`SubwayTransferTargetArrivalPredictor`)가 새 자를 쓰게 하면 소비자가 생기고, 무엇보다 **새 자가 기존 판정과 같은 답을 내는지 기존 시험이 증명한다**. ④가 그 위에 올라갈 때 "재탐색 경로만 다르게 계산한다"는 의심이 사라진다.

PR-1 은 **동작 불변**이다. `TransferTargetArrivalPredictorTest` 가 한 줄도 안 바뀐 채 통과해야 한다 —
다만 그것은 **필요조건일 뿐 충분조건이 아니다.** 그 시험은 `boardable=true` 만 다루므로 시험 두 개를
먼저 추가해 현재 동작을 못 박는다(§3.3-b). 특히 **추정기가 `boardable` 로 걸러내면 환승 놓침(`MISSED`)
감지가 죽는다**(§3.1-b) — 이 PR 에서 가장 조심할 한 곳이다.

**③의 `RouteOptionCandidateProvider` 는 이관하지 않는다.** 지금 `PredictTransferUseCase` 를 직접 부르고 잘 돌며 시험도 있다. 동작 불변 리팩터링을 두 PR 에 걸쳐 늘리지 않는다 — 후속 정리 과제로 남긴다.

## 0. 이 PR 이 만드는 것

③(같은 정류장 다른 버스)이 빈손일 때, **환승 정류장에서 목적지까지 다른 경로**를 찾아 제안으로 저장한다. 지도 앱에서 길찾기를 다시 누르는 것이고, 출발지는 "지금 내 위치"가 아니라 **곧 내릴 환승 정류장**이다.

②가 만든 저장소·API 와 ③이 만든 적격성 판정·cooldown 은 이미 있다. **`REMAINING_PATH` 종류의 후보를 만들어 주는 코드만 없다.** ④가 그 자리를 채운다.

## 1. 확정 결정 (요약 — 근거는 DESIGN.md §1)

| # | 결정 |
|---|---|
| D1 | 검색 시작점은 **환승 정류장 고정**. 좌표는 개정본 `routeSnapshot` 을 `JourneyCompiler` 로 다시 컴파일해 `legIndex` 로 찾는다 |
| D2 | ODsay 응답을 **Redis 캐싱**. 여정과 무관한 공유 키, TTL 10분 |
| D3 | 기준선을 못 잰 경우 임계값 없이 **목적지 ETA 가 가장 이른 하나만**. `etaGainSec = 0`, `confidence = APPROXIMATE` 강제 |
| D4 | **새 경로의 첫 대기만** 예측한다. 이후 대기는 0으로 두고 신뢰도를 `APPROXIMATE` 로 낮춘다 |
| D5 | **지하철도 후보로 인정**. 단 실시간만 본다(모델·시간표 없음) |
| D6 | **`BoardingTimeEstimator` 포트 신규.** DB 를 읽지 않고 값만 받아 승차 시각을 추정 |
| D7 | **기준선은 ④가 스스로 다시 잰다.** 공급자 사이 순서 의존을 만들지 않는다 |
| D8 | **걷기만으로 목적지에 닿는 경로도 인정** |
| D9 | 평가 경로 상한 **2개**, 최소 개선폭 **300초**(③과 같은 값, **키는 별도**) |

## 2. 착수 시점의 실제 코드 상태 (2026-08-12 확인)

| 부품 | 상태 |
|---|---|
| `RerouteCandidateProvider` 구현체 | **1개**(`RouteOptionCandidateProvider`, `priority()=0`). `REMAINING_PATH` 는 없다 |
| `RerouteProposalService` 공급자 순회 | 완성. `priority` 순으로 **첫 비어있지 않은 공급자만 채택**한다(`RerouteProposalService.java:121-131`) |
| `RerouteEligibilityPolicy` | ③이 실구현(`WindowCooldownEligibilityPolicy`). 손대지 않는다 |
| `RerouteCooldownPort` | `Scope{TRIGGER,REQUEST}` 로 갈려 있다. 손대지 않는다 |
| `RerouteProposalTxService.store` | `Optional` 반환 + 여정 행 잠금 재확인. 손대지 않는다 |
| `RerouteCandidateApply.RemainingPathApply` | ②가 만든 계약이 이미 있다(`RerouteCandidateApply.java:45-53`) |
| `TransferPredictionInterpreter` | ③ 신규. `modeFor`·`budgetMs`·`sourceOf`·`qualityOf`·`isBoardable`·`isBlocked` |
| `RouteOptionEtaCalculator` | ③ 신규. `compute(legs, waitLeg, boardingAt)` → `Optional<Result>` |
| `JourneyCompiler.compile(path)` | `JourneyPlan(legs, transfers)` 반환. `JourneyLegDraft` 에 `legIndex` + `startX/startY/endX/endY` 있음 |
| `JourneyRevisionRepository` | `findByJourneyIdAndStatusAndDeletedAtIsNull(journeyId, ACTIVE)` — 개정본 1도 시작 때 행이 생긴다(`JourneyStartTxService.java:95`) |
| `OdSayApiPort` | `searchPath(sx, sy, ex, ey, timeoutMs)` — **좌표만, 미래 출발시각 불가**(`OdSayApiPort.java:16`) |
| `SingleFlight` | `execute(key, loader, joinWaitMs)` → `Outcome<V>(value, Role)`(`SingleFlight.java:50`) |
| `SubwayRealtimeArrivalReader` | `arrivalsByStation(lineId, stationId, stationName, wayCode)` → `List<Arrival>`(`:70`), `Arrival.hasSeconds()`(`:185`) |
| `PredictTransferUseCase` | `predict(stopId, routeId, seq, userArrivalAt, mode, budgetMs)`(`:44`) |
| `JourneyLeg` 좌표 컬럼 | **없다.** 그래서 D1 이 재컴파일로 좌표를 얻는다 |

### 참고할 형제 코드

- **공급자 구조·예산·부분 실패 처리**: `RouteOptionCandidateProvider.java` (③). 같은 모양으로 짠다
- **캐시 어댑터 관례**: `RedisRerouteCooldownAdapter.java`, `RedisRouteSearchSnapshotAdapter.java`
- **통합 시험 관례**: `RerouteRouteOptionIT.java` (③), `JourneyTransferAssessmentIT.java`
- **좌표 재컴파일의 정당성**: `JourneyCompiler.compileDetailed` javadoc — "좌표를 찾을 때마다 이름·좌표로 거꾸로 맞히는 것보다 같은 쪼개기를 한 번 더 돌려 순번을 받는 편이 어긋날 여지가 없다. 쪼개기는 같은 입력에 같은 결과를 준다"

## 3. 신규 파일

§3.1~3.3 은 **PR-1**, §3.4~3.11 은 **PR-2** 다.

### 3.1 `application/journey/port/out/BoardingTimeEstimator.java` — PR-1

```java
public interface BoardingTimeEstimator {

    boolean supports(JourneyLegType waitType);

    /**
     * @param anchorAt 사용자가 그 정류장에서 탈 준비를 마치는 시각. now 로 대신하지 않는다
     * @return 탈 수 있는 차가 없거나 근거가 없으면 값 없는 결과.
     *         "막혀서 못 물어봤다"와 "물어봤지만 근거가 없다"는 {@code blocked} 로 가른다(§3.3-b)
     *
     * <p><b>예외를 삼키지 않는다.</b> 외부 호출이 터지면 그대로 올린다 — 지금 두 예측기가
     * 그러고 있고(버스 {@code :75-81}, 지하철 {@code :69-70}), 여기서 삼키면 위임하는 순간
     * 평가 경로의 동작이 바뀐다. 재탐색 쪽은 후보마다 {@code try/catch} 로 감싼다(§3.9-7)
     */
    BoardingOutcome estimate(BoardingQuery query, Instant anchorAt, Instant now, long budgetMs);
}
```

`BoardingOutcome` 의 정의는 §3.3-b 에 있다. **`Optional<BoardingEstimate>` 로 두면 `blocked` 구분이
사라져 기존 버스 예측기가 회귀한다.**

`application/journey/dto/BoardingQuery.java`
```java
public record BoardingQuery(JourneyLegType waitType, String stopId, String stopName,
                            String routeId, Integer boardingSeq, Integer wayCode) {
}
```

`application/journey/dto/BoardingEstimate.java`
```java
/**
 * @param boardable  사용자가 준비를 마치는 시각 이후에 오는 차인가.
 *                   <b>추정기는 이 값으로 걸러내지 않는다</b> — 소비자마다 뜻이 다르다(§3.1-b)
 * @param source     REALTIME_ARRIVAL / MODEL_ARRIVAL
 * @param quality    GOOD / APPROXIMATE
 * @param observedAt 근거가 만들어진 시각. 화면 신선도 표시에 쓴다
 */
public record BoardingEstimate(Instant boardingAt, boolean boardable,
                               CurrentLegArrival.Source source, CurrentLegArrival.Quality quality,
                               Instant observedAt, String modelVersion) {
}
```

### 3.1-b `boardable` 로 걸러내는 것은 **추정기가 아니라 소비자**다

이 규칙을 어기면 **환승 놓침 감지가 죽는다.** 두 소비자가 같은 값을 정반대로 쓴다.

| 소비자 | 못 타는 예측(`boardable=false`)을 어떻게 쓰나 |
|---|---|
| **여정 평가**(`BusTransferTargetArrivalPredictor`) | **그대로 통과시킨다.** 그 시각이 `TransferRiskEvaluator:128` 로 흘러가 `slack = 예측도착 − 준비시각` 이 음수가 되고, 그게 `MISSED` 판정의 근거다. 걸러내면 `slack` 이 `null` 이 되어 상태가 `UNKNOWN` 으로 떨어진다 — **"차를 놓쳤다"가 "모르겠다"로 바뀐다** |
| **재탐색 후보**(③④) | **버린다.** 지나간 차를 "이걸 타면 5분 빠릅니다"로 제안할 수 없다. 사유는 `unboardable` |

지금 코드가 정확히 그렇게 갈려 있다 — `BusTransferTargetArrivalPredictor.java:83` 은 `predictedArrivalAt == null` 만 보고 `boardable` 을 **안 본다**. `RouteOptionCandidateProvider.java:256` 은 `isBoardable` 로 버린다.

**따라서 `BusBoardingTimeEstimator` 는 `isBoardable` 로 걸러내면 안 된다.** 걸러내면 위임하는 순간 평가 경로의 동작이 바뀐다.

### 3.2 `application/journey/service/BusBoardingTimeEstimator.java` — PR-1

현재 `BusTransferTargetArrivalPredictor.java:60-100` 의 판정을 **그대로** 옮긴다. 더하지도 빼지도 않는다.

- `supports(WAIT_BUS)`
- **`stopId` 가 null 이면 외부 호출 없이 `none()`** — 지금 예측기 `:67-69` 가 그렇게 한다.
  이 검사를 빠뜨리면 미해석 정류장으로 외부 API 를 부른다
- `routeId` 비었거나 `boardingSeq == null` → `BoardingOutcome.none()` (역시 호출 전에)
- `PredictTransferUseCase.predict(stopId, routeId, boardingSeq, anchorAt, interpreter.modeFor(anchorAt, now), budgetMs)`
- `prediction.predictedArrivalAt() == null` 이면 **여기서 갈린다** (지금 예측기 `:83-89` 와 같게):
  - `interpreter.isBlocked(prediction)` → `BoardingOutcome.blocked()`
  - 아니면 → `BoardingOutcome.none()`
- 시각이 있으면 `BoardingOutcome.of(...)`. **`boardable` 로 걸러내지 않는다**(§3.1-b) — `prediction.boardable()` 을 `BoardingEstimate.boardable` 에 그대로 싣는다
  (`Boolean` 이 null 일 수 있으므로 `Boolean.TRUE.equals(...)` 로 좁힌다)
- `source`/`quality` 는 `interpreter.sourceOf`/`qualityOf`, `observedAt` 은 `prediction.calculatedAt()`, `modelVersion` 은 `prediction.modelVersion()`

### 3.3 `application/journey/service/SubwayBoardingTimeEstimator.java` — PR-1

- `supports(WAIT_SUBWAY)`
- **`routeId` 나 `stopId` 가 null 이면 외부 호출 없이 `none()`** — 지금 예측기 `:58-66` 이 그렇게 한다
- `wayCode == null` → `none()` (**방향을 모르면 반대 방향 열차를 집는다**)
- `arrivalReader.arrivalsByStation(routeId, stopId, stopName, wayCode)` → `hasSeconds()` 인 것만 → `now + arrivalSeconds` 중 `anchorAt` 이후 **가장 이른 것**
- 없으면 `BoardingOutcome.none()`. **모델·시간표로 대체하지 않는다**(SC-6)
- `blocked` 는 **항상 false** — 지하철 실시간 조회에는 호출량 제한이 없어 "막힘" 개념이 없다
- `boardable` 은 **항상 true** — 리더가 이미 `anchorAt` 이후 열차만 고르므로, 값이 있다는 것이 곧 탈 수 있다는 뜻이다
- `source = REALTIME_ARRIVAL`, `quality = GOOD`, `observedAt = now`, `modelVersion = null`
- 기존 `SubwayTransferTargetArrivalPredictor.predictBoarding` 의 판정과 **같은 규칙**이어야 한다(`:52-95`)

### 3.3-b 기존 예측기 위임 (PR-1 의 핵심)

두 예측기는 계속 `TransferTargetArrivalPredictor` 를 구현한다(여정 평가가 쓰는 포트다). 바뀌는 것은
**내부에서 직접 계산하던 부분을 새 자에 넘기는 것**뿐이다.

```
BusTransferTargetArrivalPredictor.predictBoarding(waitLeg, anchor, now)
  ├ 앵커 null → unknown                                   (그대로)
  ├ selectedRouteOptionId → routeOptionRepository.findById (그대로 — DB 읽기는 여기 남는다)
  ├ BoardingQuery 로 옮긴다                                 (신규)
  ├ busBoardingTimeEstimator.estimate(query, anchor, now, interpreter.budgetMs())
  └ 결과를 TransferTargetArrival 로 옮긴다
```

**버스 예측기는 `boardable` 을 보지 않는다.** 위임하면서 그 필터를 추가하면 안 된다(§3.1-b).

**주의 — `blocked` 와 `unknown` 의 구분이 사라지면 안 된다.** 지금 버스 예측기는 예측이 없을 때
`interpreter.isBlocked(prediction)` 으로 "막혀서 못 물어봤다"(`blocked`)와 "물어봤지만 근거가 없다"
(`unknown`)를 가른다. 막힌 상황에서 30초마다 다시 부르면 막힌 문을 계속 두드리기 때문이다.
`BoardingEstimate` 는 그 구분을 담지 않으므로 **`Optional.empty()` 하나로 뭉치면 회귀다.**

해결: `estimate` 의 반환형을 `Optional<BoardingEstimate>` 대신 아래로 둔다.

```java
/** @param blocked 물어보는 것 자체가 막혔는가. 막힌 문을 반복해 두드리지 않으려고 가른다 */
public record BoardingOutcome(BoardingEstimate estimate, boolean blocked) {
    public boolean isPresent() { return estimate != null; }
    static BoardingOutcome none()      { return new BoardingOutcome(null, false); }
    static BoardingOutcome blocked()   { return new BoardingOutcome(null, true); }
    static BoardingOutcome of(BoardingEstimate e) { return new BoardingOutcome(e, false); }
}
```

지하철 구현은 `blocked` 를 항상 `false` 로 둔다(실시간 조회에 호출량 제한이 없어 "막힘" 개념이 없다).
④는 `blocked` 를 보지 않고 `isPresent()` 와 `boardable` 을 본다.

#### 검증 — 기존 시험만으로는 부족하다

```bash
./gradlew test --tests '*TransferTargetArrivalPredictorTest'
```

무수정 통과가 **필요조건이지만 충분조건이 아니다.** 그 시험의 `givenPrediction` 헬퍼가
`boardable` 을 **항상 `true` 로 하드코딩**하기 때문에(`TransferTargetArrivalPredictorTest.java:213`)
`boardable=false` 를 걸러내는 회귀를 **한 건도 잡지 못한다.**

그래서 **PR-1 에 시험 두 개를 추가한다.** 위임 전에 먼저 추가해 현재 동작을 못 박고, 위임 후에도
통과해야 한다.

- [ ] `boardable=false` + 시각 있음 → 예측기가 **그 시각을 그대로 돌려준다**(모름이 아니다).
      이 시험이 없으면 `MISSED` 감지가 죽는 회귀를 아무도 못 잡는다
- [ ] `predictedArrivalAt == null` + `status=BLOCKED` → `upstreamBlocked()` 가 `true`.
      (`status=NO_VEHICLE` 는 `false` — 기존 시험에 이미 있다)
- [ ] **`stopId == null`(버스) / `routeId`·`stopId == null`(지하철) 이면 외부 포트를 한 번도 부르지 않는다.**
      `verifyNoInteractions` 로 못 박는다 — 사전조건이 약해지면 미해석 정류장으로 유료 호출이 나간다
- [ ] **외부 호출이 예외를 던지면 예측기도 예외를 올린다**(삼키지 않는다). 지금 동작이다

### 3.4 `application/journey/service/JourneyWaypointResolver.java` — PR-2

```java
/** 개정본 스냅샷을 다시 쪼개 legIndex 로 좌표를 찾는다. journey_leg 에 좌표 칸이 없어서다. */
@Component
public class JourneyWaypointResolver {

    /**
     * @param routeRevision <b>{@code context.routeRevision()} 을 넘긴다.</b> "지금 ACTIVE 인 개정본"을
     *                      읽으면, 후보를 만드는 사이 수락으로 개정본이 오른 경우 <b>옛 환승·단계와
     *                      새 스냅샷 좌표가 섞인다</b> — 저장 단계의 잠금 재확인은 DB 쓰기만 막고
     *                      이미 나간 유료 호출과 캐시 오염은 되돌리지 못한다
     * @return 스냅샷이 없거나 해당 legIndex 가 없으면 빈 결과
     */
    public Optional<Waypoints> resolve(Long journeyId, int routeRevision, int legIndex);

    /**
     * @param origin      그 legIndex 단계의 시작 좌표 (검색 출발점)
     * @param destination 마지막 단계의 끝 좌표 (검색 도착점)
     */
    public record Waypoints(Coord origin, Coord destination) {}
    public record Coord(double x, double y) {}
}
```

- `JourneyRevisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(journeyId, routeRevision)` → `routeSnapshot`
  **이 조회 메서드는 지금 없다 — PR-2 에서 추가한다.** 기존 `findByJourneyIdAndStatusAndDeletedAtIsNull(journeyId, ACTIVE)` 를 쓰면 위 경합이 열린다
- `objectMapper.treeToValue(snapshot, PathResult.class)` → `compiler.compile(path).legs()`
- `legIndex` 일치 초안의 `startX/startY`, 마지막 초안의 `endX/endY`
- 좌표가 `null` 이면 빈 결과

### 3.5 `application/journey/port/out/RemainingRouteCachePort.java` — PR-2

```java
public interface RemainingRouteCachePort {

    /** 캐시 확인 자체가 실패하면 빈 결과. 캐시는 fail-open 이다(cooldown 과 반대) */
    Optional<List<PathResult>> find(String stopId, double destX, double destY);

    void put(String stopId, double destX, double destY, List<PathResult> paths);
}
```

### 3.6 `infrastructure/adapter/out/cache/journey/RedisRemainingRouteCacheAdapter.java` — PR-2

- 키: `journey:reroute:remaining:{stopId}:{destX}:{destY}` — 좌표는 **소수점 5자리 고정 포맷**(`String.format(Locale.ROOT, "%.5f", …)`)
- 값: `List<PathResult>` JSON (`ObjectMapper`)
- TTL: `properties.getRemainingPath().getCacheTtl()` (기본 `PT10M`)
- **읽기·쓰기 모두 예외를 삼킨다.** 실패하면 캐시가 없는 것처럼 동작한다(fail-open)

### 3.7 `application/journey/service/RemainingRouteSearchService.java` — PR-2

```java
/**
 * 남은 구간 경로 검색. RouteSearchService 를 부르지 않는다 —
 * 검색이력·telemetry 부수효과가 여정 경로에 섞이면 안 되고 시작점도 다르다.
 */
@Component
public class RemainingRouteSearchService {

    /** @return 캐시·검색 모두 실패하면 빈 목록. 예외를 던지지 않는다 */
    public List<PathResult> search(String stopId, Waypoints waypoints, long budgetMs);
}
```

순서: 캐시 조회 → 히트면 반환(`recordSearch("cache-hit")`) → 미스면 `SingleFlight.execute(cacheKey, loader, joinWaitMs)` → loader 안에서 `odSayApiPort.searchPath(...)` 호출 후 캐시에 넣는다 → `Outcome.role()` 로 `called`/`single-flight-join` 을 가른다 → 예외·빈 응답이면 `failed` + 빈 목록.

**`searchPath` 는 `List<PathResult>` 가 아니라 `RouteSearchResult` 를 돌려준다.** 쓰는 것은
`result.paths()` 뿐이고 `busCount`·`meta` 등은 버린다(캐시에도 `paths` 만 넣는다 — 나머지는 검색
응답 계약용이라 여정 경로에서 쓸 일이 없다). `result` 나 `result.paths()` 가 `null`·빈 목록이면
`failed` 로 세고 **캐시에 넣지 않는다** — 빈 답을 10분간 들고 있으면 그 구간이 10분 내내 빈손이 된다.

### 3.8 `application/journey/service/RemainingPathEtaComposer.java` — PR-2

ODsay 가 준 `PathResult` 하나를 후보 하나로 옮긴다.

```java
@Component
public class RemainingPathEtaComposer {

    /** @return 첫 대기를 못 잡거나 소요가 깨져 있으면 사유가 담긴 실패 */
    public Attempt compose(PathResult candidatePath, TransferConnection transfer,
                           int fromLegIndex, Instant now, long budgetMs);
}
```

내부:

1. `compiler.compile(candidatePath)` → 초안 목록
2. **`transitIdEnricher.enrich(plan)` 를 반드시 거친다.** 컴파일러는 `startStopId=null`·option `routeId=null`
   인 초안을 낸다(`JourneyCompiler.java:199-200`, `JourneyRouteOptionDraft`). 여정 시작 흐름도
   `JourneyStartService.java:69` 에서 이 단계를 거친다. **이걸 빼면 버스 후보가 전부 `no-prediction` 이
   되고 `same-as-current` 판정도 작동하지 않는다** — 비교할 `routeId` 가 양쪽 다 null 이 된다
3. `fromLegIndex` 오프셋을 더해 실제 `legIndex` 로 맞춘다
4. 첫 대기 단계를 찾는다
   - 있고 `routeId`·`boardingSeq`(버스) 또는 `routeId`·`wayCode`(지하철)를 못 채웠으면
     → 실패 `unresolved-route`
   - **없으면(걷기만 경로, D8)** 예측을 건너뛰고 `boardingAt = transfer.getUserReadyAt()`
5. **앵커에 "첫 대기 앞의 도보"를 더한다.**
   ```
   앵커 = transfer.getUserReadyAt() + (첫 대기보다 앞에 오는 WALK·TRANSFER_WALK 소요 합)
   ```
   새 경로는 **환승 정류장에서 다른 정류장까지 걸어간 뒤** 타는 모양일 수 있다. 그 도보를 빼고
   `userReadyAt` 을 그대로 앵커로 쓰면 "정류장에 이미 서 있는 사용자"로 평가해 **실제로는 못 타는
   차를 후보로 만들고 ETA 도 도보 시간만큼 빨라진다.** 그 도보 소요가 `null` 이면 실패
   `missing-duration`
6. `BoardingTimeEstimator` 를 `supports(waitType)` 로 골라 `estimate(query, 앵커, now, budgetMs)`.
   결과가 없으면 `no-prediction`, `boardable` 이 아니면 `unboardable`
7. 목적지 ETA 합산 — 계산기 오버로드(아래)에 **첫 대기 번호**를 하한으로 넘긴다. 앞의 도보는 이미
   앵커에 들어갔으므로 두 번 더하지 않는다. 걷기만 경로는 하한을 `첫 단계 번호 - 1` 로 둔다
8. 실패 사유는 `unresolved-route` · `no-prediction` · `unboardable` · `missing-duration` ·
   `no-direction` 중 하나

#### 계산기 오버로드 (§4 의 `RouteOptionEtaCalculator` 수정)

지금 시그니처는 `compute(List<JourneyLeg> legs, JourneyLeg waitLeg, Instant boardingAt)` 이고
내부에서 `waitLeg.getLegIndex()` 를 **하한(제외)** 으로 쓴다(`RouteOptionEtaCalculator.java:56`).
④는 두 가지가 다르다.

- 넘길 것이 `JourneyLeg` 가 아니라 `JourneyLegDraft` 다
- **걷기만 경로(D8)에는 대기 단계가 아예 없다** — 넘길 `waitLeg` 객체가 없다

그래서 오버로드는 **엔티티가 아니라 하한 번호를 받는다.**

```java
/**
 * @param fromLegIndexExclusive 이 번호까지는 이미 승차 시각에 반영돼 있다. 그보다 뒤만 더한다.
 *                              대기 단계가 없는 경로(걷기만)에는 (첫 단계 번호 - 1) 을 넘긴다
 */
public Optional<Result> compute(List<JourneyLegDraft> legs, int fromLegIndexExclusive,
                                Instant boardingAt);
```

**기존 메서드는 이 오버로드에 위임한다** — `compute(legs, waitLeg, boardingAt)` 은 엔티티를 하한
번호로 바꿔 넘긴다. 로직을 두 벌 두면 ③과 ④의 합산이 갈릴 수 있다.

검증: `./gradlew test --tests '*RouteOptionEtaCalculatorTest'` 가 **한 줄도 안 고친 채 통과해야 한다.**
③ 경로가 위임을 거쳐도 같은 답을 낸다는 뜻이다.

### 3.9 `application/journey/service/RemainingPathCandidateProvider.java` — PR-2

```java
@Component
public class RemainingPathCandidateProvider implements RerouteCandidateProvider {
    public RerouteProposalKind kind() { return RerouteProposalKind.REMAINING_PATH; }
    public int priority() { return 1; }
    public List<RerouteCandidate> findCandidates(RerouteContext context) { … }
}
```

`collect(context)` 순서:

1. `TransferConnectionRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(...)` → 없거나 `userReadyAt == null` 이면 빈 목록
2. `JourneyWaypointResolver.resolve(journeyId, context.routeRevision(), transfer.getToLegIndex())`
   → 없으면 빈 목록. **개정본을 `context` 값으로 고정한다**(§3.4)
3. `JourneyLegRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(...)` → 대기 단계와 지금 선택 노선을 찾는다.
   **`waitLeg.getStartStopId() == null` 이면 빈 목록** — 미해석 정류장으로 검색하면 캐시 키가
   `…:null:{목적지}` 가 되어 **다른 정류장의 경로를 서로 돌려준다**(③도 같은 이유로 거절한다,
   `RouteOptionCandidateProvider.java:120-121`)
4. `RemainingRouteSearchService.search(waitLeg.getStartStopId(), waypoints, searchBudgetMs)`
   → **빈 목록이면 여기서 끝낸다.** 기준선을 재기 전에 검색을 먼저 하는 이유가 이것이다 —
   검색이 빈손이면 기준선 예측이 통째로 낭비다(DESIGN §3 흐름도와 같은 순서)
5. 경로를 `maxPaths`(2)개까지 추린다. `same-as-current` 인 것은 먼저 버린다.
   **남는 경로가 없으면 여기서 끝낸다** — 역시 기준선을 재지 않는다
6. **기준선 재기 (D7)**: 대기 단계의 `selectedRouteOptionId` → `JourneyLegRouteOptionRepository` → `BoardingQuery` → `BoardingTimeEstimator` → `RouteOptionEtaCalculator`.
   `boardable` 이 아니거나 결과가 없으면 `baseline = null` (**빈손이 아니다 — D3 로 간다**)
7. 후보마다 `RemainingPathEtaComposer.compose(...)`. **후보마다 try/catch** — 하나 터져도 이미 모은 후보를 버리지 않는다
8. 누적 예산: 후보 평가 전에 남은 예산을 계산해 `Math.min(interpreter.budgetMs(), remaining)` 을 넘긴다. 0 이하면 `budget-exhausted` 로 중단
9. **환승 수 관문 (C)**: 후보의 "첫 대기 이후 남은 대기 수"가 기준선의 그것보다 **많으면 버린다**
   (`more-transfers`). 이유는 §5-b
10. 견주기: `baseline != null` → 개선폭 300초 이상만 / `baseline == null` → **ETA 가 가장 이른 하나만**, `etaGainSec = 0`, `confidence = APPROXIMATE`
11. `etaGainSec` 내림차순 정렬. **개수 절단 없음**(저장 쪽이 `maxCandidates` 로 자른다)
12. 전체를 `try/catch` 로 감싸 빈 목록을 낸다

**트랜잭션 없음.** 외부 호출을 하는 코드가 DB 커넥션을 잡고 있으면 안 된다.

### 3.10 화면값 (`RerouteCandidateDisplay`) — PR-2

| 필드 | 값 |
|---|---|
| `destinationEtaAt` | 계산기 결과 |
| `totalDurationSec` | `now` → `destinationEtaAt` 초 |
| `etaGainSec` | 기준선 ETA − 후보 ETA. 기준선 없으면 **0** |
| `transferCount` | **새 경로의 환승 수**(③과 달리 구조가 바뀐다) |
| `walkingSec` | 계산기 결과 |
| `source` | `REALTIME` / `ML` |
| `confidence` | 이후 환승이 남거나 기준선이 없으면 `APPROXIMATE`, 아니면 추정 결과의 `quality` |
| `dataObservedAt` | `BoardingEstimate.observedAt`. 걷기만 경로면 `now` |
| `legSummaries` | 노선명 + 승·하차 정류장명. **이름이 비어 있어도 후보를 버리지 않는다** |

`apply` = `RemainingPathApply(fromLegIndex, candidatePath)` — **ODsay 가 준 경로 원본 그대로**. ⑤가 그걸로 새 단계를 만든다. `candidateId` 는 비워 둔다(저장 쪽이 UUID 를 채운다).

### 3.11 시험 파일

| 경로 | PR | 내용 |
|---|---|---|
| `test/.../service/RemainingPathCandidateProviderTest.java` | PR-2 | 단위. 걸러내기·기준선 두 갈래·예산·부분 실패 |
| `test/.../service/RemainingRouteSearchServiceTest.java` | PR-2 | 단위. 캐시 히트/미스·SingleFlight·Redis 장애 fail-open |
| `test/.../service/JourneyWaypointResolverTest.java` | PR-2 | 단위. `legIndex` → 좌표 매핑 |
| `test/.../service/BoardingTimeEstimatorTest.java` | **PR-1** | 단위. 버스·지하철 두 구현 |
| `test/.../cache/journey/RedisRemainingRouteCacheAdapterTest.java` | PR-2 | 단위. **키 모양과 TTL 이 계약이다** |
| `test/.../journey/RerouteRemainingPathIT.java` | PR-2 | 통합. ③이 빈손인 여정에서 제안 생성 + ODsay 호출 수 검증 |

## 4. 수정 파일

| 경로 | PR | 변경 |
|---|---|---|
| `application/journey/service/BusTransferTargetArrivalPredictor.java` | **PR-1** | `BusBoardingTimeEstimator` 에 위임. `blocked`/`unknown` 구분 보존 (§3.3-b). **동작 불변** |
| `application/journey/service/SubwayTransferTargetArrivalPredictor.java` | **PR-1** | `SubwayBoardingTimeEstimator` 에 위임. **동작 불변** |
| `application/journey/config/JourneyRerouteProperties.java` | PR-2 | 중첩 클래스 `RemainingPath` 추가 + `validate()` 확장 |
| `application/journey/observability/JourneyRerouteMetrics.java` | PR-2 | `KNOWN_FILTER_REASONS` 에 `same-as-current`·`no-direction` 추가. 신규 `recordRemainingSearch(String outcome)`·`recordRemainingPaths(int given, int evaluated)` |
| `application/journey/service/RouteOptionEtaCalculator.java` | PR-2 | `JourneyLegDraft` 목록용 오버로드 추가. **③ 경로의 동작은 불변** |
| `domain/journey/repository/JourneyRevisionRepository.java` | PR-2 | `findByJourneyIdAndRouteRevisionAndDeletedAtIsNull` 추가(§3.4) |
| `src/main/resources/application.yaml` | PR-2 | `journey.reroute.remaining-path.*` |
| `docs/plan/journey-navigation/PHASE-6.md` | PR-2 | ④ 서술 3곳 정정 (§8) |
| `docs/plan/journey-navigation/SCOPE-CHANGES.md` | PR-2 | **SC-16** 추가 (직전이 SC-15) |

### 설정

```yaml
journey:
  reroute:
    remaining-path:
      minimum-eta-gain-seconds: 300
      max-paths: 2
      evaluation-budget-ms: 3000
      search-budget-ms: 2000
      cache-ttl: PT10M
```

`validate()` — 넷 다 1 이상, `cache-ttl` 은 양수.

## 5. 걸러내는 사유와 지표

| 사유 태그 | 조건 |
|---|---|
| `same-as-current` | 새 경로 첫 대중교통 구간의 `routeId` == 지금 선택 노선 `routeId`. **둘 다 해석된 경우에만 비교**한다 |
| `no-direction` | 첫 대기가 지하철인데 `wayCode` 없음 |
| `missing-duration` | 주행·도보 소요가 비었다 |
| `unboardable` | 사용자가 닿기 전에 지나갈 차 |
| `no-prediction` | 예측 자체가 없다 |
| `gain-below-threshold` | 개선폭 300초 미만 |
| `budget-exhausted` | 누적 예산 초과 |
| `unresolved-route` | enrich 후에도 첫 대기의 노선·순번(또는 방향)을 못 채웠다 |
| `more-transfers` | 후보의 이후 대기 수가 기준선보다 많다 (§5-b) |

**새 사유는 반드시 `JourneyRerouteMetrics` 목록에 넣는다.** 안 넣으면 조용히 `unknown` 으로 접힌다.

### 5-b 환승 수가 더 많은 후보를 버리는 이유

D4 는 이후 대기를 0으로 두는데, ③에서는 그 값이 **기준선과 후보에 똑같이 빠져** 뺄셈에서 상쇄됐다
(구조가 안 바뀌므로 대기 수가 같다). **④는 경로 구조 자체를 바꾸므로 그 전제가 깨진다.**

```
실제 ETA = 계산된 ETA + 생략한 대기 시간의 합
실제 개선폭 = 계산된 개선폭 + (기준선이 생략한 대기 − 후보가 생략한 대기)
```

- 후보의 이후 대기 수 **≤** 기준선 → 괄호가 0 이상 → **계산된 개선폭이 실제보다 작거나 같다**(보수적).
  허위 개선이 생기지 않는다
- 후보의 이후 대기 수 **>** 기준선 → 괄호가 음수 → **후보만 낙관되어 없는 300초 개선이 만들어진다.**
  `APPROXIMATE` 표시는 순위를 바로잡지 못한다 — 사용자는 더 느린 길로 갈아탄다

그래서 **많은 쪽은 버린다.** 대가: "환승이 하나 늘지만 훨씬 빠른 경로"를 놓친다. 그것을 정직하게
평가하려면 모든 대기를 예측해야 하고, 그건 D4 의 후속 과제다(DESIGN §9).

신규 지표
- `journey.reroute.remaining.search` — 태그 `outcome`: `cache-hit`·`single-flight-join`·`called`·`failed`. **`cache-hit` 비율이 이 칸의 요금을 결정한다**
- `journey.reroute.remaining.paths` — 준 경로 수 대비 평가한 수

## 6. 작업 순서

### PR-1 — `feat/journey-boarding-time-estimator` (M, 동작 불변)

- [ ] **1.** fresh `main`(`c4079f1` 이후)에서 브랜치를 딴다
- [ ] **2.** `BoardingQuery`·`BoardingEstimate`·`BoardingOutcome`·`BoardingTimeEstimator` 포트
      검증: `./gradlew compileJava`
- [ ] **3.** `BusBoardingTimeEstimator` + `SubwayBoardingTimeEstimator` + `BoardingTimeEstimatorTest`
      검증: `./gradlew test --tests '*BoardingTimeEstimatorTest'`
      **지하철 구현이 기존 `SubwayTransferTargetArrivalPredictor:52-95` 와 같은 판정을 내는지 단언한다**
- [ ] **4.** `BusTransferTargetArrivalPredictor` 위임 (§3.3-b). `blocked`/`unknown` 구분 보존
- [ ] **5.** `SubwayTransferTargetArrivalPredictor` 위임
- [ ] **6.** 검증: `./gradlew test --tests '*TransferTargetArrivalPredictorTest'`
      **기존 시험 파일을 한 줄도 고치지 않고 통과해야 한다.** 고쳐야 한다면 동작이 바뀐 것이므로 되돌린다
- [ ] **7.** `./gradlew build` 전체 통과 → PR-1 머지

### PR-2 — `feat/journey-reroute-remaining-search` (L, PR-1 머지 후)

- [ ] **1.** PR-1 이 머지된 fresh `main` 에서 브랜치를 딴다
- [ ] **2.** `JourneyRerouteProperties.RemainingPath` + `application.yaml` + `validate()`
      검증: `./gradlew compileJava`
- [ ] **3.** `JourneyRerouteMetrics` 사유 2개 + 지표 2개
- [ ] **4.** `JourneyWaypointResolver` + 단위 시험
      검증: `./gradlew test --tests '*JourneyWaypointResolverTest'`
      **핵심 단언**: 재컴파일한 초안의 `legIndex` 가 저장된 `journey_leg` 의 `legIndex` 와 일치한다
- [ ] **5.** `RemainingRouteCachePort` + `RedisRemainingRouteCacheAdapter` + 단위 시험(키·TTL·fail-open)
      검증: `./gradlew test --tests '*RedisRemainingRouteCacheAdapterTest'`
- [ ] **6.** `RemainingRouteSearchService` + 단위 시험(캐시 히트/미스·SingleFlight·실패)
      검증: `./gradlew test --tests '*RemainingRouteSearchServiceTest'`
- [ ] **7.** `RouteOptionEtaCalculator` 오버로드 추가
      검증: `./gradlew test --tests '*RouteOptionEtaCalculatorTest'` (**기존 시험 그대로 통과**)
- [ ] **8.** `RemainingPathEtaComposer` + 단위 시험
- [ ] **9.** `RemainingPathCandidateProvider` + 단위 시험. 외부 포트는 전부 mock
      검증: `./gradlew test --tests '*RemainingPathCandidateProviderTest'`
- [ ] **10.** `RerouteRemainingPathIT` — ③을 빈손으로 만든 뒤 ④가 도는 것까지
      검증: `./gradlew test --tests '*RerouteRemainingPathIT'`
- [ ] **11.** ②의 `RerouteCandidateContractTest` 가 ④의 후보로도 통과하는지 확인
- [ ] **12.** 문서 3종 — `PHASE-6.md` ④ 서술 정정, `SCOPE-CHANGES.md` SC-16, `DESIGN.md` 링크 확인
- [ ] **13.** `./gradlew build` 전체 통과 → PR-2

## 7. 검증

```bash
./gradlew compileJava
./gradlew test --tests '*Reroute*' --tests '*Remaining*' --tests '*Boarding*' --tests '*Waypoint*'
./gradlew build
```

### 통과해야 하는 것

- [ ] **(PR-1) `boardable=false` 예측을 예측기가 그대로 통과시킨다** — 걸러내면 `MISSED` 감지가 죽는다
- [ ] **(PR-1) `status=BLOCKED` 는 `upstreamBlocked()=true`, `NO_VEHICLE` 는 false** — 막힌 문을 반복해 두드리지 않는 성질
- [ ] (PR-1) `TransferTargetArrivalPredictorTest`·`RouteOptionEtaCalculatorTest` 가 **무수정 통과**
- [ ] ③이 빈손일 때 ④가 돌아 **제안 1건**을 저장한다
- [ ] **재컴파일한 `legIndex` 가 저장된 단계와 일치한다** (§3.4 의 유일한 외줄)
- [ ] 지금 경로와 같은 노선으로 시작하는 경로는 `same-as-current` 로 걸러진다
- [ ] **캐시가 맞으면 ODsay 를 부르지 않는다**
- [ ] 동시에 두 요청이 오면 ODsay 호출은 **1회**다.
      **같은 여정으로 시험하면 안 된다** — REQUEST cooldown 이 두 번째를 먼저 거절해서 SingleFlight 를
      전혀 지나지 않는다. **서로 다른 여정이면서 같은 캐시 키**(같은 환승 정류장 → 같은 목적지)로 시험한다
- [ ] **Redis 가 죽어도 제안이 나온다**(캐시 fail-open)
- [ ] ODsay 가 죽으면 빈 결과다. 예외가 아니다
- [ ] 첫 대기가 지하철인 경로도 후보가 된다. `wayCode` 없으면 `no-direction`
- [ ] 지하철은 실시간이 없으면 후보가 아니다 — **시간표로 채우지 않는다**
- [ ] 걷기만으로 닿는 경로도 후보가 된다(D8)
- [ ] **enrich 를 거쳐 버스 후보의 `routeId`·`boardingSeq` 가 채워진다.** 안 거치면 전부 `no-prediction` 이다
- [ ] **첫 대기 앞에 도보가 있는 경로는 그 도보만큼 앵커가 밀린다** — 안 밀면 못 타는 차를 후보로 만든다
- [ ] **후보의 이후 대기 수가 기준선보다 많으면 `more-transfers` 로 버린다**(§5-b)
- [ ] **미해석 정류장(`startStopId == null`)이면 검색을 부르지 않는다** — 캐시 키가 섞인다
- [ ] **좌표는 `context.routeRevision()` 의 스냅샷에서 온다** — ACTIVE 를 읽으면 개정본이 오른 사이 옛 단계와 새 좌표가 섞인다
- [ ] 기준선을 못 잰 경우 **1건만** 나오고 `etaGainSec == 0`, `confidence == APPROXIMATE`
- [ ] 개선폭 300초 미만이면 빈 결과다
- [ ] 후보 하나의 예측이 터져도 **이미 모은 후보는 살아남는다**
- [ ] 누적 예산을 넘기면 부분 결과를 낸다. 남은 예산이 다음 호출 상한으로 넘어간다
- [ ] 이후 환승이 남은 후보의 신뢰도는 `APPROXIMATE`
- [ ] `apply` 의 경로 원본으로 `JourneyCompiler.compile()` 이 성립한다(⑤가 쓸 모양)
- [ ] ②의 `RerouteCandidateContractTest` 통과
- [ ] 기존 여정 평가·알림·③ 시험 회귀 없음

### 배포 메모

마이그레이션이 없어 VPS 수동 배치가 없다. `journey.reroute.enabled` 는 **계속 false 로 둔다** — 켜는 것은 ⑥ 이후이고 PHASE-5 §1.1 절차를 함께 탄다.

**`SingleFlight` 는 JVM 안에서만 유효하다**(`SingleFlight.java:21-22`). 2026-08-12 기준 VPS compose 는
`prod,api` 컨테이너 하나와 `prod,worker` 컨테이너 하나이고 replicas 설정이 없다 — 재탐색 요청은 전부
api 하나로 들어오므로 지금은 충분하다. **api 를 여러 개로 늘리면** 중복 억제가 "인스턴스당 1회"로
느슨해진다(캐시가 그 뒤를 받으므로 요금이 인스턴스 수만큼 늘 뿐 정합성 문제는 아니다). 늘릴 때
분산 잠금을 다시 판단한다.

## 8. 문서 변경 (SC-16)

PHASE-6 ④ 서술 세 곳이 코드와 어긋난다. 고치고 근거를 남긴다.

| PHASE-6 서술 | 실제 | 이유 |
|---|---|---|
| "시작점은 **현재 위치 또는** 다음 환승 지점" | 환승 지점 고정 | 서버는 정밀 좌표를 받지 않는다. 여는 것은 MVP 밖 |
| "후보 캐시는 **여정 TTL** 동안" | 10분 고정, 여정과 무관한 공유 키 | 구조는 사용자·여정과 무관하다 |
| "`WaitBusArrivalPredictor`·`TransferTargetArrivalService` 를 재사용한다" | `BoardingTimeEstimator` 신규 | 기존 예측기는 저장된 단계를 받아 DB 를 읽는다. ④의 후보는 DB 에 없다 |

## 9. 함정

- **컴파일한 후보를 그대로 쓰지 마라.** `JourneyTransitIdEnricher.enrich()` 를 거쳐야 `stopId`·`routeId`·`boardingSeq` 가 채워진다. 빼먹으면 후보가 전부 `no-prediction` 이고, 원인이 "예측 API 가 답을 안 준다"로 보여 엉뚱한 곳을 파게 된다
- **앵커에 첫 도보를 더하는 것을 잊지 마라.** 새 경로는 걸어서 다른 정류장으로 가는 모양일 수 있다
- **③의 "이후 대기는 상쇄된다"를 ④에 그대로 옮기지 마라.** ④는 구조를 바꾸므로 환승 수가 다르면 상쇄되지 않는다(§5-b)
- **추정기에서 `boardable` 로 걸러내지 마라.** 여정 평가는 못 타는 예측을 **일부러** 통과시킨다 — 그 음수 여유가 `MISSED` 판정의 근거다(§3.1-b). 재탐색 후보 쪽만 버린다
- **기존 예측기를 재사용하려 하지 마라.** `predictBoarding(JourneyLeg, …)` 는 내부에서 `routeOptionRepository.findById(leg.getSelectedRouteOptionId())` 를 읽는다. ④의 후보는 DB 에 없다
- **대기 단계 소요는 언제나 `null` 이다**(`JourneyCompiler.java:210`). "없으면 포기"로 짜면 환승이 둘 이상인 여정이 통째로 죽는다(③의 R1)
- **예산은 호출 전에만 보지 마라.** 마감 직전에 시작한 호출이 제 몫을 다 쓰면 총 대기가 예산을 넘는다(③ 리뷰에서 잡힌 것)
- **후보 루프의 예외는 후보마다 잡아라.** 바깥 `try/catch` 만 있으면 하나 터질 때 이미 모은 후보까지 날아간다(③ 리뷰에서 잡힌 것)
- **캐시는 fail-open, cooldown 은 fail-closed.** 둘을 같은 규칙으로 다루면 하나가 틀린다
- **`Stream.findFirst()` 는 null 원소에 NPE 다.** 정류장·노선 이름은 비어 있을 수 있다(③에서 후보가 통째로 사라진 실제 버그)
- **`RouteOptionEtaCalculator` 오버로드를 추가할 때 ③의 동작을 바꾸지 마라.** 기존 시험이 그대로 통과해야 한다
- **`@ConfigurationProperties` 값을 시험에서 손으로 채워도 소용없다.** `@TestPropertySource` 로 줘야 한다
- 통합 시험에서 `boardingSeq`·`start_stop_id` 는 정적 노선표가 있어야 채워진다. 시험 DB 에는 없으므로 **SQL 로 직접 심어야** 후보 생성이 돌아간다(③의 `RerouteRouteOptionIT.givenResolvedBoarding` 참고)
- **`git add -A` 금지.** 경로를 지정해 커밋한다
