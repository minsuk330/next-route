# 이벤트 기반 재평가 (#415-3)

## 뭘 하나
진행 중 여정이 보는 정류장·노선·역의 실시간 데이터를 한 곳에서 감시하다가, 판정에 영향 줄 만큼 바뀌면 그 여정의 다음 평가를 앞당긴다. 지금의 간격 폴링은 안전망으로 남긴다.

## 왜
감지 지연의 95% 이상이 "다음 평가까지 대기"라 긴급 10초·주의 30초·여유 2분 SLO 를 간격 조정만으로는 못 맞춘다.

## 핵심 결정
- 처리 중인 여정은 표시(dirty)만, 아니면 앞당김 + 등급별 최소 간격 — 결과를 버리는 방식은 livelock 이라 기각. 새 칼럼은 SQL 전용 + @DynamicUpdate(엔티티 전체 저장이 표시를 지움)
- 호출 수를 여정 수가 아니라 노선 수에 비례시킴(노선 단위 조회) — 같은 감지 주기에서 키 폴링의 0.6~0.16배, 사용자 수와 무관한 상한 약 520만/일(PR-0 실측)
- 다중 인스턴스 전제: 평가기는 모두가 나눠 처리(DB 행이 대기열, MQ 불필요), 감시자는 Redis 리더 잠금으로 한 대만, 지문은 리더 메모리(Redis 는 리더가 갈릴 때 깜빡임), 시계는 앱 시계 + 차이 감시
- 판정이 쓰는 값만 지문 + 흔들림 무시(히스테리시스) — 버스 도착 초는 매번 변해 그대로 비교하면 전부 이벤트
- 지하철 먼저(추가 호출 0, 기존 스냅숏 재사용), 플래그 기본 off + 그림자 모드로 켜기 전 측정

## 건드는 곳
V67 마이그레이션, JourneySessionRepository, JourneyEvaluationScheduleWriter, JourneySession(@DynamicUpdate), RealtimeSubwayCollector, 신규 reeval 패키지(InterestIndex·ChangeDetector·ReevaluationTrigger·SubwayChangeFeed·BusRouteWatcher), 신규 LeaderLease, CachedSearchTimeBusAdapter, JourneyEvaluationIntervalPolicy

## 리스크
- 목표 부하에서 버스 호출은 설계와 무관하게 하루 약 140만~160만 회(현재 운영 8.3만) → 공급자 한도 상향이 필수
- PR-B 는 잠든 채 배포, 켜기는 한도 상향 뒤(예산 배분기가 한도 안에서 운용)
- #410 머지가 PR-A1 선행 조건. PR-A 는 A1(핵심)·A2(지하철)로 분할

## 검증법
livelock·dirty·교착·엔티티 덮어쓰기·리더 잠금·지문 계약 테스트 통과 + 전체 테스트 기존 무관 12건 외 0. 하네스(#409)로 등급별 감지 지연 p95 를 플래그 off/on 비교해 SLO 충족 여부 보고.
