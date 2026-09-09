# Policy Inventory — ODsay 호출량 절감용 제한 목록 (P0 산출물)

작성일 2026-09-07. 목적: 비용(외부 유료 호출) 때문에 둔 제한과, 자원 보호·중복 방지·UX 목적의 제한을 구분해 P2 가 무엇을 해제/복원하고 무엇을 유지할지 판단하게 한다.

판정 라벨: **REMOVE**(비용만이 근거 → OTP 전환 시 해제), **REVIEW**(비용 + 다른 목적 혼재 → P2 가 목적별로 분리), **KEEP**(자원/중복/UX 목적 → 유지).

핵심 사실: 제품 라우팅은 현재 `RouteSearchService`(`application/route/service/RouteSearchService.java`)가 `OdSayApiPort` 를 **전국** 호출한다. 지역/bbox/prefix 필터는 제품 코드에 **없다**. 따라서 intent §2 의 "지역/prefix 필터"는 제품에 해제할 대상이 아니라 P1 에서 OTP graph 데이터 커버리지(bbox)로 신설되는 것이다.

| # | 항목 | 코드/설정 위치 | 현재 값 | 도입 목적/근거 | 영향 기능 | 판정 | P2 담당 |
|---|---|---|---|---|---|---|---|
| 1 | 남은경로 재검색 평가 경로 수 상한 | `journey.reroute.remaining-path.max-paths` → `JourneyRerouteProperties.RemainingPath.maxPaths`, 소비 `RemainingPathCandidateProvider` | ~~2~~ → **4** (PR4a) | ~~비용~~ → **자원/UX**. ODsay 유료 근거 해제(OTP 내부호출=무료). 우회로 놓침 줄이려 폭 확대, 상한 근거는 경로당 승차예측(ML/실시간) 자원 + 라이브 대기. 전체 상한은 evaluation-budget-ms. **⚠ OTP 전제**: OTP 켜기 전 provider=ODSAY 면 경로 수만큼 유료 호출 증가 → 재탐색=사용자요청+shadow 라 저사용, OTP 활성 예정이라 수용 | 지연으로 다음 편 놓쳤을 때 목적지까지 대안 경로 평가 폭 | **REVIEW→재산정(PR4a)** | P2-F. 값 4·근거 자원/UX 확정. 계약테스트 `JourneyReroutePropertiesTest` 가드 |
| 2 | 남은경로 검색 시간 예산 | `journey.reroute.remaining-path.search-budget-ms` | ~~2000~~ → **3000** (PR4a) | ~~혼재(비용/지연)~~ → **지연/완결성**. OTP 내부호출로 근거가 요금→지연·완결성. 멀고 환승 많은 경로가 2초에 잘려 빈약해지느니 1초 더. 전체 여정 deadline 안 | 재검색 응답성 | **REVIEW→재산정(PR4a)** | P2-F. 값 3000 확정 |
| 3 | 남은경로 검색 캐시 TTL | `journey.reroute.remaining-path.cache-ttl` | 10m | **자원/중복**. 정류장→목적지 공유키로 중복 연산 감소(기능 제한 아님) | 캐시 적중률 | **KEEP** | P2-C. 키에 provider+graphVersion 추가 필요(값 자체 유지) |
| 4 | 동일정류장 대안노선 예측 상한 | `journey.reroute.route-option.max-evaluations` → `RouteOption.maxEvaluations` | ~~3~~ → **10** (PR4a) | **자원/UX**. 유료 라우팅 아님(저장 후보를 실시간·ML 조회). 같은 정류장 우리방향 노선 보통 3~6개 → 10 이면 사실상 전부 확인, 더 빠른 대안 놓침 방지. 폭주 가드로 상한 유지(0/무제한 불가). 실질 대기는 evaluation-budget-ms 가 먼저 컷 | ③ 대안 노선 제안 폭 | **REVIEW→재산정(PR4a)** | P2-F. 값 10 확정 |
| 5 | 대안노선 최소 개선폭 | `journey.reroute.route-option.minimum-eta-gain-seconds` / `remaining-path.minimum-eta-gain-seconds` | 300 / 300 | **UX/품질**. 예측오차보다 작은 제안 억제(신뢰 보호) | 제안 발생 여부 | **KEEP** | P2-F. 비용과 무관. 값 유지, 근거만 재확인 |
| 6 | reroute 트리거 shadow + cooldown | `journey.reroute.trigger.enabled`(shadow), `trigger.cooldown` | shadow on, ~~PT2M~~ → **PT1M** (PR4a) | cooldown: ~~비용 관측~~ → **UX/중복억제**. 요금 폭 근거 옅어져 지연 상황 더 자주 갱신(1분), 연타 가드는 1분으로 충분. **shadow 는 이번에 안 건드림** — 재탐색 부품(4b·4c) 완성 후 실제 제안 ON 하는 배포 결정으로 분리 | 자동 재탐색 활성화 | **REVIEW→cooldown 재산정(PR4a), shadow 보류** | P2-F. shadow OFF(실제 제안)는 4b·4c 후 |
| 7 | 검색당 외부호출 상한 | `route.transfer-arrival.max-external-calls-per-search` → `TransferArrivalProperties.n` | 12 | **자원**. 환승 도착보강용 서울 실시간/TMAP/ML 호출 수. ODsay 라우팅과 무관 | 환승 도착시각 보강 범위 | **KEEP** | P2-D/E. ODsay 비용 아님. 자원 보호로 유지 |
| 8 | 외부호출 동시성 상한 | `route.transfer-arrival.max-concurrent-external-calls` | 8 | **자원**. bulkhead. 근거 docs/plan/route-search-resilience | 보강 동시성 | **KEEP** | 유지 |
| 9 | 검색 deadline/격리 | `route.search.deadline-ms=3000`, `odsay-ms=2000`, tmap-pool | — | **자원**. route-search-resilience 산출. 전체 예산·격리 | 검색 응답성 | **KEEP** | P2-H. OTP 단계 예산을 이 전체 deadline 안에 포함 |
| 10 | ODsay -98 도보 fallback | `RouteSearchService` `ODSAY_CODE_WITHIN_WALK_DISTANCE=-98` | 700m 이내 → TMAP 도보 | **기능**. 근거리 도보 응답 | 근거리 검색 | **KEEP** | P2-B. ODsay adapter/정책 내부에서 기존 동작 유지 |
| 11 | 미래출발 예측 horizon | `transfer.predict.max-future-minutes=40`, `journey.reroute.route-option`/evaluator horizon | 40m | **품질/자원**. 실시간값 적용 범위 제한 | 미래시각 보강 | **KEEP** | P2-E. 실시간 horizon 은 provider 특성이라 유지 |

## 요약

- 순수 비용(REMOVE)만인 항목은 없다. ODsay 유료 라우팅에 직접 매인 것은 **#1, #2**(남은경로 검색)와 **#6**(트리거 shadow 의 요금 근거)뿐이고 모두 다른 목적과 혼재해 **REVIEW**로 넘긴다.
- `route-option`(③, #4/#5)은 유료 라우팅이 아니라 **실시간·ML 조회 + UX 대기**가 근거다. OTP 전환으로 저절로 풀리지 않으므로 비용 라벨을 떼되 상한 자체는 UX/자원으로 유지 판단한다.
- 캐시·SingleFlight·bulkhead·deadline(#3,#7,#8,#9)은 기능 제한이 아니라 중복 연산·자원 보호다. 삭제 대상 아님. 키에 provider+graphVersion 추가만 필요(P2-C).
- P2-F 가 각 REVIEW 항목의 값·코드위치·시험결과를 이 표에 갱신한다.

## 기존 실시간·ML 보강 경로 (P2-D 보존 대상, 조용히 삭제 금지)

| 기능 | 진입/포트 | 매핑 입력 |
|---|---|---|
| 검색 환승 도착보강 | `application/route/service/TransferArrivalEnricher.java` | 서울 실시간 + ML |
| 서울 버스 실시간 도착 | `application/route/port/out/SearchTimeBusQueryPort.java` (서울 `/arrive/getArrInfoByRoute`) | busLocalBlID/route/stop occurrence |
| 지하철 실시간 도착 | `application/subway/service/SubwayRealtimeArrivalReader.java`, `SubwayLineIdMapper`, `SubwayDirectionNormalizer` | 노선/방향/역 |
| ML 승차예측 feature | `application/route/service/MlFeatureVectorBuilder.java` (`ml.predictor`) | route_categories·feature |
| ODsay polyline 형상 | `RoutePolylineEnricher`, `domain/route/polyline/*`, `OdsayRoutePolylineDataService` | ODsay 노선 polyline(→ P3 OSM relation 으로 대체) |
| TMAP 도보 형상 | `application/route/service/WalkSegmentEnricher.java` (`tmap`) | 좌표 |
| 여정 실시간/재탐색 | `RemainingRouteSearchService`, `RouteOptionCandidateProvider`, `RemainingPathCandidateProvider`, `TransferArrivalProperties` | 위 매핑 재사용 |

주의: 이 경로들은 검증된 서울 대상에서 동작한다. 새 GTFS(feed/agency/route/stop occurrence) ↔ 서울 API ID 매핑이 확정된 leg 에만 적용한다(P2-D). 매핑 불가 구간은 `STATIC_SCHEDULE` + 사유로 남기고 조용히 기능을 지우지 않는다.
