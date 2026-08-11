# Phase 6 ③ — 같은 정류장 다른 버스 후보 (`journey-reroute-route-options`)

- 티어: **L** · 브랜치: `feat/journey-reroute-route-options` (fresh `main` 기준)
- 상위: [PHASE-6.md §③](../journey-navigation/PHASE-6.md) · 선행 PR: ①(#135) ②(#133) 모두 머지됨
- 마이그레이션: **없음**
- 작성일: 2026-08-11 · codex 리뷰 반영: 2026-08-11

## 0. 이 PR 이 만드는 것

환승 버스를 못 탈 것 같을 때 **같은 정류장에 서는 다른 버스 번호**를 찾아 "이걸 타면 N초 빠릅니다"를
제안으로 저장한다. 승·하차 정류장이 그대로라 **ODsay 경로 검색을 한 번도 부르지 않는다**(이 PR 의
존재 이유이자 완료 조건).

②가 만든 저장소·API·후보 계약은 이미 있고 후보를 **만들어 주는 코드만 없다**. ③이 그 자리를 채운다.

### 착수 시점의 실제 코드 상태 (2026-08-11 확인)

| 부품 | 상태 |
|---|---|
| `RerouteCandidateProvider` 구현체 | **0개**. 그래서 제안이 항상 0건 |
| `RerouteEligibilityPolicy` 구현체 | **없음**. `JourneyRerouteConfig:27` 의 "항상 거절"(`disabled`) 임시 빈이 살아 있어 `POST .../reroute-proposals` 는 무조건 409 |
| `RerouteTriggerEvaluator` | ①에서 완성. 신호를 세고 로그만 남긴다. `RerouteProposalService` 를 주입받지 않는다 |
| `journey_leg_route_option` | 여정 시작 때 채워지고 **읽는 코드는 `BusTransferTargetArrivalPredictor` 의 선택 노선 1건 조회뿐** |
| 목적지 ETA 합산기 | **없음**. `JourneyLeg.plannedDurationSec` 은 있고 downstream 을 더하는 코드가 아무 데도 없다 |
| 대기 단계 소요시간 | **항상 null**. `JourneyCompiler.java:209` — "기다리는 시간은 검색 결과에 없다". `RIDE_*`·`WALK`·`TRANSFER_WALK` 는 채워진다 |

## 1. 확정 결정

### 1.1 사용자 승인 (2026-08-11)

| # | 결정 | 기각한 대안 |
|---|---|---|
| D1 | **제안은 사용자가 요청할 때만 만든다.** `RerouteTriggerEvaluator` 는 계속 세기만 한다(shadow 유지) | 신호 발생 시 자동 생성 — 알림 통로(⑥)가 없어 사용자가 볼 수 없고, 발생률을 아직 못 잰 상태에서 실시간·ML 호출만 늘어난다 |
| D2 | **`RerouteEligibilityPolicy` 실구현을 이 PR 에 포함**한다 | 별도 선행 PR — PR 이 늘고 ③이 머지돼도 API 가 막힌 상태로 남는다 |
| D3 | **`journey_leg_route_option` 을 DB 에서 직접 읽는다.** Redis 사본을 만들지 않고, 대신 `TECHNICAL_DECISIONS §4`·`SC-10` 의 캐시 문장을 고친다 | 캐시 구현 — 만료·무효화 규칙이 새로 생기고 검증량이 두 배 |
| D4 | **목적지 ETA 를 비교 기준으로 쓴다.** 승차 예측 시각 + 승차 소요 + 이후 단계 소요 합 | "다음 차가 언제 오냐"만 비교 — 빨리 오는 차가 더 늦게 도착할 수 있다 |
| D5 | **기존 경로도 같은 호출로 다시 잰다.** 저장된 `TransferConnection.nextBusArrivalAt` 을 기준선으로 쓰지 않는다 | 저장값 재사용 — 최대 30초 낡은 값과 방금 값을 빼면 개선폭이 가짜가 된다 |
| D6 | **재요청 잠금(cooldown) 키를 용도별로 가른다** | 키 공유 — shadow 신호가 잠금을 태워 사용자 요청이 2분간 거절된다 |
| D7 | **`WAIT_BUS` 만 다룬다.** `WAIT_SUBWAY` 는 빈 결과 | 지하철 포함 — 같은 역 다른 노선은 목적지가 달라 "같은 승·하차 구간" 전제가 깨진다 |

### 1.2 codex 리뷰 반영 (2026-08-11) — 초안을 바꾼 항목

초안은 네 군데가 **머지된 코드와 어긋나거나 기능을 죽이는 규칙**이었다. 전부 코드로 확인하고 고쳤다.

| # | 초안의 문제 | 확인한 근거 | 고친 규칙 |
|---|---|---|---|
| R1 | 이후 단계 소요가 하나라도 null 이면 빈 결과 → **환승이 둘 이상인 여정은 영원히 제안 0건** | 대기 단계 소요는 언제나 null 로 저장된다(`JourneyCompiler.java:209`, `JourneyLegDraft.java:23`) | 이후 **대기 단계는 0으로 두고 건너뛴다**. 모든 후보에 똑같이 붙는 값이라 개선폭에는 영향이 없다. 대신 이후 환승이 남아 있으면 화면 신뢰도를 `APPROXIMATE` 로 낮춘다 (§3.5) |
| R2 | 적격성 판정이 최신 개정본을 조회한다고 썼는데 그런 조회 메서드가 없고, 이미 지나간 환승도 통과한다 | 포트는 `(journeyId, transferIndex, now)` 만 준다(`RerouteEligibilityPolicy.java:21`). 저장소는 `routeRevision` 을 요구한다(`TransferConnectionRepository.java:19`). 시간 조건은 음수 간격도 600초 이하라 통과한다(`RerouteTriggerPolicy` 의 시간 판정) | **포트 시그니처를 바꿔 `JourneySession` 을 넘긴다.** `routeRevision`·`currentLegIndex` 를 거기서 얻고, `toLegIndex >= currentLegIndex` 로 지나간 환승을 거절한다 (§3.2) |
| R3 | 후보 생성이 트랜잭션 밖이라 저장 시점에 여정 상태가 바뀌었을 수 있는데 확인이 없다 | `store` 는 세션을 다시 읽지 않고 넘겨받은 개정본을 현재값처럼 쓴다(`RerouteProposalTxService.java:82`) | **저장 트랜잭션 안에서 여정을 다시 읽어** `ACTIVE`·`commandVersion`·`routeRevision` 이 그대로인지 확인하고, 어긋나면 저장하지 않는다 (§3.6) |
| R4 | 예측 시각이 있으면 유효한 후보로 봤다 | 예측 결과에 `boardable` 이 따로 있고, 탈 수 있는 차가 없으면 **이미 지나간 차**를 돌려주는 경로가 있다(`TransferPredictionResult.java:8`) | `Boolean.TRUE.equals(boardable)` 인 예측만 인정한다. 기준선이 못 타는 상태면 빈 결과 (§3.4-8) |

추가로 반영한 것: 거절 사유 어휘를 기존 지표 목록에 맞춤(§3.2), 선택 노선 제외·중복 노선 제거(§3.4-5),
요청 전체 시간 예산 도입(§3.7), 단계 종류 이름 정정(`WALK`·`TRANSFER_WALK`), 예측 결과 해석 규칙 공유(§2.2).

### 1.3 남긴 한계 (의도적)

- **화면에 보이는 목적지 도착 시각은 "이후 환승 대기 시간을 뺀" 값**이다. 검색 결과에 그 값이 없다.
  개선폭 비교는 정확하지만 절대 시각은 낙관적이다 — 그래서 이후 환승이 있으면 신뢰도를 낮춰 표시한다.
  정확한 절대 시각은 이후 환승마다 예측을 한 번씩 더 부르는 일이고, 그건 요금과 지연을 부른다.
- **지금 타고 있는 버스로는 못 타는 상황(기준선이 탈 수 없음)에서는 ③이 빈손을 낸다.** 개선폭을 잴
  기준이 없기 때문이다. 사다리 순서상 그 다음 칸(④ 다른 경로 재검색)이 받는다. 빈도는 지표로 잰다.

## 2. 변경 파일

### 2.1 신규

| 경로 | 내용 |
|---|---|
| `application/journey/service/RouteOptionCandidateProvider.java` | `RerouteCandidateProvider` 구현. `kind()=ROUTE_OPTION`, `priority()=0` |
| `application/journey/service/RouteOptionEtaCalculator.java` | 목적지 ETA 합산 + 이후 환승 존재 여부 판정 |
| `application/journey/service/WindowCooldownEligibilityPolicy.java` | `RerouteEligibilityPolicy` 실구현 |
| `application/journey/service/TransferPredictionInterpreter.java` | 예측 결과 해석 공유 — 실시간/모델 선택 기준, 근거 이름, 신뢰도 등급 |
| `test/.../service/RouteOptionCandidateProviderTest.java` | 단위 |
| `test/.../service/RouteOptionEtaCalculatorTest.java` | 단위 |
| `test/.../service/WindowCooldownEligibilityPolicyTest.java` | 단위 |
| `test/.../journey/RerouteRouteOptionIT.java` | 통합. **ODsay 포트 호출 0회 검증 포함** |

### 2.2 수정

| 경로 | 변경 |
|---|---|
| `application/journey/port/out/RerouteEligibilityPolicy.java` | 시그니처를 `denialReason(JourneySession journey, int transferIndex, Instant now)` 로 (R2) |
| `application/journey/service/RerouteProposalService.java` | 이미 읽은 `JourneySession` 을 정책에 넘긴다. `store` 가 빈 결과를 낼 수 있게 반환값 처리 (R3) |
| `application/journey/service/RerouteProposalTxService.java` | `store` 안에서 여정 재확인 → 어긋나면 저장 없이 `Optional.empty()` (R3) |
| `application/journey/port/out/RerouteCooldownPort.java` | `tryAdmit` 에 용도 구분(`Scope`) 추가 (D6) |
| `infrastructure/adapter/out/cache/journey/RedisRerouteCooldownAdapter.java` | 키에 용도 구분 반영 |
| `application/journey/service/RerouteTriggerEvaluator.java` | `tryAdmit` 에 `TRIGGER` 전달 + 낡은 주석 정정("③이 켠다" → D1 로 shadow 유지) |
| `application/journey/service/RerouteTriggerPolicy.java` | 시간 조건을 `withinWindow(Instant userReadyAt, Instant now)` 로 뽑아 public 노출. 기존 판정이 그것을 부른다(임계값 한 곳 유지) |
| `application/journey/service/BusTransferTargetArrivalPredictor.java` | private 해석 로직을 `TransferPredictionInterpreter` 로 옮기고 위임 (동작 불변) |
| `application/journey/config/JourneyRerouteProperties.java` | 중첩 클래스 `RouteOption` 추가 + `validate()` + 낡은 주석 정정 |
| `application/journey/observability/JourneyRerouteMetrics.java` | 사유 목록 확장, 후보 관련 지표 두 개 추가 (§3.8) |
| `application/journey/config/JourneyRerouteConfig.java` | 임시 빈이 더는 안 쓰인다는 사실을 주석에 명시 |
| `src/main/resources/application.yaml` | `journey.reroute.route-option.*` |
| `docs/plan/journey-navigation/TECHNICAL_DECISIONS.md` §4(144행) | Redis 캐시 문장 → DB canonical 조회로 시작 |
| `docs/plan/journey-navigation/SCOPE-CHANGES.md` | **SC-15** 추가 (직전이 SC-14) |
| `docs/plan/journey-navigation/PHASE-6.md` | 진행 상황 갱신 |

## 3. 설계

### 3.1 요청이 흐르는 길

```
POST /api/journeys/{journeyId}/reroute-proposals   (②가 만든 컨트롤러)
  └ RerouteProposalService.request(userId, journeyId, transferIndex)
      ├ 소유권·ACTIVE 확인            (② 완성) → JourneySession 확보
      ├ properties.isEnabled()        (② 완성, 기본 false)
      ├ eligibilityPolicy.denialReason(journey, transferIndex, now)   ← ③ (R2)
      ├ providers 순회(priority 순)
      │   └ RouteOptionCandidateProvider.findCandidates(context)      ← ③
      └ txService.store(...)          → 저장 직전 여정 재확인 (R3)
```

### 3.2 `WindowCooldownEligibilityPolicy`

거절 사유는 **지표 태그**로 쓰이므로 값 종류를 고정한다. 기존 목록(`JourneyRerouteMetrics.java:33`)이
`disabled·not-active·cooldown·window·no-gain·already-active` 이고, 여기에 `no-transfer`·`past-transfer`·
`stale-context` 를 더한다. 목록 밖은 `unknown` 으로 접히므로 **새 사유는 반드시 목록에 넣는다**.

| 순서 | 검사 | 거절 사유 |
|---|---|---|
| 1 | `TransferConnectionRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(journeyId, journey.getRouteRevision(), transferIndex)` | `no-transfer` |
| 2 | `transfer.getToLegIndex() >= journey.getCurrentLegIndex()` | `past-transfer` |
| 3 | `userReadyAt != null` && 상태가 `UNKNOWN` 아님 && 신뢰도가 `UNKNOWN` 아님 | `window` |
| 4 | `RerouteTriggerPolicy.withinWindow(userReadyAt, now)` — 준비 시각까지 600초 이내 | `window` |
| 5 | `RerouteCooldownPort.tryAdmit(journeyId, routeRevision, transferIndex, REQUEST)` | `cooldown` |

- **2번이 R2 의 핵심이다.** 시간 조건만 보면 이미 30분 전에 지나간 환승도 "600초 이내"라 통과한다.
  요청 본문의 `transferIndex` 는 생략 시 0으로 채워지므로(`CreateRerouteProposalRequest.java:15`)
  옛 앱 버전이 보낸 빈 요청이 **첫 환승에 대한 제안**을 만들어 버린다. 진행 단계로 걸러야 막힌다.
- **3·4번은 ①과 같은 기준을 쓴다.** GPS 가 끊겨 근거가 사라지면 문이 저절로 닫히는 성질(`RerouteTriggerPolicy`
  의 시간 판정 주석)을 그대로 이어받는다. 두 경로가 임계값을 따로 갖지 않도록 판정은 한 메서드에 둔다.
- **cooldown 은 마지막에 묻는다.** 앞에서 떨어질 요청이 잠금을 태우면 실제로 가능한 요청이 2분간 막힌다.
- **`ACTIVE` 제안이 이미 있어도 막지 않는다.** `store` 가 이전 것을 `SUPERSEDED` 로 밀어내고 연타는
  cooldown 이 막는다. 여기서 또 막으면 만료 직전 제안 때문에 새 제안을 못 받는 상태가 생긴다.

### 3.3 cooldown 키 분리 (D6)

```java
public interface RerouteCooldownPort {
    enum Scope { TRIGGER, REQUEST }
    boolean tryAdmit(Long journeyId, int routeRevision, int transferIndex, Scope scope);
}
```

키: `journey:reroute:cooldown:{scope}:{journeyId}:{routeRevision}:{transferIndex}`
(현재 prefix 는 `RedisRerouteCooldownAdapter.java:27`).

SETNX + TTL 120초, 실패 시 거절(fail-closed)은 그대로다. ⑥에서 자동 생성을 켤 때 두 용도를 합칠지
다시 판단한다 — 그때는 실제로 같은 자원을 다투기 때문이다.

### 3.4 `RouteOptionCandidateProvider` 의 순서

1. `TransferConnectionRepository.findBy...TransferIndex...` → 없으면 빈 목록(적격성에서 이미 걸리지만
   provider 는 혼자서도 성립해야 한다).
2. `JourneyLegRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex` 로 **단계 전체**를
   한 번에 읽는다(이후 구간 합산에 필요).
3. `toLegIndex` 단계가 대기 단계다. `type != WAIT_BUS` 면 빈 목록(D7).
4. `JourneyLegRouteOptionRepository.findByJourneyLegIdInAndDeletedAtIsNull(List.of(waitLeg.getId()))`.
   `optionIndex` 오름차순 정렬은 코드에서 한다(메서드에 정렬이 없다).
5. **평가 대상 추리기** (R-추가):
   - 유효성: `routeId` 가 비지 않고 `boardingSeq != null`
   - **현재 선택 노선(`waitLeg.getSelectedRouteOptionId()`)은 대안 목록에서 뺀다.** 기준선으로만 쓴다
   - **`(routeId, directionKey)` 가 같은 후보는 하나만 남긴다.** 같은 노선이 여러 lane 으로 들어오면
     같은 예측을 두 번 부르고 평가 상한만 깎는다
   - `optionIndex` 순으로 `maxEvaluations`(기본 3)개까지
6. **기준선 먼저 평가한다.** 선택 노선을 §3.4-7 과 같은 방법으로 예측한다. 실패하면(예측 없음 또는
   못 타는 상태) **빈 목록**을 낸다 — 개선폭을 잴 기준이 없다(§1.3).
7. 각 후보를 `PredictTransferUseCase.predict(stopId, routeId, boardingSeq, userReadyAt, mode, budgetMs)` 로 평가:
   - `stopId` = `waitLeg.getStartStopId()` (모든 후보 공통)
   - 앵커 = `TransferConnection.getUserReadyAt()`. **`now` 로 대신하지 않는다** — 사용자가 도착하기 전에
     지나갈 차를 답으로 고르게 된다
   - `mode`·`budgetMs`·근거 이름·신뢰도 등급은 `TransferPredictionInterpreter` 가 정한다(§2.2, 기존
     `BusTransferTargetArrivalPredictor` 규칙과 동일)
8. **탈 수 있는 예측만 인정한다** (R4): `predictedArrivalAt != null` **그리고**
   `Boolean.TRUE.equals(prediction.boardable())`. 아니면 그 후보 탈락(`no-prediction` / `unboardable`).
9. `RouteOptionEtaCalculator` 로 후보·기준선의 목적지 ETA 를 낸다(§3.5).
10. `etaGainSec = 기준선 ETA − 후보 ETA`. `minimumEtaGainSeconds`(기본 300) 미만이면 탈락(`gain-below-threshold`).
11. `etaGainSec` 내림차순 정렬해 반환. **개수 절단은 하지 않는다** — `store` 가 `maxCandidates`(3)로 자른다
    (`RerouteProposalTxService.java:89`).
12. 전 과정을 `try/catch` 로 감싸 예외를 삼키고 빈 목록을 낸다. 포트 계약이 "예외를 던지지 않는다"이고,
    `RerouteProposalService.findCandidatesSafely` 가 한 겹 더 있지만 **그쪽은 지표를 남기지 않는다**.

**트랜잭션 없음.** 실시간·ML 호출을 하는 코드가 DB 커넥션을 잡고 있으면 안 된다.

### 3.5 `RouteOptionEtaCalculator` — 목적지 ETA (R1 반영)

```
목적지 ETA = 예측 승차 시각
           + 승차 단계 소요(대기 단계 다음 RIDE_* 단계의 plannedDurationSec)
           + 그 뒤 모든 RIDE_* · WALK · TRANSFER_WALK 단계의 plannedDurationSec 합
           ( 그 뒤 WAIT_* 단계는 0으로 둔다 — 검색 결과에 없는 값이다 )
```

- **이후 대기 시간을 0으로 두는 것이 개선폭을 왜곡하지 않는다.** 그 값은 모든 후보와 기준선에
  똑같이 빠지므로 뺄셈에서 상쇄된다. 왜곡되는 것은 화면에 보이는 절대 도착 시각뿐이다.
- **뒤에 대기 단계가 하나라도 남아 있으면 화면 신뢰도를 `APPROXIMATE` 로 낮춘다.** 예측이 아무리
  좋아도 그 값은 낙관적이기 때문이다. 계산기가 "이후 환승 있음"을 함께 돌려주고 provider 가 반영한다.
- **`RIDE_*`·`WALK`·`TRANSFER_WALK` 의 소요가 null 이면 그 후보를 버린다**(`missing-duration`). 이 값들은
  검색 결과에서 채워지므로(`JourneyCompiler.java:214`, `walkDurationSec`) 정상 여정에는 없어야 한다.
  없다면 데이터가 깨진 것이고, 0으로 치면 도착 시각이 실제보다 빨라진다.
- **승차 소요는 모든 후보가 공유한다.** ODsay 가 노선별 주행시간을 나눠 주지 않기 때문이다(SC-10).
- 기준선과 후보는 **같은 계산기·같은 상수항**을 쓴다. 다르게 계산하면 개선폭이 계산 방식의 차이가 된다.

### 3.6 저장 직전 재확인 (R3)

`RerouteProposalTxService.store` 첫머리에 넣는다. 후보를 만드는 동안(실시간·ML 왕복) 여정이 바뀔 수 있다.

```
세션 재조회 →  status == ACTIVE
            && commandVersion == context.commandVersion()
            && routeRevision  == context.routeRevision()
            && 해당 환승이 아직 진행 전(toLegIndex >= currentLegIndex)
아니면 → 저장하지 않고 Optional.empty(), metrics.recordRejected("stale-context")
```

`store` 의 반환형을 `Optional<RerouteProposalView>` 로 바꾸고 `RerouteProposalService.request` 는 그대로
빈 결과를 돌려준다. **오류가 아니다** — 사용자가 이미 다음 단계로 넘어갔거나 경로가 바뀐 것이고,
그 상황에서 제안을 심으면 지나간 환승에 대한 제안이 저장된다.

### 3.7 시간 예산 (codex #7)

후보 하나당 1500ms 예산이라 상한이 5면 기준선 포함 최대 9초다. 사용자가 화면에서 기다리는 요청이다.

- `maxEvaluations` 기본값을 **3**으로 둔다(대안 3개 + 기준선 1개 = 최대 4회)
- `evaluationBudgetMs`(기본 3000)를 두고 **누적 소요가 예산을 넘으면 남은 후보 평가를 멈춘다.**
  이미 평가한 후보로 제안을 만든다 — 부분 결과가 빈손보다 낫다
- 기준선 평가는 예산에서 빼지 않는다. 기준선이 없으면 어차피 빈 결과다
- 순차 실행이다. 병렬은 버스 API 동시 호출 제한과 얽히므로 이 PR 에서 하지 않는다

### 3.8 지표

- 기존: `recordRejected(reason)` — 사유 목록에 `no-transfer`·`past-transfer`·`stale-context` 추가
- 신규 `recordCandidateEvaluated(int count)` — 한 요청에서 예측을 부른 횟수(기준선 포함). 요금 추정용
- 신규 `recordCandidateFiltered(String reason)` — `no-prediction`·`unboardable`·`missing-duration`·
  `gain-below-threshold`·`baseline-unboardable`·`budget-exhausted`. **목록 고정**, 식별자 금지

### 3.9 화면 값 (`RerouteCandidateDisplay`)

| 필드 | 값 |
|---|---|
| `destinationEtaAt` | §3.5 결과 |
| `totalDurationSec` | `now` → `destinationEtaAt` 초 |
| `etaGainSec` | 기준선 ETA − 후보 ETA |
| `transferCount` | 남은 환승 수. 경로 구조가 안 바뀌므로 기준선과 같다 |
| `walkingSec` | 대기 단계 이후 `WALK`·`TRANSFER_WALK` 단계의 소요 합 |
| `source` | `REALTIME` / `ML`. `TransferPredictionInterpreter` 판정 |
| `confidence` | `GOOD` / `APPROXIMATE`. **이후 환승이 남아 있으면 무조건 `APPROXIMATE`** (§3.5) |
| `dataObservedAt` | `TransferPredictionResult.calculatedAt()` |
| `legSummaries` | 후보 노선명 + 승·하차 정류장명 |

`apply` = `RouteOptionApply(waitLeg.getLegIndex(), option.getOptionIndex(), option.getRouteId(), option.getDirectionKey())`.
`candidateId` 는 **비워 둔다** — `store` 가 UUID 를 채운다(②의 계약).

### 3.10 설정

```yaml
journey:
  reroute:
    route-option:
      minimum-eta-gain-seconds: 300
      max-evaluations: 3
      evaluation-budget-ms: 3000
```

`JourneyRerouteProperties` 주석이 "cooldown·최소 개선폭은 여기 없다"고 적었는데 ①이 `trigger.cooldown` 을
이미 여기 넣었다. ③도 같은 자리에 넣고 **그 주석을 현실에 맞게 고친다**.

## 4. 작업 순서

- [ ] **1.** `feat/journey-reroute-route-options` 브랜치를 fresh `main` 에서 딴다
- [ ] **2.** `RerouteCooldownPort` 에 `Scope` 추가 → 어댑터·`RerouteTriggerEvaluator`·기존 시험 수정.
      검증: `./gradlew test --tests '*RerouteCooldown*' --tests '*RerouteTrigger*'`
- [ ] **3.** `RerouteTriggerPolicy.withinWindow` 공개 + 기존 판정이 그것을 부르게 변경.
      검증: `./gradlew test --tests '*RerouteTriggerPolicyTest'` (기존 시험 그대로 통과)
- [ ] **4.** `RerouteEligibilityPolicy` 시그니처 변경 + `RerouteProposalService` 호출부 수정 (R2)
- [ ] **5.** `JourneyRerouteProperties.RouteOption` + `application.yaml` + `validate()`
- [ ] **6.** `WindowCooldownEligibilityPolicy` + 단위 시험. 거절 사유 다섯 경로 전부
- [ ] **7.** `TransferPredictionInterpreter` 추출 + `BusTransferTargetArrivalPredictor` 위임.
      검증: `./gradlew test --tests '*TransferTargetArrivalPredictorTest'` (동작 불변 확인)
- [ ] **8.** `RouteOptionEtaCalculator` + 단위 시험 (이후 대기 0 처리, 이후 환승 있으면 신뢰도 하향,
      `RIDE`/`WALK` 소요 누락 시 후보 버림)
- [ ] **9.** `RouteOptionCandidateProvider` + 단위 시험. `PredictTransferUseCase` 는 mock
- [ ] **10.** `RerouteProposalTxService` 저장 직전 재확인 + 반환형 변경 (R3) + 시험
- [ ] **11.** `JourneyRerouteMetrics` 사유 목록·지표 두 개
- [ ] **12.** `RerouteRouteOptionIT` — **ODsay 포트 호출 0회 검증 포함**
- [ ] **13.** ②의 `RerouteCandidateContractTest` 가 ③의 후보로도 통과하는지 확인
- [ ] **14.** 문서 3종 — `TECHNICAL_DECISIONS §4`(144행), `SCOPE-CHANGES` SC-15, `PHASE-6.md`
- [ ] **15.** `./gradlew build` 전체 통과 → PR

## 5. 검증

```bash
./gradlew compileJava
./gradlew test --tests '*Reroute*'
./gradlew build
```

### 통과해야 하는 것

- [ ] `POST .../reroute-proposals` 가 조건 충족 시 **제안 1건**을 저장한다 (지금은 무조건 409)
- [ ] **ODsay 경로 검색 포트 호출 0회** — Phase 6 완료 조건
- [ ] **환승이 둘 이상인 여정에서도 제안이 나온다** (R1 회귀 — 초안대로였다면 0건)
- [ ] 이후 환승이 남아 있으면 화면 신뢰도가 `APPROXIMATE`
- [ ] 개선폭 300초 미만이면 빈 결과. 예외가 아니라 정상 결과다
- [ ] **탈 수 없는 예측을 후보로 올리지 않는다** (R4). 기준선이 탈 수 없으면 빈 결과
- [ ] **이미 지나간 환승 번호로 요청하면 `past-transfer` 로 거절** (R2). 본문 없이 보낸 요청 포함
- [ ] **후보를 만드는 사이 여정이 끝나거나 개정본이 바뀌면 저장하지 않는다** (R3)
- [ ] 환승 준비 시각이 600초보다 멀거나 근거가 없으면 `window` 로 거절
- [ ] 2분 안에 두 번 누르면 두 번째는 `cooldown`
- [ ] **shadow 신호가 사용자 요청 잠금을 태우지 않는다** (D6 회귀)
- [ ] 선택 노선은 대안 목록에 없고, 같은 노선이 두 번 평가되지 않는다
- [ ] 시간 예산을 넘기면 남은 후보를 건너뛰고 이미 평가한 것으로 제안한다
- [ ] `WAIT_SUBWAY` 환승은 빈 결과
- [ ] `PredictTransferUseCase` 예외 시 500 이 아니라 빈 결과
- [ ] 후보에 `candidateId` 가 UUID 로 채워져 저장된다(②의 계약)
- [ ] 기존 여정 평가·알림 시험 회귀 없음 (`./gradlew build`)

### 배포 메모

마이그레이션이 없어 VPS 수동 배치가 없다. `journey.reroute.enabled` 는 **계속 false 로 둔다** —
운영에서 켜는 것은 ⑥ 이후이고 PHASE-5 §1.1 절차를 함께 탄다.

## 6. 함정

- **`RerouteTriggerEvaluator` 에 `RerouteProposalService` 를 주입하지 마라.** 협력자가 없는 것이 shadow 를
  구조로 보장하는 장치다(그 클래스 주석). D1 이 그 상태를 유지한다
- **앵커를 `now` 로 쓰지 마라.** `BusTransferTargetArrivalPredictor` 가 같은 이유로 앵커 없으면 `unknown` 을 낸다
- **`TransferTargetArrivalService` 를 재사용하려 하지 마라.** 단계 하나의 `selectedRouteOptionId` 만 보므로
  대안 노선을 평가할 수 없다. 재사용할 자리는 그 아래 `PredictTransferUseCase` 다. PHASE-6 §③ 서술
  ("`TransferTargetArrivalService` 를 재사용한다")이 이 점에서 부정확하다 — 실시간→ML 규칙
  (`SingleTransferPredictor`)은 그대로 공유되므로 의도는 지켜진다
- **대기 단계 소요는 언제나 null 이다.** 이 사실을 모르고 "null 이면 실패"로 짜면 기능이 통째로 죽는다(R1)
- **거절 사유를 새로 만들면 `JourneyRerouteMetrics` 의 목록에 넣어라.** 안 넣으면 조용히 `unknown` 으로
  접혀 운영에서 아무것도 못 본다
- **`@ConfigurationProperties` 값을 시험에서 손으로 채워도 소용없다.** `@TestPropertySource` 로 줘야 한다
- **`git add -A` 금지.** 경로를 지정해 커밋한다
- **`AbstractJourneyNotificationIT` 계열은 설정 빈을 공유한다.** 시험에서 플래그를 바꿨으면 `@BeforeEach` 에서 되돌린다
