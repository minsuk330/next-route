# 장애 알림 AI 분석기 (alert-analyzer) — PLAN

- 티어: **L** (새 서비스 + Grafana→분석기 API 계약 신설 + PR 3개)
- 작성: 2026-10-26. 사용자 승인 플랜 = `~/.claude/plans/luminous-sprouting-glacier.md`. 같은 날 Opus 독립 검토 반영(§0.1)
- 선행: 1~2단계 **PR #526**(Grafana 알림 → Slack, VPS 라이브 2026-10-26 15:31). PR-1 의 webhook 연동은 #526 머지가 전제
- 사용자 확정 결정: 모델 **Claude Sonnet 5.5**(`claude-sonnet-5-5`), Slack 표시 **알림 스레드 답글**

## 0. 착수 전에 읽을 것

| 무엇 | 어디 |
|---|---|
| 1~2단계 알림 규칙·정책·Slack 연결 | `ops/observability/grafana/provisioning/alerting/{contact-points,policies,rules}.yaml` (PR #526) |
| 관측 스택 구조, 신뢰 경계, 반영 절차 | `ops/observability/README.md` ("장애 알림" 절, "운영 주의" 신뢰 경계) |
| Python 프로젝트 형식(uv, pyproject, pytest) | `ml/pyproject.toml`, `ml/Dockerfile`, `ml/serve/app.py:320-330`, `ml/serve/test_app.py:1-5` |
| 이미지 CI | `.github/workflows/ml-image.yml` (GHCR, `latest` + sha 태그, main push 에만) |
| Alloy 로그 라벨 | `ops/observability/alloy/config.alloy:1-40` (라벨 `app`/`env`/`level`, JSON `level` 필드 추출) |
| 키가 URL 경로에 찍히는 곳 | `src/main/java/.../subway/SeoulSubwayApiAdapter.java:447,454` (`uri.getPath()` = `/api/subway/{KEY}/json/...`) |
| Loki/Prometheus 보관 | 둘 다 30일 |
| Claude API 사용법 | **`llm.py` 쓰기 전에 `claude-api` 스킬 로드**. SDK 바인딩은 추측 금지 |
| 구현 노트 규칙 | 루트 `CLAUDE.md` — 발견 즉시 `implementation_note.md` append-only |

### 0.1 Opus 독립 검토 반영 (2026-10-26)

| 지적 | 판정 | 반영 |
|---|---|---|
| B1 서울 API 키가 URL **경로**에 있음 → 마스킹 누락 | **사실 확인**: 지난 7일 Loki 200줄 중 112줄에 `SEOUL_SUBWAY_ARRIVAL` 값 그대로 | 마스킹에 경로 세그먼트 규칙, fixture 커밋 전 VPS env 값 대조 스크립트 |
| B2 Slack 출력 미이스케이프(`<!channel>` 등) | 수용 | PR-2: `& < >` 이스케이프, `unfurl_links/media=false`, 테스트 |
| B3 `slack.default.text` 는 Alertmanager 이름 | 수용 | `{{ template "default.message" . }}`, 반영 전 미리보기로 렌더 확인 |
| M1 재시작 후 반복 알림 재분석, 자기 알림 루프 | 수용 | 알림 나이 > 10분 skip, `DatasourceError`/`DatasourceNoData`/`app="alert-analyzer"` skip |
| M2 잘린 표본으로 NEW/SPIKE 판정, 메모리 | 수용(일부) | 상태는 **로거 단위 정확 집계**로만. 템플릿은 `message` 만 뽑아 작게, 기준선은 "본 적 있나" 불리언 |
| M3 스키마 모순(none/unknown 가설은 근거 없어 버려짐) | 수용 | Category 에서 none/unknown 제거, `unknown = not hypotheses`, `deploy_related` 는 코드가 계산 |
| M4 평가가 LLM 기여를 못 보여줌 | 수용 | 규칙 기반 비교선, 프롬프트 동결 후 채점, must-cite, 검증 전 무효 근거율, 교란 케이스, 합성 알림 명시 |
| M5 Slack `conversations.history` 제한 | 해당 없음(내부 앱) | `limit=15` 로 맞춰 둠 |
| 프롬프트 캐싱 무의미(드문 호출) | 수용 | 캐싱 제거 |
| slack_sdk → httpx POST 2개, Block Kit → mrkdwn 한 덩어리 | 수용 | 의존성 축소 |
| 배포를 PR-1 로 당겨 DRY_RUN 운영 | 수용 | 임시 수신기 불필요, PR-2 전 실측(RSS, 지연, 실 payload) |
| webhook 연동 `disableResolveMessage: true`, 오래된 작업 drop | 수용 | 반영 |

## 1. 목표

알림이 울리고 1분 안에 같은 Slack 스레드에 "원인 가설 + 근거 로그 + Grafana 링크"를 단다. 알림 경로(1~2단계)는 건드리지 않는다. 분석기가 죽어도 알림은 그대로 간다.

예: 10-23 19:00 worker ERROR 21건/1분(`SeoulSubwayApiAdapter` "Subway arrival API failed after 3 retries … 503", 노선 11개) → 스레드 답글 "외부 API(서울 지하철 도착) 장애 가능성 높음 / 근거: 새 ERROR 로거 2개 각 5·11건 / 다음 확인: 같은 시각 다른 노선, 공공 API 공지".

## 2. 실측 전제 (2026-10-26 VPS)

| 사실 | 값 | 설계 결과 |
|---|---|---|
| 메모리 | available 1.7G, 14일 최저 255MiB. OTP 힙 3.1~3.3G / Xmx 3.5G | `mem_limit` 192m(로컬 idle RSS 40~46MiB 실측) + `oom_score_adj: 500`. 로컬 모델·벡터 DB·무거운 프레임워크 금지 |
| WARN 양 | worker WARN 하루 21,895건, 99% 가 `SubwayTrainTransitionTracker` 한 템플릿 | 원문 대신 로거 집계 + 작은 표본 |
| ERROR 양 | 평시 하루 1건 | ERROR 로거는 전부 읽음 |
| 로그 JSON 필드 | `@timestamp`, `message`, `logger_name`, `thread_name`, `level`, `level_value`, `traceId`, `spanId` (+`stack_trace`) | |
| 비밀값 | **서울 지하철 도착 키가 로그 경로에 그대로**(112/200줄) | 경로 세그먼트 마스킹 + env 값 대조 |
| Loki 로거 집계 | 9ms | 상태 판정의 원천 |
| 재시작 감지 | `process_start_time_seconds` 전부 존재 | 코드가 `deploy_related` 계산 |
| 네트워크 | Grafana·Loki·Tempo = `observability_default` 만 | 분석기도 거기(포트 비공개) |
| Grafana webhook 실물 | 캡처 완료 `alert-analyzer/fixtures/grafana_webhook_firing.json`. `startsAt` 나노초, `values` 는 refId 키(`{"B":22,"C":1}`), Bearer 헤더 전달 확인 | |

## 3. 구조

```
Grafana 알림 ─┬─ slack 연동(기존 웹훅) ─────────────→ #채널 [FIRING] (+ PR-3: "fp: <fingerprint…>")
(contact point│
 slack-alerts)└─ webhook 연동(PR-1, resolve 안 보냄) ─ Bearer ─→ alert-analyzer:8000/webhook/grafana
                    ① 인증 → 분석할 alert 만 큐에 넣고 202
                    ② (워커 1개) 10분 넘은 작업 버림 → 맥락 수집: 로거 집계, 템플릿 표본, 메트릭, 재시작
                    ③ 마스킹
                    ④ DRY_RUN 이면 요약 로그로 끝 (PR-1 운영 상태)
                    ⑤ (PR-2) Sonnet 5.5 구조화 출력 → 근거 검증
                    ⑥ (PR-2) Slack: history 에서 fp 찾기 → 스레드 답글(없으면 별도 메시지)
```

## 4. 계약 (경계 리뷰 대상)

### 4.1 Grafana → 분석기
- `POST /webhook/grafana`, `Authorization: Bearer <ANALYZER_WEBHOOK_TOKEN>`. 불일치 401, 파싱 실패 400, 정상 202 `{"queued": n, "skipped": m}`
- 사용 필드: `status`, `groupKey`, `externalURL`, `alerts[].status|labels|annotations|startsAt|fingerprint|generatorURL|values`. 나머지 무시. `startsAt` 나노초는 마이크로초로 자름
- 분석 대상 규칙(`select_new`)
  - 최상위 `status != "firing"` → 전부 skip
  - alert `status != "firing"` → skip
  - `alertname` 이 `DatasourceError`/`DatasourceNoData` → skip (원천이 고장 — 읽어 봐야 소용없음)
  - `labels.app == "alert-analyzer"` → skip (자기 ERROR 를 자기가 분석하는 루프 방지)
  - `now - startsAt > 10분` → skip (재시작으로 기억을 잃은 뒤 1시간 반복 알림이 와도 재분석 안 함)
  - 중복 키 `fingerprint|startsAt` 을 TTL 24h 메모리 맵으로 거름(그룹 재전송 대비)
- 큐가 차면 그 alert 들의 중복 키를 되돌린다(다음 재전송이 다시 시도할 수 있게)

### 4.2 Slack 알림 메시지 ↔ 분석기 (PR-2/PR-3)
- Grafana slack 연동 `text` = `{{ template "default.message" . }}` + 줄바꿈 + `fp: {{ range .Alerts }}{{ .Fingerprint }} {{ end }}`
  - `$` 금지(provisioning env 치환). 반영 전 Grafana 템플릿 미리보기/테스트 API 로 렌더 확인, 이전 파일 tar 백업
- 분석기: `conversations.history(channel, oldest=min(startsAt)-120s, limit=15)` → 메시지 텍스트(+attachments)에 fingerprint 포함 → `chat.postMessage(thread_ts=…, unfurl_links=false, unfurl_media=false)`. 못 찾으면 10초 간격 3회 → 별도 메시지
- 봇 권한 `chat:write`, `channels:history`, `groups:history`. 내부 앱(배포 안 함)이라 history 일반 한도
- 출력은 mrkdwn 텍스트 한 덩어리 ≤ 3,000자. 로그·LLM 에서 온 모든 문자열은 `& < >` 이스케이프

### 4.3 LLM 출력 (PR-2, Pydantic)
```python
Category = Literal["deploy_restart", "external_api", "resource_exhaustion", "app_bug", "infra"]

class Hypothesis(BaseModel):
    cause: str
    category: Category
    confidence: Literal["high", "medium", "low"]
    evidence: list[str]      # 입력 id 만: "t:<8hex>" / "m:<key>" / "m:restarts"
    next_check: str

class Analysis(BaseModel):  # LLM 이 채우는 부분
    headline: str
    hypotheses: list[Hypothesis]
```
- 코드가 붙이는 것: `deploy_related = bool(bundle.restarts)`, `unknown = not hypotheses`(검증 후)
- **근거 검증**: 없는 id 제거 → 근거 0개 가설 버림 → 최대 3개. 검증 전 무효 근거 비율을 기록(평가 지표)

## 5. 디렉터리와 시그니처

```
alert-analyzer/
  pyproject.toml          # deps: fastapi, uvicorn, pydantic>=2, httpx  (PR-2: + anthropic>=1)
  uv.lock
  Dockerfile              # PR-1
  analyzer/
    config.py  logging_json.py  payload.py  dedupe.py  sources.py(Loki/Prom)
    context.py  masking.py  worker.py  app.py
    llm.py  slack.py  prompts/system.md          # PR-2
  scripts/
    capture_fixture.py    # 과거 시각으로 gather → 마스킹 bundle JSON (stdout)
    check_no_secrets.py   # env 파일 값(12자+)이 대상 파일에 있으면 실패. 값은 출력 안 함
  fixtures/
    grafana_webhook_firing.json
    incidents/<id>.json
  eval/  cases.yaml  run_eval.py                 # PR-2
  tests/ …
```

핵심 타입(PR-1 구현 기준):
```python
@dataclass(frozen=True)
class Settings:  # env: ANALYZER_WEBHOOK_TOKEN(필수), ANALYZER_LOKI_URL, ANALYZER_PROM_URL,
                 #      ANALYZER_GRAFANA_URL, ANALYZER_DRY_RUN(기본 true), ANALYZER_DAILY_LIMIT(30), ANALYZER_QUEUE_MAX(20)
    webhook_token: str; loki_url: str; prom_url: str; grafana_url: str
    dry_run: bool = True; daily_limit: int = 30; queue_max: int = 20
    # PR-1: dry_run=false 면 기동 거부. PR-2: false 면 ANTHROPIC_API_KEY, SLACK_BOT_TOKEN, SLACK_CHANNEL_ID 필수

@dataclass
class TemplateStat:
    id: str                  # "t:" + sha1(app|logger|template)[:8]
    app: str; level: str; logger: str; template: str
    status: Literal["NEW", "SPIKE", "NORMAL"]   # 로거 단위 정확 집계로 판정
    logger_total: int        # 창 안 로거 전체 건수(정확)
    logger_baseline: int     # 직전 60분 로거 전체 건수(정확)
    sample_count: int        # 창 표본(≤100줄) 중 이 템플릿 줄 수
    sample_capped: bool      # 창 표본이 상한에 걸렸나(그러면 sample_count 는 하한)
    baseline_seen: bool | None  # 기준선 표본에 이 템플릿이 있었나. 표본이 잘렸고 못 찾았으면 None(모름)
    samples: list[str]       # 최대 2줄(message 500자 + stack 15줄)
    trace_ids: list[str]
```

수집 규칙:
- 창 `W = [min(startsAt)-10m, max(startsAt)+1m]`, 기준선 60분
- 1단계(정확): `sum by (app, level, logger_name) (count_over_time({app=~"api|worker", level=~"WARN|ERROR"} | json logger_name [Wm]))` at W.end, 같은 쿼리 `[60m]` at W.start → 로거별 `classify`(baseline 0 → NEW, window ≥5 이고 분당 비율 ≥ 3배 → SPIKE)
- 2단계(작게): ERROR 로거 + NEW/SPIKE 로거, 최대 8개
  - 창 메시지: `{app,level} | json logger_name, message | logger_name="X" | line_format "{{.message}}"` limit 100
  - 기준선 메시지: 같은 쿼리 기준선 구간 limit 300 → 템플릿 집합
  - 표본: `{app,level} | json logger_name | logger_name="X"` limit 2 (원문 JSON, stack·traceId 포함)
- 나머지 로거는 `quiet_summary` 한 줄씩(최대 5)
- OTP(`app="otp"`, 평문): `|~ "ERROR|Exception"` limit 50 → 템플릿 1종 + 기준선 개수(metric)
- Prometheus(instant, time=min(startsAt) 와 30분 전): 메모리, `up`, 5xx 10분, HTTP 클라이언트 실패 10분(client_name·status), 힙. 재시작 = `process_start_time_seconds` at W.end 가 `[min(startsAt)-15m, W.end]`
- 소스별 실패는 `collection_errors` 에 기록하고 계속. 직렬화 40,000자 상한(quiet → 표본 → 하위 템플릿 순으로 자름, `truncated` 기록)

마스킹(`masking.py`, 순서대로, 규칙마다 테스트):
| 이름 | 대상 → 치환 |
|---|---|
| slack | `xox?-…`, `xapp-…`, `hooks.slack.com/services/…` → `<slack-secret>` |
| anthropic | `sk-ant-…` → `<anthropic-key>` |
| jwt | `eyJ….….…` → `<jwt>` |
| bearer | `Bearer …` → `Bearer <redacted>` |
| api_key | `(?i)(servicekey|apikey|api_key|key|token|secret|password)=값(6자+)` → `\1=<redacted>` |
| **url_path_secret** | `/` 와 `/`·끝 사이 영숫자 16자+ 세그먼트 → `/<redacted>` (서울 열린데이터 `/api/subway/{KEY}/…`, `:8088/{KEY}/…`) |
| toss_key | `tossUserKey[=:]값` → `tossUserKey=<redacted>` |
| email / phone | → `<email>` / `<phone>` |
| coord | 한국 위경도 소수 4자리+ → 2자리 |
| uuid | → `<uuid>` |

## 6. PR-1 수집기 + DRY_RUN 배포 — 브랜치 `feat/alert-analyzer-collector`

- [x] 1. 실 payload 확보: Grafana receivers test API → 임시 수신 컨테이너(정리 완료) → `fixtures/grafana_webhook_firing.json`
- [x] 2. 골격·config·logging_json·payload·dedupe·sources·context·masking·worker·app + 테스트 (75개)
- [x] 3. `scripts/capture_fixture.py`, `scripts/check_no_secrets.py`
- [x] 4. `Dockerfile`(python:3.12-slim + uv, `CMD uvicorn analyzer.app:app --port 8000`), `.github/workflows/alert-analyzer-image.yml`(ml-image 복제 + pytest 단계, PR 에서는 테스트만)
- [x] 5. `ops/observability/compose.yaml` 서비스(포트 없음, `env/alert-analyzer.env`, mem_limit, oom_score_adj, healthcheck, logrotate), `env/alert-analyzer.env.example`
- [ ] 6. (**#526 머지 후**) `contact-points.yaml`: webhook 연동 추가(`disableResolveMessage: true`, Bearer `$ANALYZER_WEBHOOK_TOKEN`). slack 연동 text 는 **안 건드림**(PR-3)
- [x] 7. README: 분석기 절 + 신뢰 경계 예외 문구
- [ ] 8. 머지 → CI 이미지 → VPS 반영(백업 → compose 블록만 삽입 → env 작성 → 분석기 기동 → idle RSS 측정 → provisioning 반영 + grafana `--no-deps` 재생성 → receivers test 로 실 수신 로그 확인)
- [x] 9. **fixture 5건 캡처 완료(2026-10-26, 배포 전이라 VPS 임시 컨테이너로)**: 원래 계획은 운영 컨테이너에서 `python -m scripts.capture_fixture` → `check_no_secrets.py` 로 VPS env 값 대조 0건 확인 → 커밋(후속 PR 또는 PR-2)
  | id | 시각(KST) | 기대 |
  |---|---|---|
  | id | 시각(KST) | 규칙을 실제로 넘었나 | 기대 |
  |---|---|---|---|
  | `subway-api-503` | 2026-10-23 19:01 | 넘음(worker ERROR 12건/5분, 19:01~19:06) | external_api |
  | `api-5xx-deploy` | 2026-10-20 12:23 | 넘음(5xx 5.3건, 12:23~12:27). api·worker·**otp** 재시작 | deploy_restart |
  | `otp-fail-deploy` | 2026-10-20 12:24 | 넘음(10.7건/10분, 12:24~12:32). 위와 같은 배포 — 상관된 케이스 | deploy_restart |
  | `memory-low-1021` | 2026-10-21 19:49 | **안 넘음**(300MiB 미만 1분뿐, 규칙은 5분 지속) | unknown |
  | `quiet` | 2026-10-25 14:00 | 안 넘음(거짓 알림 합성) | 가설 없음 |
  - ~~`otp-fail-deploy` 10-22 17:57~~ 틀림: OTP 실패 폭주는 10-20 12:24 배포 때였다
  - 이 장애들은 알림 규칙보다 먼저 났다 → 알림은 합성. 각 건이 실제 규칙을 넘었을지 캡처 때 메트릭으로 확인해 기록
- [ ] 10. 노트 기록 + PR

검증: `uv run --extra dev pytest -q` 전부 통과 / VPS: 분석기 healthy·idle RSS 기록·webhook 수신 로그·Grafana webhook 오류 없음 / fixture 비밀값 대조 0건

## 7. PR-2 분석과 Slack — 브랜치 `feat/alert-analyzer-llm-slack`

- [ ] 1. `claude-api` 스킬 로드 → Python SDK 1.x `messages.parse`, `output_config.effort`, refusal fallback(`fallbacks: "default"` + `server-side-fallback-2026-07-01`), 타임아웃·재시도, 비동기 클라이언트 확인. 프롬프트 캐싱은 쓰지 않음
- [ ] 2. `prompts/system.md` (입력 id 만 근거, 근거 없으면 가설 비움, 한국어, 개인정보 재출력 금지, 최대 3개). **평가 전에 동결**
- [ ] 3. `llm.py`: Sonnet 5.5, effort `medium`, `max_tokens` 16000, timeout 60s, 재시도 2, refusal → 실패. 검증 전 무효 근거 수 기록
- [ ] 4. `slack.py`: httpx 로 `conversations.history`·`chat.postMessage` 직접 호출, mrkdwn ≤3,000자, 이스케이프, unfurl 끔
- [ ] 5. worker: dry_run=false 일 때만 LLM·Slack. 실패는 WARN 로그 + 스레드에 "AI 분석 실패: 사유" 한 줄. 하루 상한 초과 1회 안내
- [ ] 6. 테스트: 근거 검증, refusal·타임아웃, 스레드 찾기 3경우, 이스케이프(`<!channel>`→`&lt;!channel&gt;`), 길이 자르기, Settings 필수값
- [ ] 7. `eval/cases.yaml`(기대 category, must-cite 템플릿 id, 정답 한 줄) + `eval/run_eval.py`(실 API, 예상 비용 출력, `--yes` 필수). 출력: LLM 1순위 적중 / **규칙 비교선**(재시작→deploy, client_fail>0→external_api, 그 외 unknown) 적중 / must-cite 적중 / 무효 근거율 / 토큰·비용·지연. 교란 케이스(배포+실제 503 동시)는 실사례 없으면 합성하고 합성이라 표기
- [ ] 8. 사용자 승인 후 평가 실행(약 $0.5). 결과를 노트에. 비교선 대비 LLM 이 낫지 않으면 그대로 기록하고 사용자와 진행 여부 결정
- [ ] 9. 노트 + PR

## 8. PR-3 켜기 — 브랜치 `feat/alert-analyzer-enable`

- [ ] 1. 사용자 작업 완료 확인(§11)
- [ ] 2. `contact-points.yaml` slack 연동 text 에 fp 줄 추가(§4.2). 반영 전 렌더 확인, 이전 파일 백업
- [ ] 3. VPS env: `ANALYZER_DRY_RUN=false` + 키들 → 분석기만 재생성 → 임시 규칙 `vector(42)` 로 "알림 → 스레드 답글" 실물 확인 → `deleteRules`
- [ ] 4. 노트 + PR

## 9. 실패 경로 (경계 리뷰 대상)

| 실패 | 동작 | 테스트 |
|---|---|---|
| 분석기 다운 | webhook 연동만 실패, slack 연동은 발송 | PR-1 배포 때 수동 확인 |
| 인증 실패 / 파싱 실패 | 401 / 400 | PR-1 |
| 오래된·자기·datasource 알림 | skip | PR-1 |
| 큐 가득 | 202 + skipped, 중복 키 되돌림 | PR-1 |
| 꺼낸 작업이 10분 넘음 | 버리고 INFO 로그 | PR-1 |
| Loki/Prom 실패 | `collection_errors` 기록, 계속 | PR-1 |
| LLM 타임아웃·오류·refusal | WARN 로그 + 스레드 "AI 분석 실패" | PR-2 |
| 스레드 못 찾음 | 3회 후 별도 메시지 | PR-2 |
| 메모리 상한 초과 | 분석기만 OOM, restart | PR-1 RSS 실측 |

## 10. 플래그 기본값 (경계 리뷰 대상)

| 플래그 | 코드 기본 | 운영 |
|---|---|---|
| `ANALYZER_DRY_RUN` | `true` (PR-1 은 false 거부) | PR-3 에서 false |
| `ANALYZER_DAILY_LIMIT` | `30` | 그대로 |
| webhook 연동 `disableResolveMessage` | `true` | |
| 끄는 법 | contact point 의 webhook 연동 삭제 + reload (1~2단계 무영향) | |

## 11. 사용자 작업 (PR-3 전)

- Slack 앱 권한 추가 `chat:write`, `channels:history`, `groups:history` → 재설치. 노출된 `xoxb`/`xapp` 폐기
- 새 Bot token, Anthropic API 키는 VPS `/root/apps/observability/env/alert-analyzer.env` 에 직접
- 봇 채널 초대, 채널 ID 전달 / 평가 라벨 확인, 평가 비용 승인

## 12. 알려진 함정

- provisioning `$VAR` env 치환 → 템플릿·문자열에 `$` 금지
- 파일에서 규칙을 빼도 DB 에 남음 → `deleteRules` + reload
- receivers API `lastNotifyAttempt` 는 group_wait 뒤에 찍힘
- VPS compose 는 git 아님 → 블록 단위 수정, `--no-deps`
- 운영 로그에 서울 지하철 키가 평문으로 있다(Loki 내부). 분석기 밖으로 나가는 모든 문자열은 마스킹, fixture 는 env 값 대조 후 커밋
- 새 GHCR 패키지는 기본 private → VPS pull 가능 여부 확인

## 13. 범위 밖

- 4단계 RAG, 5단계 Slack 양방향, Tempo 트레이스 요약, VPS 자체 사망 감지
- 서울 지하철 키가 로그에 찍히는 문제(앱 쪽 `uri.getPath()` 로그 수정) — 별건 이슈로
- `SubwayArrivalEventBatchService` "Phase A failed" 매일 04:30 원인 조사
