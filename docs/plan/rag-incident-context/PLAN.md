# RAG 장애 맥락: 배포 커밋 코드 + 과거 장애 카드 (L)

결정 근거와 실험 결과는 이 문서 끝 "근거" 절. 사용자 결정은 2026-11-26~27 대화에서 하나씩 확정했다.

## 목표
알림 분석(alert-analyzer)에 두 재료를 더한다.
1. **코드(S)**: 장애 난 role 이 실제로 돌고 있는 **배포 커밋**의 Java 메서드. 원인 가설의 근거로 인용 가능
2. **과거 장애 카드(K)**: 메타데이터로 좁힌 카드. **"고칠 곳 후보"에서만** 인용 가능
LLM 답에 `fix_candidates` 칸을 더하고, S·K 번호도 E 번호처럼 코드가 대조한다.

## 범위 밖 (의도적으로 안 함)
- 코드 의미 검색(임베딩·벡터 저장소). 대신 "코드를 무엇으로 찾았나" 기록 → "못 찾음"이 쌓이면 별건으로 만든다
- 카드 의미 검색. 카드 수백 장 넘으면 별건
- 카드 자동 초안(RESOLVED → 초안). 별건
- 수정 코드 생성(파이프라인 4단계). 별건

## PR 구성 (순서 의존: PR-1 → PR-2 → PR-3)

### PR-1 앱이 자기 빌드 커밋을 메트릭으로 알린다 (S, 앱만)
브랜치 `feat/rag-build-info` (fresh origin/main)

검토 반영: revision 을 deploy.sh·compose 로 넘기지 않고 **jar 안에 굽는다**. deploy.sh 의 revision 은 짧은 SHA·`manual`·운영자 입력이라 GitHub fetch(40자 SHA 필요)에 못 쓰고, cherry-pick 빌드처럼 jar 와 어긋날 수 있다. deploy.sh·compose 는 안 건드린다(롤백 상태·worker2 누락 위험도 같이 사라짐).

- [ ] `build.gradle`: `springBoot { buildInfo { properties { additional = [revision: <git rev-parse HEAD>, dirty: <git status --porcelain 비어있지 않으면 true>] } } }`. git 이 없거나 실패하면 `unknown`. → `META-INF/build-info.properties`
- [ ] `src/main/java/watoo/grd/nextroute/common/observability/BuildInfoMetrics.java` (신규)
  ```java
  @Component
  public class BuildInfoMetrics implements MeterBinder {
      // ObjectProvider<BuildProperties> — build-info 없는 테스트·IDE 실행에서도 뜬다(unknown)
      public void bindTo(MeterRegistry r) {
          Gauge.builder("app.build", () -> 1).tag("revision", revision).tag("dirty", dirty).register(r);
      }
      // revision: ^[0-9a-f]{40}$ 아니면 "unknown"
  }
  ```
  이름은 `app.build` → Prometheus `app_build`. `.info`/`_info` 는 Prometheus 클라이언트가 Info 타입으로 바꾸는 예약 접미사라 피한다(검토 11). role·instance 라벨은 수집 설정이 붙인다(`ops/observability/prometheus/prometheus.yml:17-25`)
- [ ] application.yaml 변경 없음(`app:` 키가 L387 에 이미 있어 새 최상위 블록은 기동 실패 — 검토 9. 이번 설계는 yaml 을 안 씀)
- [ ] 테스트 `BuildInfoMetricsTest`
  - `PrometheusMeterRegistry.scrape()` 출력에 `app_build{dirty="false",revision="<40자>"} 1` 이 나옴(실제 노출 이름 계약)
  - BuildProperties 없음 → revision="unknown"; 짧은 SHA·이상한 문자 → "unknown"
- 검증: `./gradlew test --tests '*BuildInfoMetricsTest'`, `./gradlew bootJar` 후 `unzip -p build/libs/*.jar META-INF/build-info.properties` 에 40자 revision
- 배포: 평소대로 api·worker(→worker2) 배포 → Prometheus `app_build` 3개 role 의 revision = 배포 커밋 전체 SHA, dirty=false. 🔴 worker2 교체 누락 이력(implementation_note 2026-11-27) — 배포 뒤 worker2 확인

### PR-2 분석기가 배포 커밋의 코드를 넣는다 (M, 분석기)
브랜치 `feat/rag-code-context`

- [ ] `alert-analyzer/Dockerfile`: `git` 설치(apt --no-install-recommends). 신규 Python 라이브러리 없음. 늘어난 이미지 크기를 노트에 기록
- [ ] `config.py` Settings: `code_context_enabled=False`(`ANALYZER_CODE_CONTEXT_ENABLED`), `github_repo="minsuk330/next-route"`, `github_token`(repr=False, enabled 인데 비면 기동 실패), `code_cache_dir="/tmp/code-cache"`
- [ ] **스택 프레임은 sample 이 아니라 전체 stack_trace 에서 뽑는다**(검토 3): `context.py` `_logger_templates`(L259~) 가 전체 JSON 줄을 받을 때 잘리지 않은 `stack_trace` 전부를 `Caused by` 포함 훑어 `watoo.grd.nextroute` 프레임을 `TemplateStat.frames: list[str]`(신규, 기본 빈값, 최대 5, 중복 제거)에 담는다. 바깥 예외 앞 15줄이 전부 라이브러리 프레임인 RestClient·JDBC 스택을 fixture 로 테스트
- [ ] **worker2 로그도 수집**(검토 4): `APP_SELECTOR`(context.py:45) = `app=~"api|worker|worker2"`, `slack.py:67` explore 필터도 같이
- [ ] `alert-analyzer/analyzer/code.py` (신규)
  ```python
  @dataclass
  class CodeSnippet:
      id: str; revision: str; role: str; path: str; symbol: str   # "OtpRoutingAdapter#search"
      header: str      # 패키지.클래스 + 클래스 javadoc 첫 줄
      start_line: int; end_line: int
      body: str        # 줄 번호 접두, 최대 150줄
      via: Literal["stack", "keyword"]

  async def revisions(prom, at) -> dict[str, str]
  # last_over_time(app_build{job="nextroute-app"}[5m]) @ 알림 startsAt. role 별로 값이 둘이면(배포 중) 그 role 은 unknown
  # revision 이 40자 hex 가 아니거나 dirty="true" 면 unknown(fetch 시도 안 함)
  class SourceCache:
      async def checkout(self, revision) -> Path | None
      # git init → sparse-checkout set src/main/java → fetch --depth 1 --filter=blob:none origin <40자 sha> → checkout
      # GIT_ASKPASS 스크립트(Username=x-access-token, Password=토큰)를 fetch·checkout 둘 다에 env 로. GIT_TERMINAL_PROMPT=0, -c credential.helper=
      # 명령마다 timeout 30초, 넘으면 프로세스 그룹 종료. 최근 3개 커밋 LRU, asyncio.Lock
      # 예외·로그 문자열에 토큰·인증 URL 금지
  def keywords(templates, alerts) -> list[str]      # 템플릿의 "[Tag]" 접두, 알림 라벨 uri
  def enclosing_method(source, line) -> tuple[int, int] | None   # 중괄호 짝(문자열·문자·주석 안 무시)
  def find_by_keyword(root, kw) -> list[tuple[Path, int]]         # 리터럴, 5파일 초과면 버림
  async def collect(templates, alerts, prom, cache, *, budget_chars=24_000) -> CodeContext
  ```
  - role: 프레임이 나온 템플릿의 `app` 라벨(api/worker/worker2). 키워드 경로는 알림 instance(`api:8081`) → `app_build` 의 instance·role 짝으로 role 변환 → 없으면 api
  - via: "stack" | "keyword" | "none" | "disabled" | "version_unknown"
- [ ] **예산 순서**(검토 2): `gather()`(context.py:175-226) 안에서 `fit_budget` **전에** `collect` 를 부른다(잘리기 전 templates·frames 를 본다). 코드는 별도 한도 24k 자 — `MAX_BUNDLE_CHARS`(40k) 는 기존 부분에만 그대로 적용. 테스트로 "큰 장애에서도 코드는 자기 한도까지 남는다" 고정. gather 시그니처에 `code: CodeCollector | None = None`(None 이면 지금과 동일)
- [ ] `ContextBundle`: `code: list[dict]`, `code_via: str = "disabled"`. `from_dict` 는 키 없으면 기본값(옛 fixture·eval 호환 테스트). `ids()` 에 s:*
- [ ] **마스킹**(검토 7): `masking.py` 에 비밀값 규칙만(slack·anthropic·jwt·bearer·api_key) 거는 `mask_code` 를 두고 `mask_bundle` 이 code body 에 적용. 좌표 규칙은 코드 숫자 리터럴을 바꾸므로 제외. 테스트
- [ ] `app.py` lifespan: enabled 이면 SourceCache 를 만들어 gather 에 넘김. collect 실패는 `collection_errors` 에만, 분석 계속
- [ ] `llm.py`: 가설 evidence 에 s:* 허용. `prompts/system.md` "입력"에 code 설명("배포 커밋 코드, 지금 돌고 있는 사실. 스택이 가리킨 줄의 로직을 원인 근거로 쓸 수 있다")
- [ ] `worker.py` "analyzed" 로그에 `code_via=%s`
- [ ] `slack.py` `_describe`: `s:1` → `OtpRoutingAdapter#search L130-158 @47d81acf`
- [ ] 테스트: 프레임 추출(Caused by·$Lambda·Inner→파일명·바깥 패키지 제외), enclosing_method(생성자·람다·문자열/주석 안 중괄호·필드 줄 None, 실제 파일 3개 fixture), find_by_keyword 상한, revisions(중복 시리즈·dirty·짧은 SHA), SourceCache(로컬 bare repo 로 checkout·LRU·예외에 토큰 없음), 예산, 마스킹, check_evidence s:*
- 검증: `uv run --project alert-analyzer pytest` 전부. 첫 checkout 때 컨테이너 최대 메모리 실측(192m 상한, 검토 12)을 노트에
- 배포: GHCR → env 에 ANALYZER_GITHUB_TOKEN(사용자 발급, fine-grained contents:read 이 저장소만) + ANALYZER_CODE_CONTEXT_ENABLED=true → `--force-recreate`. 실물: scripts/ 1회용으로 최근 실제 5xx 시각을 넣어 collect 결과 확인

### PR-3 과거 장애 카드 + 고칠 곳 후보 (M, 분석기)
브랜치 `feat/rag-incident-cards`

- [ ] `alert-analyzer/analyzer/incidents/cards.json` (신규. `analyzer/` 아래라 Dockerfile COPY 에 포함됨 — 검토 5. `Path(__file__)` 기준으로 읽음. 파일 없거나 깨지면 카드 없이 진행 + collection_errors, 테스트): 씨앗 25장. 칸 = id(K01..), 유형, 감지경로, 증상, 증상로그, 원인, 조치, 고친곳, 날짜. 원본 = `docs/plan/rag-incident-context/seed-cards.json`(2026-11-27 실험 카드, id C01..C25 → K01..K25 로 바꿔 옮김). 카드 추가 규칙은 `alert-analyzer/analyzer/incidents/README.md`
- [ ] `alert-analyzer/analyzer/cards.py` (신규)
  ```python
  def load(path) -> list[Card]
  def select(cards, bundle, limit=10) -> list[Card]
  # 점수 = 카드 "고친곳" 클래스명이 code.symbol·template.logger 에 겹침(3) + 클래스명 토큰(Subway/Bus/Otp/Journey/...)이 bundle 텍스트에 겹침(1). 점수 0 은 안 넣음
  ```
- [ ] `context.py`: bundle 에 `cards: list[dict]`(id `k:K11` 형식). `ids()` 는 그대로(원인 근거 아님). 별도 `fix_ids()` = s:* ∪ k:*
- [ ] `llm.py`
  ```python
  class FixCandidate(BaseModel):
      place: str            # 클래스나 계층
      why: str
      refs: list[str]       # s:* 또는 k:* 만
  class Analysis(BaseModel):
      headline: str
      hypotheses: list[Hypothesis]
      fix_candidates: list[FixCandidate] = []
  ```
  - `Verdict.fix_candidates: list = field(default_factory=list)`(기존 테스트가 Verdict 를 직접 만들므로 기본값 필수, 검토 6). 개수 상한은 스키마로 못 걸어 코드에서 `[:3]`
  - 옛 응답 JSON(필드 없음) 파싱 테스트, worker "analyzed" 로그에 `fix=%d`
  - check_evidence: 가설 evidence 의 k:* 는 무효(invalid 로 셈)
  - check_fix: refs 를 fix_ids() 로 대조, 남은 ref 없는 후보 제거
  - 프롬프트: 카드 설명 + "카드는 과거 일이다. 원인의 근거로 쓰지 말고 고칠 곳 후보에서만 인용한다. 비슷해 보여도 증상·로그가 다르면 쓰지 않는다"
- [ ] `slack.py`: 가설 아래 "고칠 곳 후보" 줄(최대 3). 3000자 자르기(slack.py:39,140-145)에서 후보가 꼬리말보다 먼저 잘리는지 테스트. 카드는 `K11 9호선·공항철도 도착 0대(10-10)` 로 표시
- [ ] 평가: `alert-analyzer/eval/` 에 빼고 맞히기 스크립트를 옮겨 둔다(카드 수정 때 다시 돌림). 기준 = 2026-11-27 결과(고친 클래스 정확 1→8/24)
- [ ] 테스트: select 점수·상한·0점 제외, k: 가 가설 근거면 제거, fix refs 대조, Slack 표시
- 검증: pytest 전부, 평가 스크립트 1회(비용 약 1달러, 사용자 승인 후)

## 리스크
- **cherry-pick·로컬 수정 빌드**: GitHub 에 없는 커밋이거나 dirty 면 코드 없이 "버전 불확실". 정상 배포(origin/main 깨끗한 트리 빌드)에서만 코드가 붙는다
- **메모리**: 분석기 192m 상한에 git 자식 프로세스. 첫 checkout 실측, 넘으면 상한 조정 별건
- **토큰 노출**: GitHub 토큰은 env 파일에만. git 명령 실패 메시지에 URL 이 섞이지 않게 GIT_ASKPASS 사용, 테스트로 가드
- **분석 지연**: 첫 checkout(약 7MB) 몇 초. 분석 큐는 하나라 영향은 그 건뿐. git 명령 timeout 30초, 넘으면 코드 없이 진행
- **잘못 이끄는 카드**: 실험에서 2건. K 는 원인 근거 금지 + 번호 대조 + 0점 카드 제외로 줄인다

## 검토 반영 (Opus 독립 검토 2026-11-27, 12건)
1 짧은 SHA·manual → jar 에 40자 SHA 굽기(PR-1 재설계) / 2 예산 순서 → fit_budget 전 collect, 코드 별도 한도 / 3 sample 스택 커버리지 → 전체 stack_trace 의 watoo 프레임 / 4 worker2 로그 미수집·instance≠role → APP_SELECTOR, app_build 라벨로 변환 / 5 cards.json 이미지 누락 → analyzer/ 아래 / 6 Verdict 계약 → 기본값 필드, [:3] / 7 마스킹 우회 → mask_code / 8 deploy.sh 서브셸 → PR-1 재설계로 해당 없음 / 9 app: 키 중복 → yaml 안 씀 / 10 조회 시각·중복 시리즈 → startsAt + last_over_time, 둘이면 unknown / 11 _info 예약 접미사 → app_build, PrometheusMeterRegistry 테스트 / 12 git 세부 → ASKPASS 를 checkout 에도, 프로세스 그룹 종료, 메모리 실측

## 근거
- 실험(2026-11-27): 카드 25장 빼고 맞히기, Sonnet 5.5, 채점 LLM. 원인 점수 A 22 / B 24 (50점), 고칠 곳 16/17 (25점), 고친 클래스 정확 1 → 8 (24건). 오도 2건(C16←C04, C11←C12). 비용 약 $1.9
- 최근 30일 분석 7회 → 프롬프트 캐시 적중 없음. 카드 필터 이유 = 비용 아닌 잡음
- 운영 버전 ≠ main 이 실제로 일어남(2026-11-26 api 7acc7a34, main 3fbcc18a) → 배포 커밋 기준
- 저장소 333MB 라 tarball 대신 sparse checkout(src/main/java 7.4MB)
- 5xx 는 #650 필터로 스택이 반드시 남음 → 스택 경로가 주 경로
