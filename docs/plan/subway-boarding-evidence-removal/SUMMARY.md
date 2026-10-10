# 지하철 탑승 증거·자동 판정기 제거

## 뭘 하나
앱이 더 이상 보내지 않는 모션 탑승 증거 API와, 그 증거로만 돌던 자동 탑승 판정기(worker 2초 tick)를 통째로 지운다. 열차 고정은 사용자 선택(train-selection) 하나만 남는다.

## 왜
앱이 모션 자동 탑승을 없애 증거가 0건 → 판정기는 빈 스캔만 도는 죽은 코드다.

## 핵심 결정
- API 3개(boarding-evidence·binding/reset·not-boarded) 전부 삭제 — 앱 호출 0, 열차 바꾸기는 train-selection 재호출
- 판정기·스케줄러·판정 지표·관문 설정 삭제, 설정은 lockTimeout·algorithmVersion 만 남김
- DB 무변경 — 컬럼·enum·이벤트 행 유지(기존 행 호환, 마이그레이션 0). 컬럼 drop 기각
- 재탐색 상속·여정 종료 정리(Lifecycle)·링·선택 흐름은 그대로
- 판정기 시나리오 IT 는 삭제, 링·종료 시나리오는 사용자 선택 셋업으로 바꿔 유지

## 건드는 곳
JourneySubwayBindingController, SubwayBoardingEvidenceService/Request/DTO, SubwayBindingUseCase, SubwayBindingMatchService/TxService, SubwayTrainMatcher, SubwayBindingMatchScheduler, SubwayBindingDecision, SubwayLegBinding, SubwayLegBindingRepository, SubwayBindingMetrics, JourneySubwayBindingProperties, application.yaml, 관련 테스트 10여 개

## 리스크
- 시나리오 IT 셋업 교체 중 링·종료 검증 범위가 줄 수 있음 — 남길 시나리오 목록을 PLAN 에 고정
- VPS env `JOURNEY_SUBWAY_BINDING_ENABLED` 잔여(무해, 정리는 선택)

## 검증법
컴파일 + `*Subway*` 테스트 통과, 전체 테스트에서 기존 무관 12건 외 신규 실패 0, 삭제 심볼 grep 0건.
