# 묶인 버스 구간: 먼저 오는 노선으로 시작 (#614)

## 뭘 하나
여정 시작 때 첫 버스 구간의 선택 노선을 `lanes[0]` 대신 **걸어가서 탈 수 있는 차 중 가장 먼저 오는 노선**으로 둔다(검색 스냅샷 실시간 기준).
그 노선의 탑승 소요로 도착 예정을 잰다. 선택이 서버 기본(DEFAULT)인지 사용자(USER)인지 응답에 싣는다.

## 왜
journey 516: 140 3분 뒤인데 402(19분) 선택 → 앱 "402 19분 뒤", 도착 예정 20분 가까이 늦음.

## 핵심 결정
- 도달 기준 = `predictedArrivalAt ≥ userArrivalAt + (시작 시각 − 검색 시각)` — 검색 뒤 늦게 시작한 만큼 정류장에 닿기 전 지나가는 차 제외.
- 첫 버스 구간만. 두 번째 이후 구간 값은 앞 버스 가정의 조건부라 0번 유지(onwardWaits 로 잇기는 PR-2).
- 재탐색 수락은 규칙 변경 없음(후보에 묶음·실시간 없음). 출처 칸만 이어받기.
- 출처 칸 V78 `route_option_selected_by`(DEFAULT/USER, CHECK). 같은 후보 재전송도 USER 로 기록(PR-2 가 옮기지 않게).
- 대기 중 재선택(#614 ③)은 PR-2.

## 건드는 곳
TransferArrival, TransferArrivalEnricher(리팩터), JourneyRouteOptionDraft, JourneyCompiler, JourneyStartTxService, RerouteAcceptTxService,
BusRouteSelectionService, JourneyLeg, JourneyLegView, JourneyQueryService, V78, journey IT @Sql 목록

## 리스크
- #598 사용자 선택 때 탑승 소요는 여전히 대표 값(기존 결함, 별건).
- 재탐색 "같은 경로" 거름이 선택 노선만 봄(기존 동작, 별건).

## 검증법
도달 경계·조건부 구간 단위 시험, JourneyStartIT(140/741/402 → 140 + 탑승 소요), 재탐색·사용자 선택 IT 출처 칸, 기존 검색 시험 통과.
