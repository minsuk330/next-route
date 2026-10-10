# [요청] 지하철 진행 링 — 정차 구간 포함·TYPICAL 직선·STALE 축소 (2026-10-10 제품 결정)

기준: `origin/main` bb1aba23 (#359 병합 직후). 아래 `파일:줄`은 모두 이 커밋 기준이다.
범위: 링 계산·상태·push payload·스냅샷. **고정(binding)·열차 선택 로직은 건드리지 않는다.**

## 0. 문제와 원칙

실시간 원천은 사건형(전역 진입/도착/출발 → 진입/도착/출발)이라 폴링을 촘촘히 해도 열차가 전역에 서 있는 동안 링이
0 에 얼어 있고, 구간이 넘어갈 때마다 회색 STALE "확인 중"이 뜬다. 위젯은 두 시각 사이 **직선 타이머**
(ProgressView timerInterval) 또는 정적 값만 그린다 — 곡선 불가. push 예산은 한정돼 있다.

원칙: (1) 링은 뒤로 가지 않는다(#306/#358, `SubwayRingCalculator.notBehindScreen` 102-112 유지)
(2) 근거 없이 도착을 말하지 않는다 — ENTERING/ARRIVED 전에는 상한(`progressTarget` 0.90) 이하
(3) **느리게라도 움직이는 것이 멈추는 것보다 낫다.**

**설계 방향 전환을 명시한다.** 현재 코드는 "모르면 멈춘다 — 정적 소요시간·중위값·시간표로 채우지 않는다"를 원칙으로
문서화해 두었다(`SubwayRingCalculator` 39-43, `SubwayRingQuality` 15-20, `SubwayRingSignalReader` 29).
이번 결정은 이를 뒤집되, **추정임을 `etaSource=TYPICAL` 로 앱에 정직하게 알리는 것**으로 대신한다. 해당 Javadoc 을 함께 고친다.

## 1. 실측 증거 — journey 202 (시뮬레이터, 2호선 강남 → 다음 역 교대)

| 시각(UTC) | quality | p / target | ETA | holdReason | 현재 코드 경로 |
|---|---|---|---|---|---|
| 12:55:26 | STALE | 0 / 0.9 | 없음 | - | 구간 전환 `advanced()` 329-334 (무조건 STALE) |
| 12:55:56 | NORMAL | 0 / 0 | 없음 | PREV_STATION_STOPPED(강남) | `fromPreviousStation` 187-190 `hold()` — 73초간 정지 |
| 12:57:09 | NORMAL | 0.9 / 0.9 | - | LATE | `fromMeasured` 221-224 — 직선 없이 0 → 0.9 점프 |

마지막 행의 원인: 실측 예정 = 전역 출발을 **처음 본 recptnDt** + 실측 중앙값(`SubwayRingSignalReader.measured` 120-131).
recptnDt 는 1~2분 뒤처져 있고(`application.yaml` 621, `SubwayRingSignalReader` 161-162), 강남→교대처럼 짧은 구간은
중앙값이 그보다 짧아 **처음 관측한 순간 이미 예정이 지나** `now ≥ eta` → LATE 로 간다. 실제로는 늦은 게 아니라 원천 지연이다(§3-5).

## 2. 현재 동작 요약 (코드 근거)

- 구간 = "다음 역 도착까지". 전역 정차(PREV_ENTERING/PREV_ARRIVED)는 `hold(base)` + `progressTarget=progress`
  (`SubwayRingCalculator` 187-190, 244-249). 신호 쪽도 정차 중엔 예정을 싣지 않는다(`SubwayRingSignalReader` 105-108).
- 구간 전환: `ARRIVED`(1.00) 다음 tick 에 `targetSequence` 가 +1(396-405), `DEPARTED` 는 `advanced(target+1)`(161).
  `advanced()` 는 항상 `p=0, ETA=null, STALE`(329-334). 위치 폴백 ARRIVED/ENTERING 도 STALE(314-317).
- 예정 원천은 셋뿐이다.
  - LIVE: `ENROUTE(99)` + `barvlDt` → `reanchored`(164-167). PREV_DEPARTED 에는 barvlDt 를 쓰지 않는다(`SignalReader` 115-118).
  - MEASURED: `PREV_DEPARTED` + `SubwaySegmentTimePort.recentSeconds` 중앙값(`SignalReader` 120-131, `isMeasured` = `SubwayRingObservation` 54-56).
  - 없음: 표본이 없으면 새 구간 `0 STALE`(`fromPreviousStation` 199-204), 같은 구간이면 `freeze`.
- **실측 중앙값은 운행 시간만이다**: "전역 출발(arvlCd 3 첫 관측) → 그 역 진입/도착(0/1)", 도착역 키, 최신 3개, TTL 3h,
  900초 초과 제외(`SubwaySegmentTimePort` 7-31, `SubwaySegmentTimeRecorder` 61-62, `RedisSubwaySegmentTimeAdapter` 37-40).
  **정차 시간 실측은 어디에도 없다.** (`RedisSubwayArrivalDepartureLogAdapter`/`SubwayDepartureLookup` 은 떠난 열차 선택용이지 중앙값 원천이 아니다.)
- 계획값: `SubwayLineOrder.runSeconds`(54, 출발→다음 역 도착 = 운행) / `arrivalGapSeconds`(81, 도착→도착 = 운행+정차).
- LATE 는 **MEASURED 경로에만** 있다(221-224). LIVE `reanchored` 는 예정이 지나면 cap 으로 두고 NORMAL(273-275).
- STALE: 관측 없음/`observedAt`(=recptnDt) 이 `freshnessWindow`(120s) 초과(`isStale` 407-417, 82-84),
  + 위 `advanced()`/위치 폴백/표본 없음. `progressValidUntil = baseAt + freshnessWindow`(382), MEASURED 만 `eta + window` 로 연장(233-236).
- 하차 예정: `estimatedNextStationAt + arrivalGapSeconds(next→하차)`, 다음 역 예정이 없으면 null(`SubwayRingUpdateService.alightingAtOf` 171-188).

## 3. 요청 변경

### 3-1. 구간 재정의: "전역 도착 → 다음 역 도착"(정차 포함)
- 링 앞부분 `0 ~ dwellShare`(기본 0.15, 설정)는 정차, `dwellShare ~ progressTarget` 은 운행.
- 전역 정차 관측(PREV_ENTERING/PREV_ARRIVED): `hold()` 대신 직선 — base = 같은 구간이면 지금 화면 값, 아니면 0,
  target = `dwellShare`, ETA = 정차 시작 + 전형 정차시간 × factor, `etaSource=TYPICAL`, NORMAL.
  정차 시작 = 이 구간에서 4/5 를 처음 본 시각(같은 4/5 반복 시 재앵커 금지 — 기존 journey 158 교훈, 181-182).
  예정이 지나도 출발이 안 보이면 `dwellShare` 에서 멈춘다(holdReason 유지).
- `holdReason=PREV_STATION_STOPPED` / `holdStationName` 은 **그대로 싣는다**(#356, 앱 문구 "○○ 정차 중").
  단 의미가 "링이 멈춤"에서 "열차가 전역에 서 있음(링은 느리게 진행 가능)"으로 바뀌므로
  `SubwayRingState` 31-34, `SubwayRingHoldReason` 6-8, `SubwayGuidance` 39 Javadoc 을 고친다.
- 전역 출발(PREV_DEPARTED): `max(지금 화면, dwellShare)` 에서 cap 까지 운행 직선(MEASURED 또는 §3-2 TYPICAL).
- 다음 역 도착/출발로 구간이 넘어갈 때(§3-3) 새 구간은 정차 직선부터 시작한다. 단 관측이 `DEPARTED`(이미 그 역 출발)면
  정차를 건너뛰고 `dwellShare` 에서 운행 직선으로 시작.
- 승차역 구간(BOARDING_SEQUENCE=0, cap 0, 369-375·438-440)은 기존대로 0 고정 — 이번 변경 제외.

### 3-2. TYPICAL 직선 + `etaSource`
- 실시간/실측 예정이 없을 때 전형 시간으로 직선을 그린다.
  - 정차: (선택) 실측 정차 — 수집기에서 다음 역 행의 4/5 → 3 전환 간격을 표본화(`SubwaySegmentTimeRecorder` 확장, 별도 키).
    없으면 GTFS `arrivalGapSeconds(prev,next) − runSeconds(prev,next)`, 그것도 없으면 `defaultDwell`(설정, 예 30s).
  - 운행: `recentSeconds` 중앙값이 있으면 MEASURED(기존), 없으면 GTFS `runSeconds(prev,next)` → TYPICAL.
  - TYPICAL 값에는 `typicalFactor`(기본 1.2, 설정)를 곱한다. MEASURED·LIVE 에는 곱하지 않는다.
- `enum SubwayRingEtaSource { LIVE, MEASURED, TYPICAL }` 추가. 예정이 없을 때(hold·freeze·진입·도착)는 null.
  `SubwayRingState`·`SubwayGuidance`(스냅샷)·push `props` 에 `etaSource` 로 싣는다. 앱은 TYPICAL 이면 시각 표시를 숨긴다.
- TYPICAL 의 `progressValidUntil` 은 `max(baseAt + window, eta + window)` — 현재 `anchored` 382 그대로면
  긴 TYPICAL 구간에서 예정 전에 앱 보간이 끊긴다(`staleDateSeconds` 148-155 도 이 값을 후보로 쓴다).
- 일찍 도착 → 다음 관측에서 앞으로 점프(허용). 늦음 → cap 에서 `holdReason=LATE`(§3-5). TYPICAL 도 **같은 LATE** 를 쓴다.

### 3-3. 정상 구간 전환에서 STALE 금지
- `advanced()`(329-334)는 STALE 대신 §3-1 의 새 구간 직선(TYPICAL, NORMAL)을 바로 만든다.
  위치 폴백 ARRIVED/ENTERING(314-317)·DEPARTED(318)·LEFT_PREVIOUS(320-322)도 같은 규칙. 표본 없는 PREV_DEPARTED 첫 관측(199-204)도 TYPICAL NORMAL.
- STALE 은 "새 관측이 임계값보다 오래 없음"일 때만. 제안 60s(설정 `staleAfter`).
  **주의(확인 필요 1)**: 현재 신선도 판정은 `observedAt`=recptnDt 기준(407-417)인데 recptnDt 자체가 1~2분 뒤처져 있어,
  같은 기준으로 60s 를 두면 대부분이 STALE 이 된다. 제안: **서버 시계 기준 "우리 열차 행을 마지막으로 본 시각"**
  (`SubwayRealtimeSnapshot.collectedAt` 18 또는 tick 의 `now`)으로 `staleAfter` 를 재고, recptnDt 기반 `freshnessWindow`(120s)는
  `progressValidUntil` 계산용으로만 남긴다. 두 값의 역할을 `JourneySubwayRingProperties` Javadoc 에 분리해 적는다.
- STALE 화면은 지금처럼 base 에 멈추고 ETA 를 비운다(`freeze` 342-363).

### 3-4. `estimatedAlightingAt` 항상 채우기
- 다음 역 예정이 있으면 기존식(`alightingAtOf` 171-188).
- 없으면(정차 hold 초과·LATE·진입·도착·freeze): `max(now, 다음 역 예정 추정)` + 남은 구간별 전형 시간 합(정차+운행, TYPICAL factor).
  다음 역에 대한 추정은 LATE/진입이면 `now`, 정차 중이면 `now + 남은 정차 + 운행`.
- 출처: `alightingEtaSource`(같은 enum) 별도 필드를 제안한다 — 다음 역 예정 출처를 그대로 쓰되, 다음 역 예정이 없어서
  전형 합으로 채운 경우 TYPICAL. (남은 구간은 지금도 GTFS 계획이라 "LIVE"가 과장될 수 있다 — 앱 정책에 맞춰 필요시
  "다음 역이 하차역일 때만 LIVE/MEASURED, 아니면 TYPICAL"로 좁힐 수 있다. 결정은 서버가 제안하고 앱과 맞춘다.)
- `extendProtection`(136-149)은 alighting 이 항상 채워지면 TYPICAL 값으로도 보호를 민다 — 상한 cap 이 있어 위험은 작지만 의도 확인.

### 3-5. LATE 판정 정리
- 현재 LATE 는 MEASURED 경로에서 `now ≥ eta`(221-224). TYPICAL 도 동일하게 적용(cap 에서 LATE).
- §1 처럼 원천 지연만으로 LATE 가 되는 문제를 막는다: 예정을 서버 시계로 옮겨 비교하거나(`eta + (now − observedAt)`),
  `lateGrace`(설정, 예 30s)를 둔다. 첫 관측에서 이미 예정이 지난 경우는 LATE 가 아니라 `max(화면, dwellShare)` → cap 짧은 직선으로 보정 권장.

## 4. 제약 (코드로 확인)

- **Redis 롤백 안전**: `RedisSubwayRingAdapter.find` 는 Boot 기본 `ObjectMapper` 로 `readValue`(91). 커스텀 매퍼 빈이 없어
  Boot 기본(FAIL_ON_UNKNOWN_PROPERTIES=false)이므로 옛 코드가 새 JSON(etaSource 포함)을 읽어도 무시된다. 실패해도
  `Optional.empty()`(92-95) → 링이 0 부터 재시작(치명적이지 않지만 화면 리셋). **기존 enum 에 새 값 추가는 하지 않는다**
  (옛 코드가 모르는 enum 값은 예외 → 리셋). `SubwayRingHoldReason` 은 그대로, 새 enum 은 새 필드로만.
- **레코드 필드는 뒤에 붙이고 옛 생성자 유지**(`SubwayRingState` 67-112, `SubwayGuidance` 65-86 패턴). 단:
  - `with…()` 7개(115-191)는 전부 정규 생성자를 직접 호출한다 — **새 필드를 모두 통과시킬 것**(빠지면 조용히 null).
  - 계산기는 18인자 옛 생성자로 상태를 만든다(`freeze` 345-362, `anchored` 377-383) — 새 필드를 실을 수 있게 바꿀 것.
  - `SubwayGuidance.of`(103-112)에 매핑 추가. `pending`(94-100)은 null.
- **push 지문**: `SubwayRingPushDecider.signatureOf`(91-106)에 `etaSource`(+`alightingEtaSource`)를 넣는다 — 안 넣으면
  TYPICAL→MEASURED 전환이 같은 30초 버킷이면 ANCHOR 가 안 나가 앱이 시각을 계속 숨긴다. `progressTarget` 은 지금 지문에 없다 —
  정차 직선(target 0.15)과 hold(target=p)의 구분이 진행률·ETA 버킷만으로 갈리는지 확인하고, 아니면 `progressTarget` 버킷도 넣는다.
- **critical/재시도**: 확인 필요 2 참조 — 재시도는 critical 과 무관하다. STALE↔NORMAL 플립이 줄면 `QUALITY`(critical) push 가
  구간마다 1건 줄어 예산에 유리하다. 새 reason 은 만들지 않는다(ANCHOR 로 충분).
- **guidanceVersion**: `SubwayRingUpdateService.guidanceVersionOf`(157-163)는 decider 가 비어 있지 않을 때만 올린다 →
  지문에 etaSource 를 넣으면 출처 전환 때 버전이 자동으로 오른다. 추가 작업 없음.
- **payload**: `SubwayRingPushPayloadFactory.props`(92-118)에 `etaSource`(·`alightingEtaSource`) 추가, null 이면 null.
  종료 프레임은 마지막 props 를 SQL 로 복사하므로(`SubwayRingPushOutboxRepository.enqueueTerminalByJourneyIn` ~186) 별도 작업 없음.
  `staleDateSeconds`(138-160) 규칙은 그대로(STALE 이면 null).
- 새 설정은 `JourneySubwayRingProperties`(42·45·53 옆)와 `application.yaml` ring 블록(615-631)에: `dwell-share: 0.15`,
  `typical-factor: 1.2`, `default-dwell: PT30S`, `stale-after: PT60S`, `late-grace: PT30S`(이름은 서버 재량).

## 5. 수용 기준 (시험)

`SubwayRingCalculatorTest` — 도착 코드별:
1. PREV_ARRIVED/PREV_ENTERING 첫 관측 → NORMAL, p=0, target=dwellShare, ETA=시작+정차×factor, TYPICAL, PREV_STATION_STOPPED+역명.
2. 같은 4/5 반복 → 앵커 불변(되감김 없음). 정차 예정 경과 → dwellShare 에서 정지, holdReason 유지.
3. PREV_DEPARTED + 표본 있음 → MEASURED, base=max(화면, dwellShare). 표본 없음 → GTFS run × factor, TYPICAL, NORMAL(STALE 아님).
4. ENROUTE + barvlDt → LIVE. ENTERING → 0.95, ARRIVED → 1.00(etaSource null).
5. ARRIVED 다음 tick / DEPARTED / 위치 폴백 역 변화 → 새 구간 NORMAL TYPICAL(**STALE 아님**). DEPARTED 는 dwellShare 에서 시작.
6. 서버 시계 기준 `staleAfter` 초과 → STALE freeze(ETA null). 임계 이내(recptnDt 가 90s 뒤처져도) → STALE 아님.
7. TYPICAL 예정 경과, 진입 없음 → cap 에서 LATE. MEASURED 첫 관측에서 원천 지연만큼 예정 경과 → LATE 아님(§3-5).
8. 모든 경로에서 `shownProgressAt` 단조(같은 구간), cap 초과 없음(진입/도착 제외).
9. 승차역 구간 기존 동작 불변.

`SubwayRingUpdateService`/IT: 10. 다음 역 예정이 없어도 `estimatedAlightingAt` 채움 + 출처. 하차역이 다음 역이면 그 예정.
`SubwayRingStateAdvanceTest`/신규: 11. 모든 `with…()` 가 etaSource 를 보존.
`SubwayRingPushPayloadFactoryTest`: 12. props 에 `etaSource`(TYPICAL/null) 직렬화, 기존 키 불변.
`SubwayRingPushDeciderTest`: 13. etaSource 만 바뀌어도 ANCHOR + 지문 상이. 14. 정상 구간 전환이 QUALITY 를 내지 않음.
`RedisSubwayRingAdapterIT`: 15. etaSource 없는 옛 JSON 읽기 → null, 모르는 필드 있는 JSON 읽기 성공.

## 6. 앱 쪽 대응 (참고)

- `etaSource` 를 읽는다: TYPICAL 이면 도착 시각 숨김(링은 그대로 그림). 필드가 없으면(옛 서버) 기존 동작.
- `holdReason` 문구: PREV_STATION_STOPPED → "○○ 정차 중"(링이 느리게 움직여도 유지), LATE → 늦어짐 문구.
- 회색 "실시간 정보가 잠시 끊겼어요"는 `quality=STALE` 일 때만.
- 모르는 필드는 무시(push props·스냅샷 공통).

## 7. 확인 필요 (요청 전제와 코드가 다른 곳)

1. **STALE 60s 기준**: 신선도는 recptnDt 기준이고 recptnDt 가 1~2분 뒤처진다(`application.yaml` 621). 그대로 60s 는 불가 — §3-3 처럼 서버 시계 기준 별도 임계로.
2. **"ANCHOR 는 non-critical 이라 재시도 없음" — 아니다.** `critical` 은 컬럼·지표(`SubwayRingPushRelayService` 154)에만 쓰이고,
   재시도는 `resolveFailure` SQL(`SubwayRingPushOutboxRepository` 274-288)이 critical 과 무관하게 "최신이고 유효기간 안이고
   시도 < maxAttempts(3)"면 PENDING 으로 되돌린다. 따라서 hold/TYPICAL 전환을 critical 로 올릴 실익이 없다.
3. **"실측 출발 로그의 정차+운행 중앙값" — 정차 실측은 없다.** 중앙값은 `RedisSubwaySegmentTimeAdapter`(운행만, 3표본, 3h TTL)이고,
   `RedisSubwayArrivalDepartureLogAdapter` 는 떠난 열차 선택용. 정차는 GTFS 차분 또는 수집기 확장으로 새로 만들어야 한다.
4. **LATE 는 MEASURED 경로 전용**(221-224)이 맞다. 다만 journey 202 의 LATE 는 실제 지연이 아니라 원천 지연(§1, §3-5).
5. `progressTarget` 이 push 지문에 없다(91-106) — 정차 직선 도입 시 target 만 바뀌는 전환이 push 되지 않을 수 있다.
