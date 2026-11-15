# OTP 검색을 카카오맵과 맞추기 1차(앱 쪽) — PLAN (티어 M)

브랜치 `feat/otp-kakao-parity`. 비교 도구와 원자료는 `tools/route-compare/`(저장소 밖 untracked).

## 배경 (2026-11-16 실측)
카카오맵 대중교통 결과(OD 105개, 상위 7개 경로)와 운영 검색을 비교했다. 카카오는 새벽 02:30 "지금", 우리는 같은 날 14:00 출발.
- 최단 경로 소요: 80개 OD 중 45개가 ±5분. 같은 지하철 경로끼리는 평균 +1.0분.
- 카카오 상위 3개 경로 중 우리에 있는 비율 64%, 지하철만 타는 경로는 60%.
- 원인 ①OTP 가 지배당한 대안을 버려 지하철 대안이 적다 ②빈자리를 최단의 2배 넘는 다환승 버스 경로가 채운다 ③KTX·SRT 를 한 구간 타는 경로가 섞인다 ④환승역 승강장 사이 도보가 그래프에서 끊겨 있다(신도림 2↔1호선 직선 77m, 도보 1,734m) ⑤역 접근 도보 0분 ⑥광역버스 탑승시간.
- ④⑤⑥ 은 그래프 재빌드가 필요해 이 PR 범위 밖(별도 L 작업).

## 이 PR 이 하는 것
1. **지하철 대안 검색** (`RailAlternatives`, `RouteSearchService.supplementRailAlternatives`)
   - 철도만 타는 대표 경로가 `railAlternativeThreshold`(3)개 미만이면, 비용이 가장 낮은 철도 경로가 탄 노선을 하나씩 빼고(`railAlternativeMaxLines`=2) 철도만 다시 묻는다. 철도 경로가 없으면 철도만 한 번.
   - 노선의 모든 route id 는 `RailServiceClassifier.routeIdsOfLine`(지하철 노선 목록 캐시). 캐시가 비면 뒤에서 받아 두고(`SubwayLinePatternService.loadRoutesLater`) 이번 요청은 철도만 묻는다.
   - 켜져 있으면 옛 "철도만" 추가 검색은 건너뛴다(같은 일을 한다). "버스만" 추가 검색은 그대로(고유 5개 미만).
2. **느린 경로 줄이기** (`SlowRouteTrimmer`): 최단 소요 × `slowRouteFactor`(1.6) 를 넘는 경로는 가장 빠른 `slowRouteKeep`(2)개만. 순서는 비용 순 그대로.
3. **예매 열차 짧은 구간 제외** (`ExpressFilterPolicy.withoutShortIntercityHops`): 노선 이름이 `[고속]`, `[일반]` 인 국가철도를 `intercityRailMinRideMinutes`(40) 보다 짧게 타는 경로 제외. ITX-청춘은 남김. 전부 그런 경로면 그대로 둔다.

## 결과 (운영 OTP, 실제 RouteSearchService 경로, 실시간 보강 mock)
| | 전 | 후 |
|---|---|---|
| 카카오 상위 3개 일치 | 64% | 73% |
| 카카오 지하철 경로 일치 | 60% | 73% |
| 지하철만 타는 경로 수(평균) | 2.4 | 3.9 (카카오 상위 7 안 2.8) |
| 검색 시간 평균(80 OD) | 3.03s | 2.83s |
| 검색 시간 최대 | 7.1s | 5.5s |
기존 25개 OD: 지하철만 2.0 → 3.9, 평균 2.48s → 2.72s.

## 킬스위치
`ROUTING_OTP_RAIL_ALTERNATIVES_ENABLED=false`, `ROUTING_OTP_SLOW_ROUTE_FACTOR=0`, `ROUTING_OTP_INTERCITY_RAIL_MIN_RIDE_MINUTES=0`.

## 검증
- `./gradlew test --tests '*RouteSearchService*' --tests '*KakaoParity*' --tests '*ItineraryRanker*' --tests '*BusLaneMerger*' --tests '*SubwayLinePatternService*' --tests '*RailServiceClassifier*' --tests '*RemainingRoute*'`
- 비교 재실행: `tools/route-compare/compare.py ours_*.txt`.

## 남은 것 (그래프, 별도 L)
- 환승 승강장 연결: 같은 역 승강장 쌍 254개 중 도보가 직선의 3배+150m 를 넘는 쌍 30여 개(옥수 3↔경의중앙 3.5km, 대곡, 온수 1↔7 2km, 신도림, 석남, 서울역 4↔공항철도, 을지로3가 2↔3, DMC, 초지, 대방, 석계). 목록 `tools/route-compare/xfer_all.json`.
- 역 접근 도보(지금 0분), 광역버스 탑승시간 보정.
