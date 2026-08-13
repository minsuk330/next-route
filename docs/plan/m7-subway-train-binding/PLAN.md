# M7 지하철 탑승 열차 자동 판정 (백엔드) — 실행 플랜

- 티어: **L** (스키마 2장 + API 계약 3개 + 신규 외부 API + 프로필 경계 + PR 5개)
- 슬러그: `m7-subway-train-binding`
- 브랜치: **선행 PR 머지 후 최신 main 에서 새 브랜치**를 판다(스택 금지)
- 원본 설계: 모바일 저장소 `docs/plan/m7/M7_IMPLEMENTATION.md` (이 저장소에 없음 — payload 계약은 PR-5 착수 시 옮겨 온다)
- 리뷰 이력: codex 2회(설계 / 플랜). 반영 내역은 §10

> **2차 개정** — 열차 위치·출발 판정의 원천을 `realtimeStationArrival` → `realtimePosition` 으로 교체했다.
> 근거는 §2. 이 교체로 초안의 "역 row 사라짐 → 하류 관측" 구조는 **전면 폐기**됐다.

---

## 1. 무엇을 푸는가

서버는 서울 실시간 API로 **모든 열차**의 위치를 안다. 그러나 "이 사용자가 **어느** 열차에 탔는지"는 모른다.
반대로 iPhone은 Core Motion으로 "차량 이동이 시작됐다"는 알지만 `trainNo`는 모른다.

두 관측을 시간으로 결합해 Journey를 **하나의 열차에 고정(binding)** 한다.

```
iPhone: 탑승역 부근 + stationary/walking → 차량 이동
                     │ 이동 시작 시각
서버:   계획 노선·방향 + 탑승역을 실제 출발한 열차
                     │ 출발 시각(구간)
                     ↓
              유일 후보면 PROVISIONAL 고정
                     ↓ 다음 역 정차 episode 대조
              CONFIRMED / CORRECTED
```

핵심은 좌표를 계속 비교하는 것이 아니라 **탑승 순간에 identity를 한 번 잇는 것**이다.

---

## 2. 원천 API 교체 — 초안이 왜 틀렸나

### 초안의 오류

초안 PR-2는 `realtimeStationArrival/ALL` 로 이렇게 판정했다.

```
탑승역 row 에서 열차가 사라짐
  → 같은 열차가 허용 하류 역에서 관측됨
  → 탑승역 출발 확정
```

**성립하지 않는다.** 이 API는 열차 위치가 아니라 **역별 도착 예정** 정보다. 같은 열차가 여러 역의
도착 예정 row 로 **동시에** 나타난다.

```
T100 / A역 / 30초 후
T100 / B역 / 3분 후
T100 / C역 / 6분 후
```

B역에 T100이 보인다는 사실은 A역 출발의 증거가 아니다. **아직 A역에 도착도 안 했는데** B역 예정 row는
이미 존재한다.

`arrivalSeconds` 최소값이나 `ordkey` 최소값으로 대표 위치를 정하는 우회도 쓰지 않는다 —
실제 응답에서 같은 열차가 여러 역에서 `barvlDt=0`, `ordkey` 거리값 `000` 으로 동시에 나타나는 사례가 있다.

### 교체

**열차 위치와 출발·도착 추적의 주 원천을 `realtimePosition` 으로 바꾼다.**

```
GET {subway-base-url}/{key}/json/realtimePosition/0/1000/{호선명}
```

| 필드 | 뜻 | 쓰임 |
|---|---|---|
| `subwayId` | 호선 | `lineId` (arrival API와 같은 공간) |
| `trainNo` | 열차번호 | identity |
| `statnId` / `statnNm` | **현재 위치 역** | 위치 전이의 축 |
| `updnLine` | 방향. **공식 명세상 `0`/`1`** | 후보 필터. arrival API 는 `"상행"/"하행"/"내선"/"외선"` 문자열이라 **정규화 계층 필수** |
| `statnTid` / `statnTnm` | 종착역 | 행선지 양립성 |
| `trainSttus` | `0` 진입 / `1` 도착 / `2` 출발 / `3` 전역출발 | 전이 판정 |
| `directAt` | `0` 일반 / `1` 급행 / `7` 특급 | 열차 종류 필터 |
| `recptnDt` | **최종수신시간** (공식 명세 표현) | 사건 시각의 **근사**. §PR-2 2-3 참조 |

> **`recptnDt` 를 사건 발생 시각으로 단정하지 않는다.** 공식 명세는 "최종수신시간"이라고만 한다.
> 열차가 출발한 시각도, 원천이 데이터를 만든 시각도 보장하지 않는다. 시각 모델은 §PR-2 2-3 에서
> 이 불확실성을 구간으로 흡수한다.

> **"열차당 1행" 은 실측이지 계약이 아니다.** 2호선·9호선 응답에서 `(subwayId, updnLine, trainNo)`
> 당 현재 위치 row 가 1건이었지만 공식 명세는 유일성을 보장하지 않는다. **충돌 row 가 나오면
> last-wins 하지 말고 그 identity 를 해당 generation 에서 격리**한다(전이를 만들지 않는다).

이 API 는 "열차 위치의 단일 진실"이 아니라 **유일하게 허용된 외부 관측 원천**이다. 관측이 틀릴 수
있다는 전제 위에서 구간·품질로 다룬다.

`realtimeStationArrival` 은 **탑승 전 ETA 계산에만** 남긴다(`WaitSubwayArrivalPredictor` 기존 동작).
열차 위치·출발 전이 판단에는 쓰지 않는다.

### 교체가 없애 준 문제 3개

| 초안의 난제 | 교체 후 |
|---|---|
| L4 후행 확정용 "다음 역 정차" 서버 사건이 저장 안 됨 | `trainSttus=1`(도착) 이 직접 온다. 같은 로그에 남기면 끝 |
| 한 열차가 여러 역 row 로 나타나 위치가 모호 | 실측상 열차당 1행 (계약은 아님 — 위 주의) |
| 급행·지선 판별 정보가 없음 | `directAt`·`statnTid` 가 직접 온다 |

**여전히 남은 것**: "허용 하류 역 집합"은 **없앨 수 없다**. `station != A` 만 검사하면 회차·차량기지
입출고·지선 분기·원천 역행에도 출발이 확정된다. 계획 역 체인과 `SubwaySegment`(노선별 역 순서)로
**같은 방향의 다음 역인지** 확인해야 한다. 초안이 "불필요"라고 쓴 것은 지나친 결론이었다.

---

## 3. 굳은 계약 (바꾸려면 이 문서부터 고친다)

| # | 계약 | 이유 |
|---|---|---|
| C1 | 고정 이후 진행 소스는 **실시간 API 하나**. GPS 투영·polyline 진행·정적 역간 소요시간·시간표 fallback 전부 금지 | 터널에서 GPS가 죽는다. 정적 값으로 채우면 "그럴듯하게 틀린 숫자"를 사용자가 믿는다 |
| C2 | 클라이언트가 보낸 `trainNo`를 **신뢰하지 않는다**. 클라는 motion evidence만 보내고 후보 검색·고정은 서버가 한다 | 앱이 열차를 정하면 서버는 검증할 근거가 없다 |
| C3 | **유일 후보가 아니면 고정하지 않는다.** 관문 미통과는 `PROVISIONAL` 유지 | 틀린 고정이 맞는 고정보다 훨씬 비싸다(하차역 지나침) |
| C4 | 고정 후 **자동 교체 금지**. 누락은 freeze + `STALE` | 다른 열차로 조용히 갈아타면 사용자는 자기 화면이 왜 바뀌었는지 모른다 |
| C5 | Island 표시 상태는 `RIDE_SUBWAY`·`SUBWAY_ALIGHT` **둘뿐** | 원본 설계 §3.2 |
| C6 | **미확정(`PENDING`/`PROVISIONAL`) 상태에서는 하차 관련 안내를 일절 내보내지 않는다.** `SUBWAY_ALIGHT` 전환·`하차 준비`·`ALIGHT_NOW` 전부 `CONFIRMED` 이후에만 | 앞차·뒤차 어느 쪽으로 표시해도 위험(일찍 내림 / 지나침). **안전한 쪽이 존재하지 않는다** |
| C7 | 진행 링은 **DB `journey_leg.progress_*` 에 쓰지 않는다** | 그 칸은 앱 progress 보고가 소유한다(§4 근거 3) |
| C8 | `WaitSubwayArrivalPredictor` 의 "다음 열차"는 **binding 판정에 일절 쓰지 않는다** | 관문 (2)가 통과 시 유일 best 를 보장하므로 tie-break 가 도달 불가능한 죽은 분기였다. SC-6("탈 열차를 확정하는 기능이 아니다")도 그대로 지켜진다 |
| C9 | 열차 위치·출발 판정에 `realtimeStationArrival` 을 쓰지 않는다 | §2 |
| C10 | 진행 링·전이 상태는 **derived index** 다. DB 가 source of truth 이고, Redis 유실은 안전한 쪽(고정 없음/`STALE`)으로 떨어진다 | Redis 비영속 + `allkeys-lru` |
| C11 | binding 은 **`RIDE_SUBWAY` leg 이 소유**한다. `WAIT_SUBWAY` legIndex 입력은 짝 RIDE 로 정규화한다 | route option 은 WAIT 이 소유하는 기존 구조와 축을 나눠, 한 개념이 두 row 에 흩어지지 않게 |

> **C6 개정 근거**: 초안은 C6 에서 "특정 후보 대리 표시 금지"라 해 놓고 PR-4 에서 "가장 앞선 후보 기준
> 하차 준비 허용"이라 적어 자기모순이었다. 또 `ALIGHT_NOW` 조건이 C6("CONFIRMED 이후")과
> PR-4("CONFIRMED **또는** 실제 목적역 도착")로 갈렸다. **엄격한 쪽으로 통일**한다.

---

## 4. 저장소 실측 근거 (구현 전 반드시 읽을 것)

좌표는 `main` 기준. **초안의 좌표 5개가 틀려서 고쳤다** — 인용을 그대로 믿지 말고 착수 시 재확인할 것.

| 사실 | 좌표 | 설계에 주는 제약 |
|---|---|---|
| `realtimePosition` 은 **미구현**. 어댑터에 `realtimeStationArrival` 두 경로만 있음 | `SeoulSubwayApiAdapter.java:104`, `:120` | 포트 메서드·DTO·어댑터·파서가 PR-2 범위 |
| `realtimePosition` 경로 파라미터는 lineId 가 아니라 **호선명** | (외부 API 스펙) | `SubwayLineIdMapper` 는 **이름→ID 단방향**뿐이다. 역매핑 신설 필요 |
| `SubwayLineIdMapper.NAMED_LINES` 에 이름 10개, 숫자선은 `1000+n` 규칙 | `application/subway/service/SubwayLineIdMapper.java:15-25` | 역매핑은 이 표를 뒤집고 숫자선은 역산 |
| FCM 자격증명·firebase json이 **worker 컨테이너에만** 마운트 | `compose.yaml` worker `env_file: env/fcm.env` | api 노드는 push를 **물리적으로 못 보낸다** → 매칭·push는 worker |
| arrival 수집기는 worker 전용, **1초 주기**, Redis 단일 키 덮어쓰기 TTL 20초 | `application/subway/service/RealtimeSubwayCollector.java:66`, `application.yaml:302`(주기) `:306`(TTL) | 위치 수집은 **별도 키·별도 주기**로 둔다. 이 스냅샷에 섞지 않는다 |
| `journey_leg.progress_fraction`·`progress_section_id`는 **앱 progress 보고가 소유**하고 timestamp 가드로 덮어씀 | `domain/journey/repository/JourneyLegRepository.java:32-62` | 서버가 같은 칸에 링을 쓰면 신호 잡히는 역에서 앱 GPS 값이 서버 계산을 덮는다 (C7) |
| `journey_leg.vehicle_id`는 "legacy 호환 칼럼, 실제 차량을 식별하지 않는다" | `domain/journey/entity/JourneyLeg.java:80` | **재사용하지 않는다.** 버스 vehicleId 와 충돌 소지. binding 은 별도 표(V44) |
| `journey_leg` 은 재탐색마다 **전 단계가 복제**된다 | `domain/journey/entity/JourneyLeg.java:45`(`routeRevision`), `JourneyNavigationGeometryService:353` | 지하철 전용 컬럼을 얹으면 폭 증가가 revision 수만큼 증폭되고 도보·버스 행까지 넓어진다 → binding 은 1:1 별도 표 |
| route option 은 **`WAIT_*` leg 이 소유**하고 RIDE 는 같은 첫 후보를 가리킨다 | `domain/journey/entity/JourneyLegRouteOption.java` 클래스 javadoc | binding 을 RIDE 가 소유해 축을 나눈다 (C11) |
| ODsay `SubPathResult.stations`(passStopList)가 route snapshot 안에 있음 | `application/route/dto/SubPathResult.java:10`, `StationResult.java` | 역 체인에 **새 API 호출 불필요** |
| **geometry 는 재탐색 후 `journey_revision.route_snapshot` 을 읽는다** (session 것이 아니다) | `application/journey/service/JourneyNavigationGeometryService.java:353` + `JourneyRevisionRepository` 주입 | PR-1 에서 session 만 보면 재탐색 뒤 옛 역 체인을 읽는다 |
| geometry leg 분기 switch | `JourneyNavigationGeometryService.java:103`, default 는 `:108` | 초안의 `:99` 는 오류 |
| `withStops` 헬퍼 | `JourneyNavigationGeometryService.java:281` | 초안의 `:274` 는 오류 |
| `arvlCd` 의미: **`'1'` 이 정규 도착, `'3'`(전역출발) 은 제한된 보강 경로, `'2'` 는 검증된 소비자 없음** | `domain/subway/repository/SubwayArrivalRawRepository.java:49`(코드1) `:64`(코드3) | 초안의 "`'1'` 하나만 신뢰"는 과장이었다. 다만 C9 로 arrival 코드 자체를 출발 판정에 안 쓴다 |
| service date 04:00 경계 규칙 | `application/subway/service/TimetableConverter.java:66` 부근, `SubwayArrivalEventDerivationService` | 초안이 지목한 `SubwayArrivalEventBatchService` 에는 없다. **공용 `ServiceDateResolver` 를 PR-2 에서 신설**하고 위 두 곳이 그것을 쓰게 정리 |
| Redis 비영속 (`--save "" --dbfilename ""`), `maxmemory 128mb`, `allkeys-lru` | `compose.yaml` redis 서비스 | 복구 불가능한 상태를 Redis 에 두지 않는다 (C10) |
| 여정 평가기는 여정당 10~180초 간격 | `application.yaml:355-372` | 탑승 매칭을 여기 얹으면 늦다 → 전용 빠른 루프 |
| 여정 스케줄러 전부 `@Profile("worker")`, compose 의 worker 서비스는 **1개** | `infrastructure/adapter/in/scheduler/journey/*`, `compose.yaml` | 리더 선출 불필요 |
| 여정 종료·취소가 한 자리에 모임 | `application/journey/service/JourneyCommandService.java:142` 부근 | binding·ring cleanup 이 붙을 접점 ① |
| lease 만료 배치 | `application/journey/service/JourneyLeaseExpiryService.java:108` | 접점 ② |
| `SubwayRealtimeArrivalReader.select()` 는 **역 기준** 조회 + "지나간 열차 제외" 필터 | `application/subway/service/SubwayRealtimeArrivalReader.java:88-116` | 위치 추적과 축이 다르다. **재사용 금지** |
| 여정 텔레메트리 표는 "앱 이용행태 계측 원본, 여정 상태 전이의 근거로 쓰지 않는다" + `telemetry_id` 는 앱 멱등키 | `V41__create_journey_telemetry_event.sql` | 서버 판정 로그를 여기 쓰면 계약 위반 → 별도 표 |

---

## 5. 원천 갱신 주기 — 설계 무게중심을 정하는 실측

2026-08-13 18:55~18:58 로컬 실호출로 측정했다(2호선 10초 간격 7회 + 6개 노선 각 1회).
**이 절의 숫자는 추정이 아니라 실측이다.**

### 5-1. 원천은 초 단위로 갱신되지 않는다

| 측정 | 값 |
|---|---|
| 열차별 `recptnDt` 증가폭 | p50 **56초**, p90 84초, max 103초 |
| `now − recptnDt` (수신 지연) | min 56s, p50 **91s**, p90 118s, max 153s |
| 10초 tick 마다 값이 바뀐 열차 수 | 31 → 0 → 0 → 27 → 0 → 17 (**롤링 갱신**) |
| 60초 동안 열차별 갱신 횟수 | 0회:4, 1회:32, 2회:18, 3회:2 |

**관측 시간 해상도 ≈ 60~90초.** 배치가 통째로 바뀌지 않고 절반씩 롤링으로 바뀐다.

### 5-2. 결과 ① — L4 가 보조가 아니라 주력이다

러시아워 배차는 2분(120초)이다. 관문 (2)(§PR-4)는

```
e1 + S < e2 - S      →   120 > 2 × (60~90) = 120~180
```

**러시아워에는 거의 항상 실패한다.** 즉 **t0 단독 고정은 성립하지 않고**, 대부분의 여정이
`PROVISIONAL` 로 시작해 **다음 역 정차(L4)로 확정**된다.

설계를 바꾸지는 않는다 — 원래 그렇게 짜여 있다. 다만 **무게중심이 t0 에서 L4 로 옮겨간다.**
"L4 는 있으면 좋은 후행 검증"이 아니라 **주된 확정 경로**로 다뤄야 한다. C6(미확정 중 하차 안내
전면 금지)이 그만큼 오래 유지된다는 뜻이기도 하다.

### 5-3. 결과 ② — 폴링 주기는 15초, 그리고 감시 집합이 필요 없다

원천이 60~90초마다 바뀌는데 1~2초로 물어보면 **같은 값을 다시 받는다.**
`15000ms` 로 둔다(원천 갱신의 약 1/4 — 갱신을 놓치지 않으면서 헛호출을 줄이는 지점).

그러면 **전 노선을 상시 폴링해도 비용이 무시 가능**해진다.

| 항목 | 실측 기반 추정 |
|---|---|
| 호출 | 23노선 ÷ 15초 = **1.5 req/s** |
| 열차 수 | 7노선 294대 실측 → 전체 **~800대** |
| Redis 쓰기 | 노선당 HASH 1개 = **23 write / 15초** |
| Redis 메모리 | 800 × ~120B ≈ **100KB** (maxmemory 128MB) |

(서울 지하철 API 는 호출수 제한이 없다 — 예산 자체가 논점이 아니다.)

### 5-4. 그래서 노선 감시 집합(`subway:watch`)을 **두지 않는다**

초안은 "전 노선 폴링 불가"를 전제로 감시 집합을 PR-2 의 필수 부품으로 넣었다.
그 전제가 실측으로 무너졌다. 남는 근거는 비용 절감뿐인데 위 표대로 절감할 비용이 없다.

**반대로 감시 집합을 두면 잃는 것이 크다.**

전이 감지는 **직전 관측이 있어야** 성립한다. 감시 방식이면 사용자가 승강장에 도착한 뒤에야
그 노선을 부르기 시작하고, 첫 스냅샷은 baseline 으로만 쓰이므로 **웜업에 원천 갱신 1~2주기,
즉 2~3분이 필요**하다. 그 안에 타면 출발 사건 자체가 없어 `PENDING` 에서 못 나온다.
승강장 도착 2분 내 탑승은 흔하다.

이걸 막는 장치가 prewarm 인데, **prewarm 이 곧 registry 복잡도**다. 그래서 함께 사라진다:

- `SubwayLineWatchRegistry` / `SubwayLineWatchPort` / Redis 어댑터
- `SubwayLineWatchReconciler` (부팅·주기 복구)
- desired-set 질의, prewarm 정책, `watchUntil` 연장
- 상위 ZSET max 재계산, 등록·해제·만료청소의 Lua 원자성
- 수명주기 접점 4곳의 **watch 해제** 부분 (binding·ring 정리는 그대로 남는다)

### 5-5. 대신 두는 것 — 명시 노선 목록 + 운행시간

```yaml
realtime:
  subway:
    position:
      enabled: false                  # 기본 off
      poll-interval-ms: 15000
      active-hours: { start: "04:30", end: "01:00" }   # arrival 수집기와 같은 규칙
      lines: [1001, 1002, 1003, ...]  # 명시 목록
```

**노선 목록을 자동 발견하지 않는다.** 자동이면 서울이 노선을 늘리는 순간 우리가 모르는 사이
호출량이 늘고, 지원하지 않는 노선까지 추적한다. 늘리는 것은 배포 결정이어야 한다.

운행시간 제한은 기존 arrival 수집기(`realtime.subway.active-hours`)와 같은 규칙을 쓴다.

---

## 6. PR 분할

| PR | 제목 | 티어 | 의존 |
|---|---|---|---|
| PR-1 | 지하철 leg 에 역 체인 내려주기 | M | 없음 |
| PR-2a | `realtimePosition` 연결 + 정규화 + 봉투 | M | 없음 |
| PR-2b | 수집기 + 위치 전이 로그 | L | PR-2a |
| PR-3 | V44·V45 + evidence 수신 API | M | 없음 (스키마·API만) |
| PR-4 | 매칭기 + CAS + 수명주기 연동 | L | PR-2b, PR-3 |
| PR-5 | 진행 링 + Live Activity 원격 갱신 | L | PR-1, PR-4 |

PR-1·PR-2a·PR-3 은 서로 독립이라 병렬 가능.

**PR-2 를 2a/2b 로 쪼갠 이유**는 순환을 끊기 위해서다. PR-2b 의 시각 모델·전이 규칙은
원천의 실제 갱신 주기와 `updnLine` 값 공간을 알아야 정해지는데, 그걸 알려면 API 연결이 먼저다.
2a 를 먼저 붙이고 **실호출 1회**로 그 미결들을 해소했다(§5).

초안의 PR-3(스키마+API+매칭+감사+스케줄러+재탐색)은 한 PR 로 너무 커서 **PR-3/PR-4 로 쪼갰다** —
스키마와 API 는 매칭 없이도 검증 가능하다.

> **PR-2a 는 구현 완료** (브랜치 `feat/subway-position-dectect`). 실측 결과는 §5 와
> 루트 `implementation_note.md` 에 있다.

---

## PR-1 — 지하철 leg 에 역 체인 내려주기

### 목적
`RIDE_SUBWAY` leg 의 geometry에 계획 역 목록을 실어 보낸다. **진행의 근거가 아니다**(C1) —
순서·이름·남은 역 수·하차역 여부에만 쓴다.

### 변경

**`application/journey/dto/JourneyNavigationGeometry.java`**
- `StopGeometry` javadoc: 버스 전용 → 버스 정류장 **또는** 지하철 역
- 필드 추가 없음. 지하철은 `seq`(ODsay `index`)·`stopName`·`x`·`y` 를 채우고 **`stopId` 는 null**

> `stopId` 를 null 로 두는 이유: ODsay `StationResult.stationID` 와 서울 API `statnId` 는
> **다른 ID 공간**이다. 값을 실어 두면 언젠가 둘을 비교하는 코드가 생긴다.
> 다음 역 판정은 위치 API 의 `statnId` 로 하고, 계획 역 체인과의 대조는 **이름**으로 한다.

**`application/journey/service/JourneyNavigationGeometryService.java:103`**
```java
// switch (leg.getType()) 안에 추가 — default(:108) 보다 위
case RIDE_SUBWAY -> new Built(withStops(snapshotLeg(leg, sub), subwayStations(sub)), null);
```
```java
/** ODsay passStopList 를 순서 보존해 옮긴다. 좌표가 없으면 null. */
private List<StopGeometry> subwayStations(SubPathResult sub) {
    if (sub == null || sub.stations() == null) return List.of();
    return sub.stations().stream()
            .sorted(Comparator.comparingInt(StationResult::index))
            .map(s -> new StopGeometry(s.index(), null, s.stationName(), s.x(), s.y()))
            .toList();
}
```
기존 `withStops`(`:281`) 재사용. `StationResult` import 추가.

> **재탐색 주의**: `recompile`(`:353`)이 받는 snapshot 은 재탐색 후 `journey_revision.route_snapshot`
> 이다. session 것을 직접 읽지 말 것 — 옛 역 체인이 나온다.

### 시험
- `RIDE_SUBWAY` leg 에 역 체인이 순서대로 실린다
- 지하철 `stops[].stopId` 는 전부 null (ID 공간 혼동 가드)
- `passStopList` 가 없는 스냅샷이면 빈 목록이고 예외 없음
- 버스 leg 의 `stops` 는 **변화 없음** (회귀)
- 재탐색 후 revision 의 역 체인이 나온다 (session 것이 아니다)

### 검증
```bash
./gradlew test --tests '*JourneyNavigationGeometryServiceTest'
./gradlew build
```

---

## PR-2 — `realtimePosition` 수집 + 위치 전이 로그

### 2-1. 외부 API 연결 (신규)

**`application/subway/port/out/SubwayApiPort.java`**
```java
/**
 * 노선 하나의 실시간 열차 위치.
 *
 * <p>목록이 아니라 envelope 를 돌려준다. 수집기가 COMPLETE/PARTIAL/FAILED 를 갈라야 하는데
 * 빈 목록만으로는 "정상인데 열차가 없음"과 "호출 실패"를 구분할 수 없다.
 */
SubwayPositionFetchResult getRealtimePosition(String lineName);
```
```java
public record SubwayPositionFetchResult(
        String lineId,
        List<SubwayTrainPosition> rows,
        Integer totalCount,      // list_total_count. null 이면 미제공
        FetchStatus status,      // COMPLETE | PARTIAL | FAILED
        Instant fetchedAt,       // freshness 판단 전용. 사건 시각으로 쓰지 않는다
        String errorCode         // 서울 API RESULT.CODE. 성공이면 null
) {}
```

**`application/subway/dto/SubwayTrainPosition.java`** (신규 record)
```java
public record SubwayTrainPosition(
        String lineId,          // subwayId — arrival 과 같은 공간
        String trainNo,
        String stationId,       // statnId — 현재 위치
        String stationName,     // statnNm
        String direction,       // updnLine(0/1) 을 arrival 공간("상행"/"하행"/"내선"/"외선")으로 정규화한 값
        String terminalId,      // statnTid
        String terminalName,    // statnTnm
        TrainStatus status,     // trainSttus 0진입 1도착 2출발 3전역출발
        ExpressType express,    // directAt 0일반 1급행 7특급
        String receivedAt       // recptnDt "yyyy-MM-dd HH:mm:ss"
) {}
```
`trainSttus`·`directAt` 은 **원시 문자열을 밖으로 흘리지 않고** enum 으로 닫는다. 모르는 값은
`UNKNOWN` 으로 받고 로그만 남긴다(외부 API 가 값을 늘려도 수집이 죽지 않게).

**`SeoulSubwayApiAdapter`**
- URI: `{baseUrl}/{apiKey}/json/realtimePosition/0/1000/{URL-encoded 호선명}`
- 응답 DTO `SubwayPositionItem` + `SubwayPositionResponse` 신설
- **차단기는 노선별로 판다** — `"seoul-subway-position:" + lineId`.
  공통 키 하나로 두면 **한 노선의 잘못된 응답이 모든 노선을 하루 동안 막는다**
  (`ApiCallBlocker` 는 하루 단위 차단이다: `infrastructure/adapter/out/api/ApiCallBlocker.java:39-46`)
- **일일 호출 예산은 arrival 과 공유한다.** 같은 서울 지하철 API key 를 쓰므로
  (`application.yaml:141` `subway-arrival-key`) position 만 따로 세면 arrival 수집기와 역별 조회가
  쓴 몫을 못 본다. KST 일자 기준 **공유 카운터**를 두고 **실제 HTTP 시도 횟수**(재시도 포함)로 센다

**`SubwayLineIdMapper`** — 역매핑 추가
```java
/** line_id → 서울 API 호선명. realtimePosition 경로 파라미터에 쓴다. */
public static Optional<String> toLineName(String lineId);
```
`NAMED_LINES` 를 뒤집은 표 + 숫자선은 `1000+n` 역산(`"1002"` → `"2호선"`).
해석 불가면 `Optional.empty()` — **추측해서 부르지 않는다**(엉뚱한 노선을 폴링하느니 안 부른다).

### 2-2. 수집 대상과 주기 — 감시 집합 없음

**설정에 적힌 노선 전부를, 운행시간 동안, 15초마다 부른다.** 근거와 대안 기각 사유는 §5.

```
RealtimeSubwayPositionCollector  @Profile("worker")
  @Scheduled(fixedDelayString = "${realtime.subway.position.poll-interval-ms:15000}")

  1. enabled 아니면 return
  2. 운행시간(active-hours) 밖이면 return
  3. 설정된 lines 를 순회하며 getRealtimePosition(lineId)
  4. COMPLETE 인 응답만 transitionTracker.observe(result) 로 넘긴다
```

- **노선 순회는 순차**다. 노선 23개 × 응답 수백ms = 한 tick 안에 끝난다. 병렬로 만들 이유가 없고,
  병렬이면 한 tick 안에서 노선별 관측 시각이 벌어져 전이 비교가 흐려진다
- 한 노선의 실패가 다음 노선을 막지 않는다(어댑터가 예외 대신 `FAILED` 를 값으로 준다)
- tracker 예외가 수집을 죽이지 않게 노선 단위로 try/catch 한다 — 수집이 1차 기능이다

**Redis 유실·수집기 재시작 직후**: 그 노선의 첫 스냅샷은 **baseline 으로만** 저장하고 전이를
만들지 않는다. 직전 관측이 없는데 전이를 만들면 모든 열차가 방금 나타난 것처럼 보인다.
전량 폴링이라 웜업은 **첫 tick 부터 모든 노선에 대해 동시에** 진행되고, 원천 갱신 1주기
(60~90초)면 끝난다 — 사용자가 어느 노선에 나타나든 이미 데워져 있다.

### 2-3. 위치 전이와 출발 판정

열차 identity:
```
serviceDate + lineId + direction + trainNo
```
`serviceDate` 는 신설 `ServiceDateResolver`(04:00 경계, §4 표 참조)가 소유한다.
자정 전후 번호 재사용·노선별 동일 번호 때문에 `trainNo` 단독은 부족하다.

### 전이 — 인과 episode 를 요구한다

```
① A역 ENTERING(0) 또는 ARRIVED(1)          ← 선행 근거
② 더 최신 recptnDt 의 A역 DEPARTED(2)      → DEPARTURE_OBSERVED
③ 같은 운행이 A의 허용 하류 역에서 관측     → DEPARTURE_CORROBORATED
```

**`station != A` 만 검사하지 않는다.** 회차·차량기지 입출고·지선 분기·원천 역행에서도 "다른 역"이
되기 때문이다. ③은 두 조건을 함께 본다.

- **`recptnDt` 단조 증가** (같은 identity 안에서 역행하면 무시)
- **허용 하류 역 양립성** — 계획 역 체인과 `SubwaySegment`(노선별 역 순서, `domain/subway/entity/SubwaySegment.java`)로
  같은 방향의 다음 역인지 확인

**①이 없이 갑자기 나타난 `2` 는 `DEPARTURE_OBSERVED` 로만 둔다.** 반대로 **①→② 가 신선하게 연속
확인됐다면 ③을 기다리지 않고 `CORROBORATED` 로 올린다** — 인과가 이미 관측됐는데 다음 역까지
기다릴 필연성이 없다.

근거: 같은 generation 재수집·원천 stale 이면 지난 `DEPARTED` 가 다시 읽힌다. 선행 관측(①)을
요구하면 그 경우가 걸러진다. 기존 원칙 **"잘못된 확정보다 확정 지연"** 은 유지하되, 지연의 대가를
불필요하게 치르지 않는다.

`DEPARTURE_OBSERVED` 만으로도 매칭기는 `PROVISIONAL` 을 만들 수 있다(PR-4). 확정이 늦어지는
만큼 `PROVISIONAL` 구간이 길어질 뿐이다.

### 시각 모델 — `recptnDt` 를 사건 시각으로 쓰지 않는다

공식 명세의 `recptnDt` 는 **"최종수신시간"** 이다. 출발 시각도, 원천 생성 시각도 아니다.
수신 지연이 있다면 **실제 사건은 `recptnDt` 보다 과거**다.

```
tA   = 마지막 "신선한" A역 관측(①)의 recptnDt
tD   = A역 DEPARTED(②)의 recptnDt
tAway= 첫 하류 관측(③)의 recptnDt

②를 봤을 때:
    departureInterval = [tD - sourceLagBudget, min(tD, tAway)]
②를 놓쳤을 때(결측):
    departureInterval = [tA - sourceLagBudget, tAway]

estimate    = midpoint(interval)
uncertainty = intervalLength / 2
```

> **초안 정정 2가지.** ① `pollInterval` 을 하한 폭으로 썼는데, **폴링 간격은 원천 갱신 간격이 아니다**
> (장애·재시도·노선 순차 호출로 실제 관측 간격과도 다르다). ② `min(tD + maxLag, tAway)` 로
> **상한을 미래로** 밀었는데 방향이 반대다 — 수신 지연이면 사건은 과거에 있다. `sourceLagBudget`
> 은 **하한을 과거로** 넓힌다.

`sourceLagBudget` 은 Phase 0 에서 실측한 `recptnDt` 오차로 정한다(§9 미결).

**`fetchedAt`(우리가 받은 시각)은 freshness 판단에만 쓰고 사건 시각으로 쓰지 않는다.**
"신선한 관측"의 정의: `fetchedAt - recptnDt <= freshnessWindow`.

결측이 길수록 구간이 넓어지고 → PR-4 의 관문이 **자동으로 보수적**이 된다. 설계의 자동조절 장치다.

### `trainSttus=3`(전역출발) 처리

**출발 사건으로 합성하지 않는다.** 그 row 의 `statnId` 가 전역인지 다음 역인지 명확한 계약이 없다.
쓰는 곳은 하나뿐이다 — **선행 A역 episode(①②)가 있을 때 하류 corroboration(③)의 근거로만** 인정.
Phase 0 에서 실제 trace 를 보고 승격 여부를 다시 정한다(§9 미결).

### `ARRIVED` 도 남긴다

L4 후행 확정(PR-4)이 "후보 열차가 다음 역에 언제 도착했나"를 대조해야 하는데, 스냅샷은
덮어쓰기라 그때 이미 사라져 있다.

### 종착·결측

- **종착역**: `statnTid == statnId` 이거나 이후 위치 관측이 끊기면 운행 종료다. 출발 확정하지 않는다
- **결측 vs 운행 종료**: 위치 목록에서 사라진 것은 **출발 신호가 아니다**.
  초안의 `MISSING_PENDING` 개념은 폐기한다 — 위치 API 에서 사라짐은 아무 의미도 없다
- **identity 충돌**: 같은 `(serviceDate, lineId, direction, trainNo)` 가 한 generation 에 여러 row 로
  나오면 **그 identity 를 해당 generation 에서 격리**한다(last-wins 금지, 전이 생성 안 함)

### 2-4. 수집 성공과 빈 결과의 구분

노선별 호출이라 **노선 단위로** 판정한다.

판정 **순서**가 중요하다. row 수 통계는 **마지막 보조 신호**다.

```
1. RESULT.CODE 가 정상인가              → 아니면 FAILED
2. list_total_count == 받은 row 수 인가  → 아니면 PARTIAL (페이지네이션 미완결)
3. row 들의 recptnDt 가 신선한가         → 오래된 것이 다수면 PARTIAL
   (fetchedAt - recptnDt <= freshnessWindow)
4. rowCount 가 직전 N generation 중앙값의 일정 비율 미만인가 → PARTIAL (보조)
```

3번이 필요한 이유: **전체 row 수가 정상이어도 특정 열차만 갱신이 멈춰 있을 수 있다.**
그 열차의 신선하지 않은 관측은 전이 생성에서 제외한다(스냅샷 전체를 버리진 않는다).

`SubwayPositionSnapshot` 에: `generationId`(노선별 단조 증가) · `lineId` · `rowCount` ·
`totalCount` · `fetchStatus` · `fetchedAt`.

**`COMPLETE` generation 사이에서만 전이를 만든다.** 한 노선이 실패해도 다른 노선은 진행한다.

### 2-5. Redis 키

```
subway:pos:{serviceDate}:{lineId}
    HASH  field="{direction}|{trainNo}"
          value="{stationId}|{status}|{recptnEpochSec}|{terminalId}|{express}"
    TTL 900s   — 직전 위치. 전이 감지용

subway:trainlog:{serviceDate}:{lineId}:{direction}:{trainNo}
    ZSET  score=estimateEpochSec
          member(JSON) = {
              "type":    "ARRIVED" | "DEPARTED",
              "quality": "OBSERVED" | "CORROBORATED",   ← 전이 단계가 여기 산다
              "stationId": "...",
              "from": ..., "to": ...,                   ← 구간(초). 확정 시 좁아진다
              "terminalId": "...",                      ← statnTid — 매칭 시점에 다시 안 읽어도 되게
              "express":  "NORMAL|EXPRESS|SPECIAL"      ← directAt
          }
    TTL 900s   — 도착·출발 이력. PR-4 매칭과 L4 확정이 시간 범위로 조회
```

**`terminalId`·`express` 를 사건에 박아 넣는 이유**: 매칭 시점에 현재 HASH 를 읽으면 그 열차가
이미 사라졌거나 종착·급행 정보가 바뀌어 있을 수 있다. **판정에 쓸 값은 관측 순간에 고정**한다.

**`quality` 승격은 덮어쓰기다.** `OBSERVED` 사건이 `CORROBORATED` 가 될 때 새 member 를 추가하지
않고 **같은 `(type, stationId)` 의 member 를 교체**하며 구간을 좁힌다. 그래서
`SubwayTrainLogPort.append` 의 계약은 "같은 것은 무시"가 아니라 **"같은 `(type, stationId)` 는
품질이 오를 때만 교체"** 다.

> **TTL 900초(15분)** — L4 후행 확정이 다음 역 정차까지 기다린다. 3분으로는 역간 소요가 길거나
> 결측이 있으면 부족하다.

Redis 유실 시 binding 은 `PROVISIONAL` 에 머물 뿐 **잘못 고정되지 않는다** (C10).

### 2-6. 파일

**PR-2a 로 이미 구현됨** (브랜치 `feat/subway-position-dectect`)
```
application/subway/dto/SubwayPositionInfo.java                    위치 한 행
application/subway/dto/SubwayPositionFetchResult.java             COMPLETE/PARTIAL/FAILED 봉투
application/subway/dto/SubwayTrainStatus.java                     trainSttus enum
application/subway/dto/SubwayExpressType.java                     directAt enum
application/subway/service/SubwayDirectionNormalizer.java         updnLine → 도착정보 표기
application/subway/service/SubwayLineIdMapper.java#toLineName     line_id → 호선명
infrastructure/adapter/out/api/subway/dto/SubwayPositionItem.java
infrastructure/adapter/out/api/subway/dto/SubwayPositionResponse.java
infrastructure/adapter/out/api/subway/SeoulSubwayApiAdapter#getRealtimePosition
```
포트는 `SubwayApiPort.getRealtimePosition(String lineId)` — **line_id 를 받는다**.
호선명 변환은 어댑터 몫이다(응용 계층이 서울 API 의 경로 규칙을 알 이유가 없다).
`CachedSubwayApiAdapter` 는 **위임만 하고 캐시하지 않는다** — 캐시하면 수집 주기보다 오래된
위치를 새 관측으로 착각해 없던 전이를 만든다.

**PR-2b 로 남은 것**
```
application/subway/dto/SubwayTrainEvent.java                      ARRIVED/DEPARTED 사건 + 구간 + 품질
application/subway/dto/TrainIdentity.java                         serviceDate+lineId+direction+trainNo
application/subway/service/RealtimeSubwayPositionCollector.java   @Profile("worker"). 전 노선 순차 수집
application/subway/service/SubwayTrainTransitionTracker.java      전이 감지 + 출발/도착 로그
application/subway/service/ServiceDateResolver.java               04:00 경계 (공용 — 기존 두 곳도 이걸 쓰게 정리)
application/subway/port/out/SubwayTrainLogPort.java
application/subway/config/SubwayPositionProperties.java           enabled/interval/active-hours/lines
infrastructure/adapter/out/cache/subway/RedisSubwayTrainLogAdapter.java
```

**포트 시그니처**
```java
public interface SubwayTrainLogPort {
    /**
     * 도착·출발 사건 한 건.
     *
     * <p>같은 {@code (type, stationId)} 는 <b>품질이 오를 때만</b> 교체한다
     * (OBSERVED → CORROBORATED). 무조건 무시도, 무조건 추가도 아니다.
     */
    void append(TrainIdentity train, SubwayTrainEvent event);

    /** 시간 범위의 출발 사건. 매칭기가 후보를 찾는 진입점. */
    List<SubwayTrainEvent> findDepartures(String serviceDate, String lineId, String direction,
                                          String stationId, Instant from, Instant to);

    /** 특정 열차의 도착 사건. L4 후행 확정이 쓴다 — §5-2 대로 이쪽이 주력 경로다. */
    List<SubwayTrainEvent> findArrivals(TrainIdentity train, Instant from, Instant to);

    /** 직전 위치. 전이 감지의 좌변이다. */
    Optional<TrainObservation> lastSeen(TrainIdentity train);
    void remember(TrainIdentity train, TrainObservation observation);
}
```

ZSET member 추가와 TTL 갱신은 **Lua 또는 `SessionCallback`** 으로 묶는다. 쪼개지면 TTL 없는
키가 남아 `allkeys-lru` 압박이 된다.

`RealtimeSubwayPositionCollector` 는 arrival 수집기와 **별도 빈·별도 스케줄**이다.

### 2-7. 시험
**전이·시각**
- 위치 목록에서 사라진 것만으로 출발 확정하지 않는다
- 선행 관측(①) 없이 나타난 `trainSttus=2` 는 `OBSERVED` 까지만 (`CORROBORATED` 안 됨)
- ①→② 가 신선하게 연속 확인되면 하류 관측 없이 `CORROBORATED` 로 오른다
- 하류 관측이 **허용 하류 역이 아니면** 확정하지 않는다 (회차·차량기지·지선 가드)
- `recptnDt` 가 역행하는 관측은 무시한다
- 출발 구간의 **하한이 `recptnDt` 보다 과거**다 (`sourceLagBudget` 방향 가드)
- `sttus=2` 를 놓친 경우 구간이 `[tA - lag, tAway]` 로 넓어지고 `uncertainty` 가 커진다
- `trainSttus=3` 단독으로는 출발 사건을 만들지 않는다
- 같은 `(type, stationId)` 사건은 품질이 오를 때만 교체되고 중복 append 되지 않는다
- 종착 도달 열차는 출발 확정하지 않는다
- 같은 identity 가 한 generation 에 2행이면 그 identity 를 격리한다 (last-wins 금지)
- `ARRIVED` 사건에 `terminalId`·`express` 가 박혀 있다 (매칭 시점 재조회 불필요)

**수집 상태**
- `RESULT.CODE` 이상이면 `FAILED`, `list_total_count` 불일치면 `PARTIAL`
- row 수가 정상이어도 특정 열차의 `recptnDt` 가 낡았으면 그 열차만 전이에서 제외
- `FAILED`/`PARTIAL` generation 사이에서는 전이를 만들지 않는다
- 한 노선이 실패해도 다른 노선 수집이 계속된다 (노선별 차단기)
- 빈 정상 응답과 호출 실패가 구분된다 (envelope)

**수집 대상·주기**
- `enabled: false` 면 아무 노선도 부르지 않는다 (완전 no-op)
- 운행시간 밖이면 부르지 않는다
- 설정에 없는 노선은 부르지 않는다 (자동 발견 금지)
- 한 노선이 `FAILED` 여도 나머지 노선 수집이 계속된다
- Redis 유실·재시작 직후 첫 스냅샷은 baseline 으로만 쓰이고 출발 사건을 만들지 않는다
- ZSET member 추가와 TTL 갱신이 한 단위로 적용된다 (integration)

**매핑**
- `toLineName` 이 숫자선·named 선을 모두 해석하고, 모르는 lineId 는 빈 값이라 호출하지 않는다
- `updnLine` `0/1` 이 arrival 의 `"상행"/"하행"/"내선"/"외선"` 과 같은 공간으로 정규화된다

### 2-8. 검증

PR-2a (완료)
```bash
./gradlew test --tests '*SubwayLineIdMapperTest' \
               --tests '*SubwayDirectionNormalizerTest' \
               --tests '*SubwayPositionDtoTest'
./gradlew build -x test
```

PR-2b
```bash
./gradlew test --tests '*SubwayTrainTransitionTrackerTest' \
               --tests '*RealtimeSubwayPositionCollectorTest' \
               --tests '*ServiceDateResolverTest' \
               --tests '*SubwayTrainLog*Test'
./gradlew build
```

---

## PR-3 — 스키마 + evidence 수신 API

> **마이그레이션 번호 주의**: `V43__add_reroute_proposal_accepted_candidate.sql` 이 **이미 있다**.
> 이 PR 의 두 장은 **V44 · V45** 이고, PR-5 의 Live Activity 토큰 표는 **V46** 이다.
> V18+ 는 untracked convention 이라 착수 시점에 `ls src/main/resources/db/migration | sort -V | tail`
> 로 **다시 확인**할 것.

### V44 — `subway_leg_binding` (journey_leg 과 1:1)

```sql
-- 지하철 단계 하나가 어느 열차에 묶였는지. journey_leg 과 1:1 이지만 표를 나눈다.
--
-- 왜 journey_leg 컬럼이 아닌가.
--   ① journey_leg 은 재탐색마다 전 단계가 통째로 복제되는 표다. 지하철 단계에만 쓰이는
--      컬럼 8개를 얹으면 그 폭이 revision 수만큼 증폭되고, 도보·버스 단계 행까지 넓어진다
--   ② binding_status 는 부분 인덱스 predicate 라, 상태가 바뀔 때마다 journey_leg 의
--      HOT update 가 깨지고 인덱스 항목 삽입·삭제가 생긴다. 그 표는 progress 로 이미 자주 갱신된다
--   ③ binding 의 수명과 CAS 는 leg 의 것과 다르다. 한 표에 두면 두 종류의 낙관적 잠금이 섞인다
--
-- 대가는 조회 join 한 번이다. 스냅샷 조립이 이미 leg 와 route option 을 묶어 읽으므로 형태가 같다.

CREATE TABLE subway_leg_binding (
    id                  bigint generated by default as identity primary key,
    -- 감사 칼럼만 timestamp(6). 도메인 절대시각은 timestamptz 다 (V35__create_journey.sql:6)
    created_at          timestamp(6) not null,
    updated_at          timestamp(6) not null,
    deleted_at          timestamp(6),

    -- 1:1. binding 은 RIDE_SUBWAY leg 이 소유한다(C11)
    journey_leg_id      bigint       not null references journey_leg(id),
    -- 여정 단위 정리·fencing 을 leg join 없이 하기 위한 비정규화.
    -- (journey_id, route_revision, leg_index) 가 journey_leg 의 것과 같아야 한다 —
    -- 이 표에 쓰는 경로는 §PR-3 evidence 트랜잭션 하나뿐이고, 거기서 leg 을 읽어 채운다.
    -- 애플리케이션 단일 진입점이 불변식을 진다(트리거를 두지 않는 이유: 쓰기 경로가 하나다).
    journey_id          bigint       not null references journey_session(id),
    route_revision      integer      not null
        constraint subway_leg_binding_revision_check check (route_revision >= 1),
    leg_index           integer      not null
        constraint subway_leg_binding_leg_index_check check (leg_index >= 0),

    status              varchar(20)  not null
        constraint subway_leg_binding_status_check
            check (status in ('PENDING','PROVISIONAL','CONFIRMED','ABANDONED')),

    -- 열차 identity 전체를 저장한다. trainNo 단독은 자정 전후 재사용·노선별 중복으로 부족하다
    train_no            varchar(32),
    train_service_date  date,
    train_line_id       varchar(16),
    train_direction     varchar(10),
    -- 실제 승차 시각이 아니라 센서 추정값이다. boarded_at 은 사실을 참칭한다
    boarded_detected_at timestamptz,

    source              varchar(30)
        constraint subway_leg_binding_source_check
            check (source is null or source in
                  ('MOTION_AUTOMOTIVE','MOTION_UNKNOWN','USER_MANUAL','INHERITED_ON_REROUTE')),
    confidence          varchar(10)
        constraint subway_leg_binding_confidence_check
            check (confidence is null or confidence in ('LOW','MEDIUM','HIGH')),

    -- 낙관적 잠금. 늦게 온 evidence 나 재처리된 worker 가 교정된 결과를 되돌리지 못하게 한다
    version             integer      not null default 0
        constraint subway_leg_binding_version_check check (version >= 0),
    -- 사용자 보정 세대. 방금 거부한 열차를 다시 고르지 않기 위한 후보 무효화 축(§PR-3 보정 API)
    reset_generation    integer      not null default 0
        constraint subway_leg_binding_reset_gen_check check (reset_generation >= 0),
    last_movement_seq   integer
        constraint subway_leg_binding_movement_seq_check
            check (last_movement_seq is null or last_movement_seq >= 0),
    last_episode_seq    integer
        constraint subway_leg_binding_episode_seq_check
            check (last_episode_seq is null or last_episode_seq >= 0),

    -- 상태 불변식 — 값 목록 CHECK 만으로는 못 막는 것들
    constraint subway_leg_binding_train_identity_check
        check ( (train_no is null and train_service_date is null
                 and train_line_id is null and train_direction is null)
             or (train_no is not null and train_service_date is not null
                 and train_line_id is not null and train_direction is not null) ),
    constraint subway_leg_binding_bound_requires_train_check
        check ( status not in ('PROVISIONAL','CONFIRMED') or train_no is not null ),

    -- 이 표는 soft-delete 하지 않는다(아래 주석). 그래서 통 unique 다
    constraint uk_subway_leg_binding_leg unique (journey_leg_id)
);

-- 매칭 루프가 훑는 대상. 살아 있는 것만 남아야 인덱스가 팽창하지 않는다
CREATE INDEX ix_subway_leg_binding_pending
    ON subway_leg_binding (journey_id, route_revision)
    WHERE status IN ('PENDING','PROVISIONAL');

COMMENT ON TABLE subway_leg_binding IS
    '지하철 단계가 고정한 열차. journey_leg 과 1:1 이며 재탐색 복제 대상이 아니다. 행을 지우지 않는다';
COMMENT ON COLUMN subway_leg_binding.boarded_detected_at IS
    '센서가 이동 시작을 감지한 시각. 실제 승차 시각이 아니다';
COMMENT ON COLUMN subway_leg_binding.reset_generation IS
    '사용자 보정 세대. 이전 세대의 후보 감사행은 재매칭에 쓰지 않는다';
```

- **`journey_leg` 은 손대지 않는다.** `vehicle_id` 도 그대로 비워 둔다(§4)
- **`timestamptz` 로 정정.** 초안은 `timestamp(6)` 이 주변 표 관례라고 썼는데 **거꾸로였다** —
  `V35__create_journey.sql:6` 이 "절대시각은 timestamptz 다(**감사 칼럼만** 기존 관례대로 timestamp(6))"
  라고 명시한다. `boarded_detected_at` 은 도메인 절대시각이다
- **이 표는 soft-delete 하지 않는다.** `deleted_at` 은 표 관례상 두지만 **채우지 않는다**.
  지우고 새 행을 만들면 `version`·`reset_generation` 이 0으로 돌아가 ABA 가 재발한다.
  같은 이유로 `journey_notification`(V40)도 통 unique + 삭제 금지를 명문화하고 있다
- **`CORRECTED` 를 지속 상태에서 뺐다.** 교정은 `CONFIRMED` 로 끝나고, 교정 사실은 V45 감사 사건
  (`CORRECTED`)으로만 남는다. 지속 상태에 두면 `CORRECTED → CONFIRMED` 가 한 트랜잭션이라
  DB 에서 관측되지 않는 유령 상태가 된다
- **`last_movement_seq`/`last_episode_seq` 를 나눴다.** evidence sequence 가 kind 별 공간이라
  단일 컬럼으로는 두 흐름이 서로를 덮는다
- 부분 인덱스 키가 `(journey_id, route_revision)` 인 이유: 매칭기 질의가 **ACTIVE 여정의 현재
  revision** 으로 좁히기 때문(§PR-4)
- **행이 없음 = binding 없음.** `PENDING` 행은 첫 `MOVEMENT_START` evidence 가 만든다

### 재탐색과의 관계 — 표를 나눈 실질 이득

`journey_leg` 이 revision 마다 복제돼도 `subway_leg_binding` 은 **따라오지 않는다.** 새 revision 의
leg 은 binding 행이 없으므로 자동으로 "고정 없음"이다. 초안(leg 컬럼)에서는 복제가 기본이라
**계승을 막는 코드**를 따로 써야 했는데, 이제 계승이 필요할 때만 **명시적으로 행을 만든다**.

기본이 뒤집힌 것이 핵심이다 — 빠뜨렸을 때 안전한 쪽으로 기운다.

### V45 — 판정 감사 로그

```sql
CREATE TABLE subway_binding_event (
    id                  bigint generated by default as identity primary key,
    created_at          timestamp(6) not null,
    updated_at          timestamp(6) not null,
    deleted_at          timestamp(6),

    journey_leg_id      bigint       not null references journey_leg(id),
    journey_id          bigint       not null references journey_session(id),
    route_revision      integer      not null
        constraint subway_binding_event_revision_check check (route_revision >= 1),
    leg_index           integer      not null
        constraint subway_binding_event_leg_index_check check (leg_index >= 0),

    kind                varchar(10)  not null
        constraint subway_binding_event_kind_check check (kind in ('EVIDENCE','DECISION')),
    event_type          varchar(30)  not null
        constraint subway_binding_event_type_check check (event_type in (
            'MOVEMENT_START','STATION_STOP_EPISODE',
            'PENDING','PROVISIONAL','CONFIRMED','CORRECTED','ABANDONED',
            'USER_RESET','USER_NOT_BOARDED','INHERITED_ON_REROUTE')),

    -- kind 와 event_type 의 양립성. 값 목록만 막으면 DECISION 에 MOVEMENT_START 가 들어간다
    constraint subway_binding_event_kind_type_check check (
        (kind = 'EVIDENCE' and event_type in ('MOVEMENT_START','STATION_STOP_EPISODE'))
     or (kind = 'DECISION' and event_type not in ('MOVEMENT_START','STATION_STOP_EPISODE'))),

    evidence_sequence   integer
        constraint subway_binding_event_seq_check
            check (evidence_sequence is null or evidence_sequence >= 0),
    -- EVIDENCE 면 sequence 가 반드시 있어야 멱등이 성립한다
    constraint subway_binding_event_evidence_seq_required check (
        kind <> 'EVIDENCE' or evidence_sequence is not null),

    algorithm_version   varchar(20),
    binding_version     integer,
    -- 이 판정이 어느 보정 세대에서 나왔나. 후보 복구가 세대를 건너뛰지 않게 한다
    reset_generation    integer      not null default 0,

    occurred_at         timestamptz  not null,   -- 기기/원천 시각
    received_at         timestamptz  not null,   -- 서버 시각. 보관 삭제 기준

    -- 열차 identity 전체. train_no 단독으로는 후보를 복구할 수 없다
    chosen_train_no     varchar(32),
    chosen_service_date date,
    chosen_line_id      varchar(16),
    chosen_direction    varchar(10),
    constraint subway_binding_event_chosen_identity_check check (
        (chosen_train_no is null and chosen_service_date is null
         and chosen_line_id is null and chosen_direction is null)
     or (chosen_train_no is not null and chosen_service_date is not null
         and chosen_line_id is not null and chosen_direction is not null)),

    -- 후보 집합과 근거. 컬럼으로 박지 않는 이유: Phase 0 튜닝 한 바퀴마다 필드가 바뀐다
    --   { motionInterval, departureInterval, e1, e2, S, localHeadway, gate,
    --     candidates:[{ trainNo, serviceDate, lineId, direction,   ← identity 전체
    --                   departureInterval, error, expectedNextArrivalAt, rejectReason }] }
    detail              jsonb,

    -- kind 별 sequence 공간이므로 event_type 을 포함한다.
    -- 초안은 (leg, kind, seq) 였는데 MOVEMENT_START 와 STATION_STOP_EPISODE 가 둘 다
    -- kind='EVIDENCE' 라 각자의 sequence 1 이 서로 충돌했다
    CONSTRAINT uk_subway_binding_event_evidence
        UNIQUE (journey_leg_id, kind, event_type, evidence_sequence)
);

CREATE INDEX ix_subway_binding_event_journey ON subway_binding_event (journey_id, occurred_at);
CREATE INDEX ix_subway_binding_event_leg     ON subway_binding_event (journey_leg_id, id DESC);
CREATE INDEX ix_subway_binding_event_received ON subway_binding_event (received_at);
```

- `journey_leg_id` **FK 필수** — 없으면 존재하지 않는 revision/legIndex 에도 감사행이 들어간다
- **`PROVISIONAL` 후보 집합의 canonical 저장소가 이 표다.** Redis 가 날아가도
  `ix_subway_binding_event_leg` 로 최신 `PROVISIONAL` 행의 `detail.candidates` 를 읽는다.
  **단 조건이 붙는다** — `binding_version`·`reset_generation` 이 현재 binding 과 **일치하는 행만**
  읽는다. 옛 세대 후보를 되살리면 사용자가 거부한 열차가 다시 뽑힌다
- **한계 명시**: Redis 가 날아가면 후보 목록은 살아남아도 **그 후보들의 `ARRIVED` 로그는 사라진다.**
  그러면 L4 대조 대상이 없어 `CONFIRMED` 로 못 올리고 `PROVISIONAL` 이 유지된다.
  "Redis 유실 후에도 교정 가능"은 **후보 식별까지**이지 확정까지가 아니다

> `journey_telemetry_event`(V41)를 쓰지 않는 이유는 §4 마지막 행.

### evidence API

```
POST /api/journeys/{journeyId}/subway/boarding-evidence   → 202
```
```jsonc
{
  "routeRevision": 3,
  "legIndex": 4,                      // WAIT 이 와도 짝 RIDE 로 정규화한다 (C11)
  "kind": "MOVEMENT_START",           // 또는 "STATION_STOP_EPISODE"
  "evidenceSequence": 1,              // kind 별 1부터
  "movementStartedAt": "...",         // MOVEMENT_START
  "episodeStartedAt": "...",          // STATION_STOP_EPISODE
  "stationaryAt": "...",
  "resumedAt": null,                  // 없으면 후속 evidence 로 보완
  "previousActivity": "STATIONARY",   // STATIONARY | WALKING
  "currentActivity": "AUTOMOTIVE",    // AUTOMOTIVE | UNKNOWN_MOVING
  "confidence": "HIGH",               // LOW | MEDIUM | HIGH
  "sustainedSeconds": 14,
  "vibrationEvidence": true,
  "detectedAt": "..."
}
```
응답 — **고정값이 아니라 현재 canonical 상태**를 돌려준다(중복 전송도 마찬가지)
```jsonc
{ "bindingStatus": "PENDING", "bindingVersion": 0, "legIndex": 5 }
```

**트랜잭션 경계**: evidence INSERT 와 leg 전이는 **같은 트랜잭션**이다. 쪼개지면
"감사행만 있음" 또는 "상태만 있음"이 남는다.

```
[하나의 짧은 트랜잭션]
  1. 여정 소유권 + routeRevision fencing + 여정 status=ACTIVE
  2. legIndex 정규화 (WAIT → 짝 RIDE), 타입이 RIDE_SUBWAY 인지
  3. subway_binding_event INSERT (ON CONFLICT DO NOTHING = 멱등)
  4. MOVEMENT_START 이면 subway_leg_binding 을 PENDING 으로 upsert
       INSERT ... ON CONFLICT (journey_leg_id) DO NOTHING
     → 행이 이미 있으면 아무것도 덮지 않는다. 두 번째 MOVEMENT_START 가
       PROVISIONAL/CONFIRMED 를 PENDING 으로 되돌리면 안 된다
     → 비정규화 3칸(journey_id, route_revision, leg_index)은 이 자리에서 leg 을 읽어 채운다.
       이 표에 INSERT 하는 경로는 여기 하나뿐이다
  5. kind 에 따라 last_movement_seq / last_episode_seq 를 (null or < :sequence) 조건부 갱신
  6. 현재 canonical 상태 반환 (202)
```
**매칭하지 않는다** — api 는 push 를 못 보내고, 매칭은 재시도가 필요하다.

**입력 검증**
- 기기 시각 허용 범위: `received_at - maxPast ≤ occurred_at ≤ received_at + maxFuture` (§8 미결 3).
  범위 밖이면 evidence 는 저장하되 `detail.clockSuspect=true` 로 표시하고 매칭 가중을 낮춘다
- `resumedAt` 이 있으면 `stationaryAt < resumedAt`
- `STATION_STOP_EPISODE` 는 `stationaryAt` 필수
- sequence gap·out-of-order 는 거부하지 않는다. 매칭기가 가진 것으로 판단한다

### 사용자 보정 API

**대상 leg 을 반드시 지정한다.** 한 여정에 지하철 binding 이 여럿일 수 있으므로(환승),
`journeyId` 만으로는 어느 것을 되돌릴지 정해지지 않는다.

```
POST   /api/journeys/{journeyId}/subway/binding/reset      다른 열차를 탔어요
DELETE /api/journeys/{journeyId}/subway/binding            탑승하지 않았어요
   body/query: { "routeRevision": 3, "legIndex": 4 }
```
- `legIndex` 는 evidence API 와 같은 규칙으로 **WAIT → 짝 RIDE 정규화**(C11)
- `routeRevision` 이 현재 revision 이 아니면 거절 (fencing)
- reset: 행을 `PENDING` 으로 되돌리고 열차 identity 4칸을 비운다.
  **`version++` 와 `reset_generation++` 를 함께** 올리고 감사행 `USER_RESET`
- delete: `ABANDONED`, `version++`, 감사행 `USER_NOT_BOARDED`
- 둘 다 `CONFIRMED` 도 뒤집을 수 있다 — **사용자 명시 행위만이 유일한 예외**다 (C4)
- **행을 지우지 않는다** — 지우면 `version`·`reset_generation` 이 0으로 돌아가 ABA 가 재발한다

### reset 뒤 같은 열차를 다시 고르지 않기

reset 이 상태만 되돌리면 매칭기는 **같은 motion evidence 와 같은 출발 로그**를 다시 읽어
방금 거부한 열차를 그대로 재선택한다. 두 장치로 막는다.

1. **`reset_generation` 으로 후보를 무효화**한다. 후보 복구는 현재 세대의 감사행만 읽는다
2. **거부한 identity 를 기억한다.** `USER_RESET` 감사행의 `chosen_*` 4칸이 그 기록이다.
   매칭기는 **같은 leg 의 `USER_RESET` 이력에 있는 identity 를 후보에서 제외**한다
3. reset 직후에는 **새 evidence 가 오기 전까지 자동 재매칭하지 않는다**
   (`last_movement_seq` 를 비워 두고, 새 `MOVEMENT_START` 가 채우게 한다)

### 시험
- WAIT legIndex 로 보내도 짝 RIDE 의 binding 행에 기록된다 (C11)
- 다른 사용자의 여정이면 거절
- superseded revision 이면 거절
- 같은 `(leg, kind, sequence)` 재전송이 두 번 기록되지 않고, 응답이 **현재 상태**다
- evidence INSERT 가 실패하면 binding 행 생성도 롤백된다
- 기기 시각이 범위 밖이면 `clockSuspect` 로 저장된다
- `resumedAt < stationaryAt` 이면 거절
- `MOVEMENT_START` 두 번째가 이미 `PROVISIONAL`/`CONFIRMED` 인 행을 `PENDING` 으로 되돌리지 않는다
- `USER_RESET` 은 `CONFIRMED` 를 `PENDING` 으로 되돌리되 **행을 지우지 않고 `version` 을 올린다**
- 한 leg 에 binding 행이 둘 생기지 않는다 (`uk_subway_leg_binding_leg`)
- V44·V45 CHECK·FK 가 잘못된 값을 막는다 (integration)
- 동시 중복 evidence 가 unique 로 하나만 남는다 (integration)

---

## PR-4 — 매칭기 + CAS + 수명주기

### 매칭 루프

**기존 `JourneyDueEvaluatorScheduler` 에 얹지 않는다** (여정당 10~180초는 너무 느리다).

```
infrastructure/adapter/in/scheduler/journey/SubwayBindingMatchScheduler.java
  @Profile("worker")
  @ConditionalOnProperty(prefix="journey.subway.binding", name="enabled", havingValue="true")
  @Scheduled(fixedDelayString="${journey.subway.binding.tick-ms:1000}")
```

> Redis Stream + 리더 선출은 이 규모에 과하다 — worker 서비스가 1개이고 대상 leg 은
> 부분 인덱스로 훑히는 극소수다. 워커를 늘릴 때 스트림으로 승격한다.

**질의는 반드시 살아 있는 것만 본다** (초안 누락):
```sql
SELECT b.*, l.*
  FROM subway_leg_binding b
  JOIN journey_leg l       ON l.id = b.journey_leg_id
  JOIN journey_session s   ON s.id = b.journey_id
 WHERE b.status IN ('PENDING','PROVISIONAL')
   AND b.deleted_at IS NULL
   AND l.deleted_at IS NULL
   AND s.status = 'ACTIVE'
   AND b.route_revision = s.route_revision      -- superseded revision 배제
```
이게 없으면 끝난 여정·옛 revision 의 `PENDING` 을 **영원히 재처리**한다.

**트랜잭션 경계**
```
[트랜잭션 밖]  Redis 조회(출발 로그·도착 로그) + 후보 계산
[짧은 트랜잭션] CAS UPDATE + DECISION INSERT + push outbox INSERT 를 함께 커밋
```
외부 조회를 트랜잭션에 넣지 않는 것은 기존 `JourneyEvaluationService` 와 같은 원칙이다.

### 후보 판정 (`SubwayTrainMatcher`)

**1단계 — 배제**

| 조건 | 처리 |
|---|---|
| 방향(`wayCode`→direction) 미확정 | **판정 자체를 하지 않음** (기존 `WaitSubwayArrivalPredictor` 와 같은 원칙) |
| 노선·방향 불일치 | 배제 |
| 종착역(`statnTid`)이 하차역에 도달 불가 | 배제. 값은 **출발 사건에 박힌 것**을 쓴다(현재 HASH 재조회 금지) |
| `directAt` 이 급행/특급 | **MVP 는 전부 배제** (아래 주석) |
| 탑승역 출발 사건이 아예 없음 | 배제 |
| 같은 leg 의 `USER_RESET` 이력에 있는 identity | 배제 (사용자가 이미 거부) |

> **급행 배제는 MVP 한정 타협이다.** 초안은 "계획 역 체인 + 실제 도착 로그로 통과역 판정"이라 썼는데
> **탑승 시점엔 미래 도착 로그가 없어 구현 불가능**하다. 정적 급행 정차 패턴 데이터가 없는 동안은
> 급행을 후보에서 빼는 편이 일관된다 — 잘못 고정하느니 고정하지 않는다(C3).
> 대가: 사용자가 실제로 급행을 타면 `PROVISIONAL` 조차 못 만들고 안내가 "확인 중"에 머문다.
> 급행 정차 패턴 확보는 §9 미결.

> **`arrivalSeconds` 기반 "다가오는 열차 배제"는 폐기**했다. 위치 API 에는 그 값이 없고,
> 출발 사건이 있는 열차만 후보가 되므로 "아직 안 온 차"는 자연히 빠진다.

**2단계 — 관문**

```
motionInterval    = [movementStartedAt - motionUnc, movementStartedAt + motionUnc]
departureInterval = PR-2 가 만든 출발 구간 (이미 불확실성을 품고 있다)

gap(X, Y) = 두 구간이 겹치면 0,
            아니면 가까운 두 끝점 사이의 초 (구간 간 최소거리)

e1 = gap(motionInterval, departureInterval(best))
e2 = gap(motionInterval, departureInterval(second))
S  = clockSkewBudget                       ← 구간에 포함되지 않은 잔여 불확실성만

(1) e1 <= absoluteMaxSkew
(2) e1 + S < e2 - S
(3) (e2 - e1) >= beta * localHeadway       ← 선택. localHeadway 추정 가능할 때만
```

**관문 (2)를 통과하면 best 는 정의상 유일하다.** 그래서 그 뒤에 동점 처리가 올 자리가 없다 —
초안의 tie-break 단계는 도달 불가능한 죽은 분기였고 삭제했다(C8).

`motionUnc` 는 **센서·전송 지연만** 담고 기기 시계 오차는 담지 않는다. 시계 오차는 `S` 가 전담한다 —
양쪽에 넣으면 다시 이중 계산이 된다.

> **수식 정정**(codex 지적 ⑦): 초안은 `e1 = distance(interval, interval)` 로 구간 거리를 쓰면서
> `U = motionUncertainty + departureUncertainty + clockSkewBudget` 를 또 더해 **불확실성을 두 번**
> 셌다. `gap` 이 이미 두 구간의 폭을 반영하므로, 여기에 더할 것은 **구간에 안 들어간 잔여분**
> (기기 시계 오차 `S`) 뿐이다.
>
> 경계 예: `motionInterval=[100,110]`, `best=[105,115]` → 겹치므로 `e1=0`.
> `second=[200,210]` → `e2=90`. `S=15` 면 `0+15 < 90-15` 참 → 관문 (2) 통과.
> `second=[125,135]` → `e2=15`. `0+15 < 15-15` 거짓 → **고정 금지**.

> **`headway/2` 단독 관문 폐기**: ① 후보 정렬이 오차 순이라 `1위 - 2위` 가 부호를 보장하지 않고
> ② 후보가 한 대면 headway 를 못 재서 관문이 아예 사라진다(첫차·막차·심야).
>
> `localHeadway` 는 (a) 같은 노선·방향의 최근 실측 출발 이력 (b) 앞/뒤 열차 순으로 추정한다.
> 둘 다 없으면 (3)을 건너뛰고 **`PROVISIONAL` 로만** 둔다. 자동 `CONFIRMED` 금지.

**3단계 — L4 후행 확정**

```
PENDING
  → 관문 통과: PROVISIONAL(best, detail.candidates=[best, 그 외 살아남은 후보 …])
  → STATION_STOP_EPISODE 가 best 의 다음 역 ARRIVED 와 가장 잘 맞음: CONFIRMED(best)
  → 다른 후보와 더 잘 맞음:            CONFIRMED(그 후보) + 감사 사건 CORRECTED
  → 어느 후보와도 안 맞음 / 모호:      PROVISIONAL 유지. 윈도우 초과 시 ABANDONED
```

판별력 근거: 후보들의 **다음 역 도착 시각이 서로 headway(2~6분)만큼 벌어져 있고** 센서 오차는
수십 초라, 관측된 정차 시각으로 후보를 가르는 신호대잡음비가 크다.

> 초안은 "t0 판정이 틀리면 **정확히 한 대 차이**"라고 썼는데 **보장이 없다.** 결측·급행·지선·다중
> 후보가 있으면 best 의 오답이 반드시 second 라는 법이 없다. L4 는 "second 를 확인한다"가 아니라
> **"기록된 후보 집합 중 관측과 가장 잘 맞는 것을 고른다"**로 정의한다. 어느 후보와도 맞지 않으면
> `CONFIRMED` 로 올리지 않고 `PROVISIONAL` 을 유지한다.

비교 대상인 "후보의 다음 역 도착 시각"은 PR-2 의 `ARRIVED` 로그에서 읽는다(`findArrivals`).
후보 집합은 최신 `PROVISIONAL` 감사행의 `detail.candidates` 에서 복구한다 — **재시작해도 교정 가능**.

정차 episode 는 단일 시각이 아니라 `MOVING → 비이동 8~40초 → MOVING` 구간으로 본다.
신호대기·터널 서행·폰을 내려놓은 상황이 단일 `stationary` 로 보이기 때문이다.

**L4 evidence 가 오지 않아도 안내는 시작한다** — 단, C6 에 따라 하차 관련 안내는 못 나간다.

### CAS

```sql
UPDATE subway_leg_binding
   SET status = :next, train_no = :trainNo, service_date = :serviceDate,
       version = version + 1, updated_at = :now
 WHERE journey_leg_id = :legId
   AND deleted_at IS NULL
   AND version = :expectedVersion
   AND status IN ('PENDING','PROVISIONAL')
```
`CONFIRMED` 는 이 경로로 뒤집히지 않는다. 사용자 보정(PR-3)만이 예외다.

### 재탐색(reroute) 정책 — 초안 누락

`journey_leg` 은 revision 마다 전 단계가 복제되지만 `subway_leg_binding` 은 **따라오지 않는다**
(§PR-3). 새 revision 의 leg 은 binding 행이 없으므로 **기본값이 "고정 없음"** 이다.

| 상황 | 처리 |
|---|---|
| 변경 지점 이후의 새 leg | 행 없음 = 고정 없음 (별도 코드 불필요) |
| 아직 탑승 전인 WAIT/RIDE pair 의 route option 이 바뀜 | 계승하지 않음. evidence 도 승계 안 함 |
| 현재 탑승 중인 `RIDE_SUBWAY` 가 **노선·방향·승차역·하차역까지 완전히 동일** + 상태가 **`CONFIRMED`** | 새 leg 에 binding 행을 **명시적으로 새로 만든다**(`version=0`, `source=INHERITED_ON_REROUTE`) + 감사 사건 |
| 같은 조건인데 상태가 `PENDING`/`PROVISIONAL` | **계승하지 않는다** (아래 근거) |
| 과거 revision 의 binding 행 | 상태만 `ABANDONED` 로 접는다(접점 ④). 열차 identity 는 남긴다 |

> **`PROVISIONAL` 을 계승하지 않는 이유**: 후보 집합 감사행이 **옛 leg 을 가리킨다**
> (V45 unique 와 인덱스가 `journey_leg_id` 기준). 계승하면 새 leg 에서 후보를 못 읽어 L4 교정이
> 불가능한 채로 `PROVISIONAL` 만 남는다. 확정된 것(`CONFIRMED`)만 넘기고 나머지는 다시 시작한다.

계승을 "복사"가 아니라 "명시적 생성"으로 두는 이유: 빠뜨렸을 때 **고정이 없는 쪽**으로 떨어져야
한다. 복제가 기본이면 빠뜨렸을 때 엉뚱한 열차가 살아남는다.

ring 키에 `routeRevision` 을 포함한다(§PR-5) — 없으면 같은 legIndex/version 이 복제됐을 때
옛 worker 가 새 ring 을 덮는다.

### 수명주기 접점 — 초안이 둘만 봤는데 **넷**이다

| # | 접점 | 좌표 | 하는 일 |
|---|---|---|---|
| ① | 여정 종료·취소 (사용자 완료/취소/앱 보고 lease 만료가 모두 여기로 모임) | `JourneyCommandService:142` 부근 | binding `ABANDONED` + ring 삭제 |
| ② | lease 만료 배치 | `JourneyLeaseExpiryService:108` | 같음 |
| ③ | **새 여정으로 갈아탈 때 옛 여정 종료** | `JourneyStartTxService.replaceOrReject:116` | 같음. **초안 누락** — 여기서 옛 여정을 직접 `CANCELED/SUPERSEDED` 로 끝낸다 |
| ④ | **재탐색 수락으로 revision 이 오를 때** | `RerouteAcceptTxService.applyRevision:166` | 옛 revision binding 을 `ABANDONED`, 계승 대상이면 새 leg 에 명시 생성. **초안은 정책만 쓰고 접점을 안 적었다** |

③·④ 는 1차 리뷰 시점에 ④가 main 에 없었던 탓에 빠졌다. 지금은 둘 다 있다.

**superseded revision 의 미완료 binding 도 접는다.** 매칭기 질의가 현재 revision 만 보므로 기능
오류는 안 나지만, 그냥 두면 `PENDING`/`PROVISIONAL` 이 영구 미완료로 남아 지표를 오염시키고
부분 인덱스를 부풀린다. ④에서 같은 트랜잭션에 `ABANDONED(reason=SUPERSEDED)` 로 접는다.

- `CONFIRMED` binding 행은 **지우지 않는다** — 감사·복구용 canonical 사실이다
- `PENDING`/`PROVISIONAL` 만 같은 종료 트랜잭션에서 `ABANDONED` + 사유 감사행.
  여정 단위 갱신이라 `subway_leg_binding (journey_id, route_revision)` 로 한 문장에 끝난다
  (leg 을 join 하지 않아도 되도록 비정규화해 둔 이유)
- ring 은 **커밋 후** 삭제. TTL 도 반드시 둔다
- ring 은 `SCAN` 으로 지우지 말고 여정별 ring 인덱스(`subway:ring:index:{journeyId}` SET)를 유지
- **매칭기 질의 자체가 ACTIVE·현재 revision 만 보므로**, cleanup 이 늦어도 기능 오류가 되지 않는다

### 환승 — 지하철 leg 이 둘 이상

- subway pair 마다 **독립** binding·evidence·ring
- `evidenceSequence` 는 leg 단위라 두 pair 가 같은 번호를 써도 충돌하지 않는다(V45 unique 가 `journey_leg_id` 기준)

### 시험
- 방향 미확정이면 고정하지 않는다
- 출발 사건이 없는 열차는 후보가 되지 않는다
- 반대 방향·다른 노선·행선지 불양립 열차는 빠진다
- 관문 (1) 위반: 유일 후보여도 오차가 크면 고정 안 함
- 관문 (2) 경계: 위 수식 예제 두 개가 각각 통과/금지
- 후보 한 대 + headway 추정 불가 → `PROVISIONAL`, 자동 `CONFIRMED` 안 함
- 급행/특급은 후보에서 빠진다 (MVP 타협 가드)
- `USER_RESET` 이력에 있는 identity 는 다시 후보가 되지 않는다
- reset 뒤 새 evidence 가 오기 전에는 자동 재매칭하지 않는다
- 후보 복구가 **현재 `version`·`reset_generation` 과 일치하는 감사행만** 읽는다
- L4 episode 가 best 가 아닌 후보와 더 맞으면 그 후보로 `CONFIRMED` + `CORRECTED` 감사행
- 어느 후보와도 안 맞으면 `CONFIRMED` 로 올리지 않는다
- **Redis 를 비운 뒤** 후보 identity 는 감사행으로 복구되지만, `ARRIVED` 로그가 없으면
  `PROVISIONAL` 이 유지된다 (교정 "가능"의 한계 명시)
- `CONFIRMED` 는 CAS 경로로 뒤집히지 않는다
- superseded revision 의 `PENDING` 은 스캔에 잡히지 않고, 접점 ④가 `ABANDONED` 로 접는다
- 종료·취소·lease 만료와 매칭기가 동시에 돌아도 끝난 여정에 고정하지 않는다
- **여정 갈아타기**(`JourneyStartTxService.replaceOrReject`)로 끝난 옛 여정의 binding·watch 가 정리된다
- 재탐색: 새 revision 의 leg 은 **binding 행이 없다**(복제 안 됨) / 구간 완전 동일하면 명시적 생성 + `INHERITED_ON_REROUTE`
- 과거 revision 의 binding 행이 재탐색으로 수정되지 않는다
- 재탐색 중 evidence 와 매칭기 CAS 가 경쟁해도 옛 version 이 이기지 않는다
- 지하철 leg 2개가 각각 독립 binding 을 갖는다
- 자정 넘김: `serviceDate` 가 달라 같은 `trainNo` 가 섞이지 않는다

> **뺀 시험**: 초안의 "탑승역 밖 automotive 전환으로 고정되지 않는다" 는 **서버가 검증할 수 없다** —
> evidence payload 에 위치·geofence 증거가 없다. 이건 **앱이 보장하는 조건**이고 서버 시험으로
> 고정할 수 없다. 정직하게 §8 미결로 내린다.

### 검증
```bash
./gradlew test --tests '*SubwayTrainMatcherTest' --tests '*SubwayBinding*Test'
./gradlew build
```

---

## PR-5 — 진행 링 + Live Activity 원격 갱신

> **이 절은 아직 실행 플랜이 아니다.** V46 구조·토큰 등록/갱신/삭제 API·APNs 어댑터·outbox 스키마가
> 미결이고, payload 계약의 원본(모바일 `M7_IMPLEMENTATION.md`)이 이 저장소에 없다.
> **PR-4 완료 후 별도 플랜 문서**를 만든다. 아래는 그때 지켜야 할 제약만 적는다.

### 진행 링 (Redis)

```
subway:ring:{journeyId}:{routeRevision}:{legIndex}:{bindingVersion}
subway:ring:index:{journeyId}   SET — 종료 시 SCAN 없이 지우기 위한 색인
```
`routeRevision` 과 `bindingVersion` 을 **둘 다** 키에 넣는다. 하나만 넣으면 재탐색으로 복제된
같은 legIndex/version 에서 옛 worker 가 새 ring 을 덮는다.

숫자를 자주 push 하지 않고 **시간 앵커**를 저장한다:
```
progressBase / progressBaseAt / estimatedNextStationAt / progressTarget=0.90
nextStationName / quality / trainSeenAt
```
앱이 로컬 시간으로 0.90까지 보간하고, 서버는 **의미 있는 사건**만 push 한다.

계산 규칙:
- 구간 최초 유효 ETA 를 기준값으로 앵커, `1 - current/initial`, **최대 90%**
- ETA 가 늘어도 같은 역에서 링이 **역행하지 않는다**
- 실제 `ARRIVED` 전에는 100% 금지
- 값이 없으면 마지막 확정 링 유지. **정적 시간으로 채우지 않는다** (C1)
- 다음 역이 바뀌면 새 역 기준 0% 재시작

Redis 유실 시: DB 의 확정 binding + 현재 위치 로그로 재구성. 열차가 안 보이면 `STALE`.

### 미확정 상태 표시 (C6)

```
PENDING / PROVISIONAL:
  진행 UI      = "탑승 열차 확인 중"
  진행 링      = 비결정 상태 (특정 후보 값 금지)
  다음 역 이름 = 표시하지 않음
  하차 준비    = 표시하지 않음
  SUBWAY_ALIGHT / ALIGHT_NOW = 금지
```
Island phase 는 여전히 둘뿐이다(C5). `SubwayGuidance` 의 필드가 nullable 이라
`RIDE_SUBWAY` 안에서 "확인 중"이 표현된다.

### Journey snapshot 확장

```java
record SubwayGuidance(
    String bindingStatus,       // PENDING | PROVISIONAL | CONFIRMED | ABANDONED | null
    String phase,               // RIDE_SUBWAY | SUBWAY_ALIGHT | null
    String lineName,
    String nextStationName,     // 미확정이면 null
    String alightingStationName,
    Integer remainingStations,  // 미확정이면 null
    BigDecimal segmentProgress, // 미확정이면 null
    Instant estimatedNextStationAt,
    String quality,             // NORMAL | STALE
    Instant observedAt
) {}
```
**`trainNo`·`nextStationId` 는 노출하지 않는다.** 앞은 내부 식별자이고, 뒤는 ODsay/서울 ID 공간
혼동을 부른다(PR-1 과 같은 이유). 기존 앱이 `null`/`PENDING`/`ABANDONED` 를 견디는지
하위호환 시험이 필요하다.

**조립은 bulk query 한 번을 더한다 (N+1 금지).** `JourneyQueryService.snapshotOf` 가 이미
legs 1회 + route options 1회로 조립한다(`application/journey/service/JourneyQueryService.java:61-71`).
같은 자리에 한 줄을 더한다.
```java
Map<Long, SubwayLegBinding> bindingByLegId = bindingRepository
        .findByJourneyLegIdIn(legs.stream().map(JourneyLeg::getId).toList())
        .stream().collect(toMap(SubwayLegBinding::getJourneyLegId, identity()));
```
JPA lazy `@OneToOne` 을 DTO 변환 루프에서 건드리면 안 된다 — 정확히 N+1 이 된다.
현재 단계가 `WAIT_SUBWAY` 일 때는 **짝 RIDE 의 binding** 을 메모리에서 찾아 붙인다(C11).

### Live Activity push

- 토큰: `user_push_token`(V39) 재사용 금지. ActivityKit 토큰은 **Activity 단위로 폐기**되어 수명이 다르다.
  별도 표는 **V46**
- payload 에 `trainNo`·사용자 위치·원시 motion·전체 역 목록·토큰 **미포함**
- 발송 조건: 다음 역 변경 / `SUBWAY_ALIGHT` 전환 / `NORMAL ↔ STALE`. 동일 signature 무시
- **스냅샷을 새로 받았다는 이유만으로 전 사용자에게 push 하지 않는다**
- 기존 `JourneyNotificationRelayService` outbox 재사용 금지 — 그건 at-least-once 알림용이고
  링 갱신은 latest-wins 상태 동기화다. 낡은 링을 재시도하면 틀린 화면이 뒤늦게 뜬다
- 다만 **binding commit 과 push 를 잇는 경량 outbox** 는 둔다(commit 됐는데 push 유실이면 화면이 멈춘다).
  순서 키가 필요하다 — 역순 배달이 새 상태를 옛 상태로 덮으면 안 된다
- **여정 종료 시 Activity 종료도 DB terminal outbox 로 보장**한다

---

## 7. 함정

| 증상 | 원인 | 가드 |
|---|---|---|
| 아직 탑승역에 오지도 않은 열차를 "출발했다"고 판정 | `realtimeStationArrival` 을 위치로 오인 | C9 / §2. 위치는 `realtimePosition` 만 |
| API 결측·운행 종료를 출발로 오인 | "사라짐"에 의미 부여 | 위치 API 에서 사라짐은 무의미. 전이는 **역이 바뀜**으로만 |
| 원천보다 빨리 물어봐 같은 값을 다시 받음 | 폴링 주기를 원천 갱신 주기와 무관하게 정함 | 15초(원천 60~90초의 1/4). 실측 근거 §5 |
| 웜업 안 된 노선에서 전이를 못 만듦 | 필요할 때만 폴링 시작 | 전 노선 상시 폴링. 첫 스냅샷은 baseline 전용 (§5-4) |
| 옛 워커가 새 ring 을 덮음 | ring 키에 revision 없음 | 키에 `routeRevision` + `bindingVersion` 둘 다 |
| 끝난 여정·옛 revision 을 영원히 재처리 | 매칭 질의가 leg 만 봄 | `s.status='ACTIVE' AND l.route_revision=s.route_revision` |
| 재탐색으로 엉뚱한 열차가 계승됨 | leg 복제에 binding 이 딸려감 | binding 을 별도 표로 분리해 **복제되지 않는 것이 기본**. 계승은 명시적 생성만 |
| 사용자 reset 뒤 늦게 온 CAS 가 되살아남 | 행을 지워 `version` 이 0으로 리셋 | 행을 지우지 말고 `PENDING` + `version++` |
| 서버 계산 링이 앱 GPS 값에 덮임 | `progress_fraction` 은 앱 소유 | 링은 Redis (C7) |
| 지나간 열차가 추적에서 사라짐 | `SubwayRealtimeArrivalReader.select()` 재사용 | 위치 추적은 별도 경로. 그 필터 금지 |
| ODsay stationID 와 서울 statnId 를 비교 | ID 공간이 다름 | 지하철 `StopGeometry.stopId=null`, snapshot 에 `nextStationId` 미노출 |
| 자정 전후 같은 `trainNo` 가 섞임 | 번호 재사용 | identity 에 `serviceDate` (`ServiceDateResolver`) |
| 첫차·막차에 관문이 없어짐 | 후보 한 대라 headway 계산 불가 | 절대 관문만 통과 + `PROVISIONAL` 유지 |
| 불확실성을 두 번 셈 | 구간 거리에 구간 폭을 또 더함 | `gap()` + 잔여분 `S` 만 (§PR-4) |
| 도달 불가능한 죽은 분기 | 관문 (2) 통과 = best 유일인데 그 뒤에 동점 처리를 둠 | tie-break 삭제 (C8) |
| `recptnDt` 를 사건 시각으로 단정 | 공식 명세는 "최종수신시간" | 구간의 **하한을 과거로** 넓히는 `sourceLagBudget` |
| 회차·차량기지·지선에서 오출발 | `station != A` 만 검사 | 허용 하류 역 + `recptnDt` 단조 |
| 한 노선 오류가 전 노선을 하루 막음 | 공통 차단기 키 | 노선별 차단기 |
| arrival 이 쓴 quota 를 못 봄 | position 만 따로 카운트 | 같은 key = 공유 일일 카운터 |
| 거부한 열차를 즉시 재선택 | reset 이 상태만 되돌림 | `reset_generation` + `USER_RESET` identity 배제 |
| 두 evidence 흐름의 seq 1 이 충돌 | unique 에 `event_type` 없음 | unique 에 포함 + 컬럼도 kind 별로 분리 |
| 환승 시 어느 binding 을 되돌릴지 모름 | 보정 API 가 `journeyId` 뿐 | `routeRevision`+`legIndex` 필수 |
| 스냅샷 조립에서 N+1 | lazy `@OneToOne` 을 루프에서 접근 | bulk `findByJourneyLegIdIn` 한 번 |
| 사용자가 한 정거장 일찍/늦게 내림 | 미확정 상태에서 하차 안내 | C6 — `CONFIRMED` 이후에만 |
| 늦게 온 evidence 가 교정된 binding 을 되돌림 | CAS 없음 | `binding_version` CAS + `CONFIRMED` 는 사용자 보정만 |
| Redis 유실로 후보를 잃음 | 후보 집합이 Redis 에만 있음 | canonical 은 V45 `detail.candidates` (같은 version·reset_generation 만) |
| TTL 없는 키가 쌓여 다른 캐시를 밀어냄 | ZSET 추가와 TTL 이 쪼개짐 | Lua/transaction 으로 원자 갱신 + key 수 metric |
| 감사행이 존재하지 않는 leg 을 가리킴 | FK 없음 | V45 `journey_leg_id` FK |

---

## 8. 배포

- **V44·V45 는 VPS 수동배치 필요** (V18+ untracked convention — CI 로 안 나감. 누락 시 프로덕션 깨짐).
  착수 시 `ls src/main/resources/db/migration | sort -V | tail` 로 번호를 **다시 확인**할 것 — V43 은 이미 쓰였다
- PR-5 의 Live Activity 토큰 표(V46 예정)도 같은 취급
- `ALTER TABLE` 은 metadata-only 여도 `ACCESS EXCLUSIVE` lock 을 잡는다. **lock_timeout 을 걸고**
  짧은 트랜잭션으로 배포한다
- 신규 설정 키 (기본값 **전부 off**):
  - `realtime.subway.position.enabled: false`
  - `realtime.subway.position.poll-interval-ms: 2000`
  - `realtime.subway.position.poll-interval-ms: 15000`
  - `realtime.subway.position.lines: []`
  - `journey.subway.binding.enabled: false`
  - `journey.subway.ring.enabled: false`
- worker 전용: 위치 수집, 전이 추적, 매칭 루프, push
- api: evidence 수신, 보정 API, 조회
- **PR-2b 만 머지된 중간 상태**: 플래그가 off 면 안전하다. 켠 상태의 안전성은 별개다 —
  `enabled: false` 면 완전 no-op 이어야 하고, Redis key 수·호출 수·노선별 실패율 metric 이 있어야 한다

---

## 9. 미결 — Phase 0 실기기·실측 없이 확정 금지

**Phase 0 실측이 필요한 것 (구현 착수 전)**

**2026-08-13 실호출로 해소된 것** (근거 §5 / `implementation_note.md`)

- ~~`updnLine` 값 공간~~ → `0`/`1` 확정. 뜻까지 2호선 실제 이동으로 검증(`0`=상행/내선)
- ~~`recptnDt` 포맷~~ → `"yyyy-MM-dd HH:mm:ss"`. `lastRecptnDt` 는 `"yyyyMMdd"` 날짜만
- ~~`realtimePosition` 미지원 노선~~ → 시험한 7개 노선 전부 지원, `subwayId` 가 내부 line_id 와 동일
- ~~위치 폴링 주기~~ → 원천 갱신 p50 56초·p90 84초. `15000ms` 로 확정
- ~~열차당 1행 보장 여부~~ → `(subwayId, updnLine, trainNo)` 중복 0건. 다만 계약이 아니므로
  충돌 시 격리 규칙은 유지

**아직 남은 것**

1. **`recptnDt` 지연의 *분산*** → `sourceLagBudget`. 1회 표본으로 지연 범위(56~153초)는 봤지만,
   그중 얼마가 **계통 편차**(빼면 되는 것)이고 얼마가 **산포**(관문에 들어가는 것)인지 못 갈랐다.
   시각 모델 전체가 이 값에 걸려 있다 — 시간대를 달리해 여러 번 재야 한다
2. **`trainSttus=3`(전역출발) 의 `statnId` 가 무엇을 가리키는지.** 2호선 60초 표본에 **0건**이었다.
   지금은 corroboration 보조로만 쓰고 승격 여부를 추가 관측 후 결정
3. 기기 시각 허용 과거/미래 범위
4. **급행 정차 패턴 데이터 확보 가능성** — 없으면 급행 배제가 계속 유지된다.
   실측상 급행 비중이 작지 않다(1호선 20%, 9호선 40%, 수인분당선 12.5%)
5. **지선을 어떻게 가를지.** 2호선에 성수지선·신정지선·신도림지선이 `subwayId=1002` 로 섞여 온다.
   `statnTnm`(종착역)으로 갈라야 하는데 표기가 `성수종착`·`성수지선`·`성수` 처럼 흔들린다

**운영 파라미터(전부 설정값, 보수적 기본값)**

7. `absoluteMaxSkew` (초기 제안 90초)
8. `clockSkewBudget` `S`
9. `motionUnc` — 센서·전송 지연만. 시계 오차는 `S` 전담
10. `beta` (headway 비례 관문 계수)
11. 정차 episode 비이동 길이 하한·상한 (초기 제안 8~40초)
12. `freshnessWindow` (신선한 관측의 정의)
13. `PARTIAL` 판정의 rowCount 비율 (1차 판정은 `total != rows.size()` 로 이미 정해짐)
14. 수집 대상 노선 목록(`realtime.subway.position.lines`)
15. `STALE` 판정 시간
16. 링 bucket 과 Live Activity 최소 push 간격

**서버가 검증할 수 없는 것 (앱 책임으로 내림)**

17. "탑승역 부근에서만 evidence 를 보낸다" — 서버에 위치 증거가 없다. 서버가 검증하려면
    evidence 에 geofence 결과를 실어야 하는데, 그건 좌표 수집 정책과 얽힌다
    (`project-journey-gps-coordinates`: MVP 제외 결정). **앱만 보장하는 조건**으로 명시한다

### 성공 지표
```
wrong-confirm rate            ← 가장 비싼 실패
provisional-to-corrected rate
time-to-confirm
false ALIGHT_NOW count        ← 사실상 0 이어야 함
결측을 departure 로 오인한 횟수
노선별 realtimePosition 실패율 / PARTIAL 비율
```

---

## 10. 개정 이력

**2차 개정 (현재)** — 사용자 지시 + codex 플랜 리뷰 반영

- 원천 API 교체 (`realtimeStationArrival` → `realtimePosition`). 초안의 출발 판정 구조 전면 폐기 (§2)
- 호출량 제약으로 감시 축이 역 → **노선**, watch registry 가 PR-2 로 이동 (§5) — **5차에서 폐기**
- 잘못된 좌표 5개 수정 (§4)
- PR 재분할 4개 → 5개. 초안 PR-3 이 과대해서 스키마/API 와 매칭을 분리
- 재탐색 계승 정책, 종료·취소·lease 만료 정리, 환승 다중 leg — **초안 전면 누락분** 추가
- 관문 수식의 불확실성 이중 계산 정정, tie-break 와 C3 모순 제거 (4차에서 tie-break 자체를 삭제)
- C6 자기모순 제거 (미확정 시 하차 안내 **전면 금지**로 통일)
- `CORRECTED` 를 지속 상태에서 감사 사건으로 강등
- 감사 표에 FK·CHECK 추가, 후보 집합의 canonical 저장소로 지정
- 검증 불가능한 시험 1개 제거하고 앱 책임으로 명시

**3차 개정** — binding 저장 구조 변경

- **binding 저장을 `journey_leg` 8컬럼 → `subway_leg_binding` 1:1 별도 표로 교체.** 근거 ① 재탐색마다 leg
  전체가 복제돼 폭 증가가 revision 수만큼 증폭 ② `status` 가 부분 인덱스 predicate 라 상태 전이 시
  `journey_leg` 의 HOT update 가 깨짐(그 표는 progress 로 이미 자주 갱신) ③ 도보·버스 leg 행까지 넓어짐
- 부수 이득: 재탐색 계승이 **복제 기본 → 명시적 생성**으로 뒤집혔다. 빠뜨렸을 때 "고정 없음"으로
  안전하게 떨어진다. 계승 차단 코드가 불필요해졌다
- 사용자 reset 은 행을 지우지 않고 `PENDING` + `version++` — 지우면 `version` 이 0으로 돌아가
  늦게 온 CAS 가 되살아난다

**4차 개정** — codex 2차 리뷰 반영

- **tie-break 삭제(C8 폐기).** 관문 (2) 통과가 곧 best 유일이라 동점 분기가 도달 불가였다.
  1차에서 "모순 제거"로 고친 것이 기능 자체를 죽인 상태였다
- **`recptnDt` 해석 정정.** "원천 생성 시각" → "최종수신시간". 출발 구간의 `maxLag` 방향이
  반대였던 것(상한을 미래로)을 하한을 과거로 넓히도록 고침. `pollInterval` 을 폭으로 쓰던 것도 제거
- **전이에 인과 episode 요구.** 선행 A역 관측(①) 없는 `sttus=2` 는 `OBSERVED` 까지만.
  대신 ①→②가 신선하면 하류 관측 없이 확정 — 불필요한 지연 제거
- **"허용 하류 역 불필요" 철회.** 회차·차량기지·지선 가드가 필요하다
- **`OBSERVED`/`CORROBORATED` 를 로그 member 에 저장.** 본문에만 있고 저장 모델에 없었다
- 포트가 `List` → **envelope** 반환 (COMPLETE/PARTIAL/FAILED 구분 불가였음)
- **마이그레이션 번호 충돌** — V43 은 이미 쓰였다. V44·V45(·V46)로 밀었다
- **`timestamptz` 로 정정** — V35 가 "절대시각은 timestamptz, 감사 칼럼만 timestamp(6)" 라 명시.
  초안이 거꾸로 알고 있었다
- **열차 identity 4칸을 실제로 저장** — 정의만 하고 `train_no` 만 저장하고 있었다
- **evidence unique 에 `event_type` 추가** — 두 kind 의 sequence 1 이 충돌하던 것
- **보정 API 에 `routeRevision`·`legIndex` 필수** — 환승이면 binding 이 여럿인데 대상 지정이 없었다
- **`reset_generation` 신설** — reset 뒤 같은 열차를 즉시 재선택하던 문제
- **급행 MVP 배제** — "미래 도착 로그로 통과역 판정"이 구현 불가였다
- **수명주기 접점 2 → 4** — `JourneyStartTxService.replaceOrReject`, `RerouteAcceptTxService.applyRevision` 추가
- **`PROVISIONAL` 은 재탐색 계승 금지** — 후보 감사행이 옛 leg 을 가리켜 교정 불가가 된다
- **호출 예산을 arrival 과 공유**, 차단기는 노선별로 분리
- watch prewarm 모순 해소(단계 거리 기준), 상위 ZSET max 재계산, 수집 판정 순서 명시 — **5차에서 watch 자체 폐기**
- **"틀리면 정확히 한 대 차이" 주장 철회** — 보장 없음. L4 를 "후보 중 가장 잘 맞는 것 선택"으로 재정의
- 스냅샷 조립 N+1 방지 bulk query 명시

---

**5차 개정** — 로컬 실호출 실측 반영 (2026-08-13)

- **§5 를 "호출량 제약" → "원천 갱신 주기 실측"으로 교체.** 초안의 근거(전 노선 폴링 시 170만 콜/일)는
  ① 서울 지하철 API 가 호출수 무제한이고 ② 원천이 60~90초마다만 바뀌어 15초 폴링이면 충분하다는
  두 사실로 무너졌다
- **노선 감시 집합(`subway:watch`) 전면 폐기.** 남는 근거는 비용 절감뿐인데 절감할 비용이 없고,
  반대로 감시 방식은 **웜업 2~3분** 동안 전이를 못 만든다(원천 갱신 1~2주기가 필요).
  registry·reconciler·prewarm·ZSET Lua·watch 해제 접점이 함께 사라졌다
- **L4 의 지위 상승.** 관측 해상도 60~90초 vs 러시아워 배차 120초라 관문 (2)가 거의 항상 실패한다.
  t0 단독 고정은 성립하지 않고 **L4 가 주된 확정 경로**다. 설계 구조는 그대로지만 무게중심이 바뀐다
- **PR-2 를 2a/2b 로 분리.** 2b 의 시각 모델·전이 규칙이 실측에 의존하는데 실측하려면 연결이
  먼저라 순환이었다. **2a 는 구현 완료**
- 실측으로 해소된 미결 5개를 §9 에서 내리고, 새로 드러난 것(지연의 분산, 지선 표기 흔들림) 추가
- `updnLine` 매핑이 실측으로 **검증**됨 — 2호선 60초 이동 방향으로 `0`=내선순환 확인

## 11. 작업 순서

- [ ] PR-1 geometry 역 체인 (M, 독립)
- [ ] PR-2 `realtimePosition` 수집 + 전이 로그 (L, 독립) — **2a(API 연결) 구현 완료**
- [ ] PR-3 V44·V45 + evidence API (M, 독립)
- [ ] PR-4 매칭기 + CAS + 수명주기 (L, PR-2·PR-3 의존)
- [ ] PR-5 진행 링 + Live Activity (L, PR-1·PR-4 의존) — **별도 플랜 문서 선행**

각 PR: **선행 PR 머지 후 최신 main 에서 새 브랜치.** 이 플랜 파일은 PR-1 에 포함해 커밋한다.
