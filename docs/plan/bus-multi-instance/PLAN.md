# 버스 실시간 경로 다중 인스턴스 대비 (bus-multi-instance, L 티어)

작성 2026-11-03, Opus 검토 반영 같은 날(18건: MAJOR 10, MINOR 8, BLOCKER 0). 기준 커밋 origin/main `f64caa36`. 경로는 `src/main/java/watoo/grd/nextroute/` 를 줄여 쓴다. 줄 번호는 참고용이고 **기호 이름이 기준**이다(검토에서 여러 좌표가 어긋남을 확인).

## 0. 배경과 목표

### 문제
운영은 api 1대와 worker 1대다. worker 를 2대 이상으로 늘리면 버스 실시간 경로가 서버 한 대를 전제로 짜여 있어 아래가 함께 깨진다(2026-11-03 코드 조사).

| # | 깨지는 곳 | 원인 | 코드 |
|---|---|---|---|
| 1 | 버스 도착 수집기(노선별 1분마다 route-all)가 worker 수만큼 돈다 | 리더 락 없음 | `infrastructure/adapter/in/scheduler/BusArrivalScheduler.trigger` |
| 2 | 버스 위치 수집기도 같다 | 리더 락 없음 | `BusPositionScheduler.trigger` |
| 3 | 수집기 일일 예산이 JVM 메모리 카운터라 worker 수만큼 늘어난다(N × 50,000) | `AtomicInteger` | `application/bus/service/BusApiCallBudget`, `BusApiCallBudgetConfig` |
| 4 | 버스 도착 알림 폴러(1초)가 worker 마다 같은 구독을 보고 공급자를 부른다. 적재는 원자적이라(`BusAlertAtomicClaimIT`) 중복 발송은 없지만 공급자 호출이 N배다 | 리더 락 없음, 일일 몫 없음 | `BusArrivalAlertScheduler.poll`, `BusAlertDispatchService.loadArrivals` |
| 5 | 캐시 만료 순간 같은 정류장 조회가 api 와 worker 마다 1회씩 나간다(api 1 + worker 2 면 최대 3회) | 묶기(`SingleFlight`)가 JVM 메모리 `ConcurrentHashMap` | `application/route/concurrent/SingleFlight`, `infrastructure/adapter/out/api/bus/CachedSearchTimeBusAdapter`(arrivalFlight, positionFlight) |

리더 락(`common/lock/RedisLeaderLease`)은 지금 지하철 수집기, 지하철 변화 감지, 버스 노선 감시자만 지킨다(javadoc). 플래그 `journey.navigation.reeval.leader-lease.enabled` 기본 false 라 꺼져 있으면 모든 worker 가 "늘 리더"이고, api 는 설계상 늘 "리더 아님"이다.

### 경기, TAGO 버스 (사용자 제공 한도, 2026-11-03: 경기 10,000/일, TAGO 500,000/일, 포털 키 동시 세션 30은 GBIS, TAGO 버스, 지하철 TAGO 가 공유)

| 공급자 | 지금 구조 | 서버 셋이면 | 위험 |
|---|---|---|---|
| 경기(GBIS) | 캐시 JVM `ConcurrentHashMap` 15초(**빈 OK 결과도 캐시**, `GyeonggiBusArrivalAdapter.fetchAndCache` "조회 성공(빈 결과 포함)만 캐시"), JVM SingleFlight, 일일 몫은 재탐색 버킷만(`RegionalArrivalLookup` `reroute-gg-arrival`), 공개 도착 API(`BusArrivalQueryService`)는 몫 없음, 차단기 `ApiCallBlocker` JVM 메모리(시스템 시간대 자정까지) | 같은 정류장 3회, 몫은 아무도 안 셈 | **높음**. 10,000/일은 서울 검색 몫(5,000)의 두 배뿐. 넘기면 공급자 오류 코드(20, 22, 30~33)로 자정까지 차단 |
| TAGO | 위와 같음 + JVM `Semaphore(8)`(`TagoBusArrivalAdapter.MAX_CONCURRENT`) | 같은 정류장 3회, 동시 호출 8 × 3 = 24 (+ GBIS 무제한 + 지하철 TAGO) → 세션 30 초과 가능(resultCode 99) | 일일은 낮음, **동시 세션은 중간** |

→ PR-4 로 다룬다.

### 목표
- 미리 아는 키(수집 대상 노선, 알림 구독)를 부르는 주기 작업은 **리더 worker 한 대**만 한다.
- 일일 예산은 서버 수와 관계없이 **Redis 카운터 하나**로 센다.
- 미리 모르는 키(사용자 검색, 재탐색, 평가기의 캐시 miss)는 **Redis `SET NX` 로 서버들 중 한 대만** 공급자를 부르고 나머지는 그 결과를 받는다(서버 간 single-flight).
- 모든 변경은 플래그 기본 꺼짐 또는 "worker 1대에서 동작 불변"으로 들어간다. 바뀌는 점은 "동작 변경" 절에 **숨기지 않고** 적는다.

### 왜 "리더만 부르기"로 전부 해결하지 않나 (사용자 질문)
- api 의 사용자 검색은 어떤 정류장일지 미리 모른다. 리더가 미리 채울 수 없다.
- 리더에게 대신 불러 달라고 부탁할 JVM 간 통로(RPC, MQ)가 없다. 새로 만들면 Redis 한 번보다 무겁고, 멘토 기준 "필요 없는 MQ" 다.
- 그래서 미리 아는 키 = 리더, 미리 모르는 키 = 필요한 서버가 부르되 Redis 로 한 대만.

### 범위 밖 (미결로 기록만)
- 서울 버스 JVM 별 동시 호출 상한(Semaphore 8, 평가기 provider-permits 4)이 서버 수만큼 늘어나는 것.
- 포털 키 동시 세션 30을 GBIS, TAGO 버스, 지하철 TAGO 셋이 **한 상한으로** 나눠 쓰는 것(PR-4 는 TAGO 버스 상한만 서버 간 공유).
- 코드 기본값과 yaml 불일치: `BusCollectorProperties.dailyBudget=60000` vs yaml 50000, 수집 active-hours 끝 00:30(코드) vs 02:30(yaml).
- legacy `SeoulBusApiAdapter` 가 code 7 한 번에 `BusApiBlockedException` 을 던지는 것.
- `BusRouteWatcher` 켜기(그림자, 일일 몫 0).
- `ApiCallBlocker` 를 지하철 경로까지 Redis 로 바꾸는 것(검토 #8: 지하철 hot path 에 GET 이 붙고 한 서버 오류가 전부를 막음 → 별건).

---

## PR-1 수집기와 알림 폴러를 리더 한 대로 + 수집 예산 Redis 화

브랜치: fresh main 에서 `feat/bus-collector-leader`. 플래그 신설 없음(기존 리더 락 플래그를 따른다).

### 변경
1. `BusArrivalScheduler.trigger` — `LeaderLease leaderLease` 주입. enabled, active-hours, 차단 확인 뒤 `useCase.execute()` 직전에 `if (!leaderLease.isLeader()) return;`.
2. `BusPositionScheduler.trigger` — 같은 가드.
3. `BusArrivalAlertScheduler.poll` — `dispatchService.dispatchDue()` 직전 같은 가드.
4. `BusAlertDispatchService.dispatchDue` — 그룹 루프 안, `processGroup` 호출 직전에 `if (!leaderLease.isLeader()) return;` (한 바퀴가 그룹 수 × 공급자 왕복이고 legacy 어댑터는 2초/4초 재시도까지 하므로 도중 재확인, `LeaderLease` 계약 "부작용 직전 재확인").
5. `BusArrivalService.execute`
   - `LeaderLease` 주입. 노선 루프 안, **공급자 호출 직전 순서 = ① `isLeader()` 재확인(아니면 return) → ② `quota.tryAcquire(BUCKET_ARRIVAL, dailyBudget)` → ③ 호출**. 리더가 아닌 서버가 몫을 태우지 않게 ①이 ②보다 앞.
   - 예산: `@Qualifier("arrivalApiCallBudget") BusApiCallBudget budget` 제거, `BusApiQuotaPort quota` 주입. `budget.canMakeCall` + `recordCall` 셋을 `tryAcquire` 한 번으로. 실패(소진 또는 Redis 장애, fail-closed)면 지금 소진 분기와 같게 그 노선은 stale purge 만.
   - 상수 `static final String BUCKET_ARRIVAL = "collector-arrival";`
   - 시작 전 일괄 확인은 지운다(카운터를 미리 올리지 않으려고).
6. `BusPositionService.execute` — 같은 순서(리더 → `tryAcquire("collector-position", dailyBudget)` → 호출), 실패면 `break`(지금과 같음).
7. **`SeoulBusApiAdapter`**(검토 #1): 생성자의 `@Qualifier("arrivalApiCallBudget")`, `@Qualifier("positionApiCallBudget")` 두 매개변수와 필드를 지우고, `budgetStatus()` 를 로그에서 뺀다(세 로그 호출 지점). 그 뒤 `BusApiCallBudget`, `BusApiCallBudgetConfig` 삭제. 테스트 `SeoulBusApiAdapterTest` 의 budget mock 과 생성자 인자 정리.
8. `BusApiQuotaPort` javadoc "collector 몫은 기존 in-memory budget" → "collector 몫도 이름 붙은 Redis 버킷(`collector-arrival`, `collector-position`)".
9. `RedisLeaderLease` javadoc 의 "이 잠금을 따르는 작업" 목록 갱신: 지하철 수집기, 지하철 변화 감지, 버스 노선 감시자, **버스 도착 수집기, 버스 위치 수집기, 버스 도착 알림 폴러**.
10. `application.yaml` 리더 락 주석에 "버스 수집기와 도착 알림 폴러도 따른다", 예산 주석에 "Redis 버킷, 서버 수와 무관" 추가.

### 동작 불변 근거 (플래그 꺼짐)
- `isLeader()` 가 늘 true(`RedisLeaderLease.isLeader`, enabled=false 분기) → worker 1대 운영 동작 그대로.
- 예산 키 `bus:quota:search:collector-arrival:{yyyyMMdd}`(`RedisBusApiQuota.tryAcquire`, KST 자정 TTL). 접두사 `search` 는 기존 `tryAcquire` 와 같은 모양이라 그대로.

### 동작 변경 (숨기지 않음)
- 예산 판정이 "호출 뒤 기록" → "호출 전 점유". `BusApiBlockedException`, `InterruptedException` 도 이제 1 로 센다(지금은 blocked = 0). 하루 몇 건 차이라 받아들인다.
- Redis 장애 때 수집기가 멈춘다(fail-closed). 지금은 JVM 카운터라 Redis 와 무관하게 불렀다. 공급자 한도 보호가 우선.
- **플래그 켬일 때 알림 폴러 계약**(검토 #15): Redis 장애 → 모든 worker 가 "리더 아님" → 버스 도착 알림이 멈춘다(지하철 수집과 같은 성질). 리더 교체 공백 ≈ TTL 10초 + TTL/3. 운영은 버스 알림이 켜져 있으므로(`application-prod.yaml`) 플래그를 켜기 전에 이 점을 안다.

### 테스트 (계약당 1개 이상)
- `BusArrivalSchedulerTest`, `BusPositionSchedulerTest`(기존 수정: 생성자에 lease mock 추가): 리더 아님 → `useCase.execute()` 미호출 / 리더 → 호출.
- `BusArrivalAlertSchedulerTest`(신규): 리더 아님 → `dispatchDue()` 미호출.
- `BusAlertDispatchServiceTest`(기존 수정): 그룹 둘 처리 중 리더 잃음 → 둘째 그룹 공급자 미호출.
- `BusArrivalServiceTest`(기존 수정, 생성자 `BusApiQuotaPort` mock): 점유 실패 노선은 공급자 미호출 + stale purge / 루프 도중 리더 잃음 → 그 뒤 노선 공급자 미호출 **그리고 `tryAcquire` 미호출** / `BusApiBlockedException` → 즉시 종료.
- `BusPositionServiceTest`(기존 수정): 점유 실패 → break, 리더 잃음 → 중단.
- `SeoulBusApiAdapterTest`(기존 수정): budget 제거.
- `common/lock/BusCollectorLeaderIT`(신규, 패키지는 `common.lock` — `RedisLeaderLease.start/tick` 이 package-private): `RedisLeaderLease` 두 개(enabled, worker) 를 st-redis 에 붙이고 각각 `tick()` 뒤 `BusArrivalScheduler.trigger()` 를 동시에 부르면 fake 공급자 호출이 한 바퀴분(노선 수). 공유 예산: 두 서비스가 같은 버킷을 쓰면 합이 한도에서 멈춤.

### 검증 명령
```
./gradlew compileJava compileTestJava
/Users/wa/.claude/jobs/8e8c6a78/tmp/runsome.sh --tests '*BusArrival*' --tests '*BusPosition*' --tests '*BusAlert*' --tests '*LeaderLease*' --tests '*SeoulBusApiAdapter*'
/Users/wa/.claude/jobs/8e8c6a78/tmp/runfull.sh   # cleanTest test --continue, st-pg:55432 st-redis:56379
```

---

## PR-2 서버 간 single-flight (Redis SET NX, 결과 공유)

브랜치: PR-1 머지 뒤 fresh main 에서 `feat/bus-cross-instance-flight`. 플래그 `route.search.bus.cross-instance.enabled` 기본 false.

### 설계 (검토 #2, #3, #4, #5, #10 반영)
- 2단계 묶기. JVM 안 `SingleFlight` 는 그대로 두고(서버 안 100건 → 1건), 그 owner 가 공급자를 부르기 전에 Redis 로 서버들 사이에서 한 번 더 묶는다(서버 N대 → 1대).
- **결과를 캐시가 아니라 "결과 키"로 전달한다.** 기다리는 서버는 캐시 쓰기 성공 여부나 "빈 목록은 캐시 안 함" 규칙에 기대지 않는다.
- 흐름(도착 `getArrInfoByStop`, 위치 `getBusPosByRtid` 둘 다, JVM SingleFlight owner 안에서):
  1. `tryLead(type, key, lockTtl)` — Lua 하나: `SET lock token NX PX ttl` 성공이면 내 토큰을, 실패면 **현재 보유자의 토큰**을 돌려준다.
  2. 잡음(리더): **캐시를 한 번 더 읽는다**(double-check, 검토 #5: 다른 서버가 방금 채웠으면 호출 없이 `cached` 반환 + 결과 키에 그 값 기록) → 공급자 호출 → OK 이고 비어 있지 않으면 캐시 쓰기(기존) → `finish(type, key, token, result)` = `SET bus:flight:result:{type}:{key}:{token} <JSON: outcome + data> PX resultTtl` 뒤 토큰 비교 Lua 로 lock DEL.
  3. 못 잡음(대기자): `pollMs` 마다, 자기 대기 상한 안에서: (a) `GET result:{…}:{보유자 토큰}` 있으면 **OK → `cached(data)`(빈 OK 포함), BLOCKED → 그대로**(공유 차단기라 어느 서버든 같음), **LIMITED·ERROR → 직접 부른다**(리더 서버의 JVM 세마포어 포화·일시 오류는 그 서버 사정이라 공유하면 플래그 꺼짐보다 가용성이 떨어진다 — 2차 검토) (b) 결과가 없고 잠금 보유자가 **바뀌었으면**(첫 리더 만료 → 새 리더) 새 토큰을 따라간다 (c) 결과도 잠금도 없으면(리더 사망) 한 번 더 결과를 본 뒤 **스스로 리더를 시도** — 잡으면 직접 부르고(waiter_takeover), 남이 잡았으면 그 토큰을 따라간다 (d) Redis GET 오류 → `limited()` (e) 상한 초과 → `limited()`. 대기 상한 = 기한 있는 호출은 `joinWait(timeoutMs)`, 기한 없는 호출은 잠금 수명(리더가 걸릴 수 있는 최대).
  4. `tryLead` 자체가 Redis 오류 → 지금처럼 delegate 를 직접 부른다. 가용성 근거는 쓰지 않는다(검토 #16: delegate 의 검색 쿼터가 fail-closed 라 어차피 LIMITED). 지표 `redis_error` 만 남긴다.
- 결과 키가 **토큰별**이라, 이전 flight 의 ERROR 를 새 flight 대기자가 읽는 일(검토 #2)이 없다. lock 이 만료된 뒤 늦게 끝난 옛 owner 의 `finish` 는 자기 토큰 키에만 쓰고 DEL 은 토큰 비교로 막힌다.
- 값: `lockTtl` = 호출 기한이 있으면 `timeoutMs + lockMarginMs(1,000)`, 무한(`Long.MAX_VALUE`)이면 **6,000ms**(세마포어 대기 500 + connect 1,500 + read 1,500 + 차단기·몫 Redis 왕복 + 여유. 검토 #4와 2차 검토). 잠금은 갱신하지 않는다 — 넘기면 두 번째 리더가 한 번 더 부르는 것을 감수(결과 키가 토큰별이라 정합성 문제는 아님). `resultTtl` = 2,000ms. `pollMs` = 50(하한 1). 폴링 비용 = 진행 중 키당 50ms 마다 Redis GET 1~2회(검토: 키 수에 비례, pub/sub 전환은 미결).

### 신규 파일
- `application/route/port/out/CrossInstanceFlightPort.java`
```java
public interface CrossInstanceFlightPort {
    /** @return mine=true 면 token 은 내 것, false 면 현재 보유자의 토큰. Redis 오류는 예외. */
    Lead tryLead(String type, String key, Duration lockTtl);
    record Lead(boolean mine, String token) {}
    void finish(String type, String key, String token, FlightResult result, Duration resultTtl);
    /** @return 결과 없음이면 empty. Redis 오류는 예외. */
    Optional<FlightResult> peekResult(String type, String key, String token);
    /** 지금 잠금을 쥔 토큰(없으면 empty) — 대기자가 리더 교체를 따라가는 데 쓴다. */
    Optional<String> currentHolder(String type, String key);
    /** outcome = SearchTimeBusQueryPort.BusQueryResult.Outcome 그대로, data 는 OK 일 때만. */
    record FlightResult(String outcome, String dataJson) {}
}
```
- `infrastructure/adapter/out/cache/bus/RedisCrossInstanceFlight.java` — 키 `bus:flight:lock:{type}:{key}`, `bus:flight:result:{type}:{key}:{token}`. `tryLead` Lua: `SET NX PX` 성공 → 1, 실패 → `GET` 한 보유자 토큰. 해제 Lua = 토큰 같을 때만 DEL(`RedisLeaderLease.RELEASE` 와 같은 모양). data 직렬화는 기존 `RedisBusRealtimeCacheAdapter` 의 ObjectMapper 규약 재사용.
- 속성은 기존 `SearchTimeBusProperties` 에 필드 추가: `crossInstanceEnabled`(false), `crossInstancePollMs`(50), `crossInstanceResultTtlMs`(2000), `crossInstanceLockMarginMs`(500), `crossInstanceUnboundedLockTtlMs`(4000).

### 변경
- `CachedSearchTimeBusAdapter` — 생성자에 `CrossInstanceFlightPort flight` 추가. `arrivalFlight.execute(key, loader)` 와 `positionFlight.execute(...)` 의 loader 를 `crossInstance(type, key, loader, timeoutMs)` 로 감싼다. 플래그 꺼짐이면 지금 코드 그대로.
  - 생성자가 바뀌므로 `new CachedSearchTimeBusAdapter(` 를 쓰는 **`RouteSearchCacheIT`, `RouteSearchStampedeIT`, `RouteSearchRealtimeDeadlineIT`, `CachedSearchTimeBusAdapterTest`** 를 함께 고친다(검토 #13). 꺼짐 상태의 no-op 구현 `CrossInstanceFlightPort.disabled()` 정적 팩토리를 둬 테스트가 짧게 쓰게.
- `RouteSearchMetrics.recordCrossInstanceFlight(provider, type, role)` 추가. role = `leader|leader_recheck_hit|waiter_result|waiter_fallback|waiter_owner_gone|waiter_timeout|redis_error`. 기존 `recordSingleFlight` 와 같은 태그 모양.
- `SingleFlight` javadoc "현재 단일 인스턴스 배포…" → "서버 사이 묶기는 `CrossInstanceFlightPort`(플래그)가 맡는다".
- `application.yaml` `route.search.bus` 아래 `cross-instance: { enabled: ${ROUTE_SEARCH_BUS_CROSS_INSTANCE:false}, poll-ms: 50, result-ttl-ms: 2000, lock-margin-ms: 500, unbounded-lock-ttl-ms: 4000 }`.

### 테스트
- `RedisCrossInstanceFlightIT`(st-redis): 두 번째 `tryLead` 는 mine=false 이고 첫 토큰을 돌려줌 / 토큰 다른 `finish` 는 lock 을 안 지움 / 결과 키는 토큰별 / TTL 뒤 사라짐.
- `CachedSearchTimeBusAdapterCrossInstanceIT`(신규, 핵심 계약): 같은 Redis 에 붙은 **어댑터 3개**(api 1 + worker 2 흉내, 각자 SingleFlight)와 300ms 지연 fake delegate(호출 수 셈). 모든 동시 요청은 `CountDownLatch` 로 **동시에 출발**(검토 #18).
  - 플래그 켬: 동시 100건을 셋에 나눠 보내면 delegate 호출 1회, 모든 요청이 값을 받음(반복 20회 전부).
  - 플래그 끔: 같은 조건에서 3회(대조군).
  - delegate 가 빈 목록 → 대기자가 빈 `cached` 를 받고, **delegate 완료 시각부터** pollMs × 4 안에 끝남.
  - delegate 가 BLOCKED → 대기자도 BLOCKED, 공급자 1회. delegate 가 LIMITED → 대기 서버가 직접 불러 서버 수만큼 호출. 리더 예외 → 대기 서버가 직접 불러 값을 받음.
  - 기한 100ms 대기자 → LIMITED(waiter_timeout), 리더는 OK. 결과 키가 깨짐 → LIMITED. 리더가 잠금만 쥐고 죽으면 만료 뒤 대기 서버 하나가 이어받아 1회 부르고 나머지는 그 결과를 받음.
  - owner 를 lock 만 잡고 멈추게 한 뒤 lock TTL 만료 → 대기자는 `limited()`, 다음 요청은 리더를 잡음.
  - Redis 닫힘(`tryLead` 예외) → 각 서버가 delegate 직접 호출.
  - 리더 double-check: 다른 서버가 캐시를 먼저 채운 상태에서 리더가 잡으면 delegate 0회.
- 기존 `CachedSearchTimeBusAdapterTest`, `RouteSearchStampedeIT`, `RouteSearchCacheIT`, `RouteSearchRealtimeDeadlineIT` 그대로 통과(플래그 꺼짐).

### 검증 명령
```
/Users/wa/.claude/jobs/8e8c6a78/tmp/runsome.sh --tests '*CachedSearchTimeBus*' --tests '*CrossInstance*' --tests '*RouteSearch*'
/Users/wa/.claude/jobs/8e8c6a78/tmp/runfull.sh
```

---

## PR-4 경기, TAGO 를 서울 버스와 같은 구조로

브랜치: PR-2 머지 뒤 fresh main 에서 `feat/regional-bus-shared-cache`. 플래그 `regional-bus.shared.enabled`(env `REGIONAL_BUS_SHARED`) 기본 false. **플래그 꺼짐이면 아래 1~5 전부 기존 JVM 경로**(검토 #7, #8 반영: 플래그와 무관하게 켜지는 항목 없음).

### 변경
1. **캐시를 Redis 로.** 신규 `infrastructure/adapter/out/cache/bus/RedisRegionalBusCacheAdapter.java` — 키 `bus:gg:arr:{stationId}:{routeId}`, `bus:tago:arr:{cityCode}:{nodeId}:{routeId}`, TTL 15초. 값은 `BusQueryResult`(outcome + data) JSON. **빈 OK 결과도 저장한다**(검토 #6: 기존 JVM 캐시가 "조회 성공(빈 결과 포함)만 캐시"이고, resultCode 4 "이 정류장에 안 섬" 이 흔한 답이라 안 저장하면 경기 10,000/일을 더 쓴다). 실패(ERROR, LIMITED, BLOCKED)는 저장하지 않는다(기존과 같음). Redis 오류는 miss 로.
   - 포트 `application/arrival/port/out/RegionalBusCachePort.java`(`getGyeonggi/putGyeonggi`, `getTago/putTago`). 서울 캐시 포트는 `BusArrivalInfo` 와 `seq` 에 묶여 있어 재사용 불가(검토 확인).
   - `GyeonggiBusArrivalAdapter`, `TagoBusArrivalAdapter` 의 `ConcurrentHashMap` 조회·저장을 플래그에 따라 Redis 포트로. JVM SingleFlight 는 그대로.
2. **서버 간 묶기 재사용.** PR-2 의 `CrossInstanceFlightPort` 를 `type = "gg-arrival" | "tago-arrival"` 로 쓴다. 결과 키로 outcome 과 data 를 그대로 전달하므로 빈 결과 처리가 따로 필요 없다. **lockTtl 은 공급자별 상수**: 지역 어댑터는 호출 기한 인자가 없고 기본 RestClient 가 connect 3초 + read 5초, TAGO 는 자리 대기 1초가 더 있으므로 **경기 9,000ms, TAGO 10,000ms**(검토 #4). 대기 상한은 기존 `JOIN_WAIT_MS` 5,000.
3. **경기 일일 몫을 Redis 로, 어댑터 안에서.** `GyeonggiBusArrivalAdapter` 의 flight owner 안, **Redis 캐시 miss 와 double-check 뒤, `fetch()` 직전**에 `quotaPort.tryAcquire("gg-arrival", dailyLimit)`(검토 #7: 서비스 층에서 잡으면 캐시 hit 도 몫을 태운다). 속성 `gyeonggi.api.arrival-daily-limit` 기본 8,000(10,000 − 재탐색 버킷 몫). 소진이면 `BusQueryResult.limited()`. 재탐색 쪽 `RegionalArrivalLookup` 의 `reroute-gg-arrival` 점유는 어댑터 캐시 밖에 있어 두 카운터가 겹친다 → 재탐색 경로는 어댑터가 몫을 세지 않도록 호출 인자(`QuotaMode.ALREADY_CHARGED`)로 구분한다. TAGO 는 500,000/일이라 몫 카운터 없이 지표만.
4. **TAGO 동시 호출 상한을 서버 간 공유.** `RedisOtpConcurrencyGateAdapter` 는 **건드리지 않는다**(검토 #11: `RoutingDeadline` 에 묶인 API, 키 `otp:sem:permits`, 지표 `otp.sem.*`, 기존 IT). 대신 같은 Lua 본문을 쓰는 형제 `infrastructure/adapter/out/cache/RedisNamedSemaphore`(이름, 상한, **lease ≥ 가장 긴 호출 = 10초**)를 만들고, `TagoBusArrivalAdapter.permits`(JVM Semaphore 8)를 플래그 켬일 때 공유 게이트(이름 `tago-bus`, 상한 `tago.api.max-concurrent` 기본 8 **전체 합산**)로 바꾼다. 자리를 못 얻으면 지금처럼 LIMITED. fail-closed(Redis 오류 → 자리 없음).
5. **차단기는 지역 버스 두 이름만 Redis 로.** `ApiCallBlocker` 에서 인터페이스를 뽑고(`ApiCallBlockerPort`), Redis 구현 `RedisApiCallBlocker` 는 `gyeonggi-bus-arrival`, `tago-bus-arrival` 두 이름만 맡는다(검토 #8: 지하철 어댑터 세 곳과 `RegionalBusRouteSourceAdapter` 가 같은 빈을 hot path 에서 쓰고 테스트 7곳이 `new ApiCallBlocker()`). 키 `api:block:{name}`, 값 KST 날짜, **KST 자정** TTL(기존 코드는 시스템 시간대 `LocalDate.now()` — Redis 판은 KST 로 명시). Redis 오류 → JVM 차단기로 폴백. 플래그 꺼짐이면 JVM 차단기 그대로.

### 동작 변경 (플래그 켬)
- 경기 공개 조회가 하루 8,000건에서 LIMITED 를 돌려준다(지금은 한도를 넘길 때까지 부르다가 공급자 오류로 차단).
- TAGO 동시 호출이 서버 합산 8 로 줄어든다(지금은 서버당 8).
- 차단은 한 서버의 한도 오류로 모든 서버가 멈춘다(어차피 키 전체가 한도 초과 상태).

### 테스트
- `RedisRegionalBusCacheAdapterIT`: put 뒤 다른 어댑터 인스턴스에서 get hit, **빈 OK 저장됨**, 실패 미저장, TTL.
- `GyeonggiBusArrivalAdapterSharedIT`, `TagoBusArrivalAdapterSharedIT`: 어댑터 3개 + fake delegate + latch, 플래그 끔 3회 / 켬 1회.
- `GyeonggiBusArrivalAdapterTest`(수정): 캐시 hit 는 `tryAcquire` 미호출 / miss 소진 → limited, 공급자 미호출 / `ALREADY_CHARGED` 면 미호출.
- `RedisNamedSemaphoreIT`: 두 어댑터가 합산 8 을 넘지 않음, lease 만료 뒤 자리 회수.
- `RedisApiCallBlockerIT`: 한 서버 block → 다른 서버 isBlocked true, KST 자정 뒤 해제, Redis 오류 → JVM 폴백.
- 기존 `RedisOtpConcurrencyGate*` 테스트 무변경 통과.

### 검증 명령
```
/Users/wa/.claude/jobs/8e8c6a78/tmp/runsome.sh --tests '*Gyeonggi*' --tests '*Tago*' --tests '*RegionalBus*' --tests '*NamedSemaphore*' --tests '*ApiCallBlocker*' --tests '*OtpConcurrencyGate*'
/Users/wa/.claude/jobs/8e8c6a78/tmp/runfull.sh
```

---

## PR-3 측정과 문서

브랜치: fresh main 에서 `perf/bus-multi-instance-measure`. 코드 변경은 하네스만.

### 측정 A — 서버 간 묶기 단독 효과 (JUnit, 결정적)
- `CachedSearchTimeBusAdapterCrossInstanceBenchmarkTest`(`@EnabledIfEnvironmentVariable(RUN_BENCHMARK=true)`): 어댑터 1, 2, 3, 4개 × 플래그 끔/켬, 동시 100건(latch 동시 출발), 반복 10회(예열 2). 공급자 지연은 운영 Prometheus 7일 p95 값을 읽어 상수로(read-only 조회, 운영 부하 금지). 경기 어댑터도 같은 표.
- **쓰는 숫자는 공급자 호출 수만**(검토 #18: 한 프로세스 + 로컬 Redis 라 p50/p95 는 배포 환경을 대표하지 않음). 바꾸는 것은 플래그 하나뿐.

### 측정 B — worker 2대 수집 중복 (사다리, 노트북)
하네스 준비물(검토 #9, 지금 하네스로는 못 돈다):
- `run_ladder.sh` 가 `SPLIT=1` 을 `MODE=subway` 에서만 받는다 → 버스 칸에도 `SPLIT=1 WORKERS=N` 허용.
- `WORKER_PORT_BASE` 는 main 에 없다(perf/measure-resume-gaps 브랜치에만 있음) → `run_subway_ladder.sh` 와 `run_ladder.sh` 둘 다에 넣고 preflight(포트 사용 중이면 exit 6). 기본 8090(8081 은 사용자 Expo).
- worker 는 `dev` 프로필로 떠서 `application-dev.yaml` 이 버스 수집기 둘을 끈다 → 칸 인자로 `--collector.bus-arrival.enabled=true --collector.bus-position.enabled=true --collector.bus-arrival.active-hours.start=00:00 --collector.bus-arrival.active-hours.end=00:00`(같음 = 24시간) 과 position 도 같이.
- `findByRouteNameIn(target-route-names)` 이 st-pg 의 `bus_route` 행을 요구 → 칸 시작 전 매칭 노선 수를 세고 0 이면 중단.
- `LEASE` 는 `WORKERS>1` 이면 기본 true → 끔 칸은 `LEADER_LEASE=false` 를 명시.
- `bus-watcher` 가 꺼져 있는지 확인해 fake `/counts` 의 `route_all` 이 수집기만 세게.
- 칸: 리더 락 끔 / 켬, 각 10분. 기대: 끔 = 노선 수 × 2/분, 켬 = 노선 수 × 1/분. 리더 kill -9 칸에서 수집 공백(MULTI-WORKER.md 와 같은 방식).
- 컨테이너는 st-pg(:55432), st-redis(:56379)만 재사용. 운영 VPS 는 읽기 전용 조회만.

### 문서
- `load/ladder/MULTI-WORKER.md` 에 "버스 경로" 절 추가(측정 B).
- 노트 `implementation_note.md` append.
- 메모리 `project-multi-instance-scaling.md` 갱신: "worker 늘리기 전 켤 플래그 = 리더 락 + 서버 간 묶기 + 지역 버스 공유".

---

## 작업 순서 (체크박스)
- [ ] PR-1 구현 → 단위 테스트 → IT → 전체 테스트 → PR → 리뷰 → 머지
- [ ] PR-2 구현(PR-1 머지 뒤 fresh main) → IT → 전체 테스트 → PR → 리뷰 → 머지
- [ ] PR-4 구현(PR-2 머지 뒤 fresh main) → IT → 전체 테스트 → PR → 리뷰 → 머지
- [ ] PR-3 하네스 준비 → 측정 A(JUnit, 서울 + 경기) → 측정 B(사다리) → 문서 → PR → 머지
- [ ] 이력서 Redis 캐시 줄 숫자 갱신(측정 A 결과, 사용자 확인 뒤)
- 의존: PR-2 는 PR-1 과 yaml 이 겹친다 → 순서대로. PR-4 는 PR-2 의 `CrossInstanceFlightPort` 필요. PR-3 측정 B 는 PR-1, 측정 A 는 PR-2(+PR-4) 필요.

## 배포 순서 (사용자가 시점 결정, VPS compose 는 git 아님 → env 파일을 직접 고친다)
1. PR-1, PR-2, PR-4 배포: 플래그 셋 다 꺼짐 → 동작 불변.
2. worker 늘리기 직전, env 편집(검토 #17):
   - **worker env 에만** `JOURNEY_REEVAL_LEADER_LEASE=true`(api 는 설계상 늘 리더 아님. 지하철 수집 + 버스 수집 + 알림 폴러가 리더 한 대로).
   - **api 와 모든 worker env 에** `ROUTE_SEARCH_BUS_CROSS_INSTANCE=true`, `REGIONAL_BUS_SHARED=true`.
3. 배포 직전 실여정 진행 여부 확인(메모리 규칙).
4. 마이그레이션 없음.

## 리뷰에서 사람이 볼 것 (경계)
- 계약: `BusApiQuotaPort` 버킷 이름과 키, `CrossInstanceFlightPort` 의 결과 키가 **토큰별**이라는 점, FlightResult 의 outcome 이 `BusQueryResult.Outcome` 과 1:1(BLOCKED 포함).
- 실패 경로: Redis 장애 시 수집기 fail-closed(멈춤) / 검색은 delegate 직접(쿼터가 fail-closed 라 결국 LIMITED) / 지역 차단기는 JVM 폴백 — 셋이 다르고 일부러 그렇다.
- 플래그 기본값: 셋 다 꺼짐. 플래그 켬일 때 알림 폴러가 Redis 에 묶이는 계약.
