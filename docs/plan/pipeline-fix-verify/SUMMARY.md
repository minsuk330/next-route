# 파이프라인 5·6단계 — 수정 제안 자동 테스트

## 뭘 하나
4단계가 수정 제안을 내면 GitHub Actions 가 그 수정을 main 에 적용하고, 영향 받는 테스트와 컨텍스트 기동 테스트만 골라 돌린다.
결과는 같은 Slack 스레드에 답글로, 수정본은 run artifact(90일)로 남는다.

## 왜
사람이 수정 제안을 보기 전에 "이 수정이 다른 곳을 깨는가"를 숫자로 먼저 보여 주기 위해.

## 핵심 결정
- 테스트 대상 = main 커밋: 4단계 diff 기준이고 7단계 PR 도 main 대상 (배포 커밋 기각: 워크플로·덤프 없을 수 있음)
- 테스트 선택은 워크플로 안에서: 러너에 테스트 소스가 있고 분석기를 키우지 않음 (분석기 계산 기각)
- CI DB = 운영 스키마 덤프 커밋 + Flyway 나머지 적용, Slack 은 토큰 없는 test job 과 분리한 notify job 이 보냄
- 실패하면 수정 전 코드로 실패 클래스만 재실행해 "기존 실패"를 가름
- 분석기는 dispatch + "테스트 요청함" 한 줄만, 플래그 기본 off
- 테스트 결과는 참고 신호: 수정 코드가 같은 job 에서 돌아 위조 가능. 7단계 승인은 사람이 diff 로

## 건드는 곳
ops/ci/(refresh-schema.sh, schema.sql, select_tests.py, apply_edits.py, notify_slack.py), .github/workflows/fix-verify.yml,
alert-analyzer(fix.py, verify.py, config.py, worker.py, slack.py), GitHub secrets, 분석기 토큰 권한

## 리스크
- 유틸 클래스 수정은 테스트 190여 개 → 30분 넘을 수 있음 (timeout 60분)
- 덤프가 오래되면 Flyway 적용 몫이 늘어남 (덤프 갱신 스크립트로 대응)
- Slack 토큰이 GitHub secrets 에도 생김
- LLM 코드가 CI 에서 실행됨 → 입력 env 전달, checkout 자격증명 끔, Gradle 캐시 저장 금지, notify 는 main 코드만

## 검증법
PR-1: PR 임시 트리거 실행으로 contextLoads + IT 1개 통과. PR-2: 통과/실패/블록 불일치/컴파일 실패 네 경우가 스레드에 맞게 답글.
PR-3: 가짜 장애 → 수정 제안 → 실행 → 결과 답글까지 한 번에.
