# 남은 구간 재검색 설계 (Phase 6 ④ `journey-reroute-remaining-search`)

- 티어: **L** · 마이그레이션: **없음** · 작성일: 2026-08-12
- 상위: [PHASE-6.md §④](../journey-navigation/PHASE-6.md) · 선행: ①(#135) ②(#133) ③(#136) 모두 머지됨
- 후속: ⑤ 수락(`REROUTE_ACCEPTED` 해제) · ⑥ 알림 통로

## 0. 한 문장

**지도 앱에서 "길찾기"를 다시 누르는 것**이다. 단, 출발지를 "지금 내 위치"가 아니라 **곧 내릴 환승 정류장**으로 놓고 누른다.

③과의 차이가 사다리의 두 칸을 가른다.

| | ③ 같은 정류장 다른 버스 | ④ 남은 구간 재검색 |
|---|---|---|
| 바꾸는 것 | 대기 단계의 **노선만** | 대기 단계 **이후 경로 전체** |
| 외부 경로 검색 | **0회** | ODsay 1회 (캐시가 맞으면 0회) |
| 최악 대기 | 4.5초 | 6.5초 |
| 언제 도나 | 먼저 | **③이 빈손일 때만** |

순서는 이미 코드에 있다 — 공급자의 `priority()`(③=0, ④=1)를 보고 앞에서 후보가 나오면 뒤는 부르지 않는다.

## 1. 확정 결정

| # | 결정 | 기각한 대안 |
|---|---|---|
| D1 | **검색 시작점은 환승 정류장으로 고정**한다. 좌표는 개정본 `routeSnapshot` 을 `JourneyCompiler` 로 다시 컴파일해 `legIndex` 로 찾는다 | 사용자 현재 위치 — 서버는 정밀 좌표를 받지 않는다(프라이버시 계약). 열려면 앱 배포·위치정보법 대응·LOCKED 문서 3곳 변경이 따라온다(§8) |
| D2 | **ODsay 응답을 Redis 에 캐싱**한다. 키는 여정과 무관한 공유 키, TTL 10분 | 캐시 없이 시작 — ③과 달리 이건 유료 호출이다. cooldown 만으로는 같은 구간을 묻는 다른 사용자까지 막지 못한다 |
| D3 | 기준선을 못 잰 경우(③이 넘긴 상황) **임계값을 묻지 않고 가장 빠른 1건만** 낸다. `etaGainSec = 0`, 신뢰도 하향 | 배차간격(`term`)으로 대체 기준선 — `TransferPredictionResult` 에 `term` 이 없어 포트 확장이 필요(PR 하나 늘어남) / ④도 빈손 — 사다리 설계가 무의미해진다 |
| D4 | **새 경로의 첫 대기만** 실시간·ML 로 잰다. 이후 대기는 0으로 두고 신뢰도를 `APPROXIMATE` 로 낮춘다. **대신 후보의 이후 대기 수가 기준선보다 많으면 버린다**(D4-b) | 모든 환승 예측 — 정확하지만 후보당 호출이 환승 수만큼 곱해진다. **후속 과제로 남긴다**(§9) |
| D4-b | **후보의 "이후 대기 수 > 기준선"이면 후보를 버린다**(`more-transfers`) | 그냥 신뢰도만 낮춘다 — ③에서는 구조가 안 바뀌어 생략한 대기가 양쪽에서 상쇄됐지만 **④는 구조를 바꾸므로 상쇄되지 않는다.** 환승이 더 많은 후보만 낙관되어 **없는 300초 개선**이 만들어지고, `APPROXIMATE` 표시는 순위를 바로잡지 못한다 |
| D5 | **지하철도 후보로 인정**한다. 단 **실시간만** 본다 — 모델도 시간표도 쓰지 않는다 | 버스만 — 서울 경로는 지하철이 섞이는 게 흔해 후보가 거의 안 남는다 |
| D6 | **`BoardingTimeEstimator` 포트를 새로 만든다.** DB 를 읽지 않고 값만 받아 승차 시각을 추정한다 | 기존 `TransferTargetArrivalPredictor` 재사용 — 그것들은 `JourneyLeg` 를 받아 **내부에서 선택 노선을 DB 에서 읽는다**. ④의 후보는 아직 DB 에 없다 |
| D7 | **기준선은 ④가 스스로 다시 잰다** | `RerouteContext` 로 ③의 기준선을 실어 나르기 — ②의 계약을 바꿔야 하고 공급자 사이에 순서 의존이 생긴다. 게다가 ③이 "기준선을 못 쟀다"로 빈손인 경우엔 실어 줄 값도 없어 결국 두 경로가 다 필요해진다 |
| D8 | **걷기만으로 목적지에 닿는 경로도 인정**한다 | 대중교통 필수 — 환승 정류장에서 걸어갈 만한 거리면 그것도 정당한 답이다. 첫 대기가 없어 예측도 필요 없다 |
| D9 | 평가할 경로 수 상한 **2개**, 최소 개선폭 **300초**(③과 같은 값, **설정 키는 별도**) | ③과 같은 3개 — ④는 앞에 이미 2초를 쓴 상태다 |

## 2. ODsay 응답에서 무엇을 쓰나

**다시 재는 것은 "첫 차가 언제 오나" 하나뿐이다.** 나머지는 그대로 쓴다.

| ODsay 가 주는 값 | 쓰나 | 왜 |
|---|---|---|
| 경로 구조(정류장·노선·하차·도보) | **쓴다** | 이걸 얻으려고 부른다 |
| 주행 소요(`sectionTime`) | **쓴다** | 버스 주행 20분은 언제 출발해도 대략 20분이다. 출발 시각과 무관 |
| 도보 소요 | **쓴다** | 같은 이유 |
| **대기 시간** | **애초에 안 준다** | 검색 결과에 그 값이 없다(`JourneyCompiler` 가 대기 단계 소요를 `null` 로 두는 이유) |

`OdSayApiPort.searchPath(sx, sy, ex, ey, timeoutMs)` 는 **좌표만 받고 미래 출발 시각을 못 받는다.** 그래서 "10분 뒤에 출발할 건데"를 전달할 방법이 없고, 대기 시간을 우리가 앵커(`userReadyAt`) 기준으로 다시 잰다. ③에서 굳힌 규칙과 같다.

## 3. 부품과 흐름

```
RerouteProposalService.request()               (②·③ 완성 — 손대지 않는다)
  ├ 소유권·ACTIVE·기능 플래그·적격성 판정        (③ 완성)
  └ providers 순회 (priority 순)
      ├ RouteOptionCandidateProvider    priority=0   ③
      └ RemainingPathCandidateProvider  priority=1   ④  ← 신규
           ① 좌표 확보
           │    JourneyRevision(ACTIVE).routeSnapshot → JourneyCompiler.compile()
           │    → legIndex == transfer.toLegIndex 초안의 startX/startY  (출발)
           │    → 마지막 초안의 endX/endY                              (도착)
           ② 경로 구조 얻기
           │    RemainingRouteSearchService.search(from, to, budgetMs)
           │       └ Redis 캐시 → miss 면 SingleFlight → OdSayApiPort.searchPath()
           ③ 기준선 재기 (D7)
           │    지금 선택 노선을 BoardingTimeEstimator 로 → RouteOptionEtaCalculator
           ④ 후보마다: compile → **transitIdEnricher.enrich** → 첫 대기 승차 시각 추정
           │    BoardingTimeEstimator (버스=실시간→ML, 지하철=실시간만)
           │    앵커 = userReadyAt + 첫 대기 앞의 도보 소요
           ⑤ 목적지 ETA 합산 · 견주기 · 정렬
                RemainingPathEtaComposer + RouteOptionEtaCalculator(③ 것 재사용)
```

### 3.1 신규

| 경로 | 내용 |
|---|---|
| `application/journey/service/RemainingPathCandidateProvider.java` | `RerouteCandidateProvider` 구현. `kind()=REMAINING_PATH`, `priority()=1` |
| `application/journey/service/RemainingRouteSearchService.java` | 남은 구간 경로 검색 + 캐시 + 단일비행. **`RouteSearchService` 를 부르지 않는다** — 검색이력·telemetry 부수효과가 여정 경로에 섞이면 안 되고 시작점도 다르다 |
| `application/journey/port/out/RemainingRouteCachePort.java` | 경로 구조 캐시 포트 |
| `infrastructure/adapter/out/cache/journey/RedisRemainingRouteCacheAdapter.java` | 위 구현. 키·TTL 소유 |
| `application/journey/port/out/BoardingTimeEstimator.java` | "DB 에 없는 대기 단계의 승차 시각을 값만 받아 추정한다". **선행 PR 로 뗀다**(PLAN.md §PR 두 개로 나눈다) |
| `application/journey/service/BusBoardingTimeEstimator.java` | `PredictTransferUseCase` 위임 (실시간→ML). 선행 PR |
| `application/journey/service/SubwayBoardingTimeEstimator.java` | `SubwayRealtimeArrivalReader` 위임 (실시간만). 선행 PR |
| `application/journey/service/RemainingPathEtaComposer.java` | ODsay 구조 + 추정 승차시각 → 목적지 ETA·화면값 |
| `application/journey/service/JourneyWaypointResolver.java` | `routeSnapshot` 재컴파일로 `legIndex` → 좌표 |
| `application/journey/config/JourneyRerouteProperties.RemainingPath` | 중첩 설정 |
| 시험 5종 | 아래 §7 |

### 3.2 수정

| 경로 | 변경 |
|---|---|
| `application/journey/observability/JourneyRerouteMetrics.java` | 필터 사유 2개 추가, 신규 지표 2개 |
| `application/journey/config/JourneyRerouteProperties.java` | `RemainingPath` 중첩 + `validate()` |
| `src/main/resources/application.yaml` | `journey.reroute.remaining-path.*` |
| `docs/plan/journey-navigation/PHASE-6.md` | ④ 서술 정정(§8) |
| `docs/plan/journey-navigation/SCOPE-CHANGES.md` | **SC-16** 추가 |

### 3.3 재사용 · 불변

- 재사용: `RouteOptionEtaCalculator`(③), `JourneyCompiler`, `SingleFlight`, `JourneyRerouteMetrics`, `RerouteCandidateApply.RemainingPathApply`(②의 계약)
- **건드리지 않음**: `RerouteProposalService`, `RerouteProposalTxService`, `RerouteEligibilityPolicy`, `RerouteCooldownPort`. ④는 빈 자리에 공급자 하나를 꽂는 것이라 ③처럼 머지된 포트를 고칠 일이 없다

### 3.4 `BoardingTimeEstimator` 를 새로 만드는 이유

기존 예측기 둘(`BusTransferTargetArrivalPredictor`, `SubwayTransferTargetArrivalPredictor`)은 시그니처가 `predictBoarding(JourneyLeg waitLeg, ...)` 이고 **내부에서 `routeOptionRepository.findById(leg.getSelectedRouteOptionId())` 로 DB 를 읽는다.** ④의 후보는 방금 ODsay 가 준 것이라 **아직 DB 에 없다** — 저장은 사용자가 수락한 뒤(⑤)다.

③에서 이미 같은 함정을 밟았다. `TransferTargetArrivalService` 를 재사용하려다 "그건 저장된 노선 하나만 본다"를 발견하고 한 겹 아래(`PredictTransferUseCase`)를 불렀다. **세 번째 반복을 막기 위해 이름 있는 자리를 만든다.**

```java
public interface BoardingTimeEstimator {
    boolean supports(JourneyLegType waitType);

    /**
     * @return 탈 수 있는 차가 없거나 근거가 없으면 값 없는 결과.
     *         "막혀서 못 물어봤다"와 "물어봤지만 근거가 없다"는 {@code blocked} 로 가른다
     */
    BoardingOutcome estimate(BoardingQuery query, Instant anchorAt, Instant now, long budgetMs);
}
```

**`Optional` 이 아닌 이유** — 지금 버스 예측기는 예측이 없을 때 `blocked`(막혀서 못 물어봤다)와 `unknown`(물어봤지만 근거가 없다)을 가른다. 막힌 상황에서 30초마다 다시 부르면 막힌 문을 계속 두드리기 때문이다. `Optional.empty()` 하나로 뭉치면 그 구분이 사라져 회귀다. 지하철은 `blocked` 를 항상 `false` 로 둔다(실시간 조회에 호출량 제한이 없어 "막힘" 개념이 없다).

`BoardingQuery` 는 엔티티가 아니라 값이다 — 정류장/역 ID·이름, 노선 ID, `boardingSeq`, `wayCode`. `BoardingEstimate` 는 승차 시각 + **탈 수 있는지 여부** + 근거(`REALTIME`/`ML`) + 신뢰도 + 관측 시각.

**"탈 수 있는지"로 걸러내는 것은 추정기가 아니라 소비자다.** 같은 값을 두 소비자가 정반대로 쓴다.

- **여정 평가**는 못 타는 예측을 **일부러 통과시킨다.** 그 시각이 흘러가 "준비 시각보다 먼저 온 차"가 되고, 음수 여유가 곧 `MISSED` 판정의 근거다(`TransferRiskEvaluator:128`). 추정기가 걸러내면 여유가 `null` 이 되어 상태가 `UNKNOWN` 으로 떨어진다 — **"차를 놓쳤다"가 "모르겠다"로 바뀐다**
- **재탐색 후보**는 버린다. 지나간 차를 "이걸 타면 5분 빠릅니다"로 제안할 수 없다

지금 코드가 그렇게 갈려 있다(`BusTransferTargetArrivalPredictor:83` 은 안 보고, `RouteOptionCandidateProvider:256` 은 본다). 추정기를 만들 때 **③의 필터를 따라 넣으면 평가 경로가 조용히 망가진다.**

⑤가 수락 후 재검증할 때도 같은 자를 쓸 수 있다.

**이 포트는 선행 PR 로 뗀다.** 포트와 구현만 떼면 소비자 없는 죽은 코드가 머지되므로, 그 PR 에서 **기존 예측기 둘이 새 자에 위임하게** 만든다 — 소비자가 생기고, 무엇보다 기존 시험이 "새 자가 같은 답을 낸다"를 증명한다. 그때 `blocked`(막혀서 못 물어봤다)와 `unknown`(물어봤지만 근거 없음)의 구분을 잃지 않는 것이 관문이다(PLAN.md §3.3-b).

③의 `RouteOptionCandidateProvider` 는 **옮기지 않는다** — 동작 불변 리팩터링을 두 PR 에 걸쳐 늘리지 않는다. 후속 정리 과제다.

| | 버스 | 지하철 |
|---|---|---|
| 부르는 것 | `PredictTransferUseCase.predict(...)` | `SubwayRealtimeArrivalReader.arrivalsByStation(...)` |
| 근거 | 실시간 → 못 잡으면 ML | **실시간만** |
| 못 잡으면 | ML 로 내려감 | 빈 결과 |
| 시간표 대체 | — | **안 한다**(SC-6). 배차가 실제와 어긋나면 그럴듯하게 틀린 시각이 사용자를 엉뚱한 때에 움직이게 한다 |

## 4. 좌표를 어떻게 얻나 (D1)

걸림돌: 서버는 사용자 위치를 모르고, `journey_leg` 에는 **좌표 칸이 아예 없다**.

해결: 여정 시작 때 찍어 둔 사진을 다시 펼친다.

1. `JourneyRevision` 에서 현재 개정본(`status=ACTIVE`)의 `routeSnapshot` 을 읽는다 — 개정본 1도 시작 때 행이 만들어진다
2. `JourneyCompiler.compile(snapshot)` 로 다시 쪼갠다 → `JourneyLegDraft` 목록에 `legIndex` 와 `startX/startY/endX/endY` 가 함께 있다
3. `legIndex == transfer.toLegIndex` 인 초안의 시작 좌표 = **검색 출발점**
4. 마지막 초안의 끝 좌표 = **검색 도착점**

**저장된 단계와 같은 규칙으로 쪼개니 번호가 어긋나지 않는다.** 이 가정이 이 설계의 유일한 외줄이라 시험으로 못 박는다(§7). 마이그레이션도 새 컬럼도 필요 없다.

`fromLegIndex` = `transfer.toLegIndex`. **대기 단계부터 교체하고 지금 타고 있는 버스는 그대로 타고 간다.**

## 5. 후보 판정

### 5.1 걸러내는 것

| 사유 태그 | 조건 |
|---|---|
| `same-as-current` | 새 경로의 **첫 대중교통 구간의 `routeId`** 가 지금 대기 단계의 선택 노선 `routeId` 와 같다(둘 다 해석된 경우에만 비교한다 — 어느 한쪽이 비어 있으면 같다고 보지 않는다). ③이 이미 본 것을 더 비싼 방법으로 다시 제안하는 셈 |
| `no-direction` | 첫 대기가 지하철인데 `wayCode` 가 없다. 방향을 모르면 반대 방향 열차를 집는다 |
| `missing-duration` | 주행·도보 소요가 비었다. 0으로 치면 없는 개선폭이 생긴다 |
| `unboardable` | 사용자가 그 정류장에 닿기 전에 지나갈 차다 |
| `unresolved-route` | enrich 후에도 첫 대기의 노선·순번을 못 채웠다 |
| `more-transfers` | 후보의 이후 대기 수가 기준선보다 많다(D4-b) |
| `no-prediction` | 예측 자체가 없다 |
| `gain-below-threshold` | 개선폭 300초 미만 |
| `budget-exhausted` | 누적 예산 초과로 남은 경로를 건너뛰었다 |

**걸러내지 않는 것**: 대중교통 없이 걷기만으로 목적지에 닿는 경로(D8). 첫 대기가 없어 예측이 필요 없고 도보 소요만 더한다.

### 5.2 견주는 규칙

**기준선이란** 지금 대기 단계가 타기로 한 노선(`waitLeg.selectedRouteOptionId`)을 D7 대로 다시 재서 얻은 목적지 ETA 다. 그 노선을 해석할 수 없거나 탈 수 있는 차가 없으면 **"기준선을 못 쟀다"** 가 된다 — ③이 같은 이유로 빈손을 낸 상황이다.

| 상황 | 판정 |
|---|---|
| 기준선을 쟀다 | 목적지 ETA 가 300초 이상 빠른 것만. `etaGainSec` = 기준선 ETA − 후보 ETA |
| **기준선을 못 쟀다** (D3) | 임계값을 묻지 않고 **목적지 ETA 가 가장 이른 후보 하나만**. `etaGainSec = 0`, `confidence = APPROXIMATE`(이후 환승 유무와 무관하게 강제) |
| 정렬 | 개선폭 내림차순. **개수 절단은 저장 쪽**(`maxCandidates=3`) |

기준선을 못 쟀을 때 신뢰도를 무조건 낮추는 이유는 `etaGainSec = 0` 이 "개선이 없다"로 읽힐 수 있어서다. 앱은 신뢰도를 함께 보고 "정확한 비교는 못 했지만 이 길로 갈 수 있다"로 표시한다.

### 5.3 화면값

③과 같은 규칙이다. 다른 점 하나: **`transferCount` 는 새 경로의 환승 수**다(③은 구조가 안 바뀌어 기준선과 같았다). `confidence` 는 이후 환승이 남으면 `APPROXIMATE`. `apply` = `RemainingPathApply(fromLegIndex, path)` — ODsay 가 준 경로 원본을 그대로 담아야 ⑤가 새 단계를 만들 수 있다.

## 6. 외부 호출 관리

### 6.1 캐시 (D2)

```
키   journey:reroute:remaining:{stopId}:{destLat}:{destLon}
값   PathResult 목록
TTL  10분
```

- **출발은 정류장 ID로** 잡는다. 좌표로 잡으면 소수점 때문에 키가 갈라져 캐시가 거의 안 맞는다
- **목적지는 좌표 소수점 5자리**(약 1m)로 자른다. 목적지는 정류장이 아닐 수 있어 ID 가 없고, 한 여정 안에서는 고정값이라 자리수를 잘라도 갈라지지 않는다
- **여정과 무관한 공유 키다.** 같은 정류장에서 같은 곳으로 가려는 다른 사용자도 그 답을 쓴다
- **TTL 10분의 근거는 주행 소요다.** 구조는 잘 안 바뀌지만 주행시간은 시간대에 따라 다르다 — 오래 들고 있으면 출퇴근 값을 한산한 시간에 쓴다

### 6.2 중복 호출 네 겹

```
① 재요청 잠금 (2분, REQUEST 키)   ← ③이 만든 것, 이미 지난 관문
② Redis 캐시 (10분)               ← 여기서 대부분 끝난다
③ SingleFlight (키는 ②와 같게)     ← 동시에 몰린 같은 질문은 한 번만
④ OdSayApiPort.searchPath()        ← 실제 유료 호출
```

### 6.3 시간 예산

③의 방식을 그대로 쓴다 — **누적 예산을 두고 남은 예산을 다음 호출의 상한으로도 넘긴다.** 호출 전에만 확인하면 마감 직전에 시작한 호출이 예산을 넘긴다(③ 리뷰에서 잡힌 것).

| 호출 | 예산 |
|---|---|
| ODsay 검색 1회 | 2000ms (검색 경로가 쓰는 값과 같게) |
| 기준선 예측 1회 | 1500ms — **누적 예산 밖**. 없으면 어차피 결과가 없다 |
| 후보별 첫 차 예측 | 누적 3000ms 안에서, 남은 예산이 상한 |

**요청 하나의 최악 대기 = 6.5초.** 캐시가 맞으면 2초가 빠지고, ③이 답을 내면 여기까지 오지 않는다.

### 6.4 실패는 전부 조용하다

| 실패 | 결과 |
|---|---|
| ODsay 죽음·느림 | 빈 결과 + 지표 |
| **Redis 죽음** | 캐시를 건너뛰고 직접 호출. **cooldown 과 반대다** — 잠금은 fail-closed 지만 캐시는 없으면 그냥 없는 것 |
| 후보 하나의 예측 실패 | 그 후보만 버린다(③ 리뷰 반영과 같게) |
| 좌표 확보 실패 | 빈 결과 |

전부 "제안 없음"이다. **재탐색 실패가 진행 중인 안내를 흔들면 안 된다.**

### 6.5 지표

- `recordCandidateFiltered` 사유에 `same-as-current`·`no-direction` 추가 (**목록에 안 넣으면 조용히 `unknown` 으로 접힌다**)
- 신규 `journey.reroute.remaining.search` — 태그 `outcome`: `cache-hit`·`single-flight-join`·`called`·`failed`. **`cache-hit` 비율이 이 칸의 요금을 결정한다**
- 신규 `journey.reroute.remaining.paths` — ODsay 가 준 경로 수 대비 평가한 수

### 6.6 설정

```yaml
journey:
  reroute:
    remaining-path:
      minimum-eta-gain-seconds: 300   # ③과 같은 값이지만 키는 따로 — ④는 유료 호출이 붙는다
      max-paths: 2                    # 평가할 경로 수. 앞에 이미 2초를 썼다
      evaluation-budget-ms: 3000      # 후보 평가 누적. 남은 값이 다음 호출 상한
      search-budget-ms: 2000          # ODsay 1회
      cache-ttl: PT10M
```

## 7. 시험 계획

### 통과해야 하는 것

- [ ] ③이 빈손일 때 ④가 돌아 **제안 1건**을 저장한다
- [ ] **좌표가 맞는 정류장을 가리킨다** — `routeSnapshot` 재컴파일 결과의 `legIndex` 가 저장된 단계와 일치한다(§4의 외줄)
- [ ] 지금 경로와 같은 노선으로 시작하는 경로는 `same-as-current` 로 걸러진다
- [ ] **캐시가 맞으면 ODsay 를 부르지 않는다**
- [ ] 동시에 두 요청이 오면 ODsay 호출은 **1회**다(SingleFlight)
- [ ] **Redis 가 죽어도 제안이 나온다**(캐시는 fail-open)
- [ ] ODsay 가 죽으면 빈 결과다. 예외가 아니다
- [ ] 첫 대기가 지하철인 경로도 후보가 된다. `wayCode` 없으면 `no-direction`
- [ ] 지하철은 실시간이 없으면 후보가 아니다 — **시간표로 채우지 않는다**
- [ ] 걷기만으로 닿는 경로도 후보가 된다
- [ ] 기준선을 못 잰 경우 임계값 없이 **1건만** 나오고 `etaGainSec == 0`, 신뢰도가 하향된다
- [ ] 개선폭 300초 미만이면 빈 결과다
- [ ] 후보 하나의 예측이 터져도 **이미 모은 후보는 살아남는다**
- [ ] 누적 예산을 넘기면 남은 경로를 건너뛰고 부분 결과를 낸다. 남은 예산이 다음 호출 상한으로 넘어간다
- [ ] 이후 환승이 남은 후보의 신뢰도는 `APPROXIMATE`
- [ ] `apply` 에 담긴 경로 원본으로 `JourneyCompiler.compile()` 이 성립한다(⑤가 쓸 수 있는 모양)
- [ ] ②의 `RerouteCandidateContractTest` 가 ④의 후보로도 통과한다
- [ ] 기존 여정 평가·알림·③ 시험 회귀 없음 (`./gradlew build`)

### 시험 파일

| 파일 | 무엇 |
|---|---|
| `RemainingPathCandidateProviderTest` | 단위. 걸러내기·기준선 두 갈래·예산·부분 실패 |
| `RemainingRouteSearchServiceTest` | 단위. 캐시 히트/미스·SingleFlight·Redis 장애 fail-open |
| `RedisRemainingRouteCacheAdapterTest` | 단위. **키 모양과 TTL 이 계약이다** — 키가 바뀌면 히트율이 조용히 0 이 된다 |
| `JourneyWaypointResolverTest` | 단위. `legIndex` → 좌표 매핑 |
| `BoardingTimeEstimatorTest` | 단위. 버스·지하철 두 구현 |
| `RerouteRemainingPathIT` | 통합. ③이 빈손인 여정에서 제안 생성, ODsay 호출 수 검증 |

## 8. 문서 변경 (SC-16)

PHASE-6 ④ 서술 세 곳이 코드와 어긋난다. 고치고 근거를 SCOPE-CHANGES 에 남긴다.

| PHASE-6 서술 | 실제 | 왜 |
|---|---|---|
| "시작점은 **현재 위치 또는** 다음 환승 지점" | **환승 지점 고정** | 서버는 정밀 좌표를 받지 않는다. 여는 것은 MVP 밖(§9) |
| "후보 캐시는 **여정 TTL** 동안 Redis 에 둔다" | **10분 고정, 여정과 무관한 공유 키** | 구조는 사용자·여정과 무관하다. 특정 여정 수명에 묶을 이유가 없다 |
| "`WaitBusArrivalPredictor`·`TransferTargetArrivalService` 를 재사용한다" | **`BoardingTimeEstimator` 신규** | 기존 예측기는 저장된 단계를 받아 DB 를 읽는다. ④의 후보는 DB 에 없다 |

## 9. 의도적으로 남긴 것

- **새 경로의 이후 환승은 예측하지 않는다**(D4). 절대 도착 시각이 낙관적이므로 신뢰도를 낮춰 표시한다.
  **개선폭 비교에 영향이 없다는 ③의 논리는 ④에 그대로 적용되지 않는다** — ③은 경로 구조가 그대로라
  생략한 대기가 기준선·후보에 똑같이 빠졌지만, ④는 구조를 바꾸므로 환승 수가 다르면 상쇄되지 않는다.
  그래서 D4-b(환승 수가 더 많은 후보를 버린다)로 **개선폭이 항상 보수적**이 되게 막는다.
  대가는 "환승이 하나 늘지만 훨씬 빠른 경로"를 놓치는 것이고, 그걸 정직하게 평가하려면 모든 대기를
  예측해야 한다 — 호출이 환승 수만큼 곱해지므로 후보 수 축소가 함께 와야 하는 후속 과제다
- **사용자 현재 위치 기반 재탐색.** "여기서 내려서 걸어가는 게 빠릅니다" 류는 좌표 수집이 필요하고, 그건 MVP 밖으로 결정했다 — 이탈을 감지해도 대응 통로(⑤⑥)가 없어 shadow 계측만 쌓이기 때문이다. 순서는 ④→⑤→⑥→운영 배포·오차 측정→좌표 판단
- **③의 `RouteOptionCandidateProvider` 를 `BoardingTimeEstimator` 로 옮기지 않는다.** 선행 PR 이 이미 예측기 둘을 옮기므로, 동작 불변 리팩터링을 그보다 더 늘리지 않는다
- **`journey.reroute.enabled` 는 계속 false.** 켜는 것은 ⑥ 이후이고 PHASE-5 §1.1 절차를 함께 탄다

## 10. 함정

- **기존 예측기를 재사용하려 하지 마라.** `JourneyLeg` 를 받아 DB 를 읽는다. ④의 후보는 DB 에 없다(§3.4)
- **대기 단계 소요는 언제나 `null` 이다.** "없으면 포기"로 짜면 환승이 둘 이상인 여정이 통째로 죽는다(③의 R1 과 같은 함정)
- **예산은 호출 전에만 보지 마라.** 마감 직전에 시작한 호출이 제 몫을 다 쓰면 총 대기가 예산을 넘는다
- **후보 루프의 예외는 후보마다 잡아라.** 바깥 `try/catch` 만 있으면 하나 터질 때 이미 모은 후보까지 날아간다
- **캐시는 fail-open, cooldown 은 fail-closed.** 둘을 같은 규칙으로 다루면 하나가 틀린다
- **새 사유는 `JourneyRerouteMetrics` 목록에 넣어라.** 안 넣으면 조용히 `unknown` 으로 접혀 운영에서 아무것도 못 본다
- **`Stream.findFirst()` 는 null 원소에 NPE 다.** 정류장·노선 이름은 비어 있을 수 있다(③에서 후보가 통째로 사라진 실제 버그)
- **`git add -A` 금지.** 경로를 지정해 커밋한다
- 통합 시험에서 `boardingSeq`·`start_stop_id` 는 정적 노선표가 있어야 채워진다. 시험 DB 에는 없으므로 SQL 로 직접 심어야 후보 생성이 돌아간다
