# 합성 모니터링 + 화이트박스 짝 — PLAN

티어 M. 2026-11-26 사용자 결정(대화). 결정 기록: memory project-synthetic-monitoring.

## 목표
사용자가 적어(경로 검색 7일 144건, 5xx 7일 0건) 실사용자 요청만으로는 고장이 드러나지 않는 구멍을 메운다.
세 짝:
1. **합성 모니터링(블랙박스)**: alert-analyzer 컨테이너가 10분마다 "경로 검색 → 첫 경로의 탈것 구간 도착 조회"를 앱과 같은 순서로 직접 호출하고 결과를 Prometheus 숫자로 내놓는다.
2. **5xx 1건 알림(화이트박스)**: 기존 `nr-api-5xx` 를 "5분 3건" → "1건"으로, uri 를 표시.
3. **외부 API 실패 알림**: OTP 외 외부 호출(공공데이터 버스, ML 예측 서버, 서울 지하철 등) 실패 규칙 신설.

## 확정된 결정 (사용자)
| # | 결정 |
|---|---|
| D1 | 시나리오형. OD 두 쌍: SUBWAY(지하철 위주), BUS(버스 위주). 검색 → 시나리오에 맞는 첫 탈것 구간 → 앱이 부르는 도착 API |
| D2 | 주기 10분. 운행 시간 05:30~24:00 KST 만 호출. 밤엔 호출 안 함(하트비트만) |
| D3 | 실패 3층: ① HTTP 비 2xx·시간초과 = critical ② 200 인데 쓸 수 없음(경로 0개, 운행 시간인데 탈것 구간 없음, 도착 응답이 배열 아님) = warning ③ 느림(검색 10초+) = 숫자만 기록, 주간 요약. 도착 "정보 없음"(빈 배열 포함)은 정상. 검색 실패 시 도착 = skipped(실패 아님) |
| D4 | 실패(①②)면 60초 뒤 시나리오 전체 1회 재시도, 재시도 결과를 게시 |
| D5 | 호출 주체 = alert-analyzer 컨테이너(별도 asyncio 작업). 분석 작업과 독립 |

## 이 플랜에서 정한 것 (근거)
- **호출 URL = 공개 도메인 `https://api.refit-100.site`** (앱 `EXPO_PUBLIC_API_BASE_URL` 과 같음). nginx-proxy·TLS 인증서까지 사용자와 같은 길. 분석기는 `observability_default` 네트워크만 속해 api 내부 주소로는 못 감(VPS `docker inspect` 11-26). env `ANALYZER_PROBE_BASE_URL` 로 바꿀 수 있게.
- **모니터링 요청 표시 = 헤더 `X-Nextroute-Synthetic: 1`** + User-Agent `nextroute-synthetic/1`. api 가 헤더를 보면 (a) `route_search_event` 기록 생략(익명 검색도 기록돼 퍼널이 망가짐, RouteSearchEventRecorder 주석) (b) `http.server.requests` 에 태그 `synthetic="true"`(그 외 "false"). 헤더 위조는 통계에서 빠질 뿐 권한 영향 없음.
- **/metrics 는 손으로 쓴 Prometheus 텍스트**. prometheus_client 의존성 추가 안 함(게이지 몇 개뿐, 직접 구현 비용 < 의존성·이미지 증가). 구현 노트에 한 줄.
- 검색 결과 스냅샷(RouteSearchSnapshotRecorder.attach)은 그대로 둠: 하루 약 220건, 검색 응답 계약(searchSessionId) 유지가 우선.

## 메트릭 계약 (alert-analyzer `GET /metrics`)
```
# outcome: 0=ok 1=degraded(②) 2=failed(①) 3=skipped
synthetic_probe_outcome{scenario="subway|bus", step="search|arrival", reason="..."} <0..3>   # reason 라벨(구현 때 변경: Slack 요약에 사유 표시)
synthetic_probe_duration_seconds{scenario, step} <float>     # 마지막 시도
synthetic_probe_last_run_timestamp_seconds{scenario} <unix>   # 마지막 시나리오 완료
synthetic_probe_heartbeat_timestamp_seconds <unix>           # 루프가 깨어날 때마다(밤 포함)
synthetic_probe_active <0|1>                                  # 운행 시간 여부
synthetic_probe_runs_total{scenario, outcome} / synthetic_probe_slow_total{scenario}   # 주간 요약용 카운터
```
기동 직후 첫 실행 전에는 outcome 시리즈 없음 → 규칙 noDataState 는 아래대로.

## 작업 순서

### PR 하나(브랜치 `feat/synthetic-monitoring`, fresh main). 배포는 api → 분석기·관측 순.

- [ ] **1. api: 합성 요청 식별** (선행. 이게 운영에 있어야 2 를 켤 수 있음)
  - 신규 `src/main/java/watoo/grd/nextroute/common/observability/SyntheticRequest.java`
    - `static final String HEADER = "X-Nextroute-Synthetic";`
    - `static boolean isSynthetic(HttpServletRequest r)` → 헤더 값 "1"
  - 신규 `src/main/java/watoo/grd/nextroute/common/config/SyntheticAwareServerRequestObservationConvention.java`
    - `extends DefaultServerRequestObservationConvention`, `getLowCardinalityKeyValues` 에 `synthetic=true|false` 추가. `@Component` 또는 설정 빈(Spring Boot 가 ServerRequestObservationConvention 빈을 집어 감). 기존 클라이언트 쪽 예: `common/config/ExternalApiObservationConvention.java:30`
  - `RouteSearchController.java:45,57`: `HttpServletRequest` 파라미터 추가, 합성이면 `searchEventRecorder.ifAvailable(...)` 건너뜀
  - 테스트: 컨트롤러 슬라이스 또는 기존 RouteSearchController 테스트에 "헤더 있으면 recorder 호출 0" / "없으면 1". 컨벤션 단위 테스트: 헤더 유무로 태그 값
  - 검증: `./gradlew compileJava test --tests '*RouteSearchController*' --tests '*SyntheticAware*'`

- [ ] **2. alert-analyzer: 호출기**
  - 신규 `alert-analyzer/analyzer/probe.py`
    ```python
    @dataclass(frozen=True)
    class Scenario: name: str; start: tuple[float,float]; end: tuple[float,float]  # (lon, lat)
                    start_name: str; end_name: str; kind: Literal["subway","bus"]
    @dataclass
    class StepResult: outcome: int; duration_s: float; reason: str | None
    async def run_scenario(http, base_url, sc) -> dict[str, StepResult]   # {"search":..., "arrival":...}
    async def probe_loop(http, settings, state, clock, sleep)            # 10분 주기, 운행 시간, 실패 시 60초 뒤 1회 재시도
    def render_metrics(state, now) -> str                                # Prometheus 텍스트
    def in_service_hours(now_kst) -> bool                                # 05:30 <= t < 24:00
    ```
    - 검색: `GET {base}/api/route/search?startX={lon}&startY={lat}&endX&endY&startName&endName` (RouteSearchRequest: X=경도, Y=위도). timeout 15s. 헤더 `X-Nextroute-Synthetic: 1`, UA `nextroute-synthetic/1`
      - 비 2xx·timeout·연결 실패 → 2, reason `http_<code>`/`timeout`/`connect`
      - `paths` 비었거나 없음 → 1 `no_paths`
      - 시나리오 kind 의 구간(trafficType 1=지하철, 2=버스)을 가진 path 가 없음 → 1 `no_<kind>_segment`
      - 소요 ≥10s 면 outcome 은 그대로, duration 만 기록(③)
    - 도착(검색 outcome 0 일 때만, 아니면 3):
      - bus: 앱 `nextroute-mobile/src/features/transit/route-live-arrivals.ts:15-30` 과 같은 규칙. `stopId = segment.startLocalStationID`, `routeId = transferArrivals[laneIndex==0].routeId ?? lanes[0].busLocalBlID` → `GET /api/arrivals/bus/{stopId}/{routeId}`
      - subway: 같은 파일 `:102-123`. `wayCode` 필수, 좌표 = segment.startY/startX → passStopList 첫 역 → 직전 구간 끝, `line_id = 1000+lanes[0].subwayCode`(1~9 일 때만) → `GET /api/arrivals/subway?lat&lon&wayCode[&line_id]`
      - 필요한 값이 없어 요청을 못 만듦 → 1 `no_<kind>_target` (검색 계약이 깨진 신호, #627 류)
      - 비 2xx·timeout → 2. 200 인데 JSON 배열 아님 → 1 `bad_body`. 빈 배열 = 0
    - 재시도: 두 step 중 하나라도 1|2 면 60초 뒤 시나리오 전체 재실행, 그 결과만 게시. 재시도 전 결과는 로그(INFO)만
    - 시나리오 둘은 순서대로(동시 X, OTP 부하)
  - `config.py`: `probe_enabled: bool = False`(ANALYZER_PROBE_ENABLED), `probe_base_url = "https://api.refit-100.site"`(ANALYZER_PROBE_BASE_URL), `probe_interval_s = 600`
  - `app.py:42` lifespan: `probe_enabled` 면 `asyncio.create_task(probe_loop(...))` (`:68` worker 와 같은 방식, 종료 시 cancel). `GET /metrics` 추가(`:116` healthz 옆, 인증 없음 — 같은 내부 네트워크의 Prometheus 만 접근, 포트 미공개)
  - OD 두 쌍: 후보 SUBWAY 서울역(126.9707, 37.5547) → 강남역(127.0276, 37.4979), BUS 신사역(127.0201, 37.5163) → 소공동(126.9800, 37.5640)(앱 시뮬레이터 `scripts/navigation-simulator/reroute-mock.mjs:24` 실응답에 버스 4212). **구현 중 운영 API 에 헤더 붙여 낮·저녁 각 1회 검색해 kind 구간이 첫 3개 path 안에 나오는지 확인 후 확정**, 결과를 구현 노트에
  - 테스트 `alert-analyzer/tests/test_probe.py` (httpx MockTransport):
    - 정상 → search 0, arrival 0 / 검색 503 → search 2, arrival 3 / 경로 0개 → 1 / kind 구간 없음 → 1 / 도착 빈 배열 → 0 / 도착 500 → 2 / 도착 대상 못 만듦 → 1
    - 재시도: 1차 실패 2차 성공 → 게시 0, sleep(60) 호출 확인 / 둘 다 실패 → 게시 실패
    - 운행 시간 경계: 05:29 X, 05:30 O, 23:59 O, 00:00 X (KST)
    - render_metrics 텍스트 형식, 헤더 `X-Nextroute-Synthetic: 1` 이 모든 요청에 붙음
    - 기존 test_app: probe_enabled 기본 false → 기존 테스트 영향 없음, /metrics 200
  - 검증: `cd alert-analyzer && uv run --extra dev pytest -q`

- [ ] **3. Prometheus 스크랩**: `ops/observability/prometheus/prometheus.yml` 에 job `synthetic` → `alert-analyzer:8000`, `metrics_path: /metrics`, `scrape_interval: 30s` (분석기·Prometheus 둘 다 observability_default)

- [ ] **4. Grafana 규칙** `ops/observability/grafana/provisioning/alerting/rules.yaml`
  - 신규 `nr-synthetic-failed` 합성 확인 실패 (critical): `max by (scenario, step) (synthetic_probe_outcome) == 2` 와 `synthetic_probe_active == 1`, for 0s(재시도는 호출기가 함), noDataState OK. summary 에 scenario·step, description 에 reason 메트릭 확인법
  - 신규 `nr-synthetic-degraded` 합성 확인 결과 이상 (warning): `== 1`, 나머지 같음
  - 신규 `nr-synthetic-stalled` 합성 확인 멈춤 (critical): `time() - synthetic_probe_heartbeat_timestamp_seconds > 1500`, for 0s, **noDataState Alerting**(분석기 사망·스크랩 실패). 운행 시간 중이면 추가로 `time() - synthetic_probe_last_run_timestamp_seconds > 1800` 도 같은 규칙에서(OR)
  - 수정 `nr-api-5xx`: `sum by (instance, uri) (increase(...{status=~"5.."}[5m]))` > 0.5 (1건), summary `'{{ .Labels.uri }} 5xx {{ … }}건 (5분)'`. title 유지
  - 신규 `nr-external-api-failure` 외부 API 호출 실패 (warning): `sum by (client_name) (increase(http_client_requests_seconds_count{outcome!="SUCCESS", client_name!="otp"}[10m])) > 2.5`, for 0s, noDataState OK. **작성 전 7일 `increase(...[10m])` 최대값으로 하루 울림 횟수 추정, 하루 1회 넘게 울릴 기준이면 문턱 올리고 근거를 주석에**
  - promtool/grafana 형식: 기존 규칙 블록 복사 형태 유지, `$` 금지(.Labels/.Values)

- [ ] **5. 합성 트래픽을 통계에서 제외** (`synthetic!="true"` — 태그 없는 옛 시리즈도 매치)
  - `alert-analyzer/analyzer/weekly.py:71-75` API 선택자에 추가
  - `ops/observability/grafana/dashboards/usage.json:322,562`, `spring.json:21,37,53` 의 http_server_requests 선택자
  - weekly 에 한 줄 섹션: 지난주 합성 검색 10초 이상 횟수와 failed/degraded 횟수(`synthetic_probe_duration_seconds` 는 마지막 값만이라, 호출기가 카운터 `synthetic_probe_slow_total{scenario}`·`synthetic_probe_runs_total{scenario,outcome}` 도 내놓도록 2 에서 같이 추가)

- [ ] **6. 문서·노트**: `ops/observability/README.md` 알림 표에 규칙 4개(신규 3 + 외부 1)·5xx 변경, `implementation_note.md` 확정/함정/검증

## 배포 (분류기가 막으면 명령을 사용자에게)
1. 진행 중 여정 0 확인(feedback-check-live-testing-before-deploy) → api 배포(VPS deploy.sh). 확인: 헤더 붙인 검색 1회 후 `route_search_event` 행 증가 0, `http_server_requests_seconds_count{synthetic="true"}` 생김
2. main 머지 → GHCR 분석기 이미지 → VPS `env/alert-analyzer.env` 에 `ANALYZER_PROBE_ENABLED=true` → 분석기만 재생성
3. `ops/prod-sync.sh` 로 prometheus.yml·rules.yaml·대시보드 반영, Prometheus reload, Grafana provisioning 재로드
4. 확인: Prometheus target `synthetic` up, 10분 안에 outcome 시리즈 4개, Grafana 규칙 Normal

## 검증 (완료 조건)
- `./gradlew compileJava test` 관련 테스트 통과, `uv run --extra dev pytest -q` 통과
- 운영에서 실물: (a) 정상 시 outcome 전부 0 (b) **고장 주입 1회**: `ANALYZER_PROBE_BASE_URL` 을 존재하지 않는 경로로 잠시 바꿔(또는 테스트용 env 로 잘못된 OD) failed 알림이 Slack 에 1번, 원복 후 RESOLVED (c) 분석기 정지 25분 → stalled 알림 (b·c 는 낮 시간, 사용자에게 알리고)
- 퍼널: 하루 뒤 `route_search_event` 에 합성 검색 0행

## 리스크
- OTP 부하: 하루 약 220검색(+재시도). 검색 평균 3~4초, 힙 3.3/3.5G. 첫 이틀 OTP GC·검색 p95 관찰
- 외부 API 한도: 도착 조회 하루 약 220회 + 검색 내부 보강 호출. 경기 검색 몫 2,000/일, 버스 Redis 15초 캐시 공유. BUS 시나리오를 서울 정류장으로 고르면 경기·TAGO 몫 안 씀
- 외부 API 실패 규칙 소음: 7일 data.go.kr 61건 — 문턱을 실측으로 정함(4)

## 구현 중 바뀐 것 (2026-11-26, 근거는 implementation_note.md)
- 실패 사유는 outcome 의 reason 라벨. 합성 규칙은 `within_range`(2 / 1)로 판정, active 조건 없음(밤엔 결과를 비움).
- stalled 규칙: A 하트비트 나이, B 운행 시간 마지막 실행 나이(`or vector(0)`), math `$A > 1800 || $B > 1800`, for 5m, NoData=Alerting.
- nr-api-5xx: 새로 생긴 5xx 카운터의 첫 값을 `increase()` 가 안 세는 문제로 `or (현재 unless 5분 전)` 추가.
- 외부 API 실패: 실패 3건 초과 그리고 실패율 5% 초과.
- 주간 퍼널 검색 수를 `http_server_requests_seconds_count`(synthetic 제외)로 교체.
