# verify-spec — fix-approval-pr (2026-11-28)

검증자: general-purpose 1개(새 컨텍스트). 1차 판정: **FAIL** (blocker 1, major 4, minor 3, 틀린 가정 2)

## 지적과 처리
| # | 등급 | 지적 | 처리 |
|---|---|---|---|
| 1 | blocker | 저장소 설정 `can_approve_pull_request_reviews:false`(gh api .../actions/permissions/workflow) → GITHUB_TOKEN 으로 `gh pr create` 실패, push 된 브랜치만 남음 | 반영: 1단계에 설정 켜기 + GET 확인. SUMMARY 리스크에 "다른 워크플로도 PR 생성·승인 가능" 추가 |
| 2 | major | 워크플로 단위 concurrency(fix-verify.yml:37-39) → create-pr 승인 대기(최대 30일) 동안 같은 스레드 다음 run 이 pending, 세 번째에 조용히 취소 | 반영: concurrency 를 test·notify job 단위로 옮기고 create-pr 은 `fix-approval-<run_id>` 별도 그룹. 합격 기준에 추가 |
| 3 | major | upload-artifact v4 에 `overwrite` 입력 있음 → "patch 위조 불가" 근거 불성립(수정 코드가 같은 job 런타임 토큰을 얻는 경로 미차단) | 반영: create-pr 이 main 의 apply_edits.py 로 `inputs.edits` 를 IN_REF 에 다시 적용해 patch 생성, fix-patch 는 대조용(다르면 중단). SUMMARY 핵심 결정 문구 변경 |
| 4 | major | `## 가정`·`## 합격 기준` 없음 | 반영: 두 섹션 추가(명령 형태) |
| 5 | major | "Slack 실패와 무관하게 출력" 과 create-pr `if`(success()) 모순 | 반영: notify 가 출력을 Slack 전에 씀 + create-pr `if: always() && needs.test.result=='success' && needs.notify.outputs.approvable=='true'` — Slack 이 실패해도 GitHub 에서 승인 가능 |
| 6 | minor | push 후 `gh pr create` 실패 시 브랜치 잔류·무알림, 재실행 시 같은 이름 충돌 | 반영: 브랜치 `fix/alert-<run_id>-<attempt>`, `if: failure()` 단계가 브랜치 삭제 + 스레드에 실패 한 줄 |
| 7 | minor | gh 에 GH_TOKEN 필요 | 반영: `GH_TOKEN: ${{ github.token }}` 명시 |
| 8 | minor | failures 20개 잘림 → "전부 baseline" 판정이 정렬에 의존(위조 가능) | 반영: approvable 에 `len(failures) == failed` 조건, 테스트 표에 "failed 25·목록 20" |

## 틀린 가정(1차)
- create-pr 이 GITHUB_TOKEN 으로 Draft PR 생성 가능 → 틀림(지적 1)
- patch artifact 위조 불가 → 틀림(지적 3)
- 승인 대기가 다른 요청을 막지 않음 → 틀림(지적 2)

## 확인 못 함(검증자)
- 런타임 토큰으로 fix-patch overwrite 실제 가능 여부(러너 실험 필요) → 지적 3 반영으로 무관해짐
- environment PUT 성공 여부(쓰기 API) → 구현 1단계에서 확인
- 승인 대기 run 이 concurrency 를 잡는 실제 동작 → 합격 기준 C3 로 관찰

1차: verify-spec: FAIL

## 재검증 (새 검증자 — 1차 검증자 재개 불가)
- 지적 1~8 전부 닫힘
- 새 지적: N1 minor(목표·그림·브랜치 이름·SUMMARY 신뢰 경계 문구가 옛 방식) → 반영 / N2 minor(`always()` 대신 `!cancelled()`) → 반영 / N3 minor(apply_edits.py 를 `$GITHUB_SHA` 에서 작업 트리 밖으로 꺼내고 바뀐 경로만 add) → 반영

verify-spec: PASS
