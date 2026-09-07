# Phase 2 — 공통 라우팅·시간·ID·Journey 계약

## 뭘 하나
ODsay·OTP 를 공통 `RoutingPort` 뒤에 두고, 최초 검색·재탐색을 같은 규칙으로 처리한다. 기존 앱은 옛 응답(legacy), 신규는 v2 응답을 받게 하고, 실시간/ML·기존 여정은 보존한다. 8개 PR(A~H)로 나눠 세션분리 구현.

## 왜
ODsay 호출제한 탈피(전체 목표)의 서버 축. OTP 결과를 기존 파이프라인에 안전히 흘리려면 provider 무관 계약이 먼저 필요.

## 핵심 결정
- **모델 A**: 중립 `RoutingResult` 신설 + `LegacyRouteResponseMapper` 로 옛 DTO 번역 → 아래 코드(여정·저장·재탐색) 무변경. (직접매핑/신엔드포인트 기각)
- **호환성 실측 끝**: OTP↔legacy 매핑 가능. 갭 6종(출구·방향/급행·요금·색상·mapObj·실시간ID)은 원천부재라 가짜값 금지 → coverage/`STATIC_SCHEDULE` 로 노출.
- **payment(codex 반영)**: fare 3-상태 KNOWN/KNOWN_ZERO/UNKNOWN. OTP legacy int: UNKNOWN→`-1`, 무료도보→`0`. **ODsay 기존 동작 유지**(이미 0 생성, parity 전 불변). v2=nullable amount+availability.
- **provider 기본 ODSAY**, OTP 는 내부/승인만 (전환은 P7). OUT_OF_SCOPE=OTP `OUTSIDE_BOUNDS`(무경로와 구분).
- **마이그레이션 V49(ID매핑)·V50(snapshot)** — codex 지적 반영: `.gitignore` whitelist 에 추가(버전관리) + **VPS 수동배치 필수**.

## Codex 논의 반영 (조건부 진행 승인)
PR2·PR7 과대→분할(2a/b/c, 7a/b/c), flag/guard/reader 를 writer 앞 배포. 갭은 단일 STATIC 로 뭉치지 말고 GeometryQuality/MissingAttribute 개별 사유. fixture 를 stable GraphQL 필드로 재고정(deprecated startTime/intermediatePlaces 회피). 캐시키에 mapping/geometryVersion 추가. RoutingFailure 는 first-class(빈목록 흡수 금지).

## 건드는 곳
application/route/routing/(신규), RouteSearchService, RemainingRouteSearchService, OdSayApiPort, OtpRoutingAdapter/OdsayRoutingAdapter(신규), RoutingProviderSelector·LegacyRouteResponseMapper(신규), RoutingCacheKey·RemainingRouteCachePort·RedisRemainingRouteCacheAdapter, TransitIdentityMappingPort(신규)·TransferStopResolver, TransferArrivalEnricher·RouteTimelineComposer(신규), JourneyCompiler·JourneyStartService/TxService·JourneySnapshot, JourneyRerouteProperties·policy-inventory, V49/V50.

## 리스크
- OTP GraphQL 스키마 세부는 실행 이미지로 PR2 재확인(임의 필드 금지).
- P2-D 매핑 오류가 실시간 오염 → 매핑 전 STATIC 안전, 매핑 검증 필수.
- V50 배포순서(reader 선배포) 어기면 활성 여정 손상.

## 검증법
각 PR: `./gradlew compileJava` + 대상 테스트 그린 + 계약당 테스트 최소 1개. 전체: 기존 RouteSearch 회귀 유지(기본 ODSAY), OTP 내부 검색/재탐색 성공, OUT_OF_SCOPE·미지원 정직 응답, 활성 여정 조회/진행/종료 유지. handoff `handoffs/PHASE-2.md`.
