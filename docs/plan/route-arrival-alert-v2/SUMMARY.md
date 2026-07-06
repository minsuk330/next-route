# Route Arrival Alert v2

## 뭘 하나
목표 도착시각 알림을 **baseline(등록) → first check → 출발 푸시 → final check → 변경 푸시(최대 1회) → DONE** 고정 체크포인트 모델로 재구축. 소요시간은 환승 대기(실시간/모델/정적 배차간격) 반영 evaluator가 계산.

## 왜
기존 PR1 모델은 반복 재탐색 전제라 필드 의미가 틀어졌고, ODsay totalTime은 환승 대기를 반영 못해 출발 추천이 부정확. 외부 API(ODsay·버스 실시간)는 quota 자원이라 호출 최소화 필요.

## 핵심 결정
- **Evaluator 2모드**: baseline=STATIC(실시간 무시 + 버스 대기 `term/2` 정적 보정, first check 여유는 term 비례 가변으로 편향 흡수), check=REALTIME(enricher waitSeconds 합산만, 재계산 기각)
- **API 최적화 3종**: baseline enricher 스킵 / final check는 ODsay 재검색 없이 스냅샷 승차지점만 타깃 재조회 / 버스 없는 경로 final 생략 → ODsay 3→2회, fan-out 2→1회
- **스케줄러 동시성(codex 치명)**: `@Version` 낙관락 + WAITING_DEPARTURE는 next_action_at=NULL로 checker 스캔 제외(dispatcher 소유) + 발송·전이 같은 트랜잭션(dedup_key로 중복 방어)
- **V23 재작성 가드(codex 치명)**: 재작성 전 flyway_schema_history V23 존재 확인 필수, 있으면 drop/repair 또는 V24 전환
- **final check 정합성**: userLead≤5 or eligibility≠ELIGIBLE 생략, 스냅샷 STALE 비율 초과 시 CHANGE 억제, 과거 leaveBy는 now clamp(판정은 원값) / **3PR 분리**: A=스키마+evaluator+등록, B=체크(flag), C=발송(flag)

## 건드는 곳
V23 SQL, RouteArrivalAlert(+Phase/Reliability/Type/Eligibility enum, 스냅샷·version 컬럼), RouteTravelTimeEstimator 포트+구현, RouteServiceWindowChecker(신규), TransferStopResolver(term 노출), RouteSearchService(searchWithoutRealtime), RouteArrivalAlertService, RouteAlertResponse, Properties, RouteAlertCheckScheduler(신규, PR-B), RouteAlertDispatchScheduler(신규, PR-C)

## 리스크
- 실시간/모델 horizon 한계(다음 1~2대·운행차량만) → 2번째 환승 fallback 빈도↑, reliability로 완화
- final check가 대안 경로 발견 불가(타깃 재조회 트레이드오프) — 출발 직전 경로 스위칭 UX상 무의미라 수용. 지하철 지연 미감지(실시간 소스 없음)
- BusRouteStop 첫차/막차 포맷·자정 넘김, term null 비율 미확인 (구현 중 확인 항목)

## 검증법
PR별 단위테스트 통과(STATIC term/2·enricher 미호출, final check의 ODsay 미호출 verify, @Version 경합, 발송·전이 원자성, 스냅샷 STALE 억제, 후보 선택 우선순위, check→푸시→DONE 전이, stale 가드) + V23 flyway 가드 후 clean 부팅. 전체 플로우는 flag on 후 스테이징 수동 1회.
