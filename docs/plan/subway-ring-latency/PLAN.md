# 지하철 링 지연 단축 (#371) — PLAN (L)

결론: 링이 실제 열차보다 35~62초 늦은 원인 셋(도착 API만 채택, 서버가 처음 본 시각에 앵커, 조회 주기)을 PR 3개로 나눠 줄인다. 목표는 원천 사건(recptnDt) 대비 링 변경 +20초 이내.

## 0. 서버 재확인 결과 (2026-10-11, journey 225, 열차 8336)

운영 `subway_ring_push_outbox` 프레임으로 이슈 수치를 다시 확인했다.

| 사건 | 원천 recptnDt | 링 프레임 created_at | 지연 |
|---|---|---|---|
| 교대 도착 | (base 18:54:02) | 18:54:48 arrived | +46초 |
| 서초 도착 | (base 18:55:40) | 18:56:39 arrived | +59초 |
| 방배 도착 | 18:58:02 | 18:58:59 arrived | +57초 |
| 방배 출발 | 18:59:10 | 18:59:45 MEASURED, base=18:59:45 | +35초 |
| 사당 도착 | 19:00:18 | 19:01:16 arrived | +58초 |

- 사당 구간 MEASURED 예정은 19:00:38(= 서버가 본 18:59:45 + 실측 53초)이었다. 원천 출발 시각에 앵커했다면 19:00:03이 되어 실제 19:00:18에 더 가깝다.
- 교대·사당 구간은 예정이 지나도 도착을 못 봐서 LATE 0.9에 멈췄다(18:53:33~18:54:48, 19:01:11).
- 위치 수집 주기: 실측 약 16.5초(fixedDelay 15초 + 수행 약 1.5초, `[TrainTransition]` 로그 간격).
- 스케줄러: worker `taskScheduler` 풀 3개. 18:30~19:30 실행시간 평균은 `BusPositionScheduler.trigger` 5.75초(최대 9.5초), collect 1.0초(최대 2.4초). 풀 사용률 합계는 약 0.33스레드라 상시 포화는 아니다. 다만 버스 위치 트리거가 9초 동안 한 칸을 잡으면 순간 경합은 생긴다.
- 원천 원본(도착 행)은 `persist-raw-arrivals: false`라 남지 않았다. 8336 위치 이력 Redis 키도 TTL로 사라졌다. 그래서 "도착 API가 위치 API보다 늦게 보인다"는 이슈 기록기 수치에만 기댄다. PR-1에서 채택 원천 로그를 남겨 다음 여정에서 확정한다.

## 1. 원인 → 수정 매핑

| 원인 | 코드 | PR |
|---|---|---|
| 도착 행이 있으면 더 새로운 위치 사건을 버린다 | `SubwayRingSignalReader.read` 73-83 | PR-1 |
| 위치 "전역(target-1) 도착·출발"을 근거로 못 쓴다(`seen < target` → keep) | `SubwayRingCalculator.fromPosition` 348-351 | PR-1 |
| MEASURED·TYPICAL 직선을 서버가 처음 본 시각(seenAt)에 앵커 | `Calculator.fromMeasured` 261-262, `advanced(... seenOf(observation) ...)` | PR-2 |
| 링 tick 5초, 위치 수집 15초, 스케줄러 풀 3 | `application.yaml` 622, 392, 17-18 | PR-3 |

이슈의 해결안 #4(도착을 저장한 tick에 다음 구간까지 계산)는 **채택하지 않는다**. 중간 역에서 1.00 프레임(arrived push)이 사라지고, 다음 구간 직선의 시작점은 어차피 그 사건 시각이라 이득은 tick 하나(최대 5초)뿐이다. PR-3에서 tick을 2초로 줄이면 같은 효과를 얻는다.

## 2. PR-1 — 두 원천 중 새 쪽 채택 (`fix/subway-ring-fresher-source`)

### 변경
- `SubwayRingSignalReader.read(train, chain, target)`
  - 도착 관측(`fromArrival`)과 위치 관측을 **둘 다** 만든다.
  - 위치 관측이 target 기준으로 쓸 수 있는 근거이고, 그 `recptnDt`가 도착 행의 `recptnDt`보다 **엄격히 새로우면** 위치 쪽을 고른다. 같거나 오래되면 도착 쪽(초를 갖고 있음)을 고른다. 도착 행이 없으면 지금처럼 위치 폴백이다.
  - 위치 근거를 target 기준 관측으로 옮기는 표:

    | 위치 역 | trainSttus | 만드는 관측 |
    |---|---|---|
    | target-1 | 0 진입 | `arrival(PREV_ENTERING, target, null, recptn)` |
    | target-1 | 1 도착 | `arrival(PREV_ARRIVED, target, null, recptn)` → 정차 직선 |
    | target-1 | 2 출발 | `measured(...)`(도착 PREV_DEPARTED와 같은 경로: 실측 중앙값, 출발 시각 = `segmentTimePort.departedAt` ≤ recptn, 없으면 recptn). 표본 없으면 `arrival(PREV_DEPARTED, target, null, recptn)` |
    | target | 0/1/2 | 지금의 `position(status, target, recptn)` |
    | target 초과 | * | 지금 위치 폴백 그대로 |
    | target-1 | 3 전역출발·모름 | 근거로 안 씀(계약 없음) — 도착 쪽 유지 |
    | 그 밖(target-2 이하) | * | 도착 쪽 유지 |

  - 위치에서 만든 관측의 `presentAt`은 비운다(STALE 규칙은 원천 시각 + 120초 그대로).
  - 위치에서 만든 PREV_* 관측의 `Source`는 POSITION으로 둔다. `measured()`/`arrival()`에 source 인자를 받는 오버로드를 추가한다. 계산기는 code로 분기하므로 동작은 같고, 로그·메트릭만 출처를 구분한다.
- `SubwayRingCalculator.fromPosition`: 손대지 않는다. 위 표에서 target-1 사건은 리더가 PREV_* 도착 관측으로 바꿔 넘기므로 `fromPreviousStation`을 그대로 탄다.
- 관측성
  - `SubwayRingUpdateService`: 채택한 원천(arrival|position), `sourceObservedAt`, `sourceSeenAt`, stage를 DEBUG 대신 **링이 바뀐 tick에만** INFO 한 줄로 남긴다(`[SubwayRing] journey=… target=… src=… observed=… seen=… lag=…s`).
  - 메트릭 `journey.subway.ring.detect.lag`(Timer, tag source=arrival|position): 링이 바뀐 tick에서 `seenAt − observedAt`. 운영에서 지연을 다음 여정부터 바로 본다.

### 테스트
- `SubwayRingSignalReaderTest`(기존 확장)
  - 도착 99 + 위치 target-1 도착(더 새로움) → PREV_ARRIVED, source POSITION
  - 도착 PREV_ARRIVED + 위치 target-1 출발(더 새로움) → measured, 시작 = recptn
  - 도착 행이 더 새로우면 도착 쪽
  - recptn 같으면 도착 쪽
  - 위치 target 도착(더 새로움) + 도착 행 진입 → position ARRIVED
  - 위치 target-1 trainSttus 3 → 도착 쪽
- `SubwayRingReplayIT` 회귀: 기존 단언(STALE 0, 되감김 0, 하차 예정 항상, 역당 push ≤ 6) 유지 + **지연 단언 추가**: 위치 녹화의 원천 사건(역·상태 바뀐 첫 recptnDt) 대비 링이 그 사건을 반영한 시각. 변경 전/후 수치를 SUMMARY 검증에 남긴다.

## 2-R. PR-1 정정 (Opus 리뷰 반영, 2026-10-11) — 아래가 §2 를 대체한다

- **선택 기준 = 진행 순위, recptnDt 아님.** 도착 행은 내용이 같아도 recptnDt 만 새로 찍힌다(`Reader:128-129`). 순위 = 체인 역 순번 × 4 + 단계(진입 1·도착 2·출발 3). 도착 코드는 target 기준으로 환산(5→(t-1,1), 4→(t-1,2), 3→(t-1,3), 0→(t,1), 1→(t,2), 2→(t,3), 99·모름→최하). 위치 `trainSttus=3`(LEFT_PREVIOUS) at seq = **(seq-1, 출발)**(`SubwayTrainStatus:21-27` 사용자 확정). 위치가 **엄격히 앞설 때만** 위치 채택, 같으면 도착(초 보유).
- 채택된 위치가 (t-1, 진입/도착)이면 `arrival(PREV_ENTERING/PREV_ARRIVED, t, null, recptn)`, (t-1, 출발)이면 실측 경로. 둘 다 Source=ARRIVAL 로 계산기 `fromPreviousStation` 을 탄다(POSITION 이면 `fromPosition` 으로 빠짐). 출처 구분은 관측의 새 칸 `origin`(ARRIVAL_API|POSITION_API)으로 한다 — 계산기 분기는 안 바꾼다.
- 실측 키 = **target 역**(`subway:seg:*:{line}:{dir}:{target statnId}`). `measured(lineId, dir, targetKey, trainNo, …)` 로 행 의존을 뺀다.
- 위치 출발 시각 = `trainLogPort.findDepartures(serviceDate, line, dir, t-1 key, recptn-10분, recptn)` 의 우리 열차 사건 `to`(처음 출발로 본 recptn). 없으면 `segmentTimePort.departedAt`, 그것도 없으면 recptn. `lastSeen.receivedAt` 는 같은 상태여도 갱신돼 사건 시각이 아니다.
- 위치가 t 이상(진입·도착·출발)이면 지금 `position(...)` 그대로.
- 위치 기반 PREV_* 관측도 presentAt 없음 → STALE 은 원천 시각 + 120초(기존 위치 규칙). 의도: 위치는 수집 시각 칸이 없다.
- ReplayIT 단언 추가: 원천 교대 구간 되감김 0·역당 push ≤ 6 유지.

## 3-R. PR-2 정정
- 실측 표본은 recptn→recptn(`SegmentTimeRecorder:66-73`)이라 원천 시각 앵커가 표본과 같은 축이다(§3 "seen→seen" 문구 틀림).
- 짧은 구간(≈60초)은 첫 프레임이 크게 튈 수 있다 — 단언으로 상한 명시.
- PR-3 위치 주기 단축은 PR-1(진행 순위 선택) 뒤에만(`application.yaml:368-372` 두 주기 일부러 맞춤).

## 3. PR-2 — 직선 앵커를 원천 사건 시각으로 (`fix/subway-ring-source-anchor`)

### 배경
2026-10-10 journey 202에서 원천 recptnDt가 1~2분 늦어 첫 관측에 바로 LATE가 뜨는 문제가 있었다. 그래서 seenAt 앵커로 바꿨다. 이번 측정에서 원천 → 서버 수신 지연은 11~35초였다. 앵커를 원천 시각으로 되돌리되, 지연이 큰 원천에서는 202 문제가 다시 생기지 않게 상한을 둔다.

### 변경
- `SubwayRingCalculator`에 앵커 함수 하나를 둔다.
  `anchorOf(sourceAt, seenAt) = seenAt == null ? sourceAt : max(sourceAt, seenAt − maxSourceLag)`
  - `maxSourceLag` 새 설정 `journey.subway.ring.max-source-lag`, 기본 PT30S.
  - 30초 넘게 늦은 원천은 "seenAt − 30초"로 당겨 쓴다. 202처럼 2분 늦은 원천이어도 첫 프레임에서 링이 최대 30초 몫만 앞서 나간다. 지금 late-grace도 30초라 바로 LATE로 넘어가지 않는다.
- 적용 위치
  - `fromMeasured`: `start = anchorOf(observation.segmentStartAt(), observation.seenAt())`
  - `advanced(..., seenOf(observation), ...)`와 TYPICAL `typicalLine`/`dwellLine`의 `startedAt`: `anchorOf(observedAt, seenAt)`
- 되감김: 같은 구간 재앵커는 `notBehindScreen`(#358)이 이미 막는다. 새 직선이 화면 값보다 앞서는 것(점프)은 허용하는 기존 규칙을 그대로 따른다.
- 실측 구간시간 표본(`segmentTimePort`)은 "처음 본 출발 → 처음 본 도착"이라 양 끝 지연이 상쇄된다. 손대지 않는다.

### 테스트
- `SubwayRingCalculatorTest`
  - 원천 25초 지연: 예정 = 원천 출발 + 실측(seenAt 기준보다 25초 이름)
  - 원천 120초 지연: 예정 = seenAt − 30초 + 실측, 첫 프레임 LATE 아님(202 회귀)
  - seenAt 없음(옛 경로): 원천 시각 그대로
  - 같은 구간 재관측: 되감김 없음
- ReplayIT: 실제 도착 순간의 화면 진행률(이슈 지표: 0.50~0.58)을 단언에 추가한다. 목표는 0.8 이상이다.

## 4. PR-3 — 주기 (`chore/subway-ring-cadence`)

- `journey.subway.ring.tick-ms` 5000 → 2000. 링 갱신은 Redis 읽기와 계산뿐이다. 진행 중 링 수에 비례하므로 `journey_subway_ring_scanned_total`로 부하를 확인한다.
- `spring.task.scheduling.pool.size` 3 → 6. 버스 위치 트리거(최대 9.5초)가 링·수집기를 밀지 못하게 한다.
- 위치 수집 `SUBWAY_POSITION_POLL_MS` 15000 → ?: **결정 필요**. 노선당 1회 호출이라 5초면 하루 호출이 3배가 된다. 위치 키 일일 한도를 포털에서 확인한 뒤 정한다. 한도가 빠듯하면 대안은 "링이 살아 있는 노선만 5초, 나머지 15초"다(별도 PR).
- 도착 API 주기(`realtime.subway.poll-interval-ms`)는 건드리지 않는다. 일일 quota를 공유하고 PR-1 뒤로는 사건 감지를 위치 API가 맡는다.

## 5. 순서·의존
1. PR-1 → 머지 → 배포 → 실여정 1회(`[SubwayRing]` 로그 lag, detect.lag 메트릭)
2. PR-2(PR-1 위에서 지연이 줄어야 원천 앵커가 LATE를 덜 만든다) → 배포
3. PR-3(설정; 위치 주기는 한도 확인 후)
- 각 PR은 fresh main 기준 독립 브랜치이고 스크래치 clone에서 작업한다. 마이그레이션은 없다. 배포는 api+worker이며, 링은 worker 몫이다.

## 6. 검증 (완료 판단)
- 단위: `*SubwayRing*Test` 전부 통과
- IT: SubwayRingIT·SubwayRingPushIT·RedisSubwayRingAdapterIT·JourneySubwayGuidanceIT·SubwayRingReplayIT(일회용 pg/redis)
- 재생 지표(변경 전 → 후): 원천 대비 링 반영 지연, 도착 순간 화면 진행률, LATE 초, push 수
- 운영: 다음 실여정에서 `detect.lag` p50 ≤ 20초, 출발·도착 반영 ≤ +20초(이슈 기록기 재사용)
