# 평가기 선점 순서: 급한 환승 먼저

## 뭘 하나
서버가 밀릴 때 환승이 위험하거나 임박한 여정을 먼저 계산한다. 급하지 않은 여정은 60초 넘게 밀리면 같은 줄로 올려 굶지 않게 한다. 처리량(동시 4)은 그대로 둔다.

## 왜
지금은 예정 시각 순이라, 밀리면 2분 뒤 환승하는 사람과 40분 뒤 환승하는 사람이 같은 줄에서 기다린다(PR #409 측정 한계).

## 핵심 결정
- 급함 판단 = 간격 정책의 기존 `urgent()` + 시작·단계 전이·경로 교체. 기준을 두 벌로 만들지 않는다
- 기다림 상한 60초. 최대 간격(180) + 상한 < 낡음(300) 을 부팅 검증으로 강제해 비급 여정이 모름이 되지 않게 한다
- 저장 = 컬럼 하나(V66). 매번 계산은 조인과 전이 직후 값 문제로 기각
- EDF(마감 순)는 기각: 과부하에서 도미노로 급한 여정이 다시 밀린다
- 스위치 `claim-order`(기본 URGENT_FIRST, DUE_TIME = 롤백). 같은 빌드로 전후를 측정한다

## 건드는 곳
V66 마이그레이션, JourneySession, JourneySessionRepository, JourneyEvaluatorProperties, application.yaml, JourneyDueClaimService, JourneyEvaluationService, JourneyEvaluationScheduleWriter, 테스트(JourneyDueEvaluatorIT·JourneyEvaluationIT·PropertiesTest·알림 IT 호출부), 하네스(#409 추가 커밋)

## 리스크
- V66 VPS 수동배치 누락 시 운영 부팅 실패 → 노트·PR 에 명시
- 선점 쿼리에 정렬 단계가 생긴다 → 1만 행 EXPLAIN 으로 확인
- 비급 여정이 더 늦어진다(대가) → 측정해서 공개

## 검증법
순서·기아 방지·롤백·급함 저장 계약 테스트와 기존 평가기 IT 회귀가 통과하면 완료. 과부하(지연 500ms)에서 급한 여정 판단 나이 p95 를 DUE_TIME 대비 URGENT_FIRST 로 비교해 기록한다.
