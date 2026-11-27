# RAG 장애 맥락 (배포 커밋 코드 + 과거 장애 카드)

## 뭘 하나
알림 분석에 장애 난 role 의 배포 커밋 코드(스택·로그 태그로 찾은 메서드)와 메타데이터로 좁힌 과거 장애 카드를 넣는다. LLM 답에 "고칠 곳 후보"를 더하고, 코드·카드 번호도 코드로 대조한다.

## 왜
지금 분석은 로그와 숫자만 봐서 고칠 곳을 못 짚는다(실험: 카드 넣으면 정확한 클래스 1→8/24). 파이프라인 다음 단계(수정 코드)의 출발점.

## 핵심 결정
- 코드는 배포 커밋 기준: 빌드 때 jar 에 40자 커밋을 굽고 앱이 `app_build{revision}` 메트릭으로 알림 → 분석기가 GitHub 에서 그 커밋만 받음(main·deploy.sh 전달 기각)
- 코드 찾기는 스택 → 로그 태그·경로 키워드. 의미 검색은 미루고 "무엇으로 찾았나"를 기록해 숫자로 판단
- 코드(S)는 원인 근거 가능, 카드(K)는 고칠 곳 후보에서만. 원인에 K 오면 무효
- 카드는 메타데이터로 좁혀 최대 10장(벡터 검색 기각: 25장, 분석 월 7회)
- 메서드 단위 조각 + 클래스 설명 머리말, 메서드 최대 5개·약 6천 토큰

## 건드는 곳
build.gradle, BuildInfoMetrics, alert-analyzer(Dockerfile, config, code, cards, context, masking, llm, prompts/system.md, worker, slack, incidents/cards.json, eval)

## 리스크
- cherry-pick·로컬 수정 빌드는 GitHub 에 없거나 dirty → 코드 없이 "버전 불확실"
- GitHub 토큰 노출: env 에만, GIT_ASKPASS, 예외 메시지 가드 테스트
- 카드가 잘못 이끔(실험 2건): 원인 근거 금지 + 번호 대조 + 0점 제외

## 검증법
PR-1: Prometheus 에 api·worker·worker2 의 app_build revision 이 배포 커밋(40자)과 일치. PR-2: pytest + 실제 5xx 시각으로 code 수집 결과 확인. PR-3: pytest + 빼고 맞히기 재실행이 기준(1→8/24) 이상.
