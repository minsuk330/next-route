# 파이프라인 7단계 — 승인하면 Draft PR

## 뭘 하나
fix-verify 가 새 실패 0건이면 "PR 만들기" job 이 GitHub 승인 대기로 멈춘다. GitHub 에서 승인하면 수정 입력을 다시 적용해
(테스트 때 수정본과 대조) Draft PR 을 만들고 장애 스레드에 PR 링크를 단다.

## 왜
테스트를 통과한 수정 제안을 사람이 버튼 하나로 PR 로 만들게 하되, 분석기는 키우지 않기 위해.

## 핵심 결정
- 승인 = GitHub environment 승인 게이트(public 이라 무료), 분석기 변경 없음 (Slack 버튼 기각)
- 새 실패 0건일 때만 승인 대기 — 판정은 토큰 없는 test job 이 아니라 notify job 이 함
- 쓰기 권한은 create-pr job 에만, 그 job 이 실행하는 저장소 코드는 워크플로 커밋의 apply_edits.py 하나(수정 대상 코드는 실행 안 함)
- Draft PR. create-pr 은 artifact 를 믿지 않고 입력(edits)을 워크플로 커밋 스크립트로 다시 적용해 patch 를 만들고 artifact 와 대조
- concurrency 는 job 단위 — 승인 대기가 같은 스레드의 다음 테스트를 막지 않게

## 건드는 곳
.github/workflows/fix-verify.yml, ops/ci/notify_slack.py, ops/ci/pr_body.py(신규), ops/ci/tests, 저장소 environment fix-approval, 저장소 Actions 권한 설정

## 리스크
- summary 는 여전히 위조 가능 → 승인 대기가 잘못 뜰 수 있음 (최종 확인은 Draft PR diff 리뷰)
- 저장소 설정 "Actions 가 PR 생성·승인 가능"을 켬 → 다른 워크플로도 GITHUB_TOKEN 으로 PR 생성 가능(지금은 쓰기 권한 워크플로 없음)
- environment 생성이 API 에서 거부되면 설계 재검토

## 검증법
시험 스레드에서: 통과 → 승인 링크 → 승인 → Draft PR·스레드 링크, 승인 대기 중 같은 스레드 다음 run 이 돎. 새 실패 케이스는 create-pr skipped.
