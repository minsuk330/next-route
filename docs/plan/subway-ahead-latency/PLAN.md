# #291 지하철 도착·남은 역 지연 (M 티어, slug: subway-ahead-latency)

## Context
배포 직후 앱팀 실측: 신분당선 도착(#277) 첫 호출 27초, stations-ahead(#283) 첫 호출 40초+ 끊김.
운영 조사(2026-10-07) 결과 **그 지연은 서버 밖**이다:
- Prometheus `http_server_requests_seconds_max`: 두 API 모두 그 시간대 최대 0.5초. VPS CPU ~6%.
- nginx access log 에 40초 끊긴 요청(499)이 없다 → nginx 에 닿기 전에 막힘. 같은 시각 내 SSH 도 timeout, 지금 외부 측정 첫 요청 `tcp=1.14s`(SYN 재전송) → 클라이언트↔VPS 네트워크 구간.
- 지금 외부 측정: 신분당선 도착 0.19~0.23초, stations-ahead 0.34초.

그래도 이슈가 짚은 **구조적 약점 셋**은 실재하므로 고친다:
1. 콜드 캐시 첫 호출이 OTP 상태에 묶인다 — read timeout 15초 × 노선 수(2호선 내선 3개)라 OTP 가 느리면 최대 45초. 서버 상한이 없다.
2. 배포 직후 첫 사용자가 콜드 비용을 문다.
3. 캐시 적중이어도 요청마다 정차→역 매칭을 다시 해 2호선 ~240ms.

## 변경
1. **상한(5초) + 빠른 실패**: `SubwayLinePatternService` 로드에 전체 예산 5초.
   - 포트 `patterns(routeGtfsId, Duration timeout)`. 남은 예산을 read timeout 으로 넘긴다(`OtpRoutingAdapter` 의 예산 패턴과 같음). 예산이 다 떨어지면 예외 → 낡은 값이 있으면 그 값, 없으면 서비스가 503 `SUBWAY_ROUTE_UNAVAILABLE`(기존 경로). `subwayRoutes()` 도 같은 예산 안.
2. **부팅 워밍업**: `SubwayLinePatternWarmup`(`@Profile("!worker")`, `BusStopSnapshotWarmup` 패턴).
   - `ApplicationReadyEvent` 에서 **별도 스레드**로 `SubwayRoutePatternAssembler.LINE_IDS` × 방향(2호선 내/외선, 그 외 상/하행)을 순서대로 데운다.
   - 이벤트 스레드에서 돌리면 readiness 가 늦어져 deploy.sh healthcheck(180초)를 위협한다.
   - 실패는 로그만 남긴다. 키마다 예산 5초. 끝나면 키 수·소요·실패 수를 로그로 남긴다.
3. **매칭 결과 캐시**: `SubwayStationsAheadService` 가 `(lineId, direction)` 별로 `resolve` 결과를 들고 있는다. 원본 `List<DirectedRailPattern>` 참조가 같을 때만 재사용한다 — 패턴 캐시가 갱신되면 자동으로 다시 계산된다.
4. #277 도착 API: 서버 경로는 Redis 캐시만 읽는다(외부 호출 없음, 실측 22ms). 코드 변경 없음, 이슈에 근거로 답한다.

## 파일
- `application/subway/port/out/SubwayRoutePatternPort.java` (timeout 인자)
- `infrastructure/adapter/out/api/otp/OtpSubwayRoutePatternAdapter.java` (요청별 read timeout)
- `application/subway/service/SubwayLinePatternService.java` (예산 5초)
- `application/subway/service/SubwayLinePatternWarmup.java` (신규)
- `application/subway/service/SubwayStationsAheadService.java` (resolve 캐시)
- 테스트: `SubwayLinePatternServiceTest`(예산 초과 → 예외/낡은 값, 남은 예산 전달), `SubwayLinePatternWarmupTest`(전 키 호출, 실패 무시), `SubwayStationsAheadServiceTest`(같은 원본이면 매칭 1회)
- `implementation_note.md`, `docs/plan/subway-ahead-latency/{PLAN,SUMMARY}.md`

## 검증
- 위 테스트 + 기존 subway·arrival·otp 패키지 회귀.
- 배포 후:
  - api 로그 `지하철 패턴 워밍업 완료 keys=… failed=… ms=…`
  - VPS 내부 첫 호출 stations-ahead < 100ms(캐시 적중), 2호선 강남 내선 캐시 적중 < 50ms
  - Prometheus api 힙(워밍업 뒤 증가분 기록, 768MB 한도 대비)

## 배포
마이그레이션 없음. api 이미지 교체. #294(#292) 와 함께 나가도 된다(파일 겹침: `SubwayStationsAheadService` — 나중 머지 쪽이 맞춘다).
