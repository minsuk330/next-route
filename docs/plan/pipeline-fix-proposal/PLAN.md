# 파이프라인 4단계: 수정 코드 제안 (M)

사용자 결정(2026-11-27 대화, 메모리 project-grafana-slack-alerts)을 그대로 옮긴다. 상위 맥락: docs/plan/rag-incident-context (3단계 RAG, 배포 완료).

## 목표
원인 분석 뒤, 조건을 통과한 알림에 한해 LLM 이 **찾아 바꾸기 블록**으로 수정 코드를 제안하고, 배포 커밋과 main 에서 원문을 대조해 결과를 Slack 스레드 두 번째 답글(diff 미리보기)로 낸다. PR 은 만들지 않는다(쓰기 권한은 7단계에서 정함).

## 결정 (바꾸지 말 것)
| 항목 | 결정 | 근거 |
|---|---|---|
| 생성 조건 | 원인 가설 중 `app_bug` 이고 확신도 high/medium 인 것이 있고, 고칠 곳 후보에 `s:` ref 가 있음 | 외부 장애·인프라는 코드로 풀 일 아님. low 는 diff 가 판단을 흐림. `s:` 없으면 원문이 없어 상상 diff |
| 형식 | SEARCH/REPLACE 블록 | 11-27 시험(실제 fix 커밋 18건): 적용 18 대 unified diff 16, 고친 위치 16 대 13. udiff 실패는 둘 다 덩어리 줄 수 오류 |
| 적용 기준 | 배포 커밋(D)과 main(M) 둘 다에서 찾음. ① 둘 다 정확히 한 번 → main 기준 diff 제안 ② D 만 → 제안 안 함, "배포 뒤 main 에서 바뀜(커밋), 이미 고쳐졌는지 확인" ③ D 에도 없음 → 버림, 로그만 | 이미 고쳐진 버그를 또 고치지 않기 |
| 결과 위치 | Slack 같은 스레드 두 번째 답글. ③·조건 미달은 Slack 에 안 씀 | 쓰기 권한 없이 |

## 구현 (브랜치 feat/fix-proposal, fresh origin/main)

- [ ] `alert-analyzer/analyzer/config.py`: `fix_proposal_enabled: bool = False`(`ANALYZER_FIX_PROPOSAL_ENABLED`). 켜는데 `code_context_enabled` 가 꺼져 있으면 기동 실패
- [ ] `alert-analyzer/analyzer/code.py` `SourceCache`
  - `async def resolve(self, ref: str) -> str | None`: `git ls-remote origin refs/heads/<ref>` 로 40자 SHA(askpass 같은 방식, 임시 bare 디렉터리 하나 재사용). 실패 → None
  - `KEEP_REVISIONS` 3 → 4 (배포 커밋 + main 이 동시에 쓰임)
- [ ] `alert-analyzer/analyzer/fix.py` (신규)
  ```python
  Status = Literal["proposed", "main_changed", "mismatch", "skipped", "failed"]

  @dataclass(frozen=True)
  class Edit: search: str; replace: str

  @dataclass
  class FixProposal:
      status: Status
      target: dict | None = None        # 코드 조각(bundle.code 항목)
      main_sha: str | None = None
      diff: str = ""                    # main 기준 unified diff (proposed)
      main_commits: list[str] = field(default_factory=list)   # main_changed: "abc12345 제목"
      reason: str = ""
      cost_usd: float = 0.0

  def pick_target(verdict, bundle) -> tuple[dict, FixCandidate, Hypothesis] | None
      # app_bug & confidence in {high, medium} 가설 하나 이상 + fix_candidates 중 s: ref 가 bundle.code 에 있는 첫 후보
  def parse_edits(text) -> list[Edit]          # <<<<<<< SEARCH / ======= / >>>>>>> REPLACE, 최대 3개
  def apply_edits(source, edits) -> str | None # 각 search 가 정확히 한 번일 때만, 아니면 None
  async def propose(verdict, bundle, *, llm, cache, compare) -> FixProposal
  ```
  - LLM 입력(`prompts/fix.md` 시스템 + 사용자 메시지): 대상 메서드 원문(줄 번호 접두, 코드 아님 명시), 경로·심볼·배포 커밋, 원인 가설 cause, 후보 why, 후보 refs 의 카드 "조치" 칸. 출력은 블록만
  - 대조: D 파일 = `cache.checkout(target.revision)`, M = `cache.resolve("main")` → checkout. D 에 안 맞으면 mismatch. D 맞고 M 안 맞으면 main_changed + `compare(D, M, path)` 로 그 파일을 바꾼 커밋 목록(GitHub REST `GET /repos/{repo}/compare/{D}...{M}`, 읽기 토큰, 최대 5). 둘 다 맞으면 M 에 적용한 결과로 `difflib.unified_diff`(3줄 문맥)
  - D == M 이면 checkout 한 번
  - 모든 예외 → failed(사유만, 토큰·URL 금지). 분석 답글에는 영향 없음
  - LLM 출력·diff 는 `mask_code` 통과
- [ ] `alert-analyzer/analyzer/llm.py`: `async def complete(self, system, user, max_tokens=8000) -> tuple[str, float]` (텍스트 응답 + 비용, 기존 billed 재사용)
- [ ] `alert-analyzer/analyzer/slack.py`: `render_fix(p: FixProposal) -> str`
  - proposed: ":hammer_and_wrench: *수정 제안* _(참고용 — 사람이 검토 후 적용)_" + `Symbol` + "배포 abc12345, main def67890" + ```diff``` (3000자 넘으면 diff 를 자르고 "… 생략")
  - main_changed: ":warning: 수정 제안 보류 — 배포 뒤 main 에서 이 파일이 바뀜. 이미 고쳐졌는지 확인" + 커밋 목록
- [ ] `alert-analyzer/analyzer/worker.py` `process`: 분석 답글 뒤, `live.fix`(Optional) 가 있으면 `propose` → proposed/main_changed 면 `_notify` 로 두 번째 답글. 로그 `fix_proposal status=… target=… cost_usd=…`. 실패해도 분석 결과는 이미 나감
- [ ] `alert-analyzer/analyzer/app.py`: 플래그 켜면 Live 에 fix 단계 연결(같은 http, cache, Analyzer 클라이언트)
- [ ] `alert-analyzer/eval/fix_format.py`: 11-27 형식 시험을 옮긴 스크립트(저장소 git 이 필요해 로컬 실행, LLM 호출만 분석기 컨테이너). README 에 실행법
- [ ] 테스트 `tests/test_fix.py`
  - pick_target: app_bug+medium+s: → 선택 / low → None / external_api → None / k: 만 → None / s: ref 가 bundle 에 없음 → None
  - parse_edits: 여러 블록, 블록 없음, 4개 이상 자름
  - apply_edits: 0회·2회 → None, 정상 적용
  - propose: ①(D==M, 적용), ①(D≠M 둘 다 맞음), ②(main_changed + compare 커밋), ③(mismatch), LLM 예외 → failed, 출력에 비밀값 → 가려짐
  - render_fix 길이 3000 이하, 자르기 표시
  - worker: 조건 미달·mismatch 면 Slack 답글 1개, proposed 면 2개
  - config: fix 켜고 code 꺼짐 → 기동 실패
- 검증: `uv run --extra dev pytest -q`(alert-analyzer) 전부 통과

## 배포
- GHCR 이미지 → env `ANALYZER_FIX_PROPOSAL_ENABLED=true` → 분석기 --force-recreate(env 줄 직접 확인)
- 실물: 운영 컨테이너에서 연습 맥락(성수지선, SubwayDirectionNormalizer 프레임)으로 분석 + propose 1회, 상태·diff·비용 확인(약 $0.05)

## 리스크
- 조건이 좁아 실제 알림에서 한동안 안 돌 수 있다 → 로그 `fix_proposal status=skipped` 로 횟수를 센다
- main 조회가 분석 지연을 늘림 → 분석 답글을 먼저 보내고 제안은 뒤에
- LLM 이 엉뚱하지만 적용 가능한 수정을 낼 수 있다(시험 18건 중 2건) → "참고용, 검토 후 적용" 표시, 5~7단계(영향 범위·테스트·승인)가 거름
