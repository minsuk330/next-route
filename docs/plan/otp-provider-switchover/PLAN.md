# OTP provider 전환 (shadow → 실제 라우팅) — 에이전트 실행 계획 (L)

작성일: 2026-09-15. 티어: L (분산 동시성 프리미티브 신설 + 프로덕션 트래픽 전환).
상위: `docs/plan/otp-vps-shadow-deploy` (shadow 배포 완료·VPS 라이브, dormant). 이 플랜은 그 게이트를 열어 실제 provider 로 승격.
codex 논의 대상(승인 후). SUMMARY.md 는 이 파일 파생물.

## 0. 목표와 완료 계약

VPS 에 dormant 로 떠 있는 OTP(H+F, id 276)를 **수도권 검색의 실제 라우팅 provider** 로 승격한다. 승격 = `defaultProvider=OTP` + `otpEnabled=true` + `ROUTING_OTP_BASE_URL` 을 api/worker 에 동시 반영. 단 그 전에 OTP 를 보호하는 **글로벌 동시성 상한(semaphore)** 을 코드로 넣는다.

완료 = ① 게이트1 correctness(로컬 확정, 아래 §1) 재확인 ② semaphore PR 머지+앱 재배포 ③ 토글 3값으로 실 트래픽이 OTP 로 감 ④ 상한/대기/실패 동작이 검증됨 ⑤ 롤백(env 되돌림) 시 ODSAY 즉시 복귀.

## 1. 확정 사항

### 게이트1 — correctness (이미 로컬 확정, 블로커 해소)
- H+F 엔진 = baseline A 와 **경로 fingerprint mismatch 0** (docs/plan/otp-code-optimization/PLAN.md:227/231/256/257, 반복 재확인). 30분 c4 soak 도 로컬 완료(16,000/16,000, peak RSS 1.98G, 같은 PLAN:229).
- `P4_FORMAL_PENDING` = **성능 주장**(-Xmx10g 30-run paired gate)만 미완, correctness/안전 아님. 전환 블로커 아님.
- **선택 재확인(권장, 필수 아님)**: 배포 graph(seoul-bbox-202503, id 276)로 A/D 각 1회 `--query-mode correctness` → fingerprint diff 0. 실험 graph 와 배포 graph 동일성만 남은 미확인(PLAN:59). engine-level 등가라 위험 낮음.

### 게이트2 — 글로벌 동시성 상한 (semaphore, 이 플랜 본체)
- **글로벌 Redis 분산 semaphore, permit N=4** (api+worker 합쳐 총 4). 로컬 JVM semaphore 기각(api/worker 별 JVM → 합산 상한 안 됨).
- **초과 시 동작**: permit 을 **요청 자체 RoutingDeadline 남은 예산 안에서 대기**. 예산 안에 얻으면 OTP 호출(남은 예산으로), 못 얻으면 → **기존 OTP 실패/degraded 경로**(timeout). **ODSAY 폴백 없음 — provider 를 요청별로 안 섞는다**(결과 일관성).
- **permit 보유 구간 = 실제 OTP 호출 동안만**. 대기는 acquire 단계(보유 전). release 는 finally(정상/예외/timeout 전부).
- **lease TTL** = OTP 호출 max 소요 + 여유(기본 8s, 설정값). JVM crash 로 finally 못 타도 permit 이 TTL 로 자동 회수(leak 방지). ZSET score=만료시각으로 만료 토큰 자동 purge. **(codex) startup validation 강제: `leaseMs ≥ max(api OTP budget, worker search budget) + safetyMargin`** — 현 예산 api 2000ms(application.yaml:89)/worker 3000ms(application.yaml:506)라 8s 는 5s headroom 이지만, 미래에 예산을 lease 위로 올리면 유효 호출 permit 이 만료·재할당되므로 관계를 코드로 강제.
- **now 출처 = Redis 서버시간**: Lua 안에서 `redis.call('TIME')` 로 nowMs 계산(app Clock 아님 — api/worker 간 skew 회피). ARGV = limit·leaseMs·새 UUID 만. (Redis 7, TIME 은 write-effects replication 으로 복제.)
- **Redis 장애 시 = fail-closed/degraded** (codex correction, 기존 RedisBusApiQuota/Breaker 도 fail-closed). permit 검사 불가면 **포화와 동일 처리**(§4 handleOtpSaturated). ⚠ **mem_limit 은 동시성 상한 대체 아님** — 컨테이너/호스트 메모리 보호일 뿐. fail-open 기각.
- **Redis eviction — 단일 공용 Redis, 리스크 감수 결정(사용자 2026-09-15)**: 공용 Redis `allkeys-lru`(128mb, compose.yaml:62) 에 semaphore ZSET(`otp:sem:permits`)를 그냥 둔다. 별 인스턴스·정책변경·캐시 TTL 개조 안 함. **따라서 N=4 는 "강한 보장" 아니라 best-effort 상한**(codex 경고대로 강한 상한 주장 금지). 근거: ① semaphore 키는 요청마다 접근하는 hot key → LRU 상 "최근 사용"이라 실무상 축출 우선순위 최하 ② 축출은 128mb 압박 + 그 키가 LRU-cold 일 때만 = 드묾 ③ 축출돼 리셋돼도 잠깐 N>4 → **하드 안전망 = OTP container `mem_limit 3000m`**(soak c4=1.77G, c8≈2.5-3G 도 컨테이너 격리라 호스트 OOM 아님). 문서/코드 주석에 "best-effort, 하드 상한은 mem_limit" 명시.
- 구현 = 새 프레임워크 0. 기존 `RedisBusApiQuota`/`RedisBusApiBreaker`(RedisTemplate<String,String>+Clock, Lua/atomic, Port 패턴) 미러.

## 2. 함정 (증상 ← 원인 / 가드)

- permit leak(상한이 점점 조여짐) ← finally 에서 release 누락 or holder crash. **가드: release 는 try/finally, + ZSET lease TTL 로 만료 토큰 자동 purge(crash 대비).**
- 두 곳 배선 누락 ← OTP 호출부는 검색(api) + 재탐색(worker) **2개**. 한쪽만 감싸면 상한이 반쪽. **가드: RouteSearchService + RemainingRouteSearchService 둘 다.**
- 대기가 예산 이중소비 ← acquire 대기 + OTP 호출이 각각 deadline 을 따로 쓰면 총합 초과. **가드: acquire 는 남은 예산 안에서만 대기, OTP 호출은 그 후 남은 예산으로. 하나의 RoutingDeadline 공유.** (codex) ⚠ **worker(RemainingRouteSearchService.loadOtp:144)는 현재 자체 deadline 생성 → load()에서 1개 만들어 gate·loadOtp 에 같은 인스턴스 주입으로 고침.** acquire 대기 후 OTP adapter 받는 deadline 잔여시간이 줄어드는 테스트 추가.
- semaphore 상태 축출 ← 공용 Redis `allkeys-lru` 가 ZSET 키 evict → 보유 permit 소실 → 잠깐 N 초과. **결정=리스크 감수(단일 Redis).** best-effort 상한으로 취급, 하드 안전망은 OTP `mem_limit 3000m`. hot key 라 실무상 축출 드묾. "강한 전역 상한" 주장 금지(주석 명시).
- ODSAY 회귀 ← 전환 후에도 기본 구성(otpEnabled=false)에서 OTP branch 안 타야 함. **가드: 토글은 env 로만, 코드 기본값 불변(otpEnabled=false/default=ODSAY 유지).**
- 전환 시 base URL 미반영 ← shadow 중 api/worker 는 ROUTING_OTP_BASE_URL 런타임에 없음. **가드: 토글=api/worker 재생성하며 3값 동시(BASE_URL+DEFAULT_PROVIDER=OTP+OTP_ENABLED=true).**
- Redis 시계 skew/원자성 ← acquire 의 "만료 purge + count + add" 가 원자적이어야. **가드: Lua 스크립트 한 번에(ZREMRANGEBYSCORE + ZCARD + ZADD).**

## 3. 좌표 (기존 코드)

- `application/route/service/RouteSearchService.java:96 runSearch`, `:100` OTP 분기, `:159 runOtpSearch`, `:172 portFor(OTP).search`. ← semaphore 배선 지점(api).
- `application/journey/service/RemainingRouteSearchService.java:110` OTP 분기(worker). ← semaphore 배선 지점 2.
- `application/route/routing/RoutingProviderSelector.java:34 select`, `:38 otpEnabled 게이트`.
- `application/route/config/RoutingProviderProperties.java:16 defaultProvider=ODSAY`, `:18 otpEnabled=false`.
- `application/route/config/OtpRoutingProperties.java` — baseUrl, RoutingDeadline 예산 근거.
- `infrastructure/adapter/out/cache/bus/RedisBusApiBreaker.java`, `RedisBusApiQuota.java` — 미러 대상(Redis Port+adapter+Lua/atomic+Clock+fail 정책 패턴).
- `common/config/RedisConfig.java` — RedisTemplate 빈.
- `compose.yaml` (VPS 는 drift 사본, [[reference-vps-deploy]]) — 토글 env.

## 4. 작업 순서 (PR 분리, fresh main, stack 금지)

### PR-1 — OTP 동시성 gate (semaphore) 코드
- [ ] 새 Port `application/route/routing/OtpConcurrencyGate.java`:
  ```java
  public interface OtpConcurrencyGate {
      /** 남은 예산 안에서 permit 대기. 얻으면 토큰, 못 얻으면 empty. */
      Optional<OtpPermit> tryAcquire(RoutingDeadline deadline);
      void release(OtpPermit permit);   // idempotent
  }
  // OtpPermit = record(String token)
  ```
- [ ] 어댑터 `infrastructure/adapter/out/cache/route/RedisOtpConcurrencyGateAdapter.java`:
  - key `otp:sem:permits` (ZSET). tryAcquire = **Lua(EVAL) 원자**: `local t=redis.call('TIME'); local nowMs=t[1]*1000+math.floor(t[2]/1000); redis.call('ZREMRANGEBYSCORE',key,0,nowMs); if redis.call('ZCARD',key) < limit then redis.call('ZADD',key,nowMs+leaseMs,token); return 1 else return 0 end`. **now = Redis TIME**(app Clock 아님). ARGV = limit·leaseMs·UUID token(재시도마다 새 UUID).
  - tryAcquire 대기 루프: `deadline.remainingMillis()>0` 동안 재시도. 각 sleep = `min(20ms, remaining)`, **InterruptedException 시 interrupt flag 복원 후 중단**(포화로 반환).
  - release = `ZREM key token`(idempotent). Redis 예외 = 로그+무시(멤버 score 만료가 회수).
  - **Redis 예외 on acquire = fail-closed**(codex): permit empty 반환 = 포화와 동일 처리. fail-open 금지(mem_limit≠동시성상한). 메트릭 `otp.sem.redis_error`.
  - 설정: N(기본 4), leaseMs(기본 8000, **startup validation `leaseMs≥max(api2000,worker3000)+margin`**), 재시도 간격(기본 20ms) → 신규 `OtpConcurrencyProperties`.
- [ ] 배선(RouteSearchService.runSearch):
  ```java
  if (providerSelector.select(false) == RoutingProvider.OTP) {
      RoutingDeadline deadline = RoutingDeadline.of(clock, ctx.budgetMillis(deadlineProperties.getOdsayMs()));
      Optional<OtpPermit> permit = gate.tryAcquire(deadline);   // Redis 예외도 empty(fail-closed)
      if (permit.isEmpty()) { return handleOtpSaturated(ctx); } // 504, NOT ODSAY
      try { return runOtpSearch(request, ctx, deadline); }       // 남은 예산 재사용
      finally { gate.release(permit.get()); }
  }
  ```
  - `runOtpSearch` 시그니처에 deadline 주입(현재 내부 생성 → 공유로).
  - **handleOtpSaturated = `throw OdSayApiException.timeout("OTP semaphore saturated")`** (codex): 기존 handleOtpFailure(TIMEOUT) 이 504 예외를 던지므로(RouteSearchService:188-204) 그 계약 재사용. "2xx degraded body" 아님 — 정말 원하면 controller 계약+mandatory-component 의미까지 바꿔야 하므로 이번 범위 밖.
- [ ] 배선(RemainingRouteSearchService): **load()에서 deadline 1개 생성**(현 loadOtp:144 자체생성 제거) → gate·loadOtp 에 같은 인스턴스 주입. 포화/Redis예외 → reroute 기존 실패 경로(OTP-only 라 ODSAY 없음).
- [ ] 메트릭: acquire 성공/포화/redis_error, 대기시간 분포. **역할(api/worker) 태그**로 관찰. 기존 `metrics` 패턴 재사용.
- [ ] 검증: `./gradlew compileJava test`. 신규 단위테스트:
  - gate adapter: permit N개까지 획득/N+1 실패, release 후 재획득, lease 만료(TIME 기반) 후 자동 가용, **Redis 예외 → empty(fail-closed)**, release idempotent, sleep interrupt 복원.
  - RouteSearchService: OTP 선택+permit 있음→OTP 경로, permit 없음→**504(OdSayApiException.timeout), ODSAY 호출 0**(mock 검증), 기본구성(otpEnabled=false)→ODSAY(회귀 0).
  - RemainingRouteSearchService: acquire 대기 후 loadOtp 받는 deadline 잔여 감소, 포화 시 OTP 미호출.
- 산출: gate Port+adapter, OtpConcurrencyProperties(+validation), 두 배선, 테스트.

### 롤아웃 (별 PR 아님, 배포 절차)
- [ ] PR-1 머지 → **앱 재배포**(deploy.sh, api/worker 새 이미지). 이 시점엔 아직 otpEnabled=false → OTP 안 탐. semaphore 는 dormant 배선.
- [ ] (선택) 게이트1 배포-graph 재확인 1회.
- [ ] **토글** = compose(VPS drift 사본에 additive) api/worker environment 에 3값 넣고 **api/worker 재생성**:
  `ROUTING_OTP_BASE_URL=http://otp:8080` + `ROUTING_PROVIDER_DEFAULT_PROVIDER=OTP` + `ROUTING_PROVIDER_OTP_ENABLED=true`.
  (compose 엔 PR-3 에서 base URL/토글 자리 이미 있음 — shadow 기본 false. 전환 = 값 3개로 바꾸고 재생성.)
- [ ] 관찰: Grafana `{app="otp"}` 로그 + 호스트 mem/swap + OTP RSS(soak 때 cAdvisor 없으니 `docker stats`). semaphore 포화/failopen 메트릭.
- [ ] **롤백**: env 3값 되돌리고(otpEnabled=false/default=ODSAY) api/worker 재생성 → ODSAY 즉시 복귀. OTP 컨테이너는 dormant 로 남김.

## 5. 검증 (완료 판정)
- [ ] gate 단위테스트 green(획득/포화/lease만료/failopen/idempotent).
- [ ] 배선 테스트: 기본구성 회귀 0(ODSAY), OTP 선택 시 permit 게이트 동작.
- [ ] 전환 후 실 검색이 OTP 경로(Grafana 로그 planConnection) + 결과 정상.
- [ ] 부하 시 5번째 동시요청이 대기→예산초과면 degraded(ODSAY 안 섞임) 확인.
- [ ] 롤백 env → ODSAY 복귀, 앱 정상.

## 6. codex 논의 결과 (반영 완료) + 남은 배포 결정
codex 판정 = Needs-change. 반영:
- ✅ Redis 장애 = **fail-closed**(fail-open 기각). §1/§4.
- ✅ now = **Redis TIME**(Lua 내부), app Clock skew 회피. §4.
- ✅ lease TTL = **startup validation** `leaseMs≥max(api2000,worker3000)+margin`. §1/§4.
- ✅ N=4 통합 유지(역할 분할 기각, 메트릭만 역할 태그). §1.
- ✅ 20ms polling 유지(pub/sub 기각), sleep `min(20ms,remaining)`+interrupt 복원. §4.
- ✅ worker deadline 1개 공유(loadOtp 자체생성 제거). §2/§4.
- ✅ 포화 응답 = `OdSayApiException.timeout` 로 기존 504 계약 재사용("2xx degraded" 표현 오류 수정). §4.

**Redis eviction 결정 = 완료 (리스크 감수, 단일 Redis)**:
- ✅ 별 인스턴스·정책변경·캐시 TTL 개조 안 함. semaphore 는 공용 allkeys-lru Redis 에 그냥. N=4 는 best-effort, 하드 상한=OTP `mem_limit 3000m`. 코드/주석에 명시. (캐시 감사: 12어댑터 중 BusArrivalSnapshot 만 무TTL — 실시간 라벨추적 상태, 손 안 댐.)
- **남은 블로커 없음. 구현 세션 착수 가능.**
