# 잠정 고정에도 진행 링·잠금화면을 그린다

## 뭘 하나
탑승 열차가 "잠정"으로 잡힌 순간부터 진행 링과 잠금화면 갱신을 확정과 똑같이 내보낸다(하차 안내 포함). 잠정이 8분 뒤 버려지던 규칙을 없애 운행 중 링이 꺼지지 않게 한다.

## 왜
후보 열차가 하나뿐이면 판정기가 확정하지 않아, 배차가 뜸한 시간대에는 링이 한 번도 안 떴다.

## 핵심 결정
- 잠정에도 링을 그린다 — 확정 규칙 자체는 그대로(사용자 결정 C)
- "추정/확인 중"은 안 보인다 — API 는 PROVISIONAL 그대로, 앱이 구분 없이 그림(HANDOFF)
- 원래 설계 C6(미확정이면 하차 안내 금지) 폐기 — 잠정도 하차 단계·push 동일
- 잠정의 확정 창(8분) 만료 폐기 — 버리면 운행 중 링이 꺼진다
- 교정되면 새 열차로 즉시 다시 그림(한 번 튐 허용)

## 건드는 곳
SubwayLegBindingRepository, JourneySubwayGuidanceComposer, SubwayRingPushRelayTxService, SubwayTrainMatcher, SubwayBindingStatus, JourneySubwayBindingProperties, application.yaml, SubwayRingMetrics, 시험(SubwayRingIT·SubwayRingPushIT·SubwayTrainMatcherTest), HANDOFF.md

## 리스크
- 오판 링이 그대로 보인다: 급행 탑승·다른 지선·출발 기록 누락·폰 이동 오감지 → 하차 안내가 한 정거장 일찍/늦게. 벗어나는 길은 "이 열차 아니에요"뿐
- #242 시나리오 4("한산 시간대 링 안 뜸")와 모순 — 나중에 머지되는 PR 이 뒤집어야 한다
- 머지·배포하면 플래그 없이 즉시 적용

## 검증법
SubwayRing*·SubwayTrainMatcher* 시험 통과(XML 확인) + 기존 Subway* 무회귀 + 전체 시험에서 새 실패 없음.
