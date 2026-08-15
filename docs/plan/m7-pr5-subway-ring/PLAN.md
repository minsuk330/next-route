# M7 PR-5 — 진행 링 + Live Activity 원격 갱신 (실행 플랜)

> 티어 **L**. 두 PR 로 나눈다. 이 문서는 컨텍스트 없는 세션이 읽고 바로 구현할 수 있게 쓴다.
>
> 상위 플랜: `docs/plan/m7-subway-train-binding/PLAN.md` §PR-5 (1197줄부터) — 제약만 적혀 있고
> "PR-4 완료 후 별도 플랜 문서"를 요구했다. 이 문서가 그것이다.
>
> **개정 2(모바일 계약) · 3(codex 리뷰) · 4(실호출 접지)가 여러 항목을 뒤집었다.
> §9 개정 이력을 먼저 읽을 것 — 특히 개정 4가 개정 3의 경계 규칙을 취소했다.**

**한 줄 요지: 서버가 진행 숫자를 자주 push 하는 구조가 아니다. 서버는 실시간 시간 앵커를 보내고
앱이 로컬에서 자연스럽게 보간한다.**

## 0. 지금 상태

- PR-1 역 체인 **#149** / PR-2a·2b 위치 수집·전이 로그 **#148** / PR-3 V44·V45·evidence API **#150**
  / PR-4a 매칭기·CAS·스케줄러 **#152** / PR-4b 수명주기·계승 **#153** — **전부 main 머지**
- 최신 마이그레이션 = **V45**. 이 PR 이 쓸 번호는 **V46**(토큰) · **V47**(push outbox)
- `journey.subway.binding.enabled` 기본 false. `journey.subway.ring.*` 는 아직 **없다**(이 PR 이 신설)
- ring 관련 코드는 저장소에 **한 줄도 없다**(`grep -rn 'subway:ring\|SubwayRing\|progressBase' src/main` → 0건)

### 책임 분리 (개정 2 확정)

| 백엔드 | 앱 |
|---|---|
| 탑승 열차 binding | 서버가 준 **시간 앵커로 원형 ring 을 로컬 보간** |
| 실시간 `arrivalSeconds` 수집 | `progressValidUntil` 까지만 보간, 넘으면 정지 |
| 다음 역 · 하차역 · 남은 역 수 계산 | "탑승 열차 확인 중" 화면 (미확정 상태) |
| 진행 시간 앵커 계산 | Activity 생성·토큰 등록·해제 |
| `NORMAL`/`STALE` 판정 | |
| Live Activity `update`/`end` push | |

### 모바일 쪽 원본 (이 저장소 밖)

| 무엇 | 경로 |
|---|---|
| ActivityKit ContentState + props payload | `/Users/wa/golbob/nextroute-mobile/modules/journey-guidance/ios/JourneyGuidanceLiveActivity.swift:30,399,475-480` |
| 설계 문서 §7.3 payload / §8 갱신 구조 / §10 API 계약 | `/Users/wa/golbob/nextroute-mobile/docs/plan/journey-navigation/M7_SUBWAY_DYNAMIC_ISLAND_PLAN.md` |
| 앱 구현 기록 · 남은 계약 §4 | `/Users/wa/golbob/nextroute-mobile/docs/plan/journey-navigation/M7_APP_IMPLEMENTATION.md` |

Live Activity 위젯은 **이미 앱에 구현돼 있다**. 서버가 만들 것은 그 위젯이 이미 받는 모양이다.

---

## 1. 접지 — 상위 플랜이 미결로 남긴 것을 실측으로 닫았다

### ① FCM 경유가 된다 — APNs 직접 어댑터·`.p8` 키·신규 의존성 **전부 불필요**

상위 플랜은 "APNs 어댑터 신설"을 전제했다. 확인해 보니 아니다.

- `build.gradle:41` = `com.google.firebase:firebase-admin:9.10.0`
- 그 jar 의 `ApnsConfig.Builder` 에 **`setLiveActivityToken(java.lang.String)` 이 실재**한다
  (`javap -classpath <jar> 'com.google.firebase.messaging.ApnsConfig$Builder'` 로 확인)
- FCM HTTP v1 이 `apns.live_activity_token` 을 받아 APNs 로 프록시한다
  ([Firebase 문서](https://firebase.google.com/docs/cloud-messaging/customize-messages/live-activity))
- 즉 **기존 `FirebaseAdminMessagingClient` 를 확장하면 끝**이다. HTTP/2 클라이언트·JWT 서명·
  `.p8` 키 보관·`apns-topic` 계산 전부 필요 없다

> CLAUDE.md "프레임워크·라이브러리 신규 추가는 별건 판단" → **추가 없음**. 근거가 이 항목이다.

### ② `Aps` 에는 `event`/`timestamp`/`content-state` setter 가 없다

`Aps.Builder` 가 가진 것: `setAlert` `setBadge` `setSound` `setContentAvailable`
`setMutableContent` `putCustomData` `setCategory` `setThreadId`.

→ Live Activity 전용 키는 **`putCustomData`** 로 넣는다. **문자열 키라 오타가 컴파일에 안 잡힌다.**
상수로 뽑고 시험으로 고정한다(§3-5).

### ③ 토큰이 **둘** 필요하다

| 무엇 | 어디에 | 수명 | 저장 |
|---|---|---|---|
| FCM registration token | `Message.token` | 기기·앱 설치 단위 | **V39 `user_push_token` 재사용** |
| ActivityKit push token | `apns.live_activity_token` | **Activity 단위로 폐기·회전** | **V46 신규 표** |

수명이 다르므로 V39 에 합칠 수 없다. V39 의 관례는 계승한다 — 통 unique 금지(부분 unique +
`deleted_at`), `enabled=false`(provider 가 죽었다고 답함)와 `deleted_at`(사용자·앱이 해제)은 다른
사건, **토큰은 로그·지표 tag·예외 문구에 절대 싣지 않는다**.

### ④ ActivityKit content-state 는 **두 겹**이다 (개정 2에서 뒤집힘)

Expo Widgets 의 실제 ActivityKit 상태는 지하철 payload 가 아니라 봉투다
(`JourneyGuidanceLiveActivity.swift:399,475-480` — `props: encode(payload)`, 즉 **JSON 문자열**).

```
ContentState { name: String, props: String }   ← props 는 JSON 문자열
```

그래서 FCM payload 는 반드시 이 모양이다.

```json
{
  "aps": {
    "event": "update",
    "timestamp": 1786681200,
    "content-state": {
      "name": "NavigationLiveActivity",
      "props": "{\"phase\":\"RIDE_SUBWAY\", ...}"
    }
  }
}
```

**단위가 섞여 있다. 이게 이 PR 최대의 조용한 실패 경로다.**

| 값 | 단위 |
|---|---|
| `aps.timestamp` | epoch **seconds** |
| `props.updatedAt` · `progressBaseAt` · `estimatedNextStationAt` · `progressValidUntil` | epoch **milliseconds** |

`props` 최소 필드:

```ts
{
  journeyId: number;
  phase: 'RIDE_SUBWAY' | 'SUBWAY_ALIGHT';
  routeName: string;
  nextStationName: string;
  alightingStationName: string;
  remainingStations: number | null;
  segmentProgress: number;
  progressBaseAt: number;          // ms
  progressTarget: number;
  estimatedNextStationAt: number | null;  // ms
  progressValidUntil: number;      // ms
  quality: 'NORMAL' | 'STALE';
  updatedAt: number;               // ms
}
```

`trainNo`·사용자 위치·원시 motion·전체 역 목록·인증 토큰은 **넣지 않는다**.

`props` 의 필드는 non-optional 이 많다(`nextStationName`, `segmentProgress`, `routeName`).
null 을 보내면 앱 디코딩이 실패하고 갱신이 **조용히 무시**된다 →
**push 는 `bindingStatus=CONFIRMED` 일 때만** 나간다(§1-⑨).

### ⑤ 두 원천을 **역할로 나눈다** (개정 4에서 확정 — 실호출 실측 기반)

도착 API 만 남은 초를 주지만 **전 열차를 커버하지 않는다**. 위치 API 는 초를 못 주지만 **전 열차를
빠짐없이** 준다. 그래서 어느 하나를 버리면 안 된다.

| 신호 | 주 원천 | 폴백 |
|---|---|---|
| 남은 초 (0 → 0.90 보간) | 도착 `barvlDt` | **없음** → freeze + `STALE` |
| 진입 0.95 / 도착 1.00 | 도착 `arvlCd = 0 / 1` | 위치 `trainSttus = 0 / 1` |
| **구간 전환**(0% 리셋) | 도착 `arvlCd = 1` | **위치 `statnNm` 변화** ← 커버리지 안전망 |
| 열차 identity | 위치 (binding 이 이미 씀) | — |

**ETA fallback 으로 쓰지 않는 것**: 정적 `subway_segment.travelTime` · 최근 역간 소요시간 중위값 ·
시간표 · GPS/polyline. 개정 1의 "위치 로그 + 중위값 primary" 는 폐기된 채로 둔다.

#### 실측 — 왜 위치 API 를 남기나 (2026-08-15 실호출, 위치 열차 177대)

| 노선 | 위치 열차 | 도착에 **없음** | 인접행 있음 | 먼 역만 | 인접행 중 초 있음 |
|---|---|---|---|---|---|
| 2호선 | 39 | 1 | 38 | 0 | 46/46 |
| 4호선 | 46 | 1 | 44 | 1 | **28/84 (33%)** |
| 7호선 | 30 | 1 | 29 | 0 | 44/44 |
| 5호선 | 35 | 2 | 33 | 0 | 50/59 |
| 9호선 | 27 | 1 | 18 | **8 (30%)** | 25/25 |
| 합 | 177 | **6 (3%)** | **162 (91%)** | 9 (5%) | 193/258 (74%) |

도착 API 는 **역당 방향별 2대 정도**만 준다(역당 행 수 최빈 4). 우리 열차가 그 역 기준 상위 2대에
못 들면 목록에서 사라진다. 노선 편차가 크다 — 9호선 30%가 먼 역에서만(급행 추정), 4호선 초
확보율 33%(코레일 직결 구간 추정).

**이 배치면 도착 API 에 안 잡히는 열차도 구간을 잃지 않는다.** 링이 0% + `STALE` 로 멈추되, 역이
바뀌면 위치가 알려줘 다음 구간으로 정상 진행한다. 도착 하나에만 매달리면 그 여정은 내내 링이 죽는다.

#### 두 원천이 같은 것을 가리키는지 — 확인함

같은 시각에 긁어 같은 `trainNo` 의 현재 역을 대조(교집합 106대): **일치 99 / 불일치 7 (6.6%)**.
불일치는 전부 인접 역이고 전부 `recptnDt` 음수 lag(도착이 4~51초 더 오래됨) — **의미 차이가 아니라
갱신 시차**다.

| 위치 `trainSttus` | 현재 역 일치율 |
|---|---|
| `1` 도착 | 63/64 (98%) |
| `2` 출발 | 29/29 (100%) |
| `0` 진입 | **7/13 (54%)** ← 불일치가 전부 여기 |

진입만 어긋나는데 그 값은 0.95 라 정밀도 요구가 낮고 곧 도착(`1`)이 따라온다. 구간 전환 폴백으로
위치를 쓰는 것이 안전한 이유다.

> **`arvlMsg3`(열차의 절대 현재 역)는 판정에 쓰지 않는다.** 우리가 쓰는 값은 전부 **조회역 기준**
> (`arvlCd`·`barvlDt`·`arvlMsg2`)이라 위치 API 와 좌표계를 맞출 필요 자체가 없다. 맞추려 들면
> 위 6.6% 불일치를 떠안는다.

#### 조회는 공짜다

`SubwayRealtimeArrivalReader.arrivals(lineId, stationId, wayCode)`
(`application/subway/service/SubwayRealtimeArrivalReader.java:51`)는 **전체 역 스냅샷 캐시를 읽는다**.
수집기가 `realtimeStationArrival/**ALL**` 로 한 번에 긁는다(`SeoulSubwayApiAdapter:117-134`) —
실측 **2907행 / 560개 역**, 지선·중간역까지 포함. 임의의 다음 역이 캐시에 있다.

전제: **전체 역 수집기(`realtime.subway.enabled`)가 켜져 있어야 한다.** 꺼져 있으면 캐시가 비어
항상 `STALE` 이다. `arrivalsByStation`(역별 직접 호출)은 **쓰지 않는다** — 호출량이 진행 중 여정
수에 비례해 늘고, arrival 은 일일 quota 를 공유한다.

**`recptnDt` 는 역마다 다르다**(ALL 응답 안에서 실측 210종). `observedAt` 은 응답 전체가 아니라
**그 행의 `recptnDt`** 다. `adjustedSeconds(train, now)`(같은 클래스 159줄)가 이미 그렇게 보정한다.

### ⑥ 스냅샷 위치는 `JourneySnapshot` **최상위** (개정 2에서 뒤집힘)

`JourneyLegView` 안에 넣지 않는다. 모바일 기존 계약이 최상위 nullable 필드다.

```java
public record JourneySnapshot(
        // ... 기존 필드
        JourneyEtaView eta,
        SubwayGuidance subwayGuidance   // nullable
) {}
```

**복제 메서드에서 유실되면 안 된다.** 실재하는 자리 둘 —
`JourneySnapshot.of(...)`(`application/journey/dto/JourneySnapshot.java:57`)와
`withEta(eta, pollSeconds)`(같은 파일 79줄). `withEta` 는 지금 `new JourneySnapshot(...)` 을 다시
만들므로 필드를 더하면서 빠뜨리기 쉽다 → **시험으로 고정한다**(§2-8).

응답 모양:

```json
{ "journeyId": 123, "currentLeg": {}, "legs": [], "eta": {}, "subwayGuidance": {} }
```

조립은 bulk 한 줄을 더한다. `SubwayLegBindingRepository.findByJourneyLegIdIn` 은 **PR-3 이 이
목적으로 이미 넣어 뒀다.** lazy `@OneToOne` 을 DTO 변환 루프에서 건드리면 정확히 N+1 이 된다.
(`JourneyQueryService.snapshotOf` = `application/journey/service/JourneyQueryService.java:61-84`)

### ⑦ 기존 outbox 는 at-least-once 라 재사용 금지

`journey_notification`(V40) + `JourneyNotificationRelayService`: 상태 6개,
`logical_checkpoint_key`/`delivery_key` 2겹 멱등, `attempt_count` 재시도, `scheduled_at` 부분 인덱스.

링 갱신은 **latest-wins** 다. 다만 **최신 상태까지의 재시도를 없애면 안 된다**(개정 2) —
commit 은 됐는데 push 가 유실되면 화면이 멈춘다. §3-4 가 규칙을 정한다.

### ⑧ 수명주기 접점은 이미 넷 다 모여 있다

PR-4b 가 `SubwayBindingLifecycleService` 에 종료·만료·갈아타기·개정본 상승을 모았다. ring 삭제와
종료 push 는 그 네 자리에 얹는다 — PR-4b 가 일부러 안 넣은 것이 이 PR 몫이다.

### ⑨ 상태 어휘 — 도메인 값을 그대로 외부에 쓴다

외부 DTO 도 `PENDING` · `PROVISIONAL` · `CONFIRMED` · `ABANDONED` 를 쓴다(앱이 이에 맞춰 수정된다).

**`SIGNAL_STALE` 같은 값을 binding 상태로 만들지 않는다.** 실시간 정보 지연은 **별도 필드**다.

```json
{ "bindingStatus": "CONFIRMED", "quality": "STALE" }
```

상태를 섞으면 "판정이 취소된 것"과 "신호가 늦은 것"을 구분할 수 없고, 후자에서 후보를 갈아치우는
사고가 열린다.

### ⑩ 다음 역 판정에 **표시 이름을 쓰지 않는다**

역 이름은 표기가 흔들린다(`성수`/`성수종착`/`성수지선`). 내부 ring 상태에 판정용 키를 든다 —
`nextStationSequence`(계획 역 체인의 순번) 또는 `canonicalNextStationKey`(서울 `statnId`).

`statnId` 는 `subway_station` 표로 이름→ID 해석이 가능하다(`statn_id` + `statn_nm` + `line_id`,
`SubwayStationRepository.findByLineId`). 이 값이 `arrivals(lineId, stationId, wayCode)` 의 인자로도
그대로 쓰이므로 **하나로 두 목적을 겸한다**. 이름 비교가 필요한 자리에서는 `DestinationNormalizer`.

---

## 2. PR 분할

| PR | 내용 | 티어 | 마이그레이션 | 선행 |
|---|---|---|---|---|
| **PR-5a** | 진행 링 계산 + Redis + 스냅샷 확장 + ring 정리 | M~L | **없음** | PR-4b(머지됨) |
| **PR-5b** | V46 Live Activity 토큰 + V47 push outbox + FCM 발송 | L | V46·V47 | PR-5a **+ 모바일 선행(§5)** |

PR-5a 는 먼저 배포할 수 있다 — 앱이 폴링으로 `subwayGuidance` 를 읽어 링을 그린다.
**PR-5b 의 원격 push 는 모바일 토큰 등록이 확인된 뒤에 켠다.**

---

# PR-5a — 진행 링 + 스냅샷 확장

브랜치: fresh main 기준 `feat/journey-subway-ring`

## 2-1. 설정 (신규 `JourneySubwayRingProperties`)

`application/journey/config/JourneySubwayRingProperties.java`, prefix `journey.subway.ring`.

```java
private boolean enabled = false;              // 기본 off
private long tickMs = 5000;                   // 갱신 주기. 원천이 60~90초라 이보다 잦을 이유 없음
private int batchSize = 100;                  // 한 tick 이 볼 고정 수
private BigDecimal progressTarget = new BigDecimal("0.90"); // 이동 중 상한
private BigDecimal approachProgress = new BigDecimal("0.95"); // 다음 역 진입
private Duration freshnessWindow = Duration.ofSeconds(120);  // 앱 보간 허용 폭 → progressValidUntil
private Duration ttl = Duration.ofMinutes(30);              // ring 키 TTL. 반드시 둔다
```

관문 값은 전부 **잠정값**이다. 실기기 관측 전에는 근거가 없으므로 설정으로 두고 기본을 끈다.

## 2-2. Redis 내부 ring 상태 (신규)

`application/journey/dto/SubwayRingState.java` — **외부 DTO 와 다르다.** 판정용 키를 더 든다(§1-⑩).

```java
public record SubwayRingState(
        Long journeyId, int routeRevision, int legIndex, int bindingVersion,  // fencing
        int nextStationSequence,          // 판정 축. 이름으로 판정하지 않는다
        String canonicalNextStationKey,   // 서울 statnId. arrivals() 인자로도 쓴다
        String nextStationName,           // 표시용
        String alightingStationName,
        Integer remainingStations,
        BigDecimal segmentProgress,       // progressBaseAt 시점의 확정 진행률
        Instant progressBaseAt,
        BigDecimal progressTarget,
        Instant estimatedNextStationAt,   // 없으면 null
        Instant progressValidUntil,
        String phase,                     // RIDE_SUBWAY | SUBWAY_ALIGHT
        String quality,                   // NORMAL | STALE
        Instant observedAt,               // 원천 관측 시각
        Instant updatedAt
) {}
```

## 2-3. `SubwayRingCalculator` (신규, 순수 함수) ← **본체**

`application/journey/service/SubwayRingCalculator.java`. 입출력만 받는 계산기 — 시험이 쉬워야
관문 숫자를 나중에 조일 수 있다.

### 첫 유효 관측

```
segmentProgress        = 0
progressBaseAt         = observedAt
progressTarget         = 0.90
estimatedNextStationAt = observedAt + arrivalSeconds
progressValidUntil     = observedAt + freshnessWindow
```

현재 구간의 **더 이른 실시간 arrival 관측이 저장돼 있으면** 그 값으로 최초 진행률을 복원할 수 있다.
이력이 없으면 **정적 시간으로 채우지 않고 0 에서 시작**한다.

### 새 실시간 관측 — 기존 앵커로 화면 진행률을 계산하고 그 값을 새 base 로

**먼저 `arvlCd` 로 가른다. 초를 보는 것은 그다음이다.**

`barvlDt = 0` 의 뜻이 `arvlCd` 에 따라 **정반대**이기 때문이다(실측 §1-⑤-2). 개정 3이 넣은
`newArrivalSeconds <= 0 → target` 은 두 경우를 뭉뚱그려 **틀렸다**(§9 개정 4).

```
arvlCd = 1  (그 역 도착)   → 1.00        // barvlDt 는 대개 0. 다 온 것이다
arvlCd = 0  (그 역 진입)   → 0.95
arvlCd = 99 & barvlDt = 0  → ETA 없음 → freeze + STALE   // "[N]번째 전역", 아직 멀다
그 밖 (barvlDt > 0)        → 아래 비율 계산
```

**경계도 친다.** 아래 둘 중 하나라도 참이면 비율을 계산하지 않고 `currentProgress = target` 이다.
분모가 0 이면 정의되지 않고, **음수면 시간비의 의미가 뒤집힌다**.

```
oldEstimatedAt <= progressBaseAt      → currentProgress = target   // 분모 0 또는 음수
observedAt     >= oldEstimatedAt      → currentProgress = target   // 예정 시각을 이미 지났다
```

그 밖의 경우에만 비율을 쓴다.

```
currentProgress = base + (target - base)
                × clamp((observedAt - progressBaseAt) / (oldEstimatedAt - progressBaseAt), 0, 1)

segmentProgress        = max(previousConfirmedProgress, currentProgress)   // 서버 앵커 역행 금지
progressBaseAt         = newObservedAt
estimatedNextStationAt = newObservedAt + newArrivalSeconds
progressValidUntil     = newObservedAt + freshnessWindow
```

**"역행 금지"가 지키는 것은 서버 앵커까지다.** `max(previousConfirmed, current)` 는 서버가 내보내는
`segmentProgress` 가 내려가지 않게 할 뿐이다. 앱은 새 앵커를 **비동기로** 받으므로, 새 ETA 가
짧으면 화면이 앞으로 튀고 길면 뒤로 튈 수 있다 — 앵커 자체는 단조여도 그 사이를 보간하던 값이
새 직선으로 갈아타기 때문이다.

> **UI 역행 금지는 앱 책임이다.** 앱이 직전에 그리던 값을 하한으로 잡아야 한다. 서버가 보장하는
> 것은 "앵커가 뒤로 가지 않는다"이지 "화면이 뒤로 가지 않는다"가 아니다. 이 경계를 모바일과
> 맞춰야 한다(§7 미결).

### 역 이벤트 — 한 구간이 `arvlCd` 시퀀스로 완결된다

**우리는 언제나 바로 다음 역만 조회한다.** 그래서 그 역 기준 코드가 곧 우리 구간의 진행이다.

| 우리 열차 위치 | 다음 역 조회 응답 | 링 |
|---|---|---|
| 직전 역 출발 | `arvlCd=3` "전역 출발" (+ 초 대개 있음) | **0 앵커** |
| 구간 이동 중 | `arvlCd=99` "M분 S초 후" | 0 ~ 0.90 보간 |
| 구간 이동 중(초 없음) | `arvlCd=99` "[N]번째 전역" | freeze + `STALE` |
| 다음 역 **진입** | `arvlCd=0` | 0.95 |
| 다음 역 **도착** | `arvlCd=1` | **1.00** → 다음 구간 0 |

**실제 도착 이벤트 전에는 1.00 을 만들지 않는다.**

`[N]번째 전역` 의 N 은 실측상 **2 이상만** 나온다(N=1 자리는 `arvlCd=3/4/5` 가 차지한다). 즉
남은 정거장이 `N≥2 → 1(전역 3/4/5) → 0(진입·도착 0/1)` 로 빈칸 없이 이어진다 —
`remainingStations` 의 교차 검증에 쓸 수 있다.

**도착 API 에 우리 열차가 안 잡히면**(실측 3%+5%) 위치 API 로 떨어진다 —
`trainSttus=0/1` 로 진입·도착을, `statnNm` 변화로 구간 전환을 판정한다. 초가 없으므로 그 구간의
링은 앵커에 멈추고 `quality=STALE` 이다.

### stale

`observedAt` 이 freshness 기준을 넘거나 `arrivalSeconds` 를 얻지 못하면:

- 마지막 계산 진행률에서 **정지**
- `quality = STALE`
- `estimatedNextStationAt = null` (앱이 보간을 멈춘다)
- 정적 시간으로 **연장하지 않는다**
- binding 된 열차를 다른 후보로 **자동 교체하지 않는다**(freeze)

## 2-4. Redis 어댑터 (신규)

포트 `application/journey/port/out/SubwayRingPort.java`,
어댑터 `infrastructure/adapter/out/cache/journey/RedisSubwayRingAdapter.java`.
관례는 `infrastructure/adapter/out/cache/subway/RedisSubwayTrainLogAdapter.java` 를 따른다.

```
subway:ring:{journeyId}:{routeRevision}:{legIndex}:{bindingVersion}
subway:ring:index:{journeyId}   SET — 종료 시 SCAN 없이 지운다
```

- **키에 `routeRevision` 과 `bindingVersion` 을 둘 다** 넣는다. 하나만 넣으면 재탐색으로 복제된
  같은 `legIndex` 에서 옛 worker 가 새 ring 을 덮는다
- `SCAN` 으로 지우지 않는다. 여정별 index SET 만 읽고 지운다
- **TTL 을 반드시 둔다.** 키 추가와 TTL 이 두 문장으로 쪼개지면 TTL 없는 키가 쌓여 다른 캐시를
  밀어낸다 → `SessionCallback`/Lua 로 원자 갱신
- Redis 유실은 안전한 쪽으로 떨어진다(C10) — ring 이 없으면 `STALE`, DB 의 `CONFIRMED` binding 과
  실시간 arrival 로 다음 tick 에 재구성된다

## 2-5. `SubwayRingUpdateService` + 스케줄러 (신규)

`application/journey/service/SubwayRingUpdateService.java` +
`infrastructure/adapter/in/scheduler/journey/SubwayRingUpdateScheduler.java`
(`@Profile("worker")` + `@ConditionalOnProperty(journey.subway.ring.enabled)`, PR-4a 스케줄러와 같은 모양).

한 tick:

1. **신규 질의 `findRingTargets(limit)`** — PR-4a `findMatchable` 과 같은 조인(세션 `ACTIVE` +
   `route_revision` 일치 + leg 살아 있음), 조건만 `status='CONFIRMED'`.
   **`PENDING`/`PROVISIONAL` 은 대상이 아니다**
2. 계획 역 체인(`JourneySubwayStationChainReader`)에서 다음 역·남은 역 수·하차역
3. **다음 역 도착정보에서 우리 열차를 집는다** — `(lineId, canonicalNextStationKey, wayCode)` 로
   전체 역 스냅샷 캐시를 걸러 `trainNo` 매칭 → `arvlCd` · `barvlDt`(→`adjustedSeconds`) · `arvlMsg2`
4. **없으면 위치 API 로 떨어진다** — `lastSeen(train)` 의 `statnNm` 변화로 구간 전환,
   `trainSttus` 로 진입·도착. 초가 없으므로 링은 앵커에 멈추고 `STALE`
5. `SubwayRingCalculator` → 새 ring
6. `SubwayRingPort.save` + index SET
7. **의미 있는 변화가 있으면** push outbox 에 넣는다 → PR-5b 에서 붙인다(5a 는 자리만)

한 건의 실패는 그 건만 격리한다(PR-4a `runOnce` 와 같은 규칙).

> **`SubwayRealtimeArrivalReader.select()` 를 그대로 쓰면 안 된다.** 그 안의
> `adjustedSeconds >= 0` 필터가 **`barvlDt=0` 인 진입·도착 열차를 버린다**
> (`SubwayRealtimeArrivalReader.java:100-116`) — 링이 1.00 을 영영 못 찍는다. 링 조회는 그 필터를
> 타지 않는 별도 경로여야 한다. 상위 플랜 함정 표의 "그 필터 금지"가 여기서도 유효하다.

> **두 원천의 폴링 주기를 15초로 맞춘다.** 도착이 지금 10초라 위치(15초)와 어긋나 있고, 어긋난
> 채로 두면 두 신호가 가리키는 시각이 계속 달라 구간 전환 판정이 흔들린다. 통일하면 arrival
> 일일 호출도 8640 → 5760 회로 줄어 공유 quota 에 여유가 생긴다.

## 2-6. 스냅샷 확장

`application/journey/dto/SubwayGuidance.java` (신규) — **최종 모양**:

```java
public record SubwayGuidance(
        String bindingStatus,            // PENDING | PROVISIONAL | CONFIRMED | ABANDONED
        String phase,                    // RIDE_SUBWAY | SUBWAY_ALIGHT

        String lineName,
        String nextStationName,          // 미확정이면 null
        String alightingStationName,
        Integer remainingStations,       // 하차역 포함. 다음 역이 하차역이면 1

        BigDecimal segmentProgress,      // progressBaseAt 시점의 확정 진행률
        Instant progressBaseAt,
        BigDecimal progressTarget,       // 이동 중 0.90
        Instant estimatedNextStationAt,  // 실시간 API 기반. 없으면 null
        Instant progressValidUntil,      // 앱이 로컬 보간을 허용할 마지막 시각

        String quality,                  // NORMAL | STALE
        Instant observedAt               // 원천 실시간 데이터 관측 시각
) {}
```

- **`JourneySnapshot` 최상위 nullable 필드**로 더한다(§1-⑥). `JourneyLegView` 는 건드리지 않는다
- `of()`·`withEta()` 등 **모든 복제 경로에서 유실되지 않아야 한다**
- ring 값은 Redis 에서 **여정당 한 번** 읽는다(현재 지하철 단계의 것만)
- 현재 단계가 `WAIT_SUBWAY` 면 **짝 `RIDE` 의 binding** 을 메모리에서 찾아 붙인다(C11)
- `trainNo`·`nextStationId` 는 노출하지 않는다

## 2-7. phase 와 remainingStations 정의 (확정)

- **`SUBWAY_ALIGHT` 전환 조건은 `canonicalNextStation == alightingStation` 하나다.**
  ETA·남은 시간으로 전환하지 않는다
- `remainingStations` = 현재 위치 이후 하차역까지 남은 역 수, **하차역 포함**.
  다음 역이 하차역이면 **1**
- Island phase 는 둘뿐이다 — `RIDE_SUBWAY` · `SUBWAY_ALIGHT`

## 2-8. 수명주기 ring 정리

`SubwayBindingLifecycleService` 의 접기 3종과 계승에 ring 삭제를 얹는다.

- **커밋 후** 삭제한다. 트랜잭션 안에서 지우면 롤백됐을 때 ring 만 사라진다 →
  `TransactionSynchronizationManager.registerSynchronization` 의 `afterCommit`
- 여정 단위 삭제는 index SET 을 읽어서 한다(`SCAN` 금지)
- 계승 시 옛 키를 새 키로 옮기지 않는다 — 다음 tick 이 새 `bindingVersion` 키로 만든다

## 2-9. 시험 (5a)

**단위 — `SubwayRingCalculatorTest`** (시험 먼저 쓴다)
- `arrivalSeconds` 가 줄면 진행이 오른다
- **ETA 가 늘어도 서버 앵커가 역행하지 않는다**
- **경계 둘이 `target` 으로 떨어진다** — `oldEstimatedAt <= progressBaseAt`(분모 0·음수) /
  `observedAt >= oldEstimatedAt`. 예외·NaN 이 없다
- **`arvlCd` 가 초보다 먼저다** — `arvlCd=99` + `barvlDt=0` 은 **freeze + `STALE`**(멀다),
  `arvlCd=1` + `barvlDt=0` 은 **1.00**(도착). 같은 0 이 반대로 처리된다
- **도착 API 에 열차가 없으면 위치 API 로 구간 전환만 이어간다** — 링은 앵커에 멈추고 `STALE`,
  역이 바뀌면 다음 구간 0 으로 정상 진행한다(여정 내내 죽지 않는다)
- 이동 중 상한 0.90 을 넘지 않는다
- **역 진입 0.95 / 실제 도착 1.00** — 도착 이벤트 전에는 1.00 이 없다
- 다음 역 변경 시 0 으로 초기화된다
- 실시간 데이터 누락 시 **freeze + `STALE` + `estimatedNextStationAt=null`**
- **정적·최근 역간 소요시간 fallback 을 쓰지 않는다**(누락 시 값이 늘어나지 않음을 확인)
- `SUBWAY_ALIGHT` 는 다음 역이 하차역일 때만 전환된다
- `remainingStations` 는 하차역을 포함한다(다음 역이 하차역 → 1)

**IT — `SubwayRingIT`**
- `CONFIRMED` 만 훑는다 / `PROVISIONAL`·`PENDING` 은 대상이 아니다
- 끝난 여정·옛 개정본은 훑지 않는다
- ring 키에 `routeRevision`·`bindingVersion` 이 들어가고 **TTL 이 있다**
- 다음 역 판정이 **이름이 아니라 순번/canonical key** 로 된다(이름만 바뀐 경우 재시작 안 함)
- 여정 종료 후 index SET 으로 ring 이 지워진다
- 기능이 꺼져 있으면 스케줄러 빈이 없다

**IT — `JourneySubwayGuidanceIT`**
- **`subwayGuidance` 가 `JourneySnapshot` 최상위에 있다**
- **`withEta()` 등 복제 후에도 guidance 가 유지된다**
- `CONFIRMED` 면 다음 역·앵커가 실린다 / `PROVISIONAL` 이면 `nextStationName`·`segmentProgress` 가 null
- `bindingStatus` 는 도메인 4값이고 `quality` 는 별도 필드다
- binding 이 없으면 `subwayGuidance` 가 null 이고 기존 필드는 그대로다(하위호환)
- 현재 단계가 `WAIT_SUBWAY` 면 짝 RIDE 의 binding 이 붙는다
- **스냅샷 조립 질의 수가 늘지 않는다**(N+1 회귀 가드 — Hibernate statistics)

## 2-10. 검증 (5a)

```bash
./gradlew test --tests '*SubwayRingCalculatorTest'
./gradlew test --tests '*SubwayRingIT' --tests '*JourneySubwayGuidanceIT'
./gradlew test --tests '*Journey*' --tests '*Subway*'
./gradlew build
```

`build/test-results/test/*.xml` 의 `tests` 개수를 **반드시 확인**한다 — 필터가 아무것도 못 잡아도
`BUILD SUCCESSFUL` 로 끝난다(PR-2b 함정).

---

# PR-5b — Live Activity 토큰 + push outbox + 발송

브랜치: fresh main 기준 `feat/journey-subway-live-activity`

## 3-1. V46 — `subway_live_activity_token`

```sql
CREATE TABLE subway_live_activity_token (
    id           bigint generated by default as identity primary key,
    created_at   timestamp(6) not null,
    updated_at   timestamp(6) not null,
    deleted_at   timestamp(6),
    user_id      bigint      not null references users(id),
    journey_id   bigint      not null references journey_session(id),
    -- ActivityKit 토큰은 Activity 단위로 폐기·회전된다. 그래서 V39 에 합치지 않는다.
    activity_token varchar(512) not null,
    -- 이 Activity 를 어느 기기가 들고 있나. FCM 발송에 두 토큰이 다 필요하다.
    push_token_id  bigint      not null references user_push_token(id),
    environment  varchar(20) not null default 'PRODUCTION'
        check (environment in ('PRODUCTION','DEVELOPMENT')),
    enabled      boolean     not null default true,
    last_seen_at timestamptz not null
);
-- 살아 있는 토큰 하나 = 행 하나. 부분 unique(V39 관례) — 통 unique 면 재등록이 막힌다.
CREATE UNIQUE INDEX uq_subway_la_token ON subway_live_activity_token (activity_token)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_subway_la_token_journey ON subway_live_activity_token (journey_id)
    WHERE enabled = true AND deleted_at IS NULL;
```

`push_type` 칼럼은 두지 않는다 — **update/end 전용**이다. push-to-start(iOS 17.2+)는 범위 밖이고,
넣으면 쓰지 않는 값이 계약처럼 굳는다.

> **토큰은 journey-scoped 다. route/binding fencing 은 토큰 표가 아니라 발송기가 맡는다.**
> Activity 가 여정 단위이므로 재탐색으로 개정본이 올라도 **수신 대상은 그대로**다 — 여기에
> `leg_index`/`binding_version` 축을 더하면 계승 때마다 토큰을 다시 등록해야 하고, 그 사이
> push 가 통째로 끊긴다. 낡은 payload 를 막는 일은 outbox 가 한다(§3-3, §3-4).

## 3-2. 토큰 등록/해제 API

`infrastructure/adapter/in/api/journey/JourneyLiveActivityTokenController.java`

```
POST   /api/journeys/{journeyId}/live-activity-token   { activityToken, pushToken, environment }
DELETE /api/journeys/{journeyId}/live-activity-token   { activityToken }
```

- 재등록은 **upsert**(`ON CONFLICT (activity_token) WHERE deleted_at IS NULL`). 앱은 토큰이 회전될
  때마다 다시 보낸다
- `pushToken` 은 V39 에서 찾아 `push_token_id` 로 잇는다. 없으면 400(그 등록 API 는 이미 따로 있다)
- **토큰을 응답·로그·지표에 싣지 않는다**

## 3-3. V47 — `subway_ring_push_outbox`

```sql
CREATE TABLE subway_ring_push_outbox (
    id             bigint generated by default as identity primary key,
    created_at     timestamp(6) not null,
    updated_at     timestamp(6) not null,
    journey_id     bigint      not null references journey_session(id),
    route_revision integer     not null,
    leg_index      integer     not null,
    -- 이 payload 가 어느 고정 세대를 보고 만들어졌나. 재탐색 계승은 새 leg 행을 만들며
    -- version 을 0 으로 되돌리므로(SubwayLegBinding.inheritTo), revision 만 재확인하면
    -- 같은 개정본 안에서 일어난 리셋을 못 막는다.
    binding_version integer    not null,
    -- 순서 키. 역순 배달이 새 상태를 옛 상태로 덮으면 안 된다.
    -- Activity(=여정) 전체에서 단조 증가한다. ring 키별로 다시 시작하면 안 된다.
    sequence       bigint      not null,
    -- 같은 화면이면 다시 보내지 않는다.
    signature      varchar(64) not null,
    -- 중요 이벤트만 최신인 동안 재시도한다.
    critical       boolean     not null default false,
    -- "update" | "end"
    event          varchar(10) not null check (event in ('update','end')),
    payload        jsonb       not null,   -- {name, props} 그대로
    status         varchar(20) not null
        check (status in ('PENDING','SENDING','SENT','SKIPPED','FAILED')),
    attempt_count  integer     not null default 0,
    expires_at     timestamptz not null,
    sent_at        timestamptz
);
-- 여정·단계마다 살아 있는 PENDING 은 하나. 새 것이 오면 옛 것을 SKIPPED 로 밀고 갈아탄다.
CREATE UNIQUE INDEX uq_subway_ring_outbox_open
    ON subway_ring_push_outbox (journey_id, route_revision, leg_index)
    WHERE status = 'PENDING';
CREATE INDEX idx_subway_ring_outbox_due
    ON subway_ring_push_outbox (created_at) WHERE status = 'PENDING';
```

## 3-4. 발송 조건과 재시도 정책

**언제 넣나** (bucket 을 주 방식으로 쓰지 않는다 — 서버는 앵커만 보낸다)

- **다음 역 변경** · **`SUBWAY_ALIGHT` 전환** · **`NORMAL ↔ STALE`** · **실제 도착** · **Activity 종료**
  → `critical = true`
- 그 밖에는 앵커가 의미 있게 바뀔 때만. 같은 `signature` 는 무시
- **스냅샷을 새로 받았다는 이유만으로 전 사용자에게 push 하지 않는다**

**어떻게 재시도하나** (latest-wins 유지 + bounded retry)

```
새 sequence 생성  → 이전 PENDING 은 SKIPPED
발송 실패
  ├─ 여전히 최신 sequence  → bounded retry (critical 은 반드시)
  └─ 더 최신 sequence 있음 → SKIPPED
```

### 최신성은 **발송 직전**과 **결과 반영 시점** 두 번 본다

부분 unique 는 `WHERE status = 'PENDING'` 이라, 워커가 행을 `PENDING → SENDING` 으로 옮기는 순간
**그 행은 술어에서 빠진다**. 그때 갱신 tick 이 새 `PENDING` 을 넣을 수 있어 `SENDING` 과 `PENDING`
이 동시에 존재한다. unique 하나로는 이 창을 못 막는다.

```
발송 직전   같은 키(journey_id, route_revision, leg_index)의 최대 sequence 를 다시 읽는다
            ├─ 내가 구형이다                              → SKIPPED, 발송하지 않는다
            └─ 지금 (routeRevision, legIndex, bindingVersion) 이 payload 와 다르다 → SKIPPED
전송 성공   → SENT. 이미 나간 것을 되돌릴 수 없으므로 순서 문제는 aps.timestamp 에 맡긴다
전송 실패   → 같은 키를 잠근 뒤 최신성을 다시 검사해 원자적으로
            ├─ 여전히 최신 → PENDING 재등록 (bounded retry)
            └─ 아니면      → SKIPPED
            재등록이 unique 와 충돌하면 그것도 SKIPPED 로 귀결한다(누가 더 새 걸 넣었다는 뜻)
```

**`sequence` 와 `aps.timestamp` 는 둘 다 Activity(=여정) 전체에서 단조 증가해야 한다.**
ring 키별로 다시 시작하면 개정본이 오르거나 leg 이 바뀔 때 값이 되감겨 늦게 도착한 옛 push 가
새 화면을 덮는다. 이미 `SENDING` 인 것을 취소할 수는 없으므로, **역전을 실제로 막는 최후 방어선이
timestamp 단조**다. 둘을 같은 원천(여정 단위 단조 카운터)에서 파생시킨다.

**종료 push 는 유효기간 안에서 반드시 재시도한다** — 안 나가면 잠금화면에 죽은 안내가 남는다.

## 3-5. 발송기

`application/journey/service/SubwayRingPushSender.java` +
`application/journey/port/out/LiveActivityPushPort.java` +
`infrastructure/adapter/out/notification/FcmLiveActivityPushAdapter.java`

- 기존 `FirebaseAdminMessagingClient` 구조를 따른다 — **SDK 타입은 어댑터 밖으로 안 나간다**,
  비동기 API 에 **직접 시한**을 건다, 예외 문구를 그대로 옮기지 않는다(토큰이 들어 있을 수 있다)
- `content-state` 는 **`{name, props}` 두 겹**이고 `props` 는 **JSON 문자열**이다(§1-④)
- **단위**: `aps.timestamp` = epoch **seconds**, `props` 의 시각 전부 epoch **milliseconds**
- `event`/`timestamp`/`content-state` 문자열 키를 상수로 뽑고 **발송 payload 를 시험으로 고정**한다
- `UNREGISTERED`/`InvalidRegistration` 은 **영구 실패** → 그 `activity_token` 을 `enabled=false`
  (V39 규칙과 같다. 지우지 않는다)

## 3-6. 종료 보장

- `SubwayBindingLifecycleService` 의 접기 경로에서 **terminal outbox 행**을 넣는다
  (`event='end'`, `critical=true`, `dismissal-date` 포함)
- **DB 로 보장한다** — 앱이 꺼져 있어도 나가야 하므로 Redis 에 두면 안 된다
- 링 갱신과 규칙이 다르다는 것을 코드 주석에 남긴다

## 3-7. 시험 (5b)

- 토큰 등록이 upsert 다 / 해제가 부분 unique 를 막지 않는다 / 토큰이 응답·로그에 없다
- **`CONFIRMED` 만 push 대상이다**
- 같은 signature 는 두 번 안 들어간다
- **더 최신 sequence 가 생기면 이전 행이 `SKIPPED` 로 폐기된다**(`uq_..._open` 계약)
- **`SENDING` 중에 새 `PENDING` 이 들어와도** 구형은 발송 직전 재확인에서 `SKIPPED` 된다
- **`bindingVersion` 이 그 사이 바뀌면 발송하지 않는다**(같은 개정본 안의 리셋도 잡힌다)
- **전송 실패 후 재등록이 unique 와 충돌하면 `SKIPPED` 로 귀결한다**(예외가 아니다)
- **`sequence`·`aps.timestamp` 가 개정본·leg 이 바뀌어도 되감기지 않는다**(Activity 전체 단조)
- **최신 outbox 만 재시도된다** / `critical` 은 최신인 동안 반드시 재시도된다
- **`{name, props}` content-state 계약** — `props` 가 JSON 문자열이고 필드에 null 이 없다
- **seconds/milliseconds 단위 계약** — `aps.timestamp` 는 초, `props` 의 시각은 밀리초
- `aps.timestamp` 가 Activity 별로 단조 증가한다
- `event`/`timestamp`/`content-state`/`live_activity_token` 이 실제 `Message` 에 실린다(대역 검증)
- `UNREGISTERED` 면 그 토큰이 `enabled=false` 가 되고 다른 토큰 발송은 계속된다
- 여정 종료 시 `event=end` 행이 생기고 유효기간 안에서 재시도된다

## 3-8. 검증 (5b)

```bash
./gradlew test --tests '*LiveActivity*' --tests '*SubwayRingPush*'
./gradlew test --tests '*Journey*' --tests '*Subway*' --tests '*Notification*'
./gradlew build
```

---

## 4. 함정

| 증상 | 원인 | 가드 |
|---|---|---|
| 앱이 갱신을 조용히 무시 | `content-state` 가 한 겹이거나 `props` 에 null | `{name, props}` + `CONFIRMED` 만 + 계약 시험 |
| 시각이 1000배 어긋남 | `aps.timestamp`(초)와 `props`(밀리초) 혼동 | 단위 시험 |
| `event`/`content-state` 오타 | `Aps` 에 전용 setter 가 없어 문자열 키 | 상수 + payload 시험 |
| 링이 뒤로 감 | 새 관측에서 base 를 다시 계산하지 않음 | `max(previousConfirmed, current)` + 재앵커 |
| 링이 0 으로 튀거나 NaN | 분모가 0/음수 | 경계 둘 → `target` (§2-3) |
| **멀리 있는 열차를 90%로 표시** | `barvlDt=0` 을 "곧 도착"으로 읽음. 실제로는 `arvlCd=99` 면 "[N]번째 전역" | **`arvlCd` 로 먼저 가른다** (§2-3) |
| 링이 1.00 을 영영 못 찍음 | `select()` 의 `adjustedSeconds>=0` 이 진입·도착 열차를 버림 | 링 조회는 그 필터 우회 (§2-5) |
| 특정 열차만 링이 죽음 | 도착 API 가 역당 2대만 준다(실측 미커버 3%+먼역 5%) | 위치 API 폴백으로 **구간은 유지** (§1-⑤) |
| 구간 전환이 흔들림 | 도착 10초 / 위치 15초로 시각이 어긋남 | 폴링 15초 통일 (§5) |
| 화면이 앞뒤로 점프 | 새 앵커를 비동기로 받아 보간 직선이 갈아탐 | 서버는 앵커 단조까지. **UI 하한은 앱 책임** |
| 늦은 `SENDING` 이 새 화면을 덮음 | 부분 unique 가 `PENDING` 만 봐서 창이 열림 | 발송 직전 재확인 + Activity 전체 단조 timestamp |
| 같은 개정본 안의 리셋을 못 막음 | `inheritTo()` 가 `version=0` 으로 되돌림 | outbox 에 `binding_version` 보존 + 발송 직전 대조 |
| 개정본이 오르며 순서가 되감김 | `sequence` 를 ring 키별로 재시작 | Activity(=여정) 단위 단조 카운터 |
| 도착 전에 100% | 이동 중 상한을 안 둠 | 0.90 / 진입 0.95 / 도착만 1.00 |
| 같은 역인데 링이 0 으로 리셋 | 표시 이름으로 역 변경 판정 | `nextStationSequence`/canonical key |
| 옛 worker 가 새 ring 을 덮음 | 키에 revision·bindingVersion 없음 | 둘 다 키에 |
| TTL 없는 키가 쌓임 | 키 추가와 TTL 이 두 문장 | 원자 갱신 + 키 수 지표 |
| 롤백됐는데 ring 만 사라짐 | 트랜잭션 안에서 Redis 삭제 | `afterCommit` |
| 낡은 링이 뒤늦게 화면을 덮음 | 무조건 재시도 | 최신 sequence 인 동안만 + `aps.timestamp` 단조 |
| commit 됐는데 화면이 멈춤 | 재시도를 통째로 없앰 | `critical` bounded retry |
| 잠금화면에 죽은 안내 | 종료 push 유실 | terminal outbox + 유효기간 내 재시도 |
| `subwayGuidance` 가 사라짐 | `withEta()` 복제에서 필드 누락 | 복제 유지 시험 |
| 스냅샷 N+1 | lazy `@OneToOne` 을 변환 루프에서 | bulk + 질의 수 시험 |
| 거짓 진행 | ETA 없을 때 정적·중위값으로 채움 | freeze + `STALE` 이 정답 |
| 신호 지연을 판정 취소로 오해 | 상태에 `SIGNAL_STALE` 을 섞음 | `bindingStatus` × `quality` 분리 |
| 실시간이 항상 비어 `STALE` | 전체 역 수집기가 꺼져 있음 | `realtime.subway.enabled` 선행. `arrivalsByStation` 금지 |
| `BUILD SUCCESSFUL` ≠ 시험 실행됨 | 필터가 0건 잡아도 성공 | XML `tests` 수 확인 |

## 5. 배포

- **V46·V47 은 VPS 수동배치 필요**(V18+ untracked convention — CI 로 안 나감. 누락 시 프로덕션
  깨짐). 착수 시 `ls src/main/resources/db/migration | sort -V | tail` 로 번호를 **다시 확인**
- 신규 설정 (기본값 전부 off): `journey.subway.ring.enabled` (+ §2-1 전량) ·
  `journey.subway.ring.push.enabled`
- **기존 설정 변경 1건**: `realtime.subway.poll-interval-ms` **10000 → 15000**
  (`src/main/resources/application.yaml:303`). 위치 수집기(15초)와 주기를 맞춘다 — 두 신호의
  시각이 어긋나면 구간 전환 판정이 흔들린다. 부수로 arrival 일일 호출 8640 → 5760 회
- worker 전용: ring 갱신, push 발송 / api: 토큰 등록·해제, 스냅샷 조회
- 켜는 순서: `realtime.subway.enabled`(전체 역 도착 수집 — ETA 원천) ·
  `realtime.subway.position.*`(위치 전이) → `journey.subway.binding.enabled` →
  `journey.subway.ring.enabled` → `journey.subway.ring.push.enabled`
- **신규 의존성 없음** (§1-①)

### 모바일 선행 조건 — PR-5b 활성화 전제

백엔드 push 를 켜기 전에 앱에서 완료될 것:

1. Activity 를 `pushType: .token` 으로 시작
2. `enablePushNotifications: true`
3. ActivityKit token 발급·**회전** 관찰
4. FCM token + ActivityKit token 서버 등록
5. Activity 종료 시 token 해제
6. 시간 기반 SwiftUI ring 렌더링

**PR-5a 스냅샷은 먼저 배포할 수 있다. PR-5b 의 원격 push 는 토큰 등록이 확인된 뒤에 켠다.**

## 6. 지표

- `journey.subway.ring.updated{quality}` — `STALE` 비율이 곧 실시간 원천 신선도
- `journey.subway.ring.eta{source=arrival|none}` — `none` 비율이 높으면 수집기·역 매핑 문제
- `journey.subway.ring.keys` — TTL 누락 회귀 감시
- `journey.subway.ring.push{result=sent|skipped|failed}` +
  `...push.reason{next_station|phase|quality|arrived|end}`
- `journey.subway.ring.push.retry{critical}` — bounded retry 가 얼마나 도는지
- `journey.subway.ring.push.latency` — 링 계산에서 발송까지

## 7. 미결 (실측·협의 전 확정 금지)

1. **`freshnessWindow`** (초안 120초) — 앱 보간 허용 폭. 길면 죽은 화면이 살아 있는 척하고, 짧으면
   링이 자주 멈춘다. 실기기로 봐야 한다
2. ~~`arrivalSeconds` 가 구간 초반에 안 잡히는 비율~~ → **실측함**(§1-⑤). 인접 행 기준 초 확보
   74%, 미커버 3% + 먼 역만 5%. 남은 것은 **노선 편차** — 9호선 30%가 먼 역에서만(급행 추정),
   4호선 초 확보율 33%(코레일 직결 구간 추정). 노선별로 링 품질이 다르다는 것을 지표
   `ring.eta{source, line}` 으로 재고, 나쁜 노선은 `STALE` 로 정직하게 표시한다
3. ~~역 진입(0.95) 판정의 원천~~ → **해소.** 도착 API `arvlCd=0`(진입)·`1`(도착)이 원천이다.
   폴백은 위치 `trainSttus`
4. **개정본 상승 시 Activity 를 이어 쓰나 다시 만드나** — 서버 쪽 결론은 났다(토큰은
   journey-scoped, fencing 은 발송기 — §3-1). 남은 것은 **앱이 재탐색 뒤에도 같은 Activity 를
   유지하는지** 확인하는 일이다. 다시 만든다면 토큰이 회전되므로 등록 시점이 계약이 된다
5. **앱이 `props.updatedAt` 과 `aps.timestamp` 중 무엇으로 순서를 가리는지** — 서버 `sequence` 와
   별개 축이라 확인 필요
7. **UI 역행 금지를 앱이 실제로 거는지** — 서버는 앵커 단조까지만 보장한다. 앱이 직전 표시값을
   하한으로 잡지 않으면 새 ETA 가 짧을 때 화면이 앞으로 튄다(§2-3). 모바일 구현 확인 필요
6. **push-to-start(iOS 17.2+)** — 범위 밖. 앱이 Activity 를 먼저 만든다는 전제

## 8. 작업 순서

- [ ] **PR-5a** — fresh main 에서 `feat/journey-subway-ring`
  - [ ] 설정 (§2-1)
  - [ ] `SubwayRingState` (§2-2)
  - [ ] **`SubwayRingCalculator` + 단위 시험 (§2-3) ← 본체. 시험 먼저**
  - [ ] `SubwayRingPort` + Redis 어댑터, TTL 원자 갱신 (§2-4)
  - [ ] `findRingTargets` 질의 + 갱신 서비스 + 스케줄러 (§2-5)
  - [ ] `SubwayGuidance` + **`JourneySnapshot` 최상위** + 복제 유지 시험 (§2-6)
  - [ ] phase·remainingStations 확정 규칙 (§2-7)
  - [ ] 수명주기 4접점 ring 삭제(`afterCommit`) (§2-8)
  - [ ] 시험 전량 + XML 확인 (§2-9, §2-10)
- [ ] **PR-5b** — 5a 머지 + **모바일 선행(§5) 확인** 후 `feat/journey-subway-live-activity`
  - [ ] V46 토큰 표 + 등록·해제 API (§3-1, §3-2)
  - [ ] V47 outbox + signature·재시도 규칙 (§3-3, §3-4)
  - [ ] FCM Live Activity 어댑터 + **2겹 payload·단위 계약 시험** (§3-5)
  - [ ] 종료 보장(terminal outbox, 유효기간 내 재시도) (§3-6)
  - [ ] 시험 전량 + XML 확인 (§3-7, §3-8)

플랜 파일은 구현 PR 에 포함해 커밋한다.

## 9. 개정 이력

**개정 4 (2026-08-15) — 서울 실시간 API 실호출 접지. 개정 2·3에서 뒤집힌 것**

표본: 도착 `ALL` 2907행/560역, 위치 5개 노선 177대, 교차 검증 106대. 로그는
`implementation_note.md` 의 `2026-08-15 m7-pr5-subway-ring 실호출 접지` 절.

| # | 개정 2·3 | 개정 4 | 근거 |
|---|---|---|---|
| 1 | `newArrivalSeconds <= 0` → `target(0.90)` (개정 3, codex 제안) | **취소.** `arvlCd` 로 먼저 가른다 — `99`+0 은 freeze+`STALE`(멀다), `1`+0 은 1.00(도착) | `barvlDt=0` 이 표본의 절반이고 뜻이 정반대다. 그대로 뒀으면 **3정거장 전 열차를 90%로 표시** |
| 2 | ETA 원천 = `arrivalSeconds` **유일** primary, 위치는 전이 판단 전용 | **역할 분담으로 재작성.** 초는 도착만, **구간 전환 폴백은 위치** | 도착 API 는 역당 2대만 준다 — 미커버 3% + 먼 역만 5%. 위치를 빼면 그 열차는 여정 내내 링이 죽는다 |
| 3 | 진입 0.95 판정 원천 미결 | **해소.** `arvlCd=0`(진입)·`1`(도착) | 코드값이라 문자열 파싱보다 안정 |
| 4 | (없음) | **`select()` 우회 명시** | `adjustedSeconds>=0` 필터가 `barvlDt=0` 인 진입·도착 열차를 버려 링이 1.00 을 못 찍는다 |
| 5 | (없음) | **폴링 15초 통일**(도착 10초 → 15초) | 두 신호 시각이 어긋나면 구간 전환이 흔들린다. arrival 호출도 8640 → 5760 회 |
| 6 | `arvlMsg3` 로 현재 역 판정 검토 | **쓰지 않는다** | 우리 값은 전부 조회역 기준이라 위치와 좌표계를 맞출 이유가 없다. 맞추면 6.6% 불일치를 떠안는다 |
| 7 | (없음) | `observedAt` 은 **그 행의 `recptnDt`** | `ALL` 응답 안에서 `recptnDt` 가 210종 — 한 시점 스냅샷이 아니다 |

확인된 전제(뒤집히지 않은 것): 도착 `btrainNo` = 위치 `trainNo` **같은 번호 공간**(교집합 16/16,
결측 0). 이 축이 어긋났으면 전 구간 `STALE` 이 될 뻔했다.

**개정 3 (2026-08-15) — codex 설계 리뷰 반영**

| # | 지적 | 처리 |
|---|---|---|
| 1 | 부분 unique 가 `PENDING` 만 봐서, 워커가 `SENDING` 으로 옮긴 창에 새 `PENDING` 이 들어온다. 최신성 검사 시점이 안 적혀 있다 | **수용.** 발송 **직전** 최대 sequence 재확인 → 구형이면 `SKIPPED`. 성공은 `SENT` 로 기록하고 순서는 timestamp 에 맡긴다. 실패는 같은 키 잠금 아래 원자 판정(최신이면 `PENDING` 재등록, 아니면 `SKIPPED`), 재등록의 unique 충돌도 `SKIPPED` (§3-4) |
| 2 | 재앵커 공식의 분모가 0 이면 정의되지 않고 음수면 시간비가 뒤집힌다. `observedAt >= oldEstimatedAt` 이면 `target` 이어야 한다 | **수용.** 경계 셋(`oldEstimatedAt <= progressBaseAt` / `observedAt >= oldEstimatedAt` / `newArrivalSeconds <= 0`)을 공식 **앞**에 두고 전부 `target` 으로 (§2-3) |
| 3 | "ring 은 뒤로 가지 않는다"가 과장이다. 앵커를 비동기로 받으면 새 ETA 가 짧을 때 전진 점프·길 때 후퇴 점프가 난다 | **수용, 범위를 좁힘.** 서버는 **앵커 단조**까지만 보장한다고 명시하고, **UI 역행 금지는 앱 책임**(직전 표시값을 하한)으로 분리 (§2-3, §7 미결 7) |
| 4 | 토큰이 `journey_id` 만 무는 것 자체는 사고가 아니다. 사고는 옛 `bindingVersion` payload 가 현재 토큰으로 나가는 것이고, `inheritTo()` 가 `version=0` 으로 되돌리므로 **개정본만 재확인해도 못 막는다** | **수용.** 토큰 표에 축을 더하지 **않는다**(더하면 계승마다 재등록이 필요해 push 가 끊긴다). 대신 outbox 에 **`binding_version` 보존** + 발송 직전 `(routeRevision, legIndex, bindingVersion)` 대조 (§3-1, §3-3, §3-4) |
| 5 | ring 키별로 재시작하는 sequence 로는 역전을 못 막는다 | **수용.** `sequence` 와 `aps.timestamp` 둘 다 **Activity(=여정) 전체 단조**로 못박고 같은 원천에서 파생 (§3-3, §3-4) |

리뷰 밖 정정: 미결 4(개정본 상승 시 Activity 계승)는 **서버 쪽 결론이 났다** — 남은 것은 앱이
재탐색 뒤 같은 Activity 를 유지하는지 확인하는 일뿐이다.

**개정 2 (2026-08-14) — 모바일 계약 반영. 개정 1에서 뒤집힌 것**

| # | 개정 1 | 개정 2 | 왜 |
|---|---|---|---|
| 1 | `SubwayGuidance` 를 `JourneyLegView` 안에 | **`JourneySnapshot` 최상위 nullable** | 모바일 기존 계약. `of()`·`withEta()` 복제 유실 금지 |
| 2 | ETA = 위치 로그 + 최근 역간 소요 중위값(주), arrival(폴백) | **`arrivalSeconds` 가 유일 primary.** 위치 로그는 역 전이 판단 전용. 중위값·정적·시간표·GPS 는 fallback 금지 | 중위값은 "실시간처럼 보이는 추정". 게다가 `arrivals()` 는 전체 역 스냅샷 캐시라 **조회가 공짜**여서 폴백으로 둘 이유가 없었다 |
| 3 | content-state = Swift `SubwayLiveActivityPayload` 한 겹 | **`{name, props}` 두 겹, `props` 는 JSON 문자열** | Expo Widgets 의 실제 ContentState. 한 겹으로 보내면 조용히 무시된다 |
| 4 | 단위 미명시 | **`aps.timestamp`=초, `props` 시각=밀리초** | 1000배 오차가 조용히 난다 |
| 5 | ring bucket 5% 변경 시 push | **bucket 은 주 방식이 아니다.** 중요 이벤트 중심 + 앵커 | 서버가 숫자를 자주 보내는 구조를 버리고 앱 보간으로 전환 |
| 6 | 링 재앵커 공식 없음 | **`clamp` 재앵커 공식 명시** + 역행 금지 = `max(previousConfirmed, current)` | 보간과 역행 금지가 충돌하지 않게 하는 유일한 방법 |
| 7 | 이동 중 0.90 / 도착 1.00 | **진입 0.95** 추가 | 진입과 도착을 가른다 |
| 8 | outbox 는 재시도 없음(latest-wins) | **최신 sequence 인 동안 bounded retry**, `critical` 은 반드시 | 재시도를 통째로 없애면 commit 됐는데 화면이 멈춘다 |
| 9 | `aps.timestamp` 규칙 없음 | **Activity 별 단조 증가** | 늦게 도착한 `SENDING` 이 새 상태를 덮는다 |
| 10 | 다음 역 판정 축 없음 | **`nextStationSequence`/`canonicalNextStationKey`** — 표시 이름 금지 | 역명 표기가 흔들린다(`성수`/`성수종착`) |
| 11 | phase 전환·`remainingStations` 미결 | **확정**: `nextStation == alightingStation` / 하차역 포함(다음 역이 하차역이면 1) | 미결 3·4 해소 |
| 12 | 상태 어휘 언급 없음 | **도메인 4값 유지, `SIGNAL_STALE` 금지, `quality` 별도 필드** | 판정 취소와 신호 지연을 섞으면 후보 교체 사고가 열린다 |
| 13 | 모바일 선행 조건 없음 | **PR-5b 는 토큰 등록 확인 후 활성화** | 켜도 받을 토큰이 없으면 전량 실패로 보인다 |
