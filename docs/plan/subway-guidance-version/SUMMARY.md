# 지하철 링 버전 + 진행률 되감김 수정

## 뭘 하나
링 화면이 바뀔 때만 오르는 `guidanceVersion` 을 스냅샷에 싣고, 조건부 조회에 `afterGuidanceVersion` 을 받는다(#307).
같은 구간에서 "전역 출발" 관측마다 진행률을 0 으로 되돌리던 계산기 버그를 고친다.

## 왜
링이 바뀌어도 204 라 Dynamic Island 가 안 뜨고, 앱이 옛 스냅샷을 가릴 값이 없다(#306). 링이 15~30초마다 0 으로 되감긴다.

## 핵심 결정
- 버전 = 화면 변경 시각(millis) — 재탐색으로 링 키가 바뀌어도 단조(카운터 기각)
- 변경 판정 = push 와 같은 기준(`SubwayRingPushDecider`) — 매 tick 증가 기각
- ETA 는 버전에 안 넣음 — 조회 시점 계산값이라 저장 지점 없음
- 링 없으면 0, 파라미터 안 보내면 기존 동작(하위 호환)
- 같은 구간 반복 관측은 지금 화면 값에서 이어 그림, ETA 없으면 멈춤

## 건드는 곳
SubwayRingState, SubwayRingUpdateService, SubwayRingCalculator, SubwayGuidance, JourneySnapshot, JourneyController, 모바일 HANDOFF

## 리스크
- 배포 직후 Redis 옛 링은 버전 0 → 다음 tick 에 바로 부여(규칙에 포함)

## 검증법
계산기: 같은 구간 연속 관측에서 진행률 비감소. IT: 같은 화면 tick 은 버전 유지, 다음 역 변경 시 증가. 컨트롤러: 버전 같으면 204, 다르면 200, 파라미터 없으면 기존.
