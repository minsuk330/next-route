# Phase 6 — PR 분해 인덱스

- 상위 계획: [PLAN.md](./PLAN.md) §6.6 · §Phase 6
- 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md) §4.8 (R1~R8)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md) §4 · §6.3
- 범위 변경 이력: [SCOPE-CHANGES.md](./SCOPE-CHANGES.md) (SC-2 · SC-10)
- 앞 단계: [PHASE-5.md](./PHASE-5.md)
- 작성일: 2026-08-11

이 문서는 **어떤 PR 을 어떤 순서로 낼지 정리한 목차**다. 구현 상세는 각 PR 의 `PLAN.md` 에 쓴다.

## 0. Phase 6 가 해결하는 것

Phase 5 로 서버는 "곧 못 탈 것 같다"를 사용자 폰까지 밀어 보낸다. 그런데 **알림을 받은 사용자가
할 수 있는 것이 없다.** "환승을 놓쳤어요 / 앱에서 새 안내를 확인하세요"라고 보내 놓고, 앱을 열면
놓쳤다는 말이 한 번 더 있을 뿐이다. 새 안내가 아직 없다.

Phase 6 은 그 뒷문장을 만든다. **"그럼 어떻게 가면 되냐"에 답한다.** 세 조각이다.

1. **언제 찾아볼지** — 위험해졌다고 매번 찾으면 요금과 지연이 폭발한다. 문을 여는 조건과
   실제 방아쇠를 나눈다
2. **무엇을 찾을지** — 같은 정류장에서 다른 버스를 타면 되는 경우가 먼저다. 그것이 안 될 때만
   아예 다른 경로를 찾는다
3. **사용자가 고르면 갈아끼우기** — 지나온 구간은 그대로 두고 남은 구간만 새 판으로 바꾼다

### 지금 있는 것과 없는 것

| | 상태 |
|---|---|
| 노선 후보 표 | `journey_leg_route_option`(V35)에 **이미 쌓이고 있다.** 여정 시작 때 ODsay `lane` 을 전부 보존한다 — 그런데 **읽는 코드가 아직 없다.** Phase 6 이 첫 소비자다 |
| 경로 개정본 | `journey_revision`(V35). `ACTIVE`/`SUPERSEDED` 와 여정당 `ACTIVE` 하나 제약까지 있으나, 지금은 1번 개정본만 생긴다 |
| `REROUTE_ACCEPTED` | 상태머신이 `NOT_SUPPORTED_YET` 으로 **거절 중**(`JourneyStateMachine.java:40`) |
| 제안 표 | 없다. **V42** 로 `reroute_proposal` 을 만든다 |
| routeOptions Redis 캐시 | **없다.** TECHNICAL_DECISIONS §4 에 계획만 있고 구현체가 없다(`infrastructure` 에 `RouteOption` 참조 0). PostgreSQL 이 canonical 이므로 **DB 조회로 시작**하고, 부하가 확인되면 그때 캐시를 넣는다 |
| 다른 경로 후보 캐시 | `RedisRouteSearchSnapshotAdapter` 가 있지만 **사용자 검색 응답 보관용**(`searchSessionId + pathKey`)이라 키 모양이 다르다. 여정용 후보 캐시는 새로 만든다 |
| 실시간·ML 평가 | 재사용하는 자리는 `TransferTargetArrivalService`·`WaitBusArrivalPredictor` 가 **아니다**(SC-15·SC-16). 그것들은 저장된 단계를 받아 DB 를 읽는데 ③의 대안 노선과 ④의 후보 경로는 그 모양이 아니다. ③은 `PredictTransferUseCase` 를, ④는 값만 받는 `BoardingTimeEstimator` 를 쓴다 — 실시간→ML 규칙(`SingleTransferPredictor`)은 그대로 공유된다 |
| 알림 발송 | Phase 5 로 완성. 제안 알림은 체크포인트 하나를 더하는 일이다 |
| 운영 발송 스위치 | 여전히 **꺼져 있다**(PHASE-5 §1.1, 2026-08-15 이후). 제안 push 도 같은 스위치를 탄다 |

### 이 Phase 의 축은 "외부 검색을 안 부르는 것"이다

ODsay 호출은 요금이 붙고 느리다. 그리고 재탐색은 사용자가 **가장 초조한 순간**에 일어난다 —
느린 응답을 기다릴 여유가 없는 자리다.

그래서 SC-10 이 사다리를 두 칸으로 나눠 뒀다.

1. **같은 정류장 다른 노선**(`routeOptions`) — 승·하차 정류장이 똑같으니 경로를 새로 찾을 이유가
   없다. 이미 저장된 후보에 실시간 도착만 얹으면 된다. **ODsay 경로 검색 0회**(실시간 버스 API·ML
   은 부른다 — 그쪽은 이미 여정마다 도는 호출이라 후보 몇 개가 늘어도 같은 캐시를 공유한다)
2. **다른 경로 구조** — 1번이 빈손일 때만. 여기서 처음 ODsay 를 부른다

그리고 **환승 정류장 도착·임박은 방아쇠가 아니다.** 그것은 "이제 찾아봐도 되는 구간에 들어왔다"는
문일 뿐이고, 실제로 찾게 만드는 것은 **위험도 하락 또는 경로 무효화**다(SC-2 4항). 이 둘을 섞으면
정상적으로 잘 가는 사용자마다 외부 검색이 나간다.

## 1. PR 목록

| 순서 | 슬러그 | 티어 | 마이그레이션 | 선행 |
|---|---|---|---|---|
| ① | `journey-reroute-trigger` | **M** | 없음 | — |
| ② | `journey-reroute-proposal-store` | **L** | V42 `reroute_proposal` | — |
| ③ | `journey-reroute-route-options` | **L** | 없음 | ①② |
| ④ | `journey-reroute-remaining-search` | **L** | 없음 | ③ |
| ⑤ | `journey-reroute-accept` | **L** | 없음 | ②③④ |
| ~~⑥~~ | ~~`journey-reroute-notification`~~ | — | — | **범위에서 뺐다(SC-18)** |

①과 ②는 서로 독립이라 동시에 진행할 수 있다(①은 판정만, ②는 표와 API 만). 마이그레이션은
**V42 하나뿐**이다.

**⑤를 ③④ 뒤에 두는 이유** — 초안은 "제안 하나를 받아 revision 을 바꾸는 일"이라며 ⑤를 ② 뒤에
바로 놓았다. 그런데 수락이 실제로 하는 일은 **후보 payload 를 새 leg 세트로 바꾸는 변환**이고,
그 payload 를 만드는 것은 ③④다. 후보가 없는 상태에서 ⑤를 만들면 손으로 지어낸 fixture 로만
검증하게 되고, 그 fixture 가 ③④가 실제로 만드는 모양과 다르면 **머지된 뒤에야 어긋난다.**

②는 그래도 먼저 간다. 다만 ②가 얼어붙여야 하는 것은 화면 JSON 이 아니라 **적용 가능한 canonical
후보 계약**이고(§②), ②의 PR 에 그 계약을 지키는 **consumer contract test** 를 함께 넣는다 —
③④가 만든 후보가 ⑤가 읽을 수 있는 모양인지를 각 PR 이 그 시험으로 확인한다.

**진행 상황** — ①(#135)·②(#133) 머지됨. ③ 구현 완료(브랜치 `feat/journey-reroute-route-options`).
남은 것은 ④⑤⑥. ③까지 왔어도 `journey.reroute.enabled` 는 **계속 꺼 둔다** — 켜는 것은 알림
통로(⑥)가 붙은 뒤다.

### ① `journey-reroute-trigger`

**언제 찾아볼지**를 정한다. 아무것도 만들지 않고 **판정만 한다.**

- **문을 여는 조건(decision window)** — `userReadyAt - now <= 600초` 또는 현재 버스
  `remainingStops <= 2`. 이 조건만 만족해서는 **아무 일도 일어나지 않는다**
- **실제 방아쇠** — ⓐ 환승 상태가 `AT_RISK`·`MISSED` 로 **하락**, ⓑ 환승할 다음 차 후보가
  소실·통과해 무효화. 하락 판정은 evaluator 가 이미 계산한 상태 전이를 재사용한다. **새 평가
  주기를 만들지 않는다**
- **"후보가 사라졌다"와 "실시간 정보가 잠깐 안 보인다"는 다른 사건이다.** 실시간 API 가 비거나
  느려도 후보 목록은 그대로다 — 그 순간을 무효화로 읽으면 **API 가 흔들릴 때마다 전 사용자에게
  재탐색이 돈다.** 그래서 `UNKNOWN`·upstream 차단 상태는 방아쇠에서 **제외**하고, 무효화는
  **긍정적 근거**로만 인정한다: ⓐ 후보의 예측 도착시각이 지나갔는데 사용자가 아직 못 탔다(통과),
  또는 ⓑ 정상 응답에서 그 `(stopId, routeId, directionKey)` 후보가 **연속 N회** 사라졌다.
  N 과 판정 방식은 ①의 PLAN 에서 확정한다. X1(실시간 누락은 `UNKNOWN` 으로 낮출 뿐)과 같은 원칙이다
- 상태 판정 원본은 `TransferConnection.status` 다. `lastNotifiedStatus`(알림용 흔적)를 쓰면
  알림이 꺼져 있을 때 방아쇠가 통째로 죽는다
- cooldown 120초와 single-flight 키 `(journeyId, routeRevision, transferIndex, searchBucket)`.
  PLAN 은 `transferId` 라고 적었지만 실제 컬럼은 `transferIndex` 다
- `remainingStops` 는 앱 progress 가 준다. **GPS 가 끊기면 window 판정의 한쪽 다리가 사라진다** —
  이때 `userReadyAt` 만으로 문을 열지, 아예 닫을지를 ①의 PLAN 에서 정한다
- **이 PR 은 shadow 다.** 방아쇠가 켜져도 metric·structured log 만 남기고 제안을 만들지 않는다.
  켜기 전에 **실제 발생률을 먼저 재기 위해서**다 — 하루에 몇 번 도는지 모르는 채로 외부 검색을
  붙이면 요금이 먼저 온다
- M 인 이유 — 조건이 REQUIREMENTS·SC 에 이미 열거돼 있고, 붙일 자리(evaluator)도 하나다

### ② `journey-reroute-proposal-store`

제안의 보관과 수명. **Phase 6 의 뼈대다.**

- V42 `reroute_proposal` 신설: `journey_id·route_revision·transfer_index·kind·status·
  base_command_version·base_route_revision·candidates(jsonb)·snapshot_observed_at·expires_at`
- `kind` 는 `ROUTE_OPTION`(같은 구간 다른 노선) / `REMAINING_PATH`(다른 경로). 하나로 뭉치면
  ③과 ④ 중 무엇이 실제로 일하는지 운영에서 못 가른다
- 상태는 `ACTIVE → ACCEPTED / EXPIRED / SUPERSEDED`. **환승 하나에 살아 있는 제안은 하나**이고,
  새 제안이 뜨면 앞의 것을 `SUPERSEDED` 로 밀어낸다
- **unique 는 처음부터 부분 인덱스로**(`WHERE status = 'ACTIVE'`). Phase 5 ②가 `delivery_key` 를
  전체 unique 로 걸어 지금 그 표를 **영원히 못 지우는 상태**가 됐다(PHASE-5 §1 후속 과제). 같은
  실수를 반복하지 않는다
- **`EXPIRED` 로 넘겨 줄 주인이 있어야 한다.** `expires_at` 이 지나도 상태는 저절로 안 바뀌므로,
  부분 unique 만 믿으면 **만료된 `ACTIVE` 행이 자리를 차지해 새 제안 삽입이 막힌다.** 배치 worker
  를 새로 만들지 않고 **조회·삽입·수락 세 진입점이 지나갈 때 만료를 함께 넘긴다**(lazy). 삽입은
  "만료 전이 → 삽입"을 **한 트랜잭션**에서 하고, 조회·수락은 만료된 행을 `ACTIVE` 로 취급하지
  않는다. 만료 전이가 어디서 일어났는지는 사유로 남긴다
- **만료돼도 행을 지우지 않는다.** 지우면 늦게 도착한 수락 요청이 "그런 제안 없음(404)"인지
  "만료됨(409)"인지 구별이 안 되고, 사용자는 왜 안 되는지 모른다
- API 는 `GET .../reroute-proposals/active`(조회)와 `POST .../reroute-proposals`(사용자가 "다시
  찾아줘"를 직접 누르는 경로). **명시 요청도 ①의 window·cooldown 을 똑같이 통과해야 한다** —
  버튼 연타가 외부 검색 연타가 되면 안 된다
- **후보마다 안정된 ID 를 준다.** 제안 하나에 후보가 최대 3개인데 수락 경로가
  `.../{proposalId}/accept` 뿐이면 **사용자가 무엇을 골랐는지 서버가 모른다.** `candidateId` 를
  제안 저장 시점에 확정해 응답에 싣고, 수락 요청 body 에 그 값을 받는다. 순번(index)을 쓰면 후보
  정렬이 바뀌는 순간 다른 경로가 적용되므로 **순번이 아니라 값**이어야 한다
- **후보는 두 겹이다 — 보여 줄 것과 적용할 것.** 화면용 요약(leg 요약·목적지 ETA·환승 횟수·
  도보시간·데이터 신선도, R6)만 저장하면 `REMAINING_PATH` 를 수락했을 때 **새 leg·routeOption·
  geometry 를 만들 재료가 없다.** 그래서 후보마다 적용 가능한 **canonical route snapshot**
  (기존 `PathResult` 와 같은 계열의 경로 원본)을 함께 보존하고, 화면 DTO 는 거기서 파생시킨다.
  이 canonical 계약이 ②가 얼어붙이는 진짜 산출물이고 ③④는 그것을 채우는 쪽, ⑤는 읽는 쪽이다
- **consumer contract test 를 ②에 함께 넣는다.** ③④가 만든 후보가 ⑤가 소비할 수 있는 모양인지를
  각 PR 이 이 시험으로 확인한다. 계약이 문서에만 있으면 세 PR 이 서로 다르게 읽는다
- L 인 이유 — 상태·만료·중복 제거·소유권 검증이 한 표에 얽히고, 여기서 정한 canonical 계약을
  ③④⑤⑥이 전부 따른다

### ③ `journey-reroute-route-options` — **구현 완료**

사다리 첫 칸. **같은 정류장에서 다른 버스를 탄다.**

구현하며 아래 서술에서 **네 가지가 달라졌다**(근거는 SC-15, 실행 계획은
`docs/plan/journey-reroute-route-options/PLAN.md`).

- 재사용하는 자리는 `TransferTargetArrivalService` 가 아니라 그 아래 `PredictTransferUseCase` 다.
  전자는 단계 하나의 `selectedRouteOptionId` 만 보므로 **대안 노선을 평가할 수 없다.** 실시간→ML
  규칙(`SingleTransferPredictor`)은 그대로 공유되므로 "새 예측기를 만들지 않는다"는 의도는 지켜졌다
- 이후 대기 단계의 소요는 **언제나 비어 있다**(검색 결과에 없다). 그래서 0으로 두고 건너뛰며,
  이후 환승이 남아 있으면 화면 신뢰도를 `APPROXIMATE` 로 낮춘다. 필수로 뒀다면 환승이 둘 이상인
  여정은 제안이 영원히 0건이었다
- 적격성 판정(`RerouteEligibilityPolicy`)의 실 구현도 이 PR 에 들어갔다. 없으면 ③이 머지돼도
  API 가 `disabled` 로 막힌 상태로 남는다
- **제안은 사용자 요청으로만 만든다.** 방아쇠(①)는 계속 shadow 다 — 자동 생성해도 알릴 통로가
  없어 아무도 못 본다

- `journey_leg_route_option` 에서 현재 `WAIT_BUS` 의 다른 `routeId` 를 읽는다. **DB 가 canonical
  이고 Redis 캐시는 만들지 않는다** — 없는 캐시를 이 PR 에서 새로 만들면 검증할 것이 두 배가 된다.
  조회 부하가 실제로 보이면 그때 넣는다
- **이건 확정 문서를 거스르는 결정이었다.** TECHNICAL_DECISIONS §4 와 SC-10 3항은 "Redis 에
  Journey TTL 의 routeOptions 조회 cache 를 유지한다"로 적혀 있었고, 그 문장은 **한 번도 구현된
  적이 없다.** 코드가 문서를 조용히 어기게 두면 다음 사람이 없는 캐시를 있다고 읽는다. 그래서
  ③의 PR 에서 **TECHNICAL_DECISIONS §4 문장을 고치고 SC-15 에 근거를 남겼다**
- 각 후보를 `(stopId, routeId, directionKey, targetSeq)` 로 실시간 → ML fallback 평가한다.
  **새 예측기를 만들지 않는다** — `PredictTransferUseCase` 를 그대로 부르고, 근거 고르기·결과
  해석은 `TransferPredictionInterpreter` 가 주기 평가와 공유한다
- **비교 대상은 "다음 차가 언제 오냐"가 아니라 목적지 ETA 다.** 빨리 오는 차가 더 늦게 도착할 수
  있다. 대기 + 승차 기본시간 + 남은 downstream 을 합쳐야 비교가 성립한다
- ODsay 는 lane 별 주행시간을 나눠 주지 않으므로 **같은 subPath 의 후보들은 paired ride 기본시간을
  공유**한다(SC-10). 노선별 실제 차이는 운영 오차로 계측하고 나중에 보정한다
- **기존 경로도 같은 스냅샷으로 다시 계산한다.** 10초 전 숫자와 지금 숫자를 빼면 개선폭이 가짜로
  나온다. 5분 개선이라는 문턱이 그 오차보다 크다고 방심하면 안 된다 — 실시간 도착은 한 번에
  몇 분씩 튄다
- `minimumEtaGainSeconds=300` 미달이면 제안하지 않는다. **아무것도 안 하는 것이 기본값**이다
- 검증 항목 하나가 이 PR 의 존재 이유다 — **routeOption 으로 해결되면 ODsay 호출 0회**
- L 인 이유 — 후보 평가가 evaluator·실시간 캐시·ML 세 곳과 물리고, "무엇과 무엇을 비교하느냐"가
  설계 논쟁 지점이다

### ④ `journey-reroute-remaining-search`

사다리 둘째 칸. **③이 빈손일 때만 외부로 나간다.**

- `RemainingRouteSearchService` 를 새로 만든다. **기존 `RouteSearchService` 를 부르지 않는다** —
  검색 이력 기록·telemetry 같은 부수효과가 여정 경로에 섞이면 안 되고, 시작점도 다르다(최초
  출발지가 아니라 **다음 환승 지점 고정**. 현재 위치는 서버가 정밀 좌표를 받지 않아 MVP 밖이다 —
  SC-16). 좌표는 개정본 스냅샷을 다시 쪼개 얻는다. `journey_leg` 에 좌표 칸이 없어서다
- **ODsay 포트는 좌표만 받고 미래 출발시각을 못 받는다.** 그래서 ODsay 응답에서 가져오는 것은
  경로 **구조**뿐이고, 대기·승차·환승 시간은 `userReadyAt` 기준 실시간·ML 로 **다시 계산한다.**
  ODsay 가 준 시간을 그대로 쓰면 "지금 출발" 기준이라 10분 뒤 환승 지점에서는 틀린 숫자다
- 검색 응답 캐시는 **10분 고정**이고 키에 여정도 사용자도 넣지 않는다(`{stopId}:{destX}:{destY}`).
  경로 **구조**는 누가 묻든 같은 답이라 공유해야 요금이 준다 — 여정 TTL 로 묶으면 다른 사용자의
  같은 질문이 매번 유료 호출이 된다(SC-16). cache miss 일 때만 외부 호출이다
- `application/route/concurrent/SingleFlight` 가 이미 있다. 같은 키로 몰리는 중복 호출은 그것으로
  막는다
- **검색 실패는 조용히 "제안 없음"이다.** 재탐색이 실패해도 진행 중인 안내는 멀쩡해야 한다.
  Phase 5 의 "알림 실패가 여정을 실패로 만들면 안 된다"와 같은 원칙이다
- L 인 이유 — 외부 API 경계를 새로 만들고, 좌표·시각·캐시 키 설계가 한꺼번에 온다

### ⑤ `journey-reroute-accept`

**사용자가 고른 것을 실제로 갈아끼운다.**

- `POST /api/journeys/{journeyId}/reroute-proposals/{proposalId}/accept` 전용 엔드포인트.
  `ApplyJourneyEventRequest` 는 reroute 를 받지 않는다(그 파일에 이미 그렇게 적혀 있다)
- 상태머신의 `NOT_SUPPORTED_YET` 을 푼다. `commandVersion +1`, `routeRevision +1`
- **완료된 leg 의 내용과 상태는 바뀌지 않는다. 하지만 새 개정본에도 그 행이 있어야 한다.** 여정
  조회는 `findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex` 로 **한 개정본의 leg
  전체를 순서대로** 읽는다. 남은 구간만 새 개정본에 넣으면 사용자의 화면에서 **지나온 길이
  통째로 사라진다.** 그래서 새 개정본은 **전체 leg 를 복제하되 완료 구간의 상태·진행·증거를 그대로
  보존**하고, 남은 구간만 새 후보로 갈아끼운다
- **geometry 원천을 바꿔야 한다 — 이게 ⑤의 숨은 절반이다.** `JourneyNavigationGeometryService` 는
  지금 `JourneySession.routeSnapshot`(시작 시점 원본, **영원히 안 바뀜**)을 recompile 한다. cache
  key 만 `(journeyId, routeRevision)` 이고 **원천은 개정본을 보지 않는다.** 그대로 두면 재탐색을
  수락해도 지도와 진행 엔진에 **옛 경로 geometry** 가 계속 내려간다. 원천을 `ACTIVE`
  `JourneyRevision.routeSnapshot` 으로 옮기고, 1번 개정본에서 둘이 같은 값이라는 것으로 회귀를
  막는다
- **"지금 진행 중인 leg 는 어느 쪽이냐" 확정(SC-17)** — 교체 시작점을 **지났으면 409**, 같으면
  적용하고 `ACTIVE`·`activatedAt` 을 잇는다(종류가 같을 때만). 버스에 타고 있는 중이면 하차
  지점부터 새 경로다. **진행도·예측은 잇지 않는다** — geometry 가 바뀌면 그 좌표가 가리키는
  지점이 달라진다
- **새 개정본 스냅샷은 합성한다(SC-17)** — 옛 스냅샷의 앞 구간 + 후보 경로. 그리고 **새 단계는
  그 합성본을 다시 쪼개서** 얻는다. 후보를 따로 쪼개 번호만 밀면 경계에서 도보 종류가 바뀌고
  접합부 환승이 생기지 않는다. 마이그레이션도 **V43** 한 건이 생긴다(수락한 후보 ID)
- 수락 요청은 `proposalId` 와 **`candidateId`** 를 함께 받는다(②). 후보를 안 고르면 서버가 임의로
  1번을 적용하지 않고 거절한다 — 사용자가 안 고른 경로로 여정이 바뀌는 것이 가장 나쁘다
- 수락 트랜잭션에서 셋을 다시 본다: **제안 만료 · `baseCommandVersion` · `baseRouteRevision`**.
  하나라도 어긋나면 `409` + 최신 snapshot. 제안을 만든 시점과 사용자가 누르는 시점 사이에 2분이
  있다
- **이전 개정본의 pending 알림과 due task 를 취소한다.** Phase 5 ⑤가 `route-changed` 취소 사유와
  `routeRevision` 재검증을 이미 넣어 뒀다 — 그 코드가 **처음으로 실제 동작하는 자리**다
- 동시 수락은 조건부 update 로 한 번만 성공시킨다
- 전체 geometry 의 cache key 는 `(journeyId, routeRevision)` 이다. 개정본이 오르면 앱이 새
  geometry 를 받아 지도·진행 엔진을 원자적으로 교체하고, **서버는 이전 개정본 geometry 를 더
  내주지 않는다**(SC-8 6항)
- `journey_revision.reason` 어휘를 여기서 정한다(엔티티 주석이 "Phase 6 이 정한다"고 비워 뒀다).
  최소한 `ROUTE_OPTION_ACCEPTED`·`REMAINING_PATH_ACCEPTED` 를 가른다
- L 인 이유 — 한 트랜잭션 안에서 개정본·leg·환승·알림 취소가 동시에 움직이고, 낙관적 락이 세 겹이다

### ⑥ `journey-reroute-notification` — **범위에서 뺐다(SC-18, 2026-08-13)**

> **만들지 않는다.** 이 칸이 존재할 이유는 "앱을 닫아 둔 사용자에게 대안을 알린다"였고,
> 그것이 성립하려면 **자동 제안 생성**이 함께 열려야 한다 — 제안은 사용자 요청으로만 생기므로
> 알림만 붙이면 결과 화면을 보고 있는 사람에게 한 번 더 울릴 뿐이다. 자동 생성은 유료 검색을
> 방아쇠 발생률만큼 여는 일이고, 실서비스·사용자 확보까지 거리가 있는 지금은 이르다.
> **Phase 6 은 ⑤(수락)까지로 마감한다.** 아래 서술은 되돌릴 때를 위한 기록이다.

**제안이 생겼다는 것을 알린다.**

- 체크포인트를 하나 더한다 — `REROUTE_PROPOSED`. Phase 5 ④가 "체크포인트는 셋에서 멈춘다"고 했고,
  이것이 넷째다
- **기존 키로는 두 번째 제안이 조용히 사라진다.** `JourneyNotificationKeys.logical()` 은
  `journeyId:routeRevision:scopeType:scopeId:checkpoint` 이고, **제안은 수락 전까지
  `routeRevision` 을 올리지 않는다.** 같은 환승에서 제안이 두 번 뜨면(첫 제안 만료 후 재평가, 또는
  `SUPERSEDED` 교체) 키가 완전히 같아 `ON CONFLICT` 에 삼켜진다 — 새 대안이 있는데 알림이 안 간다.
  게다가 supersede 시 "어느 제안의 알림을 취소하는지"도 키만으로는 못 가른다. `scopeId` 는 `int`
  라 proposalId 를 그대로 담지 못하므로, **키에 proposal 을 넣는 방법을 ⑥의 PLAN 에서 확정한다** —
  후보 둘: ⓐ 알림 행에 `proposal_id` 를 더하고 키 생성 규칙을 확장(마이그레이션 1건 추가),
  ⓑ 제안 전용 알림을 아예 만들지 않고 **`MISSED` 알림 하나만 재사용**해 deep link 목적지만 제안
  화면으로 둔다. ⓑ는 마이그레이션이 없지만 `MISSED` 없이 뜨는 제안(후보 무효화 경로)을 못 알린다
- **`MISSED` 위험 알림과 거의 동시에 난다.** 놓친 순간 방아쇠가 돌고 몇 초 뒤 제안이 뜬다 — 그대로
  두면 같은 사건에 알림이 두 번 울린다. 게다가 Phase 5 ⑥이 확정한 `MISSED` 문구가 이미 "앱에서 새
  안내를 확인하세요"다. **알림을 새로 만들지 말고 그 알림의 deep link 목적지를 제안 화면으로 두는
  선택지**를 ⑥의 PLAN 에서 저울질한다. Phase 5 ④가 정한 원칙 — 배타성의 축은 상태가 아니라 단계 —
  을 여기에도 적용한다
- **제안 알림 만료에는 바닥을 씌우면 안 된다.** Phase 5 ②가 만료에 180초 바닥(재시도 예산 60초 × 3)을
  깔았는데 **제안 TTL 은 120초다.** 바닥을 그대로 적용하면 이미 만료된 제안을 광고하고, 사용자가
  눌렀을 때 409 를 본다. 접근 알림과 같은 처리(바닥 없음)가 필요하다
- payload 규칙은 그대로다 — 식별자와 `proposalId` 만 싣고 **상태를 담지 않는다.** 앱이 열면서 최신
  제안을 다시 조회한다
- 취소 사유 두 개를 추가한다: `proposal-expired`, `proposal-superseded`
- M 인 이유 — outbox·payload·deep link 계약이 Phase 5 에 다 굳어 있다. 새로 정할 것은 배타성 규칙과
  멱등 키 둘이고, 둘 다 선택지가 이미 좁혀져 있다. **키 확장(ⓐ)을 고르면 마이그레이션 1건이
  붙는다** — 그 경우 V43 이고, `.gitignore` whitelist 와 `JourneySchemaIT` 의 `@Sql` 목록도 함께
  건드린다

## 2. 티어 판정 근거

CLAUDE.md 의 L 판별 기준 — ①고칠 코드의 모양이 미리 그려지지 않음 ②스키마·API·모듈 경계를 새로
만듦 ③PR 이 여러 개 필요 ④설계 대안을 두고 다툴 여지가 있음.

| 슬러그 | 티어 | 근거 |
|---|---|---|
| journey-reroute-trigger | M | 조건이 REQUIREMENTS·SC 에 열거돼 있고 붙일 자리도 evaluator 하나. 산출물은 metric 뿐 |
| journey-reroute-proposal-store | **L** | 표·상태·만료·API 를 새로 만들고, 여기서 정한 canonical 후보 계약을 뒤 PR 넷이 전부 따른다(②③④) |
| journey-reroute-route-options | **L** | evaluator·실시간 캐시·ML 세 곳과 물리고 비교 기준이 논쟁 지점(①④) |
| journey-reroute-remaining-search | **L** | 외부 API 경계 신설 + 좌표·시각·캐시 키 설계(②④) |
| journey-reroute-accept | **L** | 한 트랜잭션에 개정본·leg·환승·알림 취소가 동시에 움직이고 geometry 원천까지 옮긴다. 낙관적 락 세 겹(①④) |
| journey-reroute-notification | M | payload·deep link·outbox 계약이 Phase 5 에 확정. 새로 정할 것은 배타성과 멱등 키 둘이고 선택지가 좁다 |

## 3. Phase 6 공통 함정

- **외부 검색은 마지막 수단이다.** 도착·임박은 문을 여는 조건일 뿐 방아쇠가 아니다. 이 둘을 섞으면
  정상 이용자마다 ODsay 가 나간다
- **두 숫자는 같은 스냅샷에서 나와야 한다.** 기존 경로의 남은 ETA 를 옛 값으로 두고 새 후보만 방금
  값으로 재면 개선폭이 가짜다
- **완료된 leg 의 내용은 절대 바뀌지 않지만, 새 개정본에는 복제돼야 한다.** 조회가 개정본 단위로
  leg 전체를 읽기 때문이다. "안 바꾼다"를 "안 만든다"로 읽으면 지나온 길이 화면에서 사라진다
- **개정본이 오르면 따라 움직여야 하는 것이 leg 만이 아니다.** geometry 원천, 알림 키,
  due task 가 모두 `routeRevision` 을 본다. `JourneySession.routeSnapshot` 처럼 **개정본을 안 보는
  원천이 남아 있으면 그 자리에서만 옛 경로가 계속 산다**
- **"보여 줄 후보"와 "적용할 후보"를 한 덩어리로 만들지 마라.** 화면 요약만 저장하면 수락 시점에
  새 경로를 만들 재료가 없고, 원본만 저장하면 앱이 매번 다시 계산한다. 둘 다 저장하고 파생 방향을
  한쪽으로 고정한다
- **서버가 혼자 갈아끼우지 않는다.** 수락 전에는 `routeRevision` 이 오르지 않는다(R8)
- **이전 revision 알림 발송 0.** Phase 5 의 완료 조건이지만 그때는 revision 이 실제로 바뀌지 않아
  **사실상 시험되지 않은 항목**이다. Phase 6 이 처음으로 그것을 진짜로 시험한다
- **평가기가 꺼져 있으면 아무 일도 없다.** 운영 발송 켜기는 여전히 PHASE-5 §1.1 절차이고
  **2026-08-15 이후**다. 제안 push 도 그 스위치를 함께 탄다
- **마이그레이션은 V42.** `.gitignore` 가 migration 을 파일별 whitelist 로 관리하므로 새 SQL 마다
  `!` 라인을 추가하고, `JourneySchemaIT` 의 `@Sql` 목록에도 넣는다
- **재탐색 실패가 여정을 실패로 만들면 안 된다.** 재탐색은 부가 기능이고 여정은 본체다

## 4. Phase 6 완료 조건

- [ ] PR 을 ①②③④⑤⑥ 순서대로 머지(①② 는 병렬 가능)
- [ ] 도착·임박만으로는 외부 검색 0회. 위험도 하락·환승 후보 소실·무정차 fixture 에서만 평가 발생
- [ ] 실시간 API 공백(`UNKNOWN`·upstream 차단)에서 재탐색 트리거 0회
- [ ] 여러 `subPath.lane` routeId 실시간 평가가 동작하고, routeOption 으로 개선되면 **ODsay 경로 검색 0회**
- [ ] routeOption 이 모두 무효·개선폭 미달일 때만 다른 경로 검색으로 넘어감
- [ ] 개선폭 미달·동일 대안 반복·만료 제안·동시 수락 테스트 통과
- [ ] 만료된 `ACTIVE` 제안이 남아 새 제안 삽입을 막지 않음(lazy 만료 전이 검증)
- [ ] 후보 3개 중 사용자가 고른 후보가 그대로 적용됨(`candidateId` 왕복). `candidateId` 누락은 거절
- [ ] `REMAINING_PATH` 수락 후 새 leg·routeOption·geometry 가 후보 원본에서 생성됨
- [ ] 수락 후 완료 leg 의 상태·진행 보존, 남은 leg 만 교체, **완료 구간이 조회에서 사라지지 않음**
- [ ] 수락 후 geometry 응답이 새 개정본 경로다(이전 개정본 geometry 재노출 0)
- [ ] 같은 환승에서 제안이 두 번 떠도 두 번째 알림이 멱등 키에 삼켜지지 않음
- [ ] 재탐색과 하차 알림 경쟁 상황에서 이전 revision 불변식 유지(이전 revision 알림 발송 0)
- [ ] cooldown·single-flight 로 반복 외부 검색 차단
- [ ] 기존 경로 검색 API·검색 이력에 회귀 없음(`RemainingRouteSearchService` 는 부수효과 없음)

## 5. Phase 7 이후로 넘긴 것

- `journey_notification` 보관(retention)과 tombstone 표 설계 — Phase 5 ②가 넘긴 것
- 계측 보관 삭제 배치와 수신 시각 창, 계정 삭제 시 `user_push_token`·telemetry 물리 삭제 —
  Phase 5 ①⑦이 넘긴 것. 탈퇴 경로가 코드베이스에 생기면 함께 붙인다
- routeOptions Redis 캐시 — DB 조회 부하가 실제로 보일 때
- lane 별 주행시간 보정(지금은 paired ride 기본시간 공유)
- 사용자 수락 없는 자동 적용, 정상 상태에서의 선제 재탐색 — 범위 밖(SC-2)
- ACTIVE Journey bootstrap·due index rebuild·retention·개인정보 운영 계측 — Phase 7
