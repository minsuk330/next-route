# Slack 알림 가독성 — Grafana 본문 템플릿 + 답글 Block Kit

## 결정 (사용자, 2026-11-28)
1. 남길 정보 (가): 알림 본문 = 제목(심각도 이모지 · 규칙 · 대상) + summary + 규칙/끄기 링크 + 채점 + fp. 해결 = 한 줄.
   AI 분석 = 헤드라인, 1번 가설만 자세히(근거 2줄, 내부 id·커밋·라인 숨김, 같은 로그 합침), 2번부터 한 줄, 메타(모델·토큰·시간) 숨김.
   이유: 두 메시지를 같은 스레드에서 같이 봄.
2. 방식 (나): 답글은 Block Kit(header · section · context · divider · actions). Grafana 본문은 텍스트 템플릿(webhook 은 blocks 불가).
3. 범위 (가): 분석기 답글 + fix-verify notify_slack.py 답글 모두 Block Kit. 이유: 같은 스레드.
- 범위 밖: 규칙 2개 동시 발화로 분석이 두 번 달리는 문제, 주간 요약(weekly render) 모양.
- 시안: 시험 스레드(C0CCXF6UGN4 / 1795782729.655779) 답글 1795841871.102789, scratchpad slack-mockup.html.

## 가정
- A1 Grafana 12.0.0 slack contact point(incoming webhook)는 `title`·`text` 를 attachment 의 title·text 로 보낸다(실측: 채널 메시지 attachments[0].title = "[FIRING:1] …", text = 본문).
- A2 env 치환(`$VAR`)은 contact point settings 에서만 일어난다(verify-spec 실측: `templates:` 의 `$i` 는 그대로). → contact-points.yaml 의 title·text 줄에만 `$` 금지. 템플릿 안 `$`·`$.` 는 써도 된다.
- A3 알림 템플릿은 provisioning `templates:` (orgId, name, template) 로 넣을 수 있고, 12.0.0 의 `POST /api/alertmanager/grafana/config/api/v1/templates/test` 로 렌더를 미리 볼 수 있다.
- A4 group_by = [grafana_folder, alertname] (policies.yaml) → 한 메시지는 규칙 하나, 대상(app·client_name)은 여러 개일 수 있다.
- A5 분석기 find_thread 는 메시지 JSON 전체에서 fingerprint 를 찾는다(slack.py find_thread) → fp 줄 위치·모양이 바뀌어도 `fp: <hex>` 가 어딘가 있으면 된다.
- A6 weekly.parse_alert_message 는 attachment title 의 `[FIRING…] name nextroute (…)` 로 규칙 이름을 뽑는다 → 새 제목 형식을 읽도록 바꿔야 하고, 지난주 옛 메시지도 계속 읽어야 한다.
- A7 Block Kit 한도: header plain_text ≤150자, section text ≤3000자, context 요소 ≤10개, 메시지 blocks ≤50, button text ≤75자, url ≤3000자. chat.postMessage 에 blocks 가 있으면 `text` 는 푸시·검색용 대체 문구.
- A8 Block Kit mrkdwn 텍스트도 & < > 이스케이프 규칙이 같다. header plain_text 는 mrkdwn·멘션을 해석하지 않는다(실 게시로 확인 — 단계 5).
- A9 URL 버튼은 앱에 상호작용 Request URL 이 없어도 링크를 연다. 경고 표시가 뜨면 links() 한 곳만 바꿔 context 링크 글자로 바꾼다(사용자 확인 대기).
- A10 bundle.code 항목은 path · revision · start_line · end_line · symbol 을 가진다(code.py CodeSnippet) → 코드 버튼 URL 을 만들 수 있다.

## 작업 순서
### 1. Grafana 본문 템플릿 (ops/observability/grafana/provisioning/alerting/)
- [ ] 새 `templates.yaml`: `nr.slack.title`, `nr.slack.text`.
  - title: firing 이면 severity critical → 🔴, 그 외 🟡; resolved 면 `🟢 해결 ·`. 뒤에 `{{ .CommonLabels.alertname }}`, 대상이 공통이면 ` · {{ or .CommonLabels.app .CommonLabels.client_name .CommonLabels.scenario }}`.
  - text(firing): 발화 alert 마다 `.Annotations.summary` 한 줄(최대 5개, 넘으면 "외 n건"), `<GeneratorURL|규칙 보기> · <SilenceURL|끄기>`(첫 alert 기준), 채점 줄, `fp: …` 줄(마지막).
  - text(resolved): `fp: …` 만(제목이 내용 전부).
- [ ] `contact-points.yaml`: slack receiver 에 `title: '{{ template "nr.slack.title" . }}'`, `text: '{{ template "nr.slack.text" . }}'`. 주석 갱신.
- [ ] 검증: 로컬 `grafana/grafana:12.0.0` 컨테이너에 provisioning 디렉터리를 마운트해 기동 로그에 오류 없음 + templates/test API 로 firing·resolved·대상 2개 샘플 렌더 결과를 노트에 붙임.

### 2. weekly.parse_alert_message (alert-analyzer/analyzer/weekly.py)
- [ ] 옛 제목(`[FIRING:n] name nextroute (…)`)과 새 제목(`🔴|🟡 name · 대상`)을 모두 읽음. `🟢 해결`·RESOLVED 는 제외.
- [ ] test_weekly: 옛/새 firing, 새 resolved, 대상 없는 제목.

### 3. 분석기 Block Kit (alert-analyzer/analyzer/slack.py, worker.py, payload.py)
- [ ] payload.py `SKIPPED_ALERTNAMES` 에 `TestAlert`(Grafana contact point 시험 알림) 추가 + test_payload — 시험 알림이 LLM 분석을 돌리지 않게.
- [ ] `SlackMessage(text: str, blocks: list[dict])` dataclass + 블록 헬퍼 `header/section/context/divider/links` (한도 A7 에서 자름, 자를 때 `&am` 같은 반쪽 이스케이프 제거 — 기존 _fit 규칙).
- [ ] `render(verdict, bundle, *, link, repo=None)` → SlackMessage (repo 는 기본값 있는 키워드 — 기존 호출 호환)
  - header `🔍 {headline}`(150자), context `AI 분석 · 참고용, 틀릴 수 있음` (+ deploy_related 면 `♻️ 직전 재시작 …`).
  - 가설 1: section `*① {분류} · 확신 {x}*\n{cause}`, context `근거  {e1} · {e2}`, section `*확인*  {next_check}`.
  - 근거 표시(_describe 대체 _brief): id·커밋·라인 숨김. 로그 템플릿 = `{app} {level} {total}건 (직전 60분 {base})` + 템플릿 앞 40자, 같은 (app, level, logger) 는 하나로 합치고 ` 외 n`; 지표 = 기존 _point; 코드 = symbol; m:restarts = 재시작 목록. 최대 2개.
  - 가설 2+: context `*②* {분류} · 확신 {x} — {cause 120자}`.
  - collection_errors: context `⚠️ 수집 실패 …`.
  - fix_candidates: divider + section `🔧 *고칠 곳*  \`{place}\`\n{why}` (후보 최대 2).
  - actions: [로그 보기](link), [코드 보기](첫 고칠 곳의 코드 ref → github.com/{repo}/blob/{revision}/{path}#L{s}-L{e}; repo 없거나 ref 가 코드가 아니면 생략).
  - text(대체) = `🔍 AI 분석: {headline}`.
  - 메타(모델·토큰·시간·없는 근거 수)는 메시지에서 뺌 — worker 로그에 이미 있음(tokens, elapsed_s, invalid_evidence). model 을 로그 줄에 추가.
- [ ] render_failure → section `⚠️ *AI 원인 분석 실패*  {reason}` + [로그 보기].
- [ ] render_fix → proposed: section `🛠 *수정 제안*  \`{symbol}\`` + context `배포 {rev} · main {sha} · 사람이 검토 후 적용` + section `\`\`\`{diff}\`\`\``(2900자에서 자름) + verify: context `⏳ 테스트 요청함 — 결과는 이 스레드에` + [진행 보기](url 이 빈 값이면 버튼 생략 — 빈 url 버튼 금지는 분석기에도 적용) / context `⚠️ 테스트 요청 실패({reason})`. main_changed → section 경고 + context 커밋 목록. 그 외 None.
- [ ] render_budget_notice → context 한 줄.
- [ ] SlackClient.post(message: SlackMessage | str, thread_ts) → payload 에 blocks 포함. Slack 이 첫 게시를 ok=false 로 거절하면(오류 종류 무관 — 첫 시도 실패면 게시된 것이 없어 중복 없음) blocks 빼고 text 만으로 한 번 다시 보냄 + 경고 로그(블록 오류로 답글이 통째로 사라지지 않게). 대체 text 는 그때 읽을 수 있게 헤드라인 + 1번 가설 cause + 할 일 정도를 담음(≤ 3000). reply/Notifier 시그니처를 SlackMessage | str 로. weekly.post(str) 는 그대로.
- [ ] worker.py process: render 에 `settings.github_repo` 전달(기본값 "minsuk330/next-route"). 코드 버튼은 repo 가 비었거나 code dict 에 `path` 가 없으면 생략(`c.get`, KeyError 금지).
- [ ] test_slack 갱신: 이스케이프(블록 안 `<!channel>` 무력화), 한도(header ≤150, section ≤3000, blocks ≤50), 내부 id·`@rev`·`L536`·토큰 수가 블록에 없음, 같은 로그 합침, 2번 가설 한 줄, 코드 버튼 URL, repo 없으면 버튼 생략, 대체 text. test_worker: Notifier 에 SlackMessage 전달. test_cards(:139-147 Explore 문구·MAX_TEXT), test_fix(:161-180 render_fix str·MAX_TEXT)도 블록 기준으로 갱신. 블록 거절 → text 재전송 테스트.

### 4. fix-verify 답글 Block Kit (ops/ci/notify_slack.py)
- [ ] render → (text, blocks). section `🧪 *수정 검증*{ — label}  {icon 상태}`, context `통과 · 실패(새/기존) · 범위 · 시간 · \`path\``, 새 실패 section(최대 5줄), compile_failed 는 section 코드블록, actions [실행 보기] (+ approvable 이면 [diff 보고 승인] → run_url).
- [ ] --pr-url: section `➡️ *Draft PR 만듦* (AI 제안, 리뷰 후 머지)` + [PR 보기]. --pr-failed: section 경고 + [실행 보기].
- [ ] post() 에 blocks, 거절 시 text 만으로 한 번 재전송(분석기와 같음). clean() 마스킹·이스케이프 그대로(블록 텍스트 전부 clean 경유). 새 정규식 `RUN_URL = ^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/runs/\d+$` 추가 — 통과 못 한 run_url 은 버튼을 빼고, 빈 url 버튼은 절대 만들지 않음. PR_URL 은 기존 정규식.
- [ ] test_notify_slack 갱신: 블록 텍스트에 `<!channel>`·비밀 모양 없음, 백틱 3개 수, 한도, approvable 일 때만 승인 버튼, --dry-run 출력은 JSON.

### 5. 로컬 검증
- [ ] `cd alert-analyzer && python -m pytest -q`, `cd ops/ci && python3 -m unittest discover -s tests`.
- [ ] 실 게시: fixture verdict/bundle(지난밤 401 장애 모양)로 render/render_fix/notify_slack 결과를 시험 스레드에 올려 휴대폰·데스크톱에서 확인(A8: header 이스케이프, A9: 버튼).

### 6. PR · 배포
- [ ] PR 1개(Grafana + weekly + 분석기 + notify_slack). 머지 → alert-analyzer-image 빌드.
- [ ] 배포(사용자에게 진행 여부 확인 후):
  1. 분석기: 이미지 pull → `docker compose up -d --force-recreate alert-analyzer` (observability compose, /root/apps/observability).
  2. Grafana: 먼저 `cp contact-points.yaml contact-points.yaml.bak-<날짜>` 백업 + templates.yaml 이 아직 없음을 확인. 그다음 templates.yaml·contact-points.yaml 을 /root/apps/observability/grafana/provisioning/alerting/ 로 복사(VPS 는 git 아님) → grafana 재시작 → 기동 로그 provisioning 오류 없음.
  3. contact point "Test"(receivers/test API)로 Slack 에 시험 알림 1건 → 새 모양 확인. 시험 알림(alertname `TestAlert`)은 analyzer webhook 에도 가므로 단계 3 에서 payload.py `SKIPPED_ALERTNAMES` 에 `TestAlert` 추가 + test_payload 테스트(verify-spec #1, 분석기 배포가 Grafana 시험보다 먼저). 분석 답글 모양은 실알림 다음 발화 때 확인.
- [ ] fix-verify notify_slack 는 main 머지 즉시 적용(워크플로가 main 의 ops/ci 사용).

## 롤백
- Grafana: 백업한 contact-points.yaml 로 되돌리고 templates.yaml 삭제 후 재시작.
- 분석기: 이전 이미지 태그로 recreate.
- Grafana 롤백 뒤 옛 제목으로 돌아가도 weekly 는 옛·새 둘 다 읽으므로 그대로.
- notify_slack: revert PR.

## 합격 기준
- C1 templates/test 렌더: firing 제목 `🟡 ERROR 로그 급증 · worker2`, 본문에 Value/Labels/description/긴 URL 문자열 없음, fp 줄 있음; resolved 제목 `🟢 해결 · …`.
- C2 운영 Grafana 시험 알림이 새 모양으로 Slack 에 옴 + 분석기 find_thread 가 그 메시지에서 fp 를 찾음(단위 테스트: 새 모양 메시지 JSON 으로 find_thread 성공).
- C3 pytest·unittest 전부 통과, 새 테스트가 결정 1의 "숨김" 항목마다 하나씩 있음.
- C4 시험 스레드 실 게시에서 분석·수정 제안·검증 결과 답글이 시안 모양(header, 회색 근거, 버튼)으로 보이고, `<!channel>` 를 넣은 fixture 가 멘션되지 않음.
- C5 weekly.parse_alert_message 가 옛·새 firing 둘 다 이름을 뽑고 resolved 는 뺌.
