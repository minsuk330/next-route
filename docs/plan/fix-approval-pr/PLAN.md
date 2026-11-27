# 파이프라인 7단계 — 승인하면 Draft PR (fix-approval-pr)

티어: M (워크플로 job 1개 + Slack 문구 + 저장소 environment 설정, PR 1개)

## 목표
fix-verify 결과가 **새 실패 0건**이면 `create-pr` job 이 GitHub environment 승인 대기로 멈춘다. 사람이 GitHub 에서
승인하면 그 job 만 쓰기 권한으로, 입력(edits)을 워크플로 커밋의 apply_edits.py 로 main_sha 에 다시 적용하고
`fix-patch` artifact 와 대조한 뒤 Draft PR 을 만들고 장애 스레드에 PR 링크를 단다. 분석기는 바뀌지 않는다.

## 사용자 결정 (11-28)
- 승인 = GitHub environment required reviewers(public 전환으로 무료). 재수정 루프는 미룸. 이유: 분석기 안 키움
- 승인 대기 = 새 실패 0건일 때만(passed, 또는 failed 인데 전부 baseline). 이유: 깨지는 수정이 PR 되면 안 됨
- Draft PR, 브랜치 `fix/alert-<run_id>-<attempt>`(main_sha + patch), base main. 본문 = 장애 이름·고친 곳·테스트 요약·실행 링크·"AI 생성 — 리뷰 필수"
- PR 생성 후 스레드에 PR 링크. main 이동은 GitHub 충돌 표시에 맡김. 승인 대기 기본 30일

## 설계
```
test (수정 코드 실행, 토큰 없음) ── fix-patch(테스트 전 업로드), fix-summary
  └─ notify (다른 VM, main 코드) ── 결과 답글 + 승인 가능 판정(outputs.approvable) + 가능하면 "승인하기" 링크
       └─ create-pr (environment: fix-approval, 승인 대기)
            checkout → 워크플로 커밋의 apply_edits.py 로 edits 재적용 → fix-patch 와 대조 → push → gh pr create --draft
            → 스레드에 PR 링크
```
- **승인 가능 판정은 notify 가 한다** — 출력(`approvable`)은 Slack 전송 **전에** 쓴다. Slack 이 실패해도 GitHub 에서 승인할 수 있다(test job 의 GITHUB_OUTPUT 은 수정 코드가 쓸 수 있음). summary.json 자체는 여전히
  위조 가능 — 받아들인 한계, 최종 확인은 Draft PR diff 리뷰
- **create-pr 은 artifact 를 믿지 않는다**(verify #3: upload-artifact v4 에 `overwrite` 가 있어 수정 코드가 같은 job 토큰으로
  fix-patch 를 바꿀 경로가 닫혀 있지 않음): main 커밋의 `ops/ci/apply_edits.py` 로 `inputs.edits` 를 IN_REF 에 **다시 적용**해
  patch 를 만들고(경로 정규식 `src/main/java/**/*.java` 포함), fix-patch 와 `git diff` 가 다르면 중단한다
- **concurrency 는 job 단위**(verify #2): 워크플로 단위면 승인 대기(최대 30일) 동안 같은 스레드의 다음 run 이 막히고 세 번째에
  조용히 취소된다. test·notify 에 `fix-verify-<thread_ts>`, create-pr 에 `fix-approval-<run_id>`
- **create-pr 이 실행하는 저장소 코드는 워크플로 커밋의 `ops/ci/apply_edits.py` 하나**(Python, 수정 대상 코드는 실행 안 함). 빌드·테스트 없음
- 권한: create-pr 만 `contents: write`, `pull-requests: write`. Slack 토큰은 PR 링크·실패 답글 단계에만
- **저장소 설정 변경**(verify #1): "Allow GitHub Actions to create and approve pull requests"(`can_approve_pull_request_reviews`)가
  꺼져 있어(개인 저장소 기본) GITHUB_TOKEN 으로 PR 을 못 만든다 → 켠다. 부작용: 다른 워크플로도 GITHUB_TOKEN 으로 PR 생성·승인 가능
  (지금 다른 워크플로는 contents: read 이고 main 보호 규칙이 없어 승인 기능 자체가 의미 없음)

## 가정
| # | 가정 | 확인 방법 |
|---|---|---|
| A1 | public 저장소라 environment required reviewers 를 쓸 수 있다 | 1단계 PUT 후 GET `protection_rules[].type == required_reviewers` |
| A2 | 승인자 id = 97379835(minsuk330) | `gh api users/minsuk330 --jq .id` (검증자 확인됨) |
| A3 | 설정을 켜면 GITHUB_TOKEN(pull-requests: write)으로 Draft PR 생성 가능 | 1단계 GET `can_approve_pull_request_reviews == true`, C2 에서 관찰 |
| A4 | test job 은 실패가 있어도 success 로 끝난다 | run_tests.py 모든 경로 return 0 (검증자 확인됨) |
| A5 | main 에 브랜치 보호·ruleset 없음 → `fix/alert-*` push 가능 | `gh api .../rulesets` = [], protection 404 (검증자 확인됨) |
| A6 | apply_edits.py 를 같은 edits·같은 ref 에 다시 돌리면 test job 의 fix.patch 와 같은 diff | C2 에서 대조 단계가 통과하는지 |
| A7 | GITHUB_TOKEN 으로 만든 PR 은 워크플로를 트리거하지 않는다 — src/main/java 를 보는 PR CI 가 없어 영향 없음 | .github/workflows pull_request path 필터(검증자 확인됨) |

## 합격 기준
| # | 기준 | 명령/관찰 |
|---|---|---|
| C1 | ops/ci 단위 테스트 통과 | `python3 -I -m unittest discover -s ops/ci/tests -p 'test_*.py'` → OK |
| C2 | 통과 케이스: 승인 대기 → 승인 → Draft PR | `gh run view <id> --json jobs` 에서 create-pr 이 waiting → (승인 후) success. `gh pr view <n> --json isDraft,headRefName,baseRefName` = true, `fix/alert-<run_id>-<attempt>`, main. 스레드에 "승인하면 Draft PR 생성" 링크와 "Draft PR 만듦" 답글 |
| C3 | 승인 대기 중에도 같은 스레드의 다음 run 이 돈다 | C2 의 run 이 승인 대기인 동안 같은 thread_ts 로 두 번째 dispatch → 두 번째 run 의 test·notify 가 completed |
| C4 | 새 실패 케이스는 PR 길이 없다 | 테스트를 깨는 edits dispatch → create-pr conclusion = skipped, 스레드에 승인 링크 없음 |
| C5 | 시험 정리 | 시험 PR close + 브랜치 삭제, `gh api .../environments/fix-approval` 유지 |

## 작업 순서
1. [ ] 저장소 설정 2개(API, 사용자 승인된 플랜 범위)
   - environment: `PUT /repos/minsuk330/next-route/environments/fix-approval`
     `{"reviewers":[{"type":"User","id":97379835}],"prevent_self_review":false,"wait_timer":0}`
     확인: GET `protection_rules` 에 required_reviewers. 실패(422)면 멈추고 사용자에게 보고.
     prevent_self_review=false: dispatch 주체(분석기 토큰 = 사용자 계정)와 승인자가 같은 사람
   - workflow 권한: `PUT /repos/minsuk330/next-route/actions/permissions/workflow`
     `{"default_workflow_permissions":"read","can_approve_pull_request_reviews":true}`. 확인: GET 에서 true
2. [ ] `ops/ci/notify_slack.py`
   - `approvable(summary) -> bool`: status==passed(그리고 failed==0), 또는 status==failed 이고 baseline_checked 이고
     `len(failures) == failed`(목록이 잘리지 않음, verify #8) 이고 failures 전부 baseline=true
   - 승인 가능이면 결과 답글 끝에 `:white_check_mark: 새 실패 없음 — <run_url|승인하면 Draft PR 생성>` 한 줄
   - `--approvable-output` 플래그: GITHUB_OUTPUT 에 `approvable=true|false` 를 **Slack 전송 전에** 쓴다
   - `--pr-failed` 모드: `:warning: Draft PR 만들기 실패 — <run_url|실행 보기>`
   - `--pr-url <url>` 모드: `:arrow_right: Draft PR 만듦 — <url|PR 보기> (리뷰 후 머지)` 를 스레드에. url 은
     `https://github.com/<repo>/pull/<숫자>` 만 허용
3. [ ] `ops/ci/pr_body.py`: summary·label·run_url → PR 본문 Markdown(신뢰 불가 값은 길이 자르고 코드 펜스·@멘션 무력화)
   - 제목: `fix: <label> (AI 제안)` 최대 100자
   - 본문: ⚠️ AI 생성 — 리뷰 필수 / 장애 / 고친 곳(path) / 테스트 요약(통과·기존 실패 수·클래스 수) / fix-verify 실행 링크
4. [ ] `.github/workflows/fix-verify.yml`
   - 워크플로 단위 concurrency 삭제 → test·notify job 에 `concurrency: fix-verify-${{ inputs.thread_ts || github.ref }}`(notify 는 `-notify` 접미), create-pr 에 `fix-approval-${{ github.run_id }}`
   - notify: `outputs: approvable: ${{ steps.post.outputs.approvable }}`, Post 단계 id=post, `--approvable-output`
   - create-pr job:
     ```yaml
     create-pr:
       needs: [test, notify]
       if: ${{ !cancelled() && github.event_name == 'workflow_dispatch' && needs.test.result == 'success' && needs.notify.outputs.approvable == 'true' }}
       environment: fix-approval
       runs-on: ubuntu-latest
       timeout-minutes: 10
       permissions: { contents: write, pull-requests: write }
     ```
     단계: checkout(fetch-depth 0, persist-credentials true — push 에 필요, 이 job 은 저장소 코드를 실행 안 함)
     → `git switch -c fix/alert-$RUN_ID-$RUN_ATTEMPT $IN_REF` → `git show "$GITHUB_SHA":ops/ci/apply_edits.py > "$RUNNER_TEMP/apply_edits.py"`
     (test job 과 같은 커밋, 작업 트리 밖) → `inputs.edits` 적용 → `git diff` 와 download 한 fix-patch 대조(다르면 중단)
     → 바뀐 경로만 `git add -- <path>` → 커밋(github-actions[bot])
     → push → `gh pr create --draft --base main`(제목·본문 파일, env `GH_TOKEN: ${{ github.token }}`) → PR URL 을 Slack
     → `if: failure()`: push 된 브랜치 삭제(`git push origin --delete`, 실패 무시) + 스레드에 실패 한 줄
   - inputs 는 전부 env 로(보안 규칙 그대로). ref 형식·main 조상 검사 반복
5. [ ] 단위 테스트(`ops/ci/tests`): approvable 판정표(passed / failed 전부 baseline / 새 실패 하나 / baseline 미확인 /
   failed 25·목록 20 전부 baseline / compile_failed / apply_failed / error / 없음), approvable 출력이 Slack 실패 전에 쓰임,
   pr-url 허용·거부, pr_body 무력화(`@everyone`·````·길이)
6. [ ] 검증: 합격 기준 C1~C5
7. [ ] implementation_note·메모리

## 리뷰
- 구현 후 독립 Opus 리뷰 에이전트(쓰기 권한 job 의 신뢰 경계, 판정 위조 경로, 입력 주입)
