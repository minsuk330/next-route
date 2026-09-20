# P7 — OTP 기본 전환 + ODsay 폴리라인 스택 제거

티어 M(작은 PR 2개). 선행 Phase = `docs/plan/national-otp-map-migration/PHASE-7.md`, 공통 계약 = 같은 디렉터리 `AGENT-GUIDE.md`.

## 0. 현황 (2026-09-20 실측, 재조사 금지)

- 프로덕션은 **이미 OTP 로 돌고 있다**. VPS compose: `ROUTING_PROVIDER_DEFAULT_PROVIDER=OTP`, `ROUTING_PROVIDER_OTP_ENABLED=true`, `ROUTING_OTP_SHAPES_ENABLED=true`. api/worker = `nextroute:20260919-142058-9a73dd4b`.
- Prometheus 7일: `otp` 168.7콜 vs `api.odsay.com` 4콜, **마지막 ODsay 호출 = 2026-09-17 21:13**(전환 토글 시각). 이후 0.
- 앱 코드 기본값은 아직 반대: `RoutingProviderProperties.java:16,18` = `ODSAY` / `otpEnabled=false`. 전환이 env 주입에만 의존 → env 유실 시 조용히 ODsay 복귀. **이것이 PR-1.**
- ODsay 폴리라인 스택은 닫힌 고리다: seed(`RoutePolylineEnricher:139` → `requestCollection`) → job → `OdsayRoutePolylineCollectionScheduler`(worker, 60s) → `OdsayRoutePolylineLoadService` → `OdSayApiPort.loadLane`(유료). seed 진입점이 ODSAY 검색 분기뿐이라 OTP 전환 후 **신규 유입 0**.
- 프로덕션 행수: `odsay_route_polyline` 27 / `odsay_route_line_seed` 0 / `odsay_route_polyline_collection_job` 27(PENDING 0). FK 없음.
- P0 policy-inventory 의 "비용 근거" 제한 4건(#1 max-paths 4, #2 search-budget-ms 3000, #4 max-evaluations 10, #6 cooldown PT1M)은 P2-F PR4a 에서 이미 해제·재산정 완료. yaml 실값 확인됨. **P7 은 이 행들을 다시 건드리지 않는다.**
- 작업 트리에 미커밋 변경 1건: `OdsayRoutePolylineDataService.java` 에 `@Deprecated` + 미사용 import `DeprecatedConfigurationProperty` 가 붙어 있다(이전 세션 흔적). PR-2 가 이 파일을 통째로 지우므로 **삭제로 흡수**한다.

## 1. 사용자 결정 (확정, 재논의 금지)

- ODSAY **라우팅 분기는 보존**한다(롤백 통로). `OdsayRoutingAdapter`·`OdSayApiAdapter.searchPath`·`RoutingProvider.ODSAY` 유지.
- **폴리라인 스택만 걷어낸다.** 지하철 폴리라인은 P3/P4 의 OTP 형상(`EncodedPolyline` + coverage-index)이 이미 대체한다.
- 결과로 ODsay 롤백 시 지하철 leg `polyline` 이 null 이 된다. 롤백은 비상용이고 P4 이전에도 null 이던 필드라 수용한다.

## 2. PR-1 — provider 기본값을 OTP 로

브랜치: fresh main 기준 `feat/otp-p7-default-provider`.

### 변경
- [ ] `src/main/java/watoo/grd/nextroute/application/route/config/RoutingProviderProperties.java`
  - `defaultProvider = RoutingProvider.OTP`, `otpEnabled = true`
  - 클래스 javadoc 갱신: "기본은 ODSAY(전환은 P7)" → "기본 OTP. ODSAY 는 env 로 되돌리는 롤백 통로." `otp-enabled` 의 원래 목적(P2-B 미승인 OTP 차단)은 그대로 문서화.
- [ ] `src/main/resources/application.yaml` — `routing:` 최상위 블록이 **없다**(현재 env relaxed binding 으로만 주입). 신설:
  ```yaml
  routing:
    provider:
      default-provider: ${ROUTING_PROVIDER_DEFAULT_PROVIDER:OTP}
      otp-enabled: ${ROUTING_PROVIDER_OTP_ENABLED:true}
  ```
  `routing.otp.*`(base-url/graph-version/coverage-index-path/shapes-enabled)는 기존대로 env 주입 유지 — 이 PR 에서 yaml 로 끌어오지 않는다(배포 값과 코드 값이 두 곳에 갈리는 것 방지).
- [ ] `RoutingProviderSelector.java:36~41` 로직 **불변**. `default-provider=OTP` + `otp-enabled=false` 오설정 방어(ODSAY 로 내림)는 그대로 둔다.

### 테스트
- [ ] 신규 `src/test/java/watoo/grd/nextroute/application/route/config/RoutingProviderPropertiesTest.java` — 계약 가드: 기본 인스턴스가 `OTP` / `otpEnabled=true`.
- [ ] `RoutingProviderSelectorTest` — 기본값 뒤집힘 반영. 최소 3케이스 유지: ①기본(요청 없음) → OTP ②`otpEnabled=false` → ODSAY ③`defaultProvider=ODSAY` 명시 → ODSAY.
- [ ] 기본값에 의존하던 기존 테스트 전수 점검. 후보: `RouteSearchServiceTest`, `RouteSearchServiceOtpWiringTest`, `RouteSearchDeadlineIT`, `RouteSearchRealtimeDeadlineIT`, `RemainingRouteSearchServiceTest`, `RemainingRouteSearchServiceOtpTest`.
  - ODSAY 경로를 검사하는 테스트는 **properties 에 ODSAY 를 명시**하게 고친다(암묵 기본값 의존 제거). 테스트가 기본값을 다시 못 쓰게 하는 게 목적.
- [ ] 검증: `./gradlew compileJava compileTestJava` → `./gradlew test --tests "*RoutingProvider*" --tests "*RouteSearchService*" --tests "*RemainingRouteSearch*"` → 전체 `./gradlew test`.

### 배포 영향
없음(코드 기본값이 VPS env 와 같아질 뿐). 마이그레이션 없음.

## 3. PR-2 — ODsay 폴리라인 스택 제거

브랜치: fresh main 기준 `refactor/otp-p7-drop-odsay-polyline`. PR-1 머지 후 시작(같은 파일 `RouteSearchService` 충돌 회피).

### 삭제 (main)
- [ ] `application/route/service/`: `RoutePolylineEnricher.java`, `OdsayRoutePolylineLoadService.java`, `OdsayRoutePolylineCollectionService.java`, `OdsayRoutePolylineSlicer.java`, `OdsayMapObjParser.java`
- [ ] `application/route/dto/`: `OdsayMapObjFragment.java`, `OdsayRoutePolylineData.java`, `OdsayRoutePolylinePoint.java`
- [ ] `domain/route/polyline/` 전체: entity(`OdsayRoutePolyline`, `OdsayRouteLineSeed`, `OdsayRoutePolylineCollectionJob`, `CollectionJobStatus`), repository 3종, `service/OdsayRoutePolylineDataService.java`
- [ ] `infrastructure/adapter/in/api/admin/OdsayRoutePolylineAdminController.java` (관리 API 3종 소멸 — 소비자 없음)
- [ ] `infrastructure/adapter/in/scheduler/OdsayRoutePolylineCollectionScheduler.java`
- [ ] odsay lane 전용 DTO: `dto/OdSayLaneGraphic.java`, `dto/OdSayLoadLaneResponse.java`, `dto/OdSayLoadLaneResult.java`, `dto/OdSayBoundary.java`

### 수정 (main)
- [ ] `application/route/port/out/OdSayApiPort.java` — `loadLane(String)` 제거. `searchPath` 는 유지.
- [ ] `infrastructure/adapter/out/api/odsay/OdSayApiAdapter.java` — `loadLane` 구현 + lane 전용 private helper/상수/URL 조립 제거. searchPath 경로는 손대지 않는다.
- [ ] `application/route/service/RouteSearchService.java` — 필드 `polylineEnricher`(:53), ODSAY 분기의 폴리라인 단계(:147~150), 관련 import 제거. WALK·BUS_REALTIME 단계와 `walkOnlyFallback`(-98)은 **보존**.
- [ ] `application/route/observability/SearchComponent.java` — `POLYLINE` 상수 제거. **계약 변화**: 검색 응답 meta 의 degraded 목록에서 `polyline` 이 영구히 사라진다(이미 OTP 전환 후 안 나오던 값). 노트 `### 확정` 에 한 줄 남긴다.
- [ ] 보존 확인(삭제 금지): `OdSayLane`(검색 `OdSaySubPath` 공유), `LaneGraphicResult`(`PathResult`·`LegacyRouteResponseMapper` 계약), `SubPathResult.polyline`(OTP 가 채움), `EncodedPolyline`, bus 계열 `BusRoutePolyline*`(PostGIS·서울 API 축, ODsay 무관), `SubwayLineIdMapper`·`TransferStopResolver`(서울 실시간 축), `JourneyLeg`/`JourneyLegRouteOption`·V36(활성 여정).

### 테스트
- [ ] 삭제: `RoutePolylineEnricherTest`, `OdsayRoutePolylineLoadServiceTest`, `OdsayRoutePolylineSlicerTest`, `OdsayMapObjParserTest`
- [ ] 수정: `OdSayApiAdapterTest`(lane 케이스 제거), `RouteSearchServiceTest`·`RouteSearchDeadlineIT`·`RouteSearchRealtimeDeadlineIT`(폴리라인 단계 stub/단정 제거), `RuntimeRoleProfileTest`(worker 프로필 빈 목록에서 스케줄러 제거), `RouteSearchServiceOtpWiringTest`
- [ ] 계약 테스트 유지 확인: ODSAY 분기가 **폴리라인 없이도** 정상 응답하는 케이스 1개 이상 남긴다(롤백 통로 회귀 가드).
- [ ] 검증: `./gradlew compileJava compileTestJava` → `./gradlew test`. 실패는 "기존 무관" 라벨로 분리.

### DB
- [ ] 신규 `src/main/resources/db/migration/V55__drop_odsay_route_polyline.sql`
  ```sql
  DROP TABLE IF EXISTS odsay_route_polyline_collection_job;
  DROP TABLE IF EXISTS odsay_route_line_seed;
  DROP TABLE IF EXISTS odsay_route_polyline;
  ```
  V4 는 **수정하지 않는다**(배포된 migration). FK 없음, 참조 테이블 없음 — 확인 완료.
- [ ] 노트 `### 배포` 에 "V55 VPS 수동배치 필요" 명시. 마이그레이션은 V18+ untracked convention 이라 CI 로 안 나간다.
- [ ] 선행 미배포분 주의: **V53·V54 가 아직 VPS 에 안 올라가 있다**(journey-reroute-nextstop). V55 배치 시 번호 순서 확인.

## 4. 검증 (PHASE-7 필수 확인 대응)

| PHASE-7 항목 | 이 플랜의 대응 |
|---|---|
| ODsay 차단 상태에서 OTP 검색·재탐색 성공 | PR-1 테스트 + 프로덕션 3일 무호출 실측(§0) |
| 기존/신규 여정 조회·진행·수락·종료 유지 | Journey 엔티티·V36 보존, 전체 test green |
| bbox 밖 거부, 수단 오분류 없음 | `RoutingFailure.OUT_OF_SCOPE` 기존 구현 유지(아래 미결 참조) |
| 비용 근거 제한 복원 확인 | P2-F PR4a 에서 완료, §0 에 실값 대조 기록 |
| 제거/보존 목록과 이유 기록 | §3 표 + `handoffs/PHASE-7.md` |

- [ ] 두 PR 머지 후 `docs/plan/national-otp-map-migration/handoffs/PHASE-7.md` 작성: 변경 파일, 제거/보존 사용처와 이유, 회귀 결과, 실제 배포 상태(코드 완료 vs 운영 전환 구분), 남은 한계.

## 5. 범위 밖 (건드리지 않는다)

- ODSAY 라우팅 분기 자체 제거 — 롤백 통로로 보존(사용자 결정).
- `OUT_OF_SCOPE` 를 빈 결과가 아닌 명시적 거부 응답으로 바꾸기. 현재 `RouteSearchService.handleOtpFailure` 가 `OUT_OF_SCOPE`/`NO_ROUTE` 를 똑같이 빈 결과로 낸다. **별건**(V2 DTO 컨트롤러 미배선과 묶여 있음) — handoff `남은 문제` 에 기록만.
- Phase 8(수도권 외 실시간 공급자), 모바일 실기기 검증.
