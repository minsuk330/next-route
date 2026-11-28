# Slack 알림 가독성 (Grafana 본문 템플릿 + 답글 Block Kit)

## 뭘 하나
Grafana 알림 본문을 "제목 · summary · 링크 · 채점 · fp" 로 줄이는 템플릿을 넣고, 분석기·fix-verify 답글을 Block Kit(큰 제목, 회색 근거, 버튼)으로 바꾼다.

## 왜
지금은 Value·Labels·긴 URL·내부 id·토큰 수가 섞여 핵심(무슨 장애, 원인 가설, 할 일)이 안 보인다.

## 핵심 결정
- 남길 정보: 1번 가설만 자세히(근거 2줄), 내부 id·커밋·라인·메타 숨김 — 두 메시지 같이 봄 (사용자)
- 답글은 Block Kit, Grafana 본문은 텍스트 템플릿 — webhook 은 blocks 불가 (사용자)
- 분석기 + fix-verify 답글 모두 Block Kit — 같은 스레드 (사용자)
- 메타는 메시지 대신 worker 로그에만 (이미 기록 중, model 만 추가)
- 주간 요약의 알림 이름 파싱은 옛·새 제목 둘 다 읽음 — 지난주 메시지 호환

## 건드는 곳
grafana provisioning alerting (templates.yaml 신규, contact-points.yaml), alert-analyzer slack.py · worker.py · weekly.py · payload.py, ops/ci/notify_slack.py, 각 테스트

## 리스크
- 버튼이 상호작용 URL 없어 경고 표시 → links() 한 곳에서 링크 글자로 전환 (사용자 확인 대기)
- Slack 이 블록 거절하면 답글 소실 → text 만으로 한 번 재전송
- contact point 시험 알림이 분석기도 돌림 → TestAlert 는 분석 제외
- 운영 Grafana provisioning 은 git 아님 → .bak 백업 후 복사·재시작

## 검증법
로컬 Grafana templates/test 렌더(firing·resolved), pytest·unittest 통과, 시험 스레드 실 게시로 모양·이스케이프·버튼 확인, 배포 후 contact point 시험 알림.
