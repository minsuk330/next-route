# 파이프라인 5·6단계 — 영향 범위 테스트 (pipeline-fix-verify)

티어: L (새 워크플로, CI DB 자원, 분석기 dispatch, GitHub 권한, PR 3개)

## 목표
4단계가 `proposed` 수정 제안을 내면, GitHub Actions 가 그 수정을 main 에 적용하고 영향 받는 테스트만 골라 돌린 뒤
결과를 같은 Slack 스레드에 답글로 단다. 수정본(patch)은 run artifact 로 남겨 7단계(승인)가 쓴다.

## 사용자 결정 (11-27)
- 5단계 영향 범위: 클래스 단위 역의존 closure(구현 인터페이스 포함), 넓게 잡는다(놓치는 쪽이 더 위험)
- 5단계 빈 깨짐: `NextrouteApplicationTests.contextLoads` 를 항상 포함
- 6단계 실행 장소: GitHub Actions `workflow_dispatch`. 브랜치 push 안 함. 수정본=artifact(90일)
- 6단계 CI DB: 운영 DB `--schema-only` 덤프 + `flyway_schema_history` 데이터를 커밋, 러너 postgis 에 복원 후 Flyway 가 나머지 적용
- 6단계 결과 보고: 워크플로가 Slack 직접 답글. job 분리(test 에는 Slack 토큰 없음, notify 는 다른 VM)
- 분석기 부담 최소: 분석기는 dispatch 한 번 + 수정 제안 답글에 "테스트 요청함 + 링크" 한 줄만

## 플랜이 정한 것 (SUMMARY 에서 승인 받음)
- **테스트 대상 커밋 = main_sha**(4단계 diff 의 기준). `proposed` 는 블록이 main 에도 그대로 맞을 때만 나오므로
  main 에 적용 가능하다. 7단계 PR 도 main 대상. 배포 커밋 D 로 돌리면 D 에 워크플로·덤프·선택 스크립트가 없을 수 있다.
- **테스트 선택은 워크플로 안에서**(러너에 전체 소스 있음). 분석기는 `src/main/java` sparse 라 테스트 파일이 없고,
  closure 를 분석기에 두면 역할이 커진다. → 6단계 입력은 "블록 + 테스트 목록" 대신 "블록 + 파일 경로 + main_sha".
- **실패하면 수정 전 main 으로 실패한 클래스만 다시 돌려** "기존 실패" 를 가른다(로컬 IT 에 timeout 실패 2건이 원래 있음).
- **6단계 결과는 참고 신호, 안전 관문 아님.** 수정 코드가 test job 안에서 돌므로 결과·기존 실패 판정은 위조 가능하다. 7단계 승인은 사람이 diff 를 보고 한다.

## 보안 규칙 (Opus 검토 반영, 모든 PR 공통)
- 워크플로 `run:` 안에 `${{ inputs.* }}` 직접 쓰기 금지. 전부 `env:` 로 넘긴다. 검증: `ref` `^[0-9a-f]{40}$` + `git merge-base --is-ancestor $REF origin/main`, `thread_ts` `^\d+\.\d+$`, `channel` `^[CG][A-Z0-9]+$`. `edits` base64 해독은 Python 에서
- test job: `permissions: contents: read`, `actions/checkout` 에 `persist-credentials: false`, **Gradle 캐시 저장 금지**(setup-java `cache:` 안 씀. 필요하면 `actions/cache/restore` 읽기만, 배포 빌드와 키 공유 금지)
- notify job: `permissions: {}`(checkout 하면 contents: read), **main 을 checkout**(inputs.ref 아님), `notify_slack.py` 만 실행. summary.json 은 신뢰 불가 데이터
- concurrency 전역 그룹 금지(대기 run 이 조용히 취소되고 notify 도 안 돎). 요청별 `fix-verify-${{ inputs.thread_ts }}`. 분량 상한은 분석기 일일 분석 상한이 이미 막는다
- run inputs 의 `edits` 는 마스킹 안 된 원문 코드(저장소 자체 코드라 저장소 읽기 권한자에게만 보임, 허용). 마스킹된 diff 를 보내면 SEARCH 가 안 맞으므로 금지

## 사실 (11-27 확인)
- 운영 DB: `postgis/postgis:17-3.5`, 확장 plpgsql·postgis 3.5.2·pgstattuple 1.5, 스키마 public 하나, flyway installed_rank 80(V80). DB 5.4GB(데이터, 덤프 대상 아님)
- Redis `redis:7-alpine`. 앱 datasource = `POSTGRES_HOST/PORT/DB/USER/PASSWORD`, `REDIS_HOST`
- `V1__baseline.sql` 1행 `create table spatial_ref_sys` → 빈 DB 재생 불가(기존 노트 4695행)
- `@SpringBootTest` 53개 = 전부 공유 DB(전체 스키마) 필요. Testcontainers 자체 컨테이너 쓰는 파일 38개는 별도(DataJpa/JDBC 계열)
- 기본값 없는 env: `GONG_API, KAKAO_REST_API_KEY, SEOUL_BUS_USAGE, SEOUL_SUBWAY_MASTER, SEOUL_SUBWAY_ROUTE` → 더미, `REDIS_PORT`(application.yaml:31 기본값 없음) → 6379
- toolchain `JavaLanguageVersion.of(17)`(build.gradle:13) → setup-java 17
- `spring.profiles.default: dev` → 테스트는 application-dev.yaml(`baseline-on-migrate`, `ddl-auto: validate`). 덤프가 main 보다 뒤면 Flyway 가 채우고, 운영에만 있는 migration(cherry-pick 이력 있음) 이면 validate 실패
- postgis/postgis 이미지는 POSTGRES_DB 에 postgis·topology·tiger 를 미리 만든다 → 덤프의 `CREATE EXTENSION IF NOT EXISTS postgis` 통과, spatial_ref_sys 는 확장 소유라 덤프에 없음
- pg_dump 17.6+ 는 `\restrict` 줄을 쓴다 → 러너 psql(16.x) 대신 서비스 컨테이너 안 psql 로 복원
- 테스트 22개 파일이 `extends Abstract…`(testsupport + `AbstractJourneyNotificationIT` 등), 23개가 `@Nested`
- workflow_dispatch 는 기본 브랜치에 파일이 있어야 등록된다. dispatch API 는 2026 초부터 `return_run_details` 로 run id·url 반환
- 저장소 private(User 소유). 11월 Actions 사용 약 25분. GitHub Free private 한도 월 2,000분(요금표 기준, 사용자 확인 권장)
- 로컬 Mac IT 전체 111클래스 29m30s. closure: 유틸 클래스 193/512 테스트 파일, 서비스·컨트롤러 3~4
- 4단계 `FixProposal` 에 블록(edits) 원문이 없다 → 필드 추가 필요 (`alert-analyzer/analyzer/fix.py:51`)

---

## PR-1 CI DB 자원 + 테스트 워크플로 뼈대 (수정 없이 지정 테스트만)

브랜치: `ci/fix-verify-db`

1. [ ] `ops/ci/refresh-schema.sh` — Mac 에서 실행, ssh `vps` 로 운영 DB 를 읽기만 한다
   - `docker exec nextroute-postgres-1 sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --schema-only --no-owner --no-privileges'`
   - 이어서 `--data-only --inserts -t flyway_schema_history --no-owner` 를 같은 파일 뒤에 붙임(COPY stdin 안 씀)
   - 결과를 `ops/ci/schema.sql` 로. 첫 줄에 주석: 덤프 시각·flyway 최대 버전
   - 검사(이름 grep 아님 — `user_push_token` 같은 표 이름에 걸림): `COPY` 0개, `INSERT` 는 `public.flyway_schema_history` 만, `CREATE ROLE|ALTER ROLE|PASSWORD '` 0개. 어기면 중단
   - 정합 검사: 덤프의 최대 성공 version == main 의 최대 `V__` 번호, 운영 history 의 모든 version 이 main 에 파일로 있음. 다르면 중단(드리프트)
2. [ ] `ops/ci/schema.sql` 커밋(사용자 실행 or 에이전트 ssh 읽기 — 읽기 전용이라 에이전트가 실행)
3. [ ] `.github/workflows/fix-verify.yml` job `test`
   - `on: workflow_dispatch` inputs: `ref`(40자), `tests`(공백 구분 FQN, PR-1 에서만), 이후 PR-2 에서 확장
   - PR-1 검증용 임시 트리거 `pull_request: paths: [.github/workflows/fix-verify.yml, ops/ci/**]`(입력 없으면 ref=PR head, tests=contextLoads + JourneyStartIT). PR-2 에서 제거
   - `permissions: contents: read`, `timeout-minutes: 60`, concurrency 요청별(보안 규칙)
   - services: `postgres: postgis/postgis:17-3.5`(POSTGRES_DB=mydatabase), `redis: redis:7-alpine`
   - 단계: 입력 검증 → checkout(ref, fetch-depth 0, persist-credentials false) → main 의 `ops/ci/schema.sql` 을 따로 받음(`git show origin/main:ops/ci/schema.sql`)
     → `docker exec -i <svc> psql -v ON_ERROR_STOP=1 -U postgres -d mydatabase -f - < schema.sql`
     → setup-java 17(temurin, 캐시 없음) → `./gradlew test --tests ...`
     (env: POSTGRES_HOST/PORT/DB/USER/PASSWORD, REDIS_HOST, REDIS_PORT=6379, 더미 5개)
   - Gradle: `filter.failOnNoMatchingTests = false` 는 CI 에서만(`-PciSelect` 같은 속성으로) — build.gradle 변경 최소
4. [ ] 검증: PR 의 pull_request 실행
   - contextLoads 통과, Flyway 로그에 "up to date" 또는 V80 이후만 적용
   - `JourneyStartIT` 통과, `@Nested` 있는 클래스 1개를 외부 FQN 으로 걸었을 때 내부 클래스도 도는지 확인(아니면 `'FQN$*'` 패턴 추가)
   - 덤프 복원 오류가 ON_ERROR_STOP 으로 실패하는지 일부러 깬 줄로 1회 확인(로컬)
   - 걸린 시간(빌드·테스트) 노트 `### 검증` 에 기록
5. [ ] implementation_note.md: 확정(덤프 출처·갱신 주기: 마이그레이션 10개마다 or 실패 시), 함정, 검증

## PR-2 수정 적용 + 테스트 선택 + artifact + Slack notify

브랜치: `ci/fix-verify-run` (PR-1 머지 후 origin/main 에서)

1. [ ] `ops/ci/select_tests.py` — scratchpad `closure2.py` 를 옮겨 정리
   - 입력: 바뀐 main 소스 경로 목록. 출력: 테스트 클래스 FQN 목록(줄 단위)
   - closure: 단순 이름 토큰 참조의 역방향, hop 제한 없음(넓게). 상위 타입은 class·interface·record·enum 의 `extends`·`implements` 전부, 키는 FQN(같은 단순 이름 여러 개 허용)
   - 테스트 쪽도 closure: closure 를 참조하는 test 파일 → 그 test 파일(추상 base·fake·fixture)을 참조하는 test 파일 … 고정점까지
   - 출력 때만 추상 클래스와 `@Test` 없는 파일 제외. 패키지는 `package` 선언에서
   - 들어오는 참조가 없는 변경 클래스(`@Scheduled`·`@EventListener`·`@Configuration`·필터)는 같은 최상위 기능 패키지의 테스트 전부 추가
   - `NextrouteApplicationTests` 항상 포함. 바뀐 파일이 java 가 아니면(리소스·SQL) 전체 테스트
   - 단위 테스트: `alert-analyzer` 와 별개라 `ops/ci/test_select_tests.py`(pytest, 작은 가짜 트리) — CI 는 기존 analyzer 워크플로와 별도 step
2. [ ] `ops/ci/apply_edits.py` — 4단계 `apply_edits` 규칙 그대로(SEARCH 정확히 1회, 겹침 금지, CRLF 정규화). 실패 시 exit 2
   - 입력: `edits` = base64(JSON `{"path": "...", "edits": [{"search","replace"}]}`)
3. [ ] fix-verify.yml 확장
   - inputs: `ref`, `edits`(base64), `channel`, `thread_ts`, `label`(장애 제목 짧게). `tests` 입력 제거
   - job `test`(Slack 토큰 없음, contents: read):
     apply_edits → `git diff > fix.patch` → **patch 를 테스트 전에 upload**(`fix-patch`, retention 90)
     → select_tests → gradlew test → 판정:
       - 종료 코드 ≠0 이고 XML 없음: `compileJava` 실패면 `compile_failed`(오류 마지막 20줄), 아니면 `error`
       - Flyway validate 오류: `error`, reason=schema_drift
       - 실행 테스트 0개: `error`(절대 passed 아님)
     → 실패 클래스가 있으면 `build/test-results` 를 옮겨 두고, `git worktree add ../base $REF` 에서 그 클래스만 재실행 → 기존 실패 판정
     → `summary.json` upload(`fix-summary`). 블록 적용 실패도 summary 로(status=apply_failed)
   - pull_request 임시 트리거 제거
   - job `notify`: `needs: test`, `if: always()`, 다른 VM, `secrets.SLACK_BOT_TOKEN`, 보안 규칙대로 main checkout·권한 최소
     summary 를 받아 `ops/ci/notify_slack.py` 로 chat.postMessage(thread_ts). summary 없으면(test job 이 중간에 죽음) "테스트 실패(결과 없음) + run 링크"
   - summary.json 은 신뢰 불가 입력(수정 코드가 썼을 수 있음): notify 는 셸 보간 금지, Python 이 읽고 길이 자르고 Slack escape, 토큰 모양 문자열 마스킹
4. [ ] `summary.json` 계약
   ```json
   {"status": "passed|failed|apply_failed|compile_failed|error", "reason": "", "ref": "<40>", "path": "...",
    "selected": 12, "passed": 40, "failed": 1, "skipped": 0, "duration_s": 512,
    "failures": [{"test": "Class#method", "message": "<=200자", "baseline": false}],
    "run_url": "...", "patch_artifact": "fix-patch"}
   ```
5. [ ] Slack 문구: `:test_tube: *수정 검증* 통과 40 · 실패 1(새 실패 1, 기존 실패 0) · 12개 클래스 · 8분 32초` + 새 실패 최대 5줄 + run 링크
6. [ ] 사용자 작업(에이전트가 못 함): `gh secret set SLACK_BOT_TOKEN` (프롬프트에 숨김 입력, 채팅에 붙이지 말 것)
7. [ ] 검증: 수동 dispatch 3번
   - 정상 블록(무해한 주석 1줄 추가) → passed + artifact 2개 + 스레드 답글
   - 테스트를 깨는 블록 → failed, 새 실패 표시
   - 안 맞는 SEARCH → apply_failed 답글
   - 컴파일 안 되는 블록 → compile_failed 답글(오류 줄 포함)
   - select_tests 단위 테스트: 추상 base 의 하위 IT 선택, 인터페이스 주입, 같은 단순 이름 두 패키지, 참조 없는 @Configuration
   - 테스트 채널/스레드(시험용 메시지) 사용

## PR-3 분석기 dispatch

브랜치: `feat/analyzer-fix-verify`

1. [ ] `fix.py`: `FixProposal.edits: list[Edit]`, `repo_path`(저장소 기준 전체 경로, `src/main/java/...` 로 정규화) 추가. proposed 일 때 채움. 원문(마스킹 전)
2. [ ] `verify.py`(신규, 작게): `dispatch(http, repo, token, workflow, proposal, channel, thread_ts, label) -> bool`
   - `POST /repos/{repo}/actions/workflows/fix-verify.yml/dispatches` `{"ref": "main", "inputs": {"ref": proposal.main_sha, ...}, "return_run_details": true}`, timeout 10s, 재시도 없음
   - 응답에 run url 있으면 그 링크, 204 면 runs 목록 링크
   - inputs 총 길이 60,000자 넘으면 dispatch 안 함(False, 사유 로그)
3. [ ] `config.py`: `ANALYZER_FIX_VERIFY_ENABLED`(기본 false), fix 꺼져 있으면 켤 수 없음(시작 가드)
4. [ ] `worker.py`: proposed 이고 enabled 면 dispatch → `render_fix(proposal, verify=...)` 끝에 한 줄
   - 성공: `:hourglass: 테스트 요청함 — <run 링크|진행 보기>`
   - 실패: `:warning: 테스트 요청 실패(사유)`
   - 로그 `fix_verify dispatched=… reason=…`
5. [ ] 테스트(pytest): dispatch payload 모양, 길이 초과 생략, 401/timeout 시 False·분석 계속, render 한 줄, 플래그 가드
6. [ ] 사용자 작업: fine-grained 토큰에 **Actions: Read and write** 추가(웹 UI). 토큰 값은 그대로라 env 변경 없음
7. [ ] 배포: 이미지 → VPS env `ANALYZER_FIX_VERIFY_ENABLED=true` 줄 추가 확인 → force-recreate
8. [ ] 검증: 4단계 스모크와 같은 방식(재현 가능한 가짜 장애)으로 proposed → 실행 → 스레드에 결과 답글

## 리뷰
- 각 PR: 구현 후 독립 Opus 리뷰 에이전트 → 지적 반영 → 머지
- 경계 리뷰 대상: summary.json 계약, workflow inputs, permissions, notify 의 신뢰 불가 입력 처리, 플래그 기본값

## 범위 밖
- 7단계(승인 버튼, PR 생성), 8단계(비용). 커버리지 지도. 분석기 polling


## 구현 중 바뀐 것 (PR-2, 11-27)
- 참조 없는 변경 클래스의 대체 범위: "패키지 앞 5단계" → **그 클래스의 패키지와 하위**. 실측: `infrastructure.adapter.in.api.HealthController` 하나가 5단계 기준이면 130 클래스(모든 adapter), 자기 패키지 기준 35
- trusted checkout: `ref: main` 고정 → **워크플로 파일과 같은 커밋**(기본값). 분석기는 항상 ref=main 으로 dispatch 하므로 운영에선 main. 브랜치에서 dispatch 하려면 쓰기 권한이 필요하고 그 사람은 워크플로 자체를 바꿀 수 있어 고정이 막는 것이 없으며, 고정하면 머지 전 검증(브랜치 dispatch)이 불가능
- 오케스트레이션은 `ops/ci/run_tests.py`(prepare / test 두 단계) — patch 업로드를 테스트 실행 전에 끼우기 위해 둘로 나눔
- pull_request 트리거는 지우지 않고 `ops/ci` 단위 테스트 job(`scripts`)만 돌게 바꿈. test·notify 는 dispatch 에서만
- summary.json 실제 계약: `ref`·`run_url`·`patch_artifact` 없음(notify 가 run 링크를 github 컨텍스트로 만들고 artifact 이름은 고정 `fix-patch`), 대신 `compile_errors`·`baseline_checked` 추가. reason 값: bad_input, edits_do_not_match, no_tests, no_tests_ran, gradle_failed, timeout, schema_drift, exception:<타입>
- 보안 규칙 추가: **이 저장소에 Actions cache(gha) 도입 금지**(test job 의 수정 코드가 같은 job 의 JS action(upload-artifact·checkout post)을 고쳐 쓸 수 있어 cache 토큰을 얻으면 main 범위 cache 오염). 더 막으려면 Run tests 를 `docker run`(target·out 만 마운트)으로 격리 — 후속 과제
- 한계: baseline 재실행은 같은 Postgres·Redis 를 쓴다(수정본 테스트가 남긴 데이터가 영향 가능). 표시 이름에 비결정 값이 있는 테스트는 이름 비교가 안 맞을 수 있음
