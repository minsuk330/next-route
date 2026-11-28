# VERIFY — slack-block-kit

## verify-spec 1차 (2026-11-28, 독립 검증자, HEAD 75a4d869)
판정: verify-spec: FAIL (blocker 0, major 3, minor 4, 틀린 가정 A2)

### 지적
| # | 등급 | 근거 | 위치 | 내용 | 처리 |
|---|---|---|---|---|---|
| 1 | major | 실행함 | PLAN:71, payload.py:58,71-74 | receivers/test 본문(alertname TestAlert, firing)이 select_new 에서 선택됨 → 시험 알림이 운영 분석·LLM 비용·답글 유발 | 반영: SKIPPED_ALERTNAMES 에 TestAlert + 테스트, 배포 3단계 문구 수정 |
| 2 | major | 경로 따라감 | slack.py:313-315, worker.py:172-174 | Slack 이 블록 거절(ok=false)하면 SlackError → _notify 경고만, 답글 통째 소실 | 반영: post 에서 블록 오류 시 text 만 재전송(분석기·notify_slack), 테스트 |
| 3 | major | 줄만 | PLAN:70,75 | 롤백은 백업 전제인데 백업 단계 없음 | 반영: 배포 2단계 앞에 cp .bak + templates 부재 확인 |
| 4 | minor | 실행함 | PLAN:14 | A2 틀림: templates: 의 $ 는 치환 안 됨, settings 만 | 반영: A2 수정 |
| 5 | minor | 경로 따라감 | worker.py:104, config.py:39 | render 호출은 worker.py, github_repo 기본값 있음 | 반영: 작업 위치 worker.py, settings.github_repo |
| 6 | minor | 경로 따라감 | test_cards.py:142-147, test_fix.py:161-180 | str 반환 전제 테스트 누락, code dict 에 path 없음 → KeyError | 반영: 갱신 목록 추가, path 없으면 버튼 생략 |
| 7 | minor | 줄만 | notify_slack.py:30,152 | RUN_URL 정규식 없음, 빈 url 버튼 → 거절 | 반영: RUN_URL 정규식 신설, 불통과 시 버튼 생략 |

### 가정 표 (확인됨 외)
| 가정 | 판정 | 근거 |
|---|---|---|
| A1 | 확인 못 함 | 운영 실측 전제, 로컬에서 Slack 본문 못 봄. fixture 와는 맞음 |
| A2 | 틀림 | 실행: settings 만 치환 → 수정함 |
| A7 | 확인됨(blocks 50 은 직접 못 봄) | docs.slack.dev |
| A8 | 확인 못 함 | header 이스케이프 문서 언급 없음 → 단계 5 실 게시 |
| A9 | 확인 못 함 | url 버튼도 interaction payload 전송 → 경고 위험, 대안 있음 |

### 확인 못 함
- 운영 Grafana attachment 실구조·렌더 (실 게시 필요)
- header plain_text 의 &amp; 표시, 버튼 경고 (실 게시 필요)
- 깨진 템플릿 시 Grafana 12 기동 실패 여부
- provisioning 파일 삭제 시 템플릿 DB 잔존 여부

### 문제없음 (요약)
기준선 pytest 267 / unittest 48 통과, C1 제목 실렌더 확인, 모든 규칙 summary·severity 보유, 배포 순서(분석기 → Grafana)로 weekly 공백 없음, fix-verify 는 main 스크립트 사용, C1~C5 관찰 가능.

## verify-spec 재검증 (2026-11-28, 새 검증자 — 1차 검증자 재개 불가)
지적 1~7 전부 닫힘, 새 blocker·major 없음.
minor(반영): render 의 repo 는 기본값 키워드 / 재전송 조건 = 첫 게시 ok=false 전부 / 분석기 [진행 보기] 빈 url 이면 버튼 생략.
minor(기록만): C2 의 find_thread 확인은 TestAlert 가 분석 제외라 운영에선 못 보고 단위 테스트로만.

verify-spec: PASS
