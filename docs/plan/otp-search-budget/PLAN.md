# OTP 전용 검색 예산 신설 — 에이전트 실행 계획 (M)

작성일: 2026-09-16. 티어: M. 상위: `docs/plan/otp-provider-switchover`.
전환 시도(2026-09-16)에서 발견한 블로커 해소용. gate(PR-1 #197)는 정상, 예산만 부족.

## 0. 배경 / 완료 계약
- **증상**: OTP 실 전환 시 검색 5/5 **502 at t=2.01s**. OTP 호출이 ODSAY용 예산 `route.search.odsay-ms=2000` 을 빌려 써 readTimeout 발화("Request cancelled")→ENGINE_ERROR→502.
- **원인**: OTP planConnection api 왕복(~2.0~2.5s) > 2000ms. gate 무관(permit 즉시 획득).
- **실측(2026-09-16 VPS, otp:18080, 경로 5종×12=60)**: p50=1.18 / p90=1.59 / p95=1.68 / **p99=1.91 / max=2.04**s. worst=응답 큰 경로(일산→천호 57KB). 동시4 버스트 max=1.89s(열화 없음). OTP mem=1.69GiB 고정(57%/2.93GiB, 압박·GC 없음).
- **완료** = ① OTP 전용 예산 property 신설(기본 3500) ② api+worker OTP 호출이 그 예산 사용(단일소스) ③ ODSAY 경로 불변(회귀 0) ④ 단위테스트 green ⑤ 재배포 후(dormant) 토글 시 실검색 200 + p99 관찰.

## 1. 확정 설계
- **예산 분리(decouple)**: OTP 검색 경로는 단일 단계(OTP 호출 1회 + 매핑, enricher 없음)라 다단계 예산기계(`ctx.budgetMillis`) 불필요. OTP 는 **전용 deadline** = `route.search.otp-ms`. `route.search.deadline-ms`(3000)·`odsay-ms`(2000)는 **안 건드림** → ODSAY 롤백 경로 불변.
- **otpMs = 3500**: OTP p99~1.9s/max 2.04s + 앱 역직렬화 오버헤드(~0.5s, 대용량 응답) + cold/GC/미표본 tail 여유. deadlineMs(3000) 안 올림 — OTP 는 자기 deadline 만 봄.
- **단일소스(D2-a)**: api(runSearch)와 worker(loadOtpGated) 둘 다 OTP 호출 deadline 을 `route.search.otp-ms` 하나에서 만든다. worker 는 기존 `searchBudgetMs`(ODSAY 재탐색 전용) 대신 OTP 경로에서 otpMs 사용.
- **reroute 여유 상향(D2-a 파급)**: worker OTP 호출이 최대 3.5s 걸리면 reroute 평가 총예산 `evaluationBudgetMs`(3000)이 후속 예측을 조기 컷 → 예측 없는 후보. `evaluationBudgetMs 3000→4500` 로 올려 3.5s 검색 + 예측 여유 확보. (검색 호출 자체는 evaluationBudgetMs 로 안 막힘 — `RemainingPathCandidateProvider:135` 이 searchBudgetMs 직접 전달. evaluationBudgetMs 는 예측평가 창 `:310 remainingBudgetMs`.)
- **connectTimeoutMs(2000, OtpRoutingProperties)**: 내부망 연결 빠름, 유지.
- **gate/PR-1 불변**: 배선(tryAcquire/release, deadline 공유)·semaphore 그대로. deadline 값 출처만 교체.

## 2. 함정 (증상 ← 원인 / 가드)
- ODSAY 회귀 ← OTP 예산 바꾸며 deadlineMs/odsayMs 를 건드림. **가드: decouple — OTP 만 전용 deadline, ODSAY 경로 코드/값 불변. 테스트로 ODSAY 가 여전히 odsayMs 쓰는지 고정.**
- 예산 이중소비 ← gate 대기 + OTP 호출이 각각 예산 → 총합 초과. **가드: deadline 1개를 gate·호출이 공유(기존 계약 유지). api/worker 둘 다.**
- worker 조기 컷 ← OTP 3.5s 검색 후 evaluationBudgetMs 3000 소진 → 예측 스킵. **가드: evaluationBudgetMs 4500.**
- 저부하 표본 과신 ← 5경로×12, 저부하. **가드: 토글 후 실 p99 재관찰(관측 축 otp.sem.wait + 검색 duration), 이상 시 otpMs 상향 or 롤백.**

## 3. 변경 파일

### 3-1. `src/main/java/watoo/grd/nextroute/application/route/config/RouteSearchDeadlineProperties.java`
- `odsayMs` 옆에 필드 추가:
  ```java
  /** OTP(mandatory 단계) 호출 예산. OTP planConnection 왕복이 ODSAY(2000)보다 느려 전용값을 둔다.
   *  실측(2026-09-16) OTP p99~1.9s/max 2.04s + 앱 역직렬화 오버헤드 + tail 여유. */
  private long otpMs = 3500;
  ```
  (@Getter/@Setter 이미 클래스 레벨 — getter 자동)

### 3-2. `src/main/java/watoo/grd/nextroute/application/route/service/RouteSearchService.java`
- `runSearch` OTP 분기(현 ~:100) deadline 출처 교체:
  ```java
  // 전
  RoutingDeadline deadline = RoutingDeadline.of(clock, ctx.budgetMillis(deadlineProperties.getOdsayMs()));
  // 후 — OTP 전용 예산(단일 단계라 ctx 다단계 예산 불필요)
  RoutingDeadline deadline = RoutingDeadline.of(clock, deadlineProperties.getOtpMs());
  ```
- 나머지(gate.tryAcquire(deadline,"api")→handleOtpSaturated / runOtpSearch(request,ctx,deadline) / finally release) 불변.

### 3-3. `src/main/java/watoo/grd/nextroute/application/journey/service/RemainingRouteSearchService.java`
- 필드 추가: `private final watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties routeSearchDeadlineProperties;` (@RequiredArgsConstructor — 생성자 자동, 인자 순서 clock 앞에)
- `loadOtpGated` deadline 출처 교체:
  ```java
  // 전
  RoutingDeadline deadline = RoutingDeadline.of(clock, budgetMs);
  // 후 — OTP 호출은 단일소스 otpMs (ODSAY 재탐색은 계속 budgetMs=searchBudgetMs)
  RoutingDeadline deadline = RoutingDeadline.of(clock, routeSearchDeadlineProperties.getOtpMs());
  ```
- `budgetMs` 파라미터는 loadOdsay(ODSAY 재탐색)에서 계속 사용 — 시그니처 유지.

### 3-4. `src/main/java/watoo/grd/nextroute/application/journey/config/JourneyRerouteProperties.java`
- `RemainingPath.evaluationBudgetMs` 기본값 `3000 → 4500` (javadoc 근거 한 줄: "OTP 검색 최대 3.5s + 예측 여유").
- 주의: 같은 이름 필드가 `RouteOption`(:140)에도 있음 — **RemainingPath(:140 근처) 것만** 변경. RouteOption 은 무관.

## 4. 작업 순서 (fresh main 독립 브랜치 `feat/otp-search-budget`, PR 1개)
- [ ] 3-1 property 추가 → `./gradlew compileJava`
- [ ] 3-2 api 배선 교체
- [ ] 3-3 worker 필드주입 + 배선 교체
- [ ] 3-4 reroute evaluationBudgetMs 상향
- [ ] 테스트 수정/추가(§5) → `./gradlew compileTestJava`
- [ ] `./gradlew test --tests '*RouteSearchService*' --tests '*RemainingRouteSearch*' --tests '*JourneyRerouteProperties*'` green
- [ ] 플랜 파일 포함 커밋 → PR
- 롤아웃: PR 머지 → VPS 재배포(dormant, `deploy.sh all`, [[reference-vps-deploy]]) → 토글(otp-provider-switchover PLAN §4) → 실검색 200 + p99 관찰.

## 5. 테스트 (계약당 최소 1)
- `RouteSearchServiceTest`(기존 생성자 호출들 영향 없음 — property 값만):
  - **신규**: OTP 선택 시 gate/otpPort 에 넘어가는 deadline 잔여가 **otpMs 반영**(> odsayMs 2000, ≈ 3500). ArgumentCaptor<RoutingDeadline> + `remainingMillis(clock)` 로 (2000, 3600] 범위 검증. (기본 otpMs=3500 을 test props 로 명시하거나 기본값 사용)
  - **회귀**: 기본 구성(ODSAY) 경로는 그대로(기존 테스트 유지 — odsayMs 사용 불변).
- `RemainingRouteSearchServiceTest`/`...OtpTest`:
  - **신규**: worker OTP 경로 deadline 이 otpMs(≈3500) 사용, 전달 budgetMs(예:2000) 아님. captor 로 검증. 생성자에 RouteSearchDeadlineProperties 추가 반영.
  - **회귀**: ODSAY 재탐색은 계속 budgetMs 사용(loadOdsay 경로).
- `JourneyReroutePropertiesTest`: RemainingPath.evaluationBudgetMs 기본값 4500 확정(RouteOption.evaluationBudgetMs 는 불변 확인).

## 6. 좌표 (기존)
- application/route/service/RouteSearchService.java:100 — runSearch OTP 분기(deadline 생성)
- application/journey/service/RemainingRouteSearchService.java loadOtpGated — worker OTP deadline
- application/journey/service/RemainingPathCandidateProvider.java:135 — searchBudgetMs 로 search 호출 / :310 remainingBudgetMs(evaluationBudgetMs 게이트)
- application/route/config/RouteSearchDeadlineProperties.java:odsayMs — 예산 이웃
- application/route/routing/RoutingDeadline.java — of(clock,budget)/remainingMillis(clock)

## 7. 배포
- 마이그레이션 없음. 신규 설정 `route.search.otp-ms`(기본 3500), 변경 `journey.reroute.remaining-path.evaluation-budget-ms`(3000→4500). yaml 미기재 시 코드 기본값.
- 기본 otp-enabled=false → OTP 경로 미진입 = 신규 예산 미사용, dormant. 회귀 0.
