# M7 PR-4 — 지하철 탑승 열차 매칭기 + 수명주기 (실행 플랜)

> 상위 플랜: `docs/plan/m7-subway-train-binding/PLAN.md` §PR-4 (설계 원본).
> 이 문서는 그 절을 **PR-2·PR-3 머지 이후의 실제 코드에 접지**해 다시 쓴 실행 플랜이다.
> 상위 플랜과 충돌하면 **이 문서가 이긴다** (상위는 PR-2/PR-3 머지 전에 쓰였다).
>
> **개정 2 (2026-08-14)**: codex 설계 리뷰 11건 전량 반영. 바뀐 것은 §10 개정 이력에 모아 뒀다.

---

## 0. 지금 상태

| 재료 | 어디까지 됐나 |
|---|---|
| 열차 위치·전이 로그 | PR-2 (#148 머지). `SubwayTrainLogPort` 로 출발/도착 조회 가능 |
| 관측 수신 + 스키마 | PR-3 (#150 머지). `subway_leg_binding`(V44)·`subway_binding_event`(V45) |
| **열차 선택** | **없다. 이 PR** — 그래서 상태가 영원히 `PENDING` 이다 |
| 진행 링·Live Activity | PR-5 (미착수) |

이 PR 이 하는 일 한 문장: **쌓인 관측과 쌓인 열차 로그를 맞춰 `PENDING → PROVISIONAL → CONFIRMED` 로 올린다.**

---

## 1. 접지 — 상위 플랜이 예측만 했고 여기서 실측한 것

구현 전에 반드시 읽을 것. §PR-4 초안이 "확인 필요"로 남긴 것들의 답이다.

### ① ID 공간 — 구멍이 없다 (초안이 걱정한 것과 반대)

`journey_leg` 의 지하철 값은 **이미 서울 ID 공간**이다. 여정 시작 시 `JourneyTransitIdEnricher` 가 해석해 넣는다.

| 필드 | 실제 값 | 좌표 |
|---|---|---|
| `leg.routeId` | 내부 lineId (`"1002"` 등) = `SubwayLineIdMapper.toInternalLineId` 결과 | `JourneyTransitIdEnricher:118` |
| `leg.startStopId` / `endStopId` | `subway_station.statn_id` (서울) | `JourneyTransitIdEnricher:125` → `SubwayArrivalQueryService.resolveStation:34` |
| `leg.sourceStartStopId` | ODsay `stationID`. **쓰지 않는다** | `JourneyLegDraft:19` |

PR-2 의 사건도 위치 API `statnId` 로 키를 만든다(`RedisSubwayTrainLogAdapter:184`). **같은 공간이라 그대로 조인된다.**
상위 플랜 §PR-1 의 "대조는 이름으로" 는 **geometry 역 체인 한정** 규칙이고, 매칭기는 ID 로 조인한다.

> **단, 해석이 실패하면 null 이다.** `unambiguousStationId` 는 후보가 2개 이상이면 null 을 넣는다
> (`JourneyTransitIdEnricher:122`). `routeId`·`startStopId` 중 하나라도 null 이면 **판정하지 않는다**.

### ② 방향 — 변환기가 이미 있지만 **값을 검증하지 않는다**

`SubwayRealtimeArrivalReader.toDirection(lineId, wayCode):145` 가 ODsay `wayCode` → 한글 표기를 이미 한다.
PR-2 가 저장하는 `TrainIdentity.direction` 도 같은 한글 표기다.

**문제**: 지금 구현은 `wayCode` 가 무엇이든 방향을 하나 만들어 낸다 — `99` 도 `-1` 도 `"하행"` 이 된다.
짝인 `SubwayDirectionNormalizer.fromUpDownLine:49` 는 `"0"`/`"1"` 외에는 null 을 주는데, 이쪽만 안 그렇다.
**방향을 지어내면 반대 방향 열차에 고정될 수 있다.**

**값 공간 (javadoc `SubwayDirectionNormalizer:12` 기준)**
```
ODsay wayCode  : 2호선(1002) → 0=내선, 1=외선 /  그 외 → 1=상행, 2=하행
서울 updnLine  : 2호선(1002) → 0=내선, 1=외선 /  그 외 → 0=상행, 1=하행
```

> ⚠️ **노선별로 허용값이 다르다.** 2호선은 `{0,1}`, 그 외는 `{1,2}` 다. 두 노선을 같은
> 화이트리스트로 묶으면 **하행(`wayCode=2`)이 통째로 죽는다.** 리뷰가 "0/1 로 고정하라"고 했지만
> 그건 `updnLine` 값 공간이고 `wayCode` 가 아니다 — 그대로 따르면 하행 판정이 전멸한다.

`wayCode` 는 leg 이 아니라 **짝 WAIT leg 의 선택된 route option** 에 있다 —
`JourneyLegRouteOption.getWayCode()`, 읽는 법은 `WaitSubwayArrivalPredictor:59` 와 같다.

### ③ 수명주기 접점 4곳 — 전부 실재하고 좌표가 맞다

| # | 좌표 | 확인 |
|---|---|---|
| ① 종료·취소 | `JourneyCommandService.applyTransition:120` (진입점 `apply:60`) | ✅ |
| ② lease 만료 | `JourneyLeaseExpiryService.expireBatch:108` | ✅ |
| ③ 여정 갈아타기 | `JourneyStartTxService.replaceOrReject:116` | ✅ |
| ④ 재탐색 수락 | `RerouteAcceptTxService.applyRevision:166` | ✅ |

### ④ 엔티티·저장소에 없는 것 (이 PR 이 추가)

- `SubwayLegBinding` 에 **고정 메서드가 없다** — `resetByUser()`·`abandon()`·`advance*Seq()` 만 있다
- `SubwayLegBindingRepository` 에 **CAS·스캔 질의가 없다** — `findByJourneyLegId`·`findByJourneyLegIdIn` 만
- `JourneySubwayBindingProperties` 에 **관문 값이 없다** — `enabled`·`maxClockPast`·`maxClockFuture` 만

### ⑤ 관측 수신은 `version` 을 올리지 않는다 (동시성 계약의 핵심)

`SubwayBoardingEvidenceService` 가 관측을 받으면 `lastMovementSeq`/`lastEpisodeSeq` 만 올리고
**`version` 은 그대로 둔다**(`SubwayLegBinding.advanceMovementSeq:171`). 그래서 `version` 만으로 CAS 하면
"내가 계산에 쓴 근거가 그 사이 바뀌었는지"를 알 수 없다 → §2-6 에서 순번을 CAS 조건에 넣는다.

### ⑥ Redis 색인 점수는 **구간이 아니라 중간점**이다

`RedisSubwayTrainLogAdapter` 는 ZSET 점수로 `SubwayTrainEvent.estimate()`(구간 중앙)를 쓰고
`rangeByScore` 로 조회한다(`:118, :136, :141-145, :161-163`). 사건 구간이 넓으면 **실제로는 겹치는데
중앙점이 조회창 밖이라 후보에 오르지도 못한다** → §2-5 에서 조회창을 넓히고 겹침으로 후필터한다.

### ⑦ 영업일 경계는 자정이 아니라 04:00

`ServiceDateResolver:27` 기준. 검색 구간이 04:00 을 걸치면 **두 날짜를 다 조회**해야 한다(§2-5).

---

## 2. PR 분할

| PR | 제목 | 티어 | 브랜치 | 의존 |
|---|---|---|---|---|
| **4a** | 매칭기 + CAS + 스케줄러 | L | `feat/journey-subway-matcher` | 없음 (main) |
| **4b** | 수명주기 4접점 + 재탐색 계승 | M | `feat/journey-subway-binding-lifecycle` | 4a 머지 후 |

**쪼갠 이유**: 4a 는 신규 파일 위주라 시험이 순수 단위 시험으로 끝나고, 4b 는 **기존 서비스 4개를 건드려**
회귀 위험 축이 다르다. 리뷰어가 봐야 하는 것이 서로 다르므로 섞지 않는다.

**푸시 알림은 이 PR 범위 밖이다.** 사용자에게 보이는 것은 전부 PR-5(진행 링 + Live Activity)가 전담한다.
C6(확정 전 하차 안내 금지)도 결국 표시 계층 규칙이라 한 곳에 모으는 편이 낫다.
**4a/4b 는 DB 상태까지만 만든다.**

---

# PR-4a — 매칭기 + CAS + 스케줄러

## 2-1. 설정 (`JourneySubwayBindingProperties` 수정)

```java
/** 판정 주기. 원천이 60~90초마다 갱신되므로 이보다 잦을 이유가 없다. */
private long tickMs = 2000;

/** 한 tick 에서 볼 최대 건수. */
private int batchSize = 50;

/** PENDING 전용. 이동 시작 관측이 이보다 오래되면 판정을 포기한다. */
private Duration matchWindow = Duration.ofMinutes(6);

/** PROVISIONAL 전용. 잠정 판정 감사행의 received_at 부터 잰다. */
private Duration confirmWindow = Duration.ofMinutes(8);

/** 관문 (1). 1위 후보의 오차가 이보다 크면 유일해도 고정하지 않는다. */
private Duration absoluteMaxSkew = Duration.ofSeconds(150);

/** 관문 (2). 구간에 안 들어간 잔여 불확실성(기기 시계 오차) 전담. */
private Duration clockSkewBudget = Duration.ofSeconds(20);

/** 센서·전송 지연. 기기 시계 오차는 여기 넣지 않는다 — 이중 계산이 된다. */
private Duration motionUncertainty = Duration.ofSeconds(30);

/** 관문 (3) 계수. headway 추정이 가능할 때만 적용. */
private double headwayBeta = 0.5;

/** 출발 사건을 찾을 때 이동 시작 앞뒤로 볼 폭. */
private Duration departureLookback = Duration.ofMinutes(5);

/** L4 에서 정차 관측 앞뒤로 도착 사건을 볼 폭. */
private Duration arrivalLookaround = Duration.ofMinutes(4);

/**
 * 색인이 중간점 기준이라 조회창을 이만큼 더 넓힌다(§1-⑥).
 * 사건 구간 반폭의 상한이다 — 이보다 넓은 구간은 어차피 판정에 못 쓴다.
 */
private Duration indexHalfWidthGuard = Duration.ofMinutes(3);

/** L4 에서 후보 도착 시각이 정차 관측과 이보다 벌어지면 그 후보는 안 맞는 것으로 본다. */
private Duration episodeMatchTolerance = Duration.ofSeconds(90);

/** 감사행에 박는 판정 알고리즘 판. 관문 수식이 바뀌면 올린다. */
private String algorithmVersion = "m7-v1";
```

`application.yaml` 에 `journey.subway.binding.*` 키 추가. **`enabled: false` 유지.**

> 숫자는 전부 **잠정값**이다. 실기기 관측 전에는 근거가 없다(§8). 그래서 전부 설정값이고 기본 off 다.

## 2-2. 방향 변환 — 지어내지 않게 막는다 (`SubwayDirectionNormalizer` 수정)

```java
/** 2호선만 내·외선을 쓰고 값 공간도 다르다. */
private static final String CIRCULAR_LINE_ID = "1002";

/**
 * ODsay {@code wayCode} 를 실시간 표기로 바꾼다. <b>모르는 값은 null 이다.</b>
 *
 * <p>{@link #fromUpDownLine} 은 이미 모르는 값에 null 을 준다. 이쪽만 무엇이든 방향을 만들어
 * 내면, 값이 깨졌을 때 <b>반대 방향 열차에 고정</b>된다. 모름이 그럴듯한 오답보다 낫다.
 *
 * <p><b>허용값이 노선별로 다르다</b> — 2호선은 {@code 0/1}, 그 외는 {@code 1/2} 다.
 * 하나로 묶으면 하행(2)이 통째로 죽는다.
 */
public static String fromWayCode(String lineId, Integer wayCode) {
    if (wayCode == null) {
        return null;
    }
    if (CIRCULAR_LINE_ID.equals(lineId)) {
        return switch (wayCode) { case 0 -> "내선"; case 1 -> "외선"; default -> null; };
    }
    return switch (wayCode) { case 1 -> "상행"; case 2 -> "하행"; default -> null; };
}
```

`SubwayRealtimeArrivalReader.toDirection:145` 는 **본문을 지우고 이 메서드에 위임**한다(시그니처 유지).

> ⚠️ **회귀 주의**: 저 메서드는 대기 지하철 ETA 경로(`WaitSubwayArrivalPredictor:59` → `select:97`)가
> 쓴다. 지금은 방향이 null 이면 **필터를 건너뛰어 전 방향을 보여준다**(`:103`). 엄격해진 뒤에도
> 그 동작이 유지되는지 시험으로 고정한다 — 여기서 새는 것은 조용하다.

## 2-3. 판정 입출력 DTO (신규)

`application/journey/dto/SubwayBindingCandidate.java`
```java
/**
 * 탑승역을 그 시간대에 출발한 열차 한 대.
 *
 * @param gapSeconds 이동 시작 구간과 출발 구간의 거리(초). 겹치면 0
 */
public record SubwayBindingCandidate(
        String trainNo, LocalDate serviceDate, String lineId, String direction,
        Instant departureFrom, Instant departureTo,
        SubwayTrainEvent.Quality quality, String terminalId, SubwayExpressType express,
        long gapSeconds) {

    public TrainIdentity identity() { … }
}
```

`application/journey/dto/SubwayBindingDecision.java`
```java
/** 판정 결과. 아무것도 하지 않는 것도 결과다 — 그게 기본값이다. */
public record SubwayBindingDecision(
        Kind kind,
        SubwayBindingCandidate chosen,
        List<SubwayBindingCandidate> candidates,
        String reason) {

    public enum Kind { NONE, PROVISIONAL, CONFIRMED, CORRECTED, ABANDON }

    public static SubwayBindingDecision none(String reason) { … }
}
```

> `NONE` 에 `reason` 을 붙이는 이유: "왜 고정 안 했나"가 이 기능의 **주된 질문**이다.
> 로그·지표가 이 문자열을 세면 관문 숫자를 실측으로 조일 수 있다.

## 2-4. `SubwayTrainMatcher` (신규, 순수 함수)

`application/journey/service/SubwayTrainMatcher.java` — **DB·Redis 를 모르는 계산기.**

```java
public SubwayBindingDecision match(MatchInput input);

public record MatchInput(
        Instant movementStartedAt,               // EVIDENCE/MOVEMENT_START 의 detail
        List<SubwayTrainLogPort.TrainDeparture> departures,
        Set<TrainIdentity> rejected,             // USER_RESET 감사행에서 복구
        String lineId, String direction, String alightStationId,
        Instant now, boolean clockSuspect,

        // L4 전용. 정차 관측이 없으면 전부 비어 있다
        Instant episodeFrom, Instant episodeTo,
        List<SubwayBindingCandidate> knownCandidates,
        Map<TrainIdentity, List<SubwayTrainEvent>> arrivalsByCandidate,
        Instant provisionalDecidedAt);
```

### 1단계 — 배제

| 조건 | 처리 | 근거 |
|---|---|---|
| `direction` 미확정 | **판정 자체를 안 함** (`NONE:direction-unknown`) | §1-② — 이제 실제로 도달 가능한 분기다 |
| `lineId`·`startStopId` 미해석(null) | 판정 안 함 (`NONE:leg-unresolved`) | §1-① |
| 노선·방향 불일치 | 배제 | |
| `express != NORMAL` (급행·특급) | **배제** | MVP 타협, §8 |
| `rejected` 에 있는 identity | 배제 | 없으면 방금 거부한 열차를 그대로 다시 고른다 |
| 출발 사건 없음 | 후보 0 → `NONE:no-departure` | |

> **종착역 도달 가능 배제는 MVP 에서 뺀다.** 초안은 종착역(`terminalId`)이 하차역에 못 미치는 열차를
> 후보에서 빼려 했는데, 그러려면 `subway_segment` 가 순환선·지선에서 **방향 있는 역 순서**를 준다는
> 보장이 필요하다. 그런 코드도 검증도 저장소에 없다(§8 미결).
>
> 그리고 여기서 잘못 배제하면 **후보가 줄어 관문 (2)가 헐거워진다** — 즉 "고정 안 함"이 아니라
> **"잘못 고정"** 쪽으로 틀어진다. 이 기능에서 가장 비싼 실패다. 근거가 설 때까지 넣지 않는다.

### 2단계 — 관문

```
motionInterval    = [movementStartedAt - motionUnc, movementStartedAt + motionUnc]
departureInterval = 사건의 [from, to]            ← 이미 불확실성을 품고 있다
gap(X, Y)         = 겹치면 0, 아니면 가까운 두 끝점 사이 초
S                 = clockSkewBudget              ← 구간에 안 들어간 잔여분만

e1 = gap(motionInterval, best.interval)
e2 = gap(motionInterval, second.interval)        ← 후보 1대면 무한대로 본다

(1) e1 <= absoluteMaxSkew                        위반 → NONE:gate1
(2) e1 + S < e2 - S                              위반 → NONE:gate2
(3) (e2 - e1) >= beta * localHeadway             headway 추정 가능할 때만
```

- **관문 (2)를 통과하면 best 는 정의상 유일하다** → tie-break 분기를 만들지 말 것 (초안의 죽은 코드)
- `clockSuspect` 면 `S` 를 2배로 본다. 버리지 않고 **더 보수적으로** 만든다
- **후보가 1대뿐이면** (2)가 자동 통과다. 이때 (3)을 못 재면 `PROVISIONAL` 까지만 — **자동 `CONFIRMED` 금지**
- `localHeadway` 는 같은 노선·방향의 최근 출발 이력 간격 중앙값. 못 재면 (3) 건너뜀

경계 예(시험으로 고정):
```
motion=[100,110], best=[105,115] → 겹침 → e1=0
  second=[200,210] → e2=90,  S=15 → 0+15 < 90-15  참  → PROVISIONAL
  second=[125,135] → e2=15,  S=15 → 0+15 < 15-15  거짓 → NONE:gate2
```

### 3단계 — L4 후행 확정 (**같은 역에서만 비교한다**)

```
비교역 S = 후보마다 "자기 출발 시각 이후 첫 도착"의 stationId
           → 모든 후보의 S 가 같아야 한다. 다르면 NONE:next-station-ambiguous
각 후보 점수 = gap(episodeInterval, 그 후보의 S 도착 구간)
best 점수가 유일 최소 && <= episodeMatchTolerance  → CONFIRMED(그 후보)
  그게 기존 chosen 이 아니면                        → CONFIRMED + 감사행 CORRECTED
모두 임계 밖 / 동점                                 → PROVISIONAL 유지
provisionalDecidedAt + confirmWindow 초과           → ABANDON
```

> **초안의 "어느 역인지 몰라도 된다"는 틀렸다 — 되돌린다.**
> `findArrivals(train, from, to)` 는 **역 필터가 없다**(`SubwayTrainLogPort:52`). 그대로 두면
> 앞차의 N+2역 도착과 뒷차의 N+1역 도착이 같은 시각에 잡혀, **둘 다 정상인데 동점이거나
> 엉뚱한 놈이 1등**이 된다. 판별력의 근거(후보 간 정차 시각이 배차간격만큼 벌어져 있다)는
> **같은 역에서 잴 때만** 성립한다.
>
> 대신 노선 위상 데이터는 여전히 쓰지 않는다 — 비교역 `S` 를 **후보들의 도착 로그에서 뽑는다**
> (출발 이후 첫 도착). 후보들은 같은 노선·방향으로 같은 역을 떠났으므로 정상이면 `S` 가 일치한다.
> 갈리면(지선 분기·결측) **확정하지 않는다.**

> **후보 집합은 최신 `PROVISIONAL` 감사행의 `detail.candidates` 에서 복구한다.** 단
> **`binding_version`·`reset_generation` 이 현재 값과 일치하는 행만** 읽는다(§2-5 의 버전 규칙 주의).

## 2-5. `SubwayBindingMatchService` (신규, 트랜잭션 경계)

```
[트랜잭션 밖]  스캔 결과 1건마다:
               - 감사행에서 movementStartedAt·episode·rejected·candidates 복구
               - serviceDate 결정(아래) → findDepartures / findArrivals
               - SubwayTrainMatcher.match(...)
[짧은 트랜잭션] ① 세션 행 FOR SHARE 재확인 → ② CAS UPDATE → ③ DECISION 감사행
```

### 조회창 보정 (§1-⑥)

색인 점수가 사건 구간의 **중간점**이므로, 원하는 겹침 판정보다 넓게 긁고 코드에서 다시 거른다.

```java
Instant from = window.start().minus(props.getIndexHalfWidthGuard());
Instant to   = window.end().plus(props.getIndexHalfWidthGuard());
// 조회 후: 사건 구간과 원래 창이 실제로 겹치는 것만 남긴다
```

### 영업일 (§1-⑦)

```java
LocalDate d1 = serviceDateResolver.resolve(window.start());
LocalDate d2 = serviceDateResolver.resolve(window.end());
// 다르면 두 날짜 모두 조회해 합친다. 04:00 을 걸친 구간이 여기에 해당한다
```

### 세션 재확인 — 종료·재탐색과의 경합 (codex ②-2)

CAS 조건만으로는 **끝난 여정에 새로 확정**될 수 있다. `findMatchable` 이 본 세션 상태는
외부 조회를 하는 동안 낡는다. 그래서 쓰기 트랜잭션 **맨 앞에서 세션 행을 다시 잡는다**:

```sql
SELECT s.id FROM journey_session s
 WHERE s.id = :journeyId AND s.status = 'ACTIVE' AND s.route_revision = :revision
 FOR SHARE
```

없으면 아무것도 하지 않고 빠진다. 종료·재탐색 접점은 세션 행을 `UPDATE` 하므로(배타 잠금)
둘이 서로 기다린다 — **순서가 정해진다.**

> 남는 경우는 "종료 직전에 확정이 먼저 커밋"뿐이다. 그건 실제로 그 시각에 확정된 것이므로
> 기록으로 맞다. `CONFIRMED` 를 접지 않는 규칙(§3-1)과도 어긋나지 않는다.

### 감사행 버전 규칙 (codex ②-3) — **틀리면 L4 가 통째로 죽는다**

CAS 는 `version` 을 `v → v+1` 로 올리지만, **네이티브 UPDATE 는 이미 읽어둔 엔티티를 고치지 않는다.**
감사행에 눈에 보이는 `v` 를 적으면, 후보 복구가 "현재 version 과 일치하는 행"만 읽으므로
**그 감사행은 쓰자마자 무효**가 되어 교정 경로가 사라진다.

**규칙: DECISION 감사행의 `binding_version` 에는 `expectedVersion + 1` 을 적는다.**
CAS 가 1행을 돌려준 뒤에만 쓰므로 그 값이 곧 새 version 이다.

**CAS 가 0행이면 감사행도 쓰지 않는다.** 누가 먼저 바꿨다는 뜻이고, 그 위에 판단을 얹으면
"뒤집힌 결정"이 기록으로 남는다.

## 2-6. 저장소 질의 (`SubwayLegBindingRepository` 수정)

```java
/**
 * 판정 대상. <b>살아 있고, 지금 판정할 수 있는 것만</b> 본다.
 *
 * <p>세션 상태·개정본 조건이 없으면 끝난 여정과 옛 개정본의 PENDING 을 영원히 재처리한다.
 *
 * <p>{@code last_movement_seq IS NOT NULL} 이 핵심이다. 정차 관측이 먼저 온 경우와 사용자
 * reset 직후는 이동 시작 근거가 없어 <b>영원히 판정 불가</b>인데, 이걸 빼면 가장 오래된 그런
 * 행들이 매 tick 배치를 통째로 차지해 <b>뒤 행이 굶는다</b>.
 */
@Query(value = """
        SELECT b.* FROM subway_leg_binding b
          JOIN journey_leg l     ON l.id = b.journey_leg_id
          JOIN journey_session s ON s.id = b.journey_id
         WHERE b.status IN ('PENDING','PROVISIONAL')
           AND b.deleted_at IS NULL
           AND l.deleted_at IS NULL
           AND s.status = 'ACTIVE'
           AND b.route_revision = s.route_revision
           AND (b.status = 'PROVISIONAL' OR b.last_movement_seq IS NOT NULL)
         ORDER BY b.updated_at
         LIMIT :limit
        """, nativeQuery = true)
List<SubwayLegBinding> findMatchable(@Param("limit") int limit);

/**
 * 낙관적 CAS. <b>CONFIRMED 는 이 경로로 뒤집히지 않는다</b> — 사용자 보정만 예외다.
 *
 * <p>순번까지 조건에 넣는 이유: 관측 수신은 순번만 올리고 {@code version} 은 올리지 않는다
 * (§1-⑤). version 만 보면 <b>낡은 근거로 한 판정이 성공</b>한다.
 *
 * @return 1 = 반영, 0 = 그 사이 누가 바꿨다
 */
@Modifying
@Query(value = """
        UPDATE subway_leg_binding
           SET status = :status, train_no = :trainNo, train_service_date = :serviceDate,
               train_line_id = :lineId, train_direction = :direction,
               confidence = :confidence, version = version + 1, updated_at = :now
         WHERE journey_leg_id = :legId
           AND deleted_at IS NULL
           AND version = :expectedVersion
           AND reset_generation = :expectedGeneration
           AND last_movement_seq IS NOT DISTINCT FROM :expectedMovementSeq
           AND last_episode_seq  IS NOT DISTINCT FROM :expectedEpisodeSeq
           AND status IN ('PENDING','PROVISIONAL')
        """, nativeQuery = true)
int casBind(...);
```

> `IS NOT DISTINCT FROM` 을 쓰는 이유: 순번은 null 일 수 있고 `= null` 은 어떤 값과도 참이 아니다.

> **엔티티 메서드를 새로 만들지 않는다.** 판정기는 전부 네이티브 CAS 로 쓴다. JPA 더티체킹과 섞으면
> 같은 트랜잭션에서 `version` 이 두 번 오른다. 엔티티의 `resetByUser()`·`abandon()` 은 PR-3 의
> 사용자 보정 경로 전용으로 남긴다.

### 판정 불가 행 청소 (스캔에서 뺐으므로 따로 접는다)

```java
/** 이동 시작 근거 없이 matchWindow 를 넘긴 PENDING. 스캔이 안 보므로 여기서 접는다. */
@Modifying
@Query(value = """
        UPDATE subway_leg_binding
           SET status = 'ABANDONED', version = version + 1, updated_at = :now
         WHERE status = 'PENDING' AND deleted_at IS NULL
           AND last_movement_seq IS NULL
           AND updated_at < :cutoff
        """, nativeQuery = true)
int abandonStaleWithoutMovement(...);
```
스케줄러가 tick 마다 부르지 않고 **N tick 마다 한 번** 부른다(설정 없이 상수 60 tick).

> 사용자 reset 직후 행도 여기 걸린다. 의도한 것이다 — reset 뒤 새 관측이 영영 안 오면
> 그 고정은 접히는 게 맞다. 접혀도 사용자 보정 API 는 그대로 동작한다(행이 남아 있다).

## 2-7. 스케줄러 (신규)

`infrastructure/adapter/in/scheduler/journey/SubwayBindingMatchScheduler.java`

```java
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.subway.binding", name = "enabled", havingValue = "true")
@Scheduled(fixedDelayString = "${journey.subway.binding.tick-ms:2000}")
```

`JourneyDueEvaluatorScheduler:1` 의 모양을 따른다 — tick 하나가 죽어도 다음 tick 은 살아야 하므로
`run()` 에서 전부 catch 하고 로그만 남긴다.

> **기존 `JourneyDueEvaluatorScheduler` 에 얹지 않는다.** 여정당 10~180초 주기라 탑승 판정에는 너무 느리다.
> Redis Stream·리더 선출은 이 규모에 과하다. 워커가 늘면 그때 승격한다 —
> **워커가 1개라는 보장은 배포 설정에 없다**(§8). 지금 안전한 이유는 CAS 가 정확성을 지키기 때문이지
> 워커가 하나여서가 아니다. 워커가 둘이면 같은 행을 두 번 계산할 뿐 결과는 하나만 반영된다.

## 2-8. 시험 (4a)

`SubwayDirectionNormalizerTest` (기존 파일에 추가)
- 2호선 `wayCode` 0/1 → 내선/외선, **2 → null**
- 그 외 노선 `wayCode` 1/2 → 상행/하행, **0·99·-1 → null**
- 회귀: 방향이 null 이면 대기 ETA 경로가 전 방향을 그대로 본다

`SubwayTrainMatcherTest` (순수 단위)
- 방향 미확정이면 판정하지 않는다 / `lineId`·`startStopId` 미해석이면 판정하지 않는다
- 출발 사건 없는 열차는 후보가 되지 않는다 / 반대 방향·다른 노선은 빠진다
- 급행/특급은 후보에서 빠진다 (MVP 타협 가드)
- **종착역으로는 배제하지 않는다** (MVP 결정 가드 — 넣으면 관문이 헐거워진다)
- `rejected` 에 있는 identity 는 다시 후보가 되지 않는다
- 관문 (1) 위반: 유일 후보여도 오차가 크면 `NONE`
- 관문 (2) 경계: 위 두 예제가 각각 `PROVISIONAL` / `NONE:gate2`
- 후보 1대 + headway 추정 불가 → `PROVISIONAL`, 자동 `CONFIRMED` 안 함
- `clockSuspect` 면 `S` 가 2배가 되어 통과하던 것이 막힌다
- **L4: 후보들의 비교역이 갈리면 확정하지 않는다** (`NONE:next-station-ambiguous`)
- **L4: 다른 역 도착은 비교에 쓰이지 않는다** — 앞차의 N+2 도착이 뒷차의 N+1 도착과 같은 시각이어도 오판하지 않는다
- L4: episode 가 best 아닌 후보와 더 맞으면 그 후보로 `CONFIRMED` + `CORRECTED`
- L4: 모두 `episodeMatchTolerance` 밖이면 `PROVISIONAL` 유지
- `matchWindow` 는 `PENDING` 에만, `confirmWindow` 는 잠정 판정 시각부터 — **6분 넘은 `PROVISIONAL` 이 8분까지 산다**

`SubwayBindingMatchServiceTest`
- CAS 0행이면 감사행을 쓰지 않는다
- **DECISION 감사행의 `binding_version` 이 `expectedVersion + 1` 이다** (아니면 후보 복구가 즉시 무효)
- 후보 복구가 현재 `version`·`reset_generation` 과 일치하는 감사행만 읽는다
- **관측 순번이 그 사이 올라갔으면 CAS 가 0행이다** (§1-⑤ 가드)
- 조회창이 `indexHalfWidthGuard` 만큼 넓게 나가고, 겹치지 않는 사건은 코드가 거른다
- 구간이 04:00 을 걸치면 **두 영업일을 모두 조회**한다
- `CONFIRMED` 는 CAS 경로로 뒤집히지 않는다

`SubwayBindingMatchIT` (Testcontainers)
- superseded revision·종료된 여정의 `PENDING` 은 `findMatchable` 에 안 잡힌다
- **이동 시작 없는 `PENDING` 은 스캔에 안 잡히고, `abandonStaleWithoutMovement` 가 접는다**
- 세션이 그 사이 `ACTIVE` 가 아니게 되면 쓰기 트랜잭션이 아무것도 하지 않는다
- 지하철 leg 2개가 각각 독립 binding 을 갖는다
- 자정(04:00) 넘김: `serviceDate` 가 달라 같은 `trainNo` 가 섞이지 않는다
- `enabled=false` 면 스케줄러 빈이 아예 없다

## 2-9. 검증 (4a)

```bash
./gradlew test --tests '*SubwayTrainMatcherTest' --tests '*SubwayBindingMatch*' \
               --tests '*SubwayDirectionNormalizerTest' --tests '*WaitSubway*'
./gradlew build
```
`build/test-results/test/*.xml` 의 `tests` 개수를 반드시 확인한다 —
필터가 아무것도 못 잡아도 `BUILD SUCCESSFUL` 로 끝난다(PR-2b 함정).

---

# PR-4b — 수명주기 4접점 + 재탐색 계승

## 3-1. `SubwayBindingLifecycleService` (신규)

접점 4곳이 같은 문장을 부르게 모은다. **부르는 쪽의 트랜잭션에 올라탄다.**

```java
void abandonOpen(Long journeyId, AbandonReason reason);
void abandonOpenOfRevision(Long journeyId, int routeRevision, AbandonReason reason);
void inheritIfEligible(JourneyLeg oldLeg, JourneyLeg newLeg, int newRevision);
```

```java
/**
 * 미완료 고정을 접는다.
 *
 * <p><b>열차 identity 를 지우지 않는다.</b> 잠정으로 무엇을 보고 있었는지가 사후 분석의 재료다.
 * 상태가 ABANDONED 면 어차피 아무도 그 열차로 안내하지 않는다.
 */
@Modifying
@Query(value = """
        UPDATE subway_leg_binding
           SET status = 'ABANDONED', version = version + 1, updated_at = :now
         WHERE journey_id = :journeyId AND deleted_at IS NULL
           AND status IN ('PENDING','PROVISIONAL')
        """, nativeQuery = true)
int abandonOpenByJourney(...);          // + 개정본 조건 붙은 변형 하나
```

> 초안 SQL 은 `train_*` 4칸을 null 로 지웠는데, 같은 문서의 계승 규칙("과거 개정본 행은 identity 를
> 남긴다")과 **정면으로 모순**이었다. 남기는 쪽으로 통일한다.

> 여정 단위 한 문장으로 끝나는 것은 `subway_leg_binding` 이 `journey_id`·`route_revision` 을
> **비정규화해 들고 있기 때문**이다(leg 조인 불필요). PR-3 이 그러려고 넣었다.

> **`CONFIRMED` 행은 지우지도 접지도 않는다** — 감사·복구용 canonical 사실이다.

## 3-2. 접점 연결

| # | 파일:라인 | 넣는 것 |
|---|---|---|
| ① | `JourneyCommandService.applyTransition:120` 부근 (종료 전이 확정 지점) | `abandonOpen(journeyId, ENDED)` |
| ② | `JourneyLeaseExpiryService.expireBatch:108` | 만료된 journeyId 마다 `abandonOpen(…, LEASE_EXPIRED)` |
| ③ | `JourneyStartTxService.replaceOrReject:116` | `abandonOpen(oldJourneyId, SUPERSEDED_BY_NEW_JOURNEY)` |
| ④ | `RerouteAcceptTxService.applyRevision:166` | `abandonOpenOfRevision(journeyId, oldRevision, SUPERSEDED)` + 계승 판정 |

③ 은 초안이 빠뜨린 접점이다 — 여기서 옛 여정을 직접 끝낸다.

**ring 삭제는 넣지 않는다.** ring 은 PR-5 가 만든다. 만들지도 않은 것을 지우는 코드를 먼저 두면
PR-5 가 키 모양을 바꿀 때 조용히 어긋난다. PR-5 가 자기 정리를 같이 가져간다.

## 3-3. 재탐색 계승 규칙 — **`PROVISIONAL` 도 옮긴다 (초안에서 뒤집힘)**

| 상황 | 처리 |
|---|---|
| 변경 지점 이후의 새 leg | 행 없음 = 고정 없음 (**코드 불필요**) |
| 탑승 전 WAIT/RIDE 쌍의 route option 이 바뀜 | 계승 안 함 |
| 탑승 중 `RIDE_SUBWAY` 가 **노선·방향·승차역·하차역 완전 동일** + **`CONFIRMED`** | 새 leg 에 행을 새로 만든다(`version=0`, `source=INHERITED_ON_REROUTE`) + 감사행 |
| 같은 조건 + **`PROVISIONAL`** | **행 + 근거를 새 leg 로 재발행한다** (아래) |
| 구간이 조금이라도 다름 | 계승 안 함 |
| 과거 개정본의 행 | `ABANDONED` 로 접는다. **열차 identity 는 남긴다** |

### `PROVISIONAL` 계승을 뒤집은 이유

초안은 "후보 감사행이 옛 leg 을 가리키므로 계승하면 교정 불가"라며 버렸다. 그런데 버리면
**되살아날 길도 없다**:

- 이동 시작 관측은 여정당 한 번뿐이라 재탐색 후 다시 오지 않는다
- 정차 관측이 먼저 와서 새 `PENDING` 이 열려도, 그 경로는 `boardedDetectedAt` 을 비워 둔다
  (`SubwayBoardingEvidenceService:99-112`) → 매칭 입력에 `movementStartedAt` 이 없어 판정 불가
- 결과: 탑승 중 재탐색을 수락하면 **그 여정은 남은 내내 "확인 중"** 이다

그래서 구간이 완전히 같을 때는 **근거까지 같이 옮긴다**:
`EVIDENCE/MOVEMENT_START` 행과 최신 `DECISION/PROVISIONAL` 행을 **새 `journey_leg_id` 로 INSERT**
(V45 unique 가 `journey_leg_id` 기준이라 충돌하지 않는다). 새 binding 행은 `version=0` 이므로
감사행의 `binding_version` 도 `0` 으로 적는다.

> 그래도 "복제가 기본"은 아니다. 조건은 **구간 완전 동일**이고, 빠뜨리면 행이 없어
> **고정 없음**으로 떨어진다. 안전한 쪽 기울기는 유지된다.

## 3-4. 시험 (4b)

- 종료·취소로 미완료 binding 이 `ABANDONED` 가 된다 / `CONFIRMED` 는 그대로다
- **접을 때 열차 identity 가 남는다** (초안 모순 가드)
- lease 만료 배치가 같은 일을 한다
- **여정 갈아타기**(`replaceOrReject`)로 끝난 옛 여정의 binding 이 정리된다
- 재탐색: 구간이 다르면 새 개정본 leg 은 **binding 행이 없다**
- 재탐색: 구간 완전 동일 + `CONFIRMED` → 새 행 + `source=INHERITED_ON_REROUTE` + 감사행
- **재탐색: 구간 완전 동일 + `PROVISIONAL` → 새 행 + 이동 시작·후보 감사행이 새 leg 로 재발행되고, 새 leg 에서 L4 확정이 실제로 된다**
- 재탐색: 과거 개정본 행이 `ABANDONED` 이고 identity 가 보존된다
- 종료와 매칭기가 동시에 돌아도 **끝난 여정에 새로 확정되지 않는다** (세션 `FOR SHARE` 가드)
- 재탐색 중 사용자 보정과 매칭기 CAS 가 경쟁해도 옛 version 이 이기지 않는다

## 3-5. 검증 (4b)

```bash
./gradlew test --tests '*SubwayBindingLifecycle*' --tests '*Reroute*' --tests '*Journey*'
./gradlew build
```

---

## 4. 함정

- **`BUILD SUCCESSFUL` ≠ 시험 실행됨.** 필터가 0건 잡아도 성공이다 → XML 의 `tests` 수 확인 (PR-2b 에서 겪음)
- **Docker 가 꺼져 있으면 IT 전량 실패**한다(`DockerClientProviderStrategy`). 코드 문제로 오독하지 말 것
- **`record` 컴포넌트와 같은 이름의 메서드**를 얹으면 컴파일 에러 → `isXxx()`/`hasXxx()` 로 이름을 달리한다
- **JPA 더티체킹 + 네이티브 CAS 혼용 금지** — `version` 이 두 번 오른다
- **감사행 `binding_version` 은 `expectedVersion + 1`** — 눈에 보이는 값을 적으면 후보 복구가 즉시 무효가 된다
- **`findMatchable` 에서 세션 조건을 빠뜨리면 끝난 여정을 영원히 재처리**한다. 이 PR 최대의 사고 경로
- **Redis 조회는 중간점 기준**이다. 창을 그대로 쓰면 넓은 구간의 사건이 후보에 오르지 못한다
- **영업일 경계는 04:00** — 자정으로 착각하면 심야에 후보가 통째로 빈다
- `wayCode` 허용값이 **노선별로 다르다**(2호선 `{0,1}`, 그 외 `{1,2}`). 하나로 묶으면 하행이 죽는다

## 5. 배포

- **마이그레이션 없음.** V44·V45 가 이미 필요한 칸을 전부 들고 있다
- 신규 설정 `journey.subway.binding.*` (§2-1 전량)
- **`journey.subway.binding.enabled` 기본 false 유지.** 켜려면 PR-2 수집기(`realtime.subway.position.enabled` + `lines`)도 함께 켜야 한다 — 로그가 없으면 후보가 0 이라 아무 일도 안 일어난다
- 스케줄러는 `worker` 프로필에서만 뜬다

## 6. 지표 (관문 숫자를 실측으로 조이기 위한 최소)

- `NONE:*` 사유별 카운터 — 어느 관문이 얼마나 막는지 모르면 숫자를 못 고친다
- `PROVISIONAL → CONFIRMED` 전환율과 소요 시간
- `CORRECTED` 발생률 — 이게 높으면 t0 판정이 신뢰할 수 없다는 뜻이다
- `ABANDONED(사유별)` — 특히 `confirmWindow` 초과 비율

## 7. 미결 (실측 전 확정 금지)

- **관문 숫자 전부** — 실기기 관측 전에는 근거가 없다. 지금 값은 관측 해상도(60~90초)에서 역산한 잠정값
- **급행 정차 패턴 데이터** — 없어서 MVP 는 급행을 통째로 배제한다. 급행을 타면 안내가 "확인 중"에 머문다
- **종착역 도달 판정** — `subway_segment` 가 순환선·지선에서 방향 있는 역 순서를 주는지 검증된 적이 없다. 그래서 MVP 에서 뺐다(§2-4). 넣으려면 위상 데이터 검증이 먼저다
- **`trainSttus=3`(전역출발)** — PR-2a 실측 표본에 한 건도 없었다. 보조 근거로만
- **탑승역 밖 automotive 전환 차단은 서버가 검증할 수 없다** — evidence payload 에 위치·geofence 증거가 없다. **앱이 보장하는 조건**
- **`localHeadway` 추정 정확도** — 첫차·막차·심야에는 표본이 없어 관문 (3)이 사실상 사라진다
- **워커 수** — 배포 설정이 1개를 보장하지 않는다. 정확성은 CAS 가 지키지만, 중복 계산량은 워커 수에 비례한다

## 8. 작업 순서

- [ ] **PR-4a** (L) — fresh main 에서 `feat/journey-subway-matcher`
  - [ ] 설정 확장 (§2-1)
  - [ ] `fromWayCode` + 값 검증 + reader 위임 + **회귀 시험** (§2-2)
  - [ ] DTO 2개 (§2-3)
  - [ ] `SubwayTrainMatcher` + 단위 시험 (§2-4) ← **여기가 본체. 시험 먼저**
  - [ ] `SubwayBindingMatchService` — 조회창 보정·영업일·세션 재확인·버전 규칙 (§2-5)
  - [ ] 저장소 질의 3개 (§2-6)
  - [ ] 스케줄러 (§2-7)
  - [ ] 시험 전량 + XML 확인 (§2-8, §2-9)
- [ ] **PR-4b** (M) — 4a 머지 후 fresh main 에서 `feat/journey-subway-binding-lifecycle`
  - [ ] `SubwayBindingLifecycleService` + 저장소 (§3-1)
  - [ ] 접점 4곳 연결 (§3-2)
  - [ ] 계승 규칙 + 근거 재발행 (§3-3)
  - [ ] 시험 (§3-4, §3-5)

이 플랜 파일은 **문서 PR 로 먼저 머지**한다(구현 PR 과 분리) — 구현은 새 세션이 main 의 이 파일을 읽고 시작한다.

## 9. 개정 이력

**개정 2 (2026-08-14) — codex 설계 리뷰 반영**

| # | 지적 | 처리 |
|---|---|---|
| 1 | L4 가 역 필터 없이 비교 → 엉뚱한 역 도착과 매칭 | **수용.** 비교역을 후보들의 "출발 후 첫 도착"으로 고정, 갈리면 확정 안 함 (§2-4) |
| 2 | `wayCode` 무검증 → 방향 지어냄 | **수용, 단 수정.** 리뷰의 "0/1 화이트리스트"는 `updnLine` 값 공간이라 그대로 쓰면 하행이 죽는다. **노선별 화이트리스트**로 (§2-2) |
| 3 | `matchWindow`/`confirmWindow` 중복 적용 | **수용.** 전자는 PENDING, 후자는 잠정 판정 시각 기준으로 분리 (§2-1) |
| 4 | 관측이 `version` 을 안 올려 낡은 CAS 성공 | **수용.** 순번을 CAS 조건에 추가 (§2-6) |
| 5 | 종료·재탐색과 CAS 경합 | **수용.** 쓰기 트랜잭션 앞에서 세션 행 `FOR SHARE` 재확인 (§2-5) |
| 6 | 감사행 `binding_version` pre/post 미정 | **수용.** `expectedVersion + 1` 로 명시 (§2-5) |
| 7 | `abandonOpen` 이 identity 를 지워 계승 규칙과 모순 | **수용.** 상태·version 만 바꾼다 (§3-1) |
| 8 | 재탐색 후 `PROVISIONAL` 재매칭 경로 없음 | **수용.** 구간 동일하면 근거까지 새 leg 로 재발행 (§3-3) |
| 9 | 판정 불가 `PENDING` 이 배치를 독점 | **수용.** 스캔에서 빼고 별도 청소 질의 (§2-6) |
| 10 | Redis 색인이 중간점이라 넓은 구간 누락 | **수용.** 조회창 확대 + 겹침 후필터 (§2-5) |
| 11 | `serviceDate` 유도 미정, 04:00 경계 | **수용.** `ServiceDateResolver` + 걸치면 두 날짜 조회 (§2-5) |
| — | (리뷰 밖) 종착역 배제의 근거 데이터 부재 | **MVP 에서 제거.** 잘못 배제하면 관문이 헐거워져 "잘못 고정" 쪽으로 틀어진다 (§2-4, §7) |
