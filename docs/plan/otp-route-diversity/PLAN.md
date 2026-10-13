# OTP 경로 다양성 — 서로 다른 경로 5개 확보 (M 티어, PR 1개)

상태: 승인됨·구현 완료(2026-10-12). 선행: `docs/plan/otp-route-ranking/PLAN.md`(#387·#389 배포됨).
경로 접두사 `B` = `src/main/java/watoo/grd/nextroute`, `T` = `src/test/java/watoo/grd/nextroute`.

## 배경

#389(같은 경로 다음 편으로 채우기 폐기) 배포 후, 고유 경로가 적은 구간은 1~3개만 나온다(부평→서울 1, 노원→강남 3).
사용자 요구: **최소 5개, 같은 경로 반복 금지** → 결정(2026-10-12) "추가 검색만"(채우기 없음, 노선 자체가 적으면 5개 미만 허용).

원인: OTP Raptor 는 "늦게 도착·환승 많음·비용 높음"인 경로를 탐색 단계에서 버린다. 부평→서울은 1호선이 버스 대안을 모두
지배해 후보에 없다(ODsay 는 11개). 서울역→잠실은 광역버스>2호선이 지하철끼리 환승(4→2)을 지배.

실측(운영 OTP, 15:00, 수단 제한 검색 합집합, 노선 이름 기준 고유):

| 구간 | 전체 수단 30분 | + 지하철만 + 버스만 |
|---|---|---|
| 부평→서울 | 2 | 12 |
| 노원→강남 | 4(버스뿐) | 7(4→2호선 등 지하철 포함) |
| 서울역→잠실 | 6 | 31(4→2·1→2호선 포함 — 미결 해소) |
| 홍천→인제 | 0→재검색 2 | 2(노선 자체가 2개) |

지하철만 0.05~0.6s, 버스만 0.4~1.3s.

## 작업 순서

- [x] **1. 설정** — `B/application/route/config/OtpRoutingProperties.java` (`rankingEnabled` L37 아래)
  - `boolean supplementaryEnabled = true` — env 킬스위치 `ROUTING_OTP_SUPPLEMENTARY_ENABLED`
  - `long supplementaryBudgetMs = 2000` — 추가 검색 1회 예산 상한
- [x] **2. 어댑터가 `RoutingQuery.modes` 를 지킴** — `B/infrastructure/adapter/out/api/otp/OtpRoutingAdapter.java`
  - `RoutingQuery.modes`(`Set<RoutingMode>`, 비면 전체 — `B/application/route/routing/RoutingQuery.java`)는 지금 무시됨
  - `requestBody`(L140~) / `transitModes`(L162~): 수단 목록을 query 에서 만든다
    - 비면 = 지금과 같음(`ALL_TRANSIT_MODES` 15개, BUS 만 reluctance, reluctance==1.0 이면 `modes` 변수 미전송)
    - 아니면 펼침: `BUS` → BUS(reluctance)·COACH·TROLLEYBUS / `SUBWAY` → SUBWAY·MONORAIL·TRAM / `RAIL` → RAIL·FUNICULAR. reluctance 1.0 이어도 전송(제한이 목적)
  - 창(30분)·창 폴백 규칙은 수단 제한 검색에도 그대로
- [x] **3. 검색 서비스 추가 검색** — `B/application/route/service/RouteSearchService.java` `runOtpSearch`(L179~), `selectCandidates`(L219~)
  1. 본 검색(전 수단) 그대로. 실패면 지금처럼 `handleOtpFailure`
  2. `rankingEnabled && supplementaryEnabled` 이고 `ItineraryRanker.rank(본, displayLimit).size() < displayLimit` 이면 순서대로:
     - ① `modes={SUBWAY, RAIL}` → 합쳐 다시 계산 → 아직 부족하면 ② `modes={BUS}`
     - 같은 permit 안에서 **순차**(OTP 동시 요청 수 불변). 각 호출 deadline = `RoutingDeadline.of(clock, min(deadline.remainingMillis(clock), supplementaryBudgetMs))`
     - 남은 예산 ≤ `deadlineProperties.getMinRemainingForOptionalMs()`(150) 이면 건너뜀
     - 추가 검색이 `RoutingFailure` 면 무시(본 결과 유지). 런타임 예외도 잡아 로그만
  - 구현 메모: `RoutingQuery.withModes(Set)` 추가. 기존 `RouteSearchServiceTest.TC_OTP선택_permit있으면…` 의 "OTP 1회" 검증을 "본 검색(modes 빈) 1회"로 좁힘(결과 1개라 추가 검색이 정상)
  3. 이어 붙이기(본 → 지하철 → 버스 = 엔진 순서) → `ItineraryRanker.rank(…, displayLimit)` → `result.withItineraries`
  - 로그 INFO: `[OTP] 고유 경로 {n} < {limit} → 추가 검색 {modes}: 후보 {m}, 합친 고유 {k}`
  - 일반화비용은 같은 비용 모델(버스 1.3 동일)이라 검색 간 비교 가능
  - 추가 검색 시간은 기존 `metrics.recordStage(SearchComponent.ODSAY, …)` 구간에 포함
- [x] **4. 재탐색 불변** — `B/application/journey/service/RemainingRouteSearchService.java` `loadOtp`(L144~): 추가 검색 안 함(엔진 순서 앞 displayLimit 자르기 유지). 테스트로 고정
- [x] **5. 테스트**(아래 표) → **6. 계획 묶음 실행** → **7. PR** → 배포는 사용자 확인 후

## 테스트

| 계약 | 테스트 |
|---|---|
| modes `{SUBWAY,RAIL}` → BUS 없음, SUBWAY·RAIL·MONORAIL·TRAM·FUNICULAR | `T/infrastructure/adapter/out/api/otp/OtpRoutingAdapterHttpTest` |
| modes `{BUS}` → BUS(1.3)·COACH·TROLLEYBUS 만 / 빈 modes → 지금과 같음(15개) | 〃 |
| 본 고유 2개 → 지하철·버스 추가 검색(modes 확인), 응답 5개·추가 결과 포함·중복 0 | `T/application/route/service/RouteSearchServiceOtpWiringTest` |
| 본 고유 5개 → 추가 호출 없음 | 〃 |
| 지하철 추가로 5개 → 버스 호출 없음 | 〃 |
| 추가 검색 실패 → 본 결과로 응답 | 〃 |
| supplementaryEnabled=false → 추가 호출 없음 | 〃 |
| 재탐색은 포트 1회만 호출 | `T/application/journey/service/RemainingRouteSearchServiceOtpTest` |

## 검증

1. `./gradlew test --tests '*ItineraryRanker*' --tests '*OtpRoutingAdapter*' --tests '*OtpPlanConnectionMapper*' --tests '*RouteSearchService*' --tests '*RemainingRouteSearch*' --tests '*RoutingModel*' --tests '*LegacyRouteResponseMapper*' --tests '*RouteSearchDeadlineIT' --tests '*RouteSearchRealtimeDeadlineIT'`
2. 배포 후(사용자 확인, api·worker, 마이그레이션 없음) 운영 검색 API: 부평→서울·노원→강남 5개·중복 0, 서울역→잠실 지하철끼리 환승 포함 여부, 강남→홍대 불변(추가 검색 없음), 응답 시간, 추가 검색 로그
3. 노트 `implementation_note.md` append
