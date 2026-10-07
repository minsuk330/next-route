# 노선·방향 기준 남은 역 목록 API (#283)

## 뭘 하나
`GET /api/subway/stations/{stationId}/stations-ahead?direction=…[&destination=…]` 신설.
승차역부터 그 방향으로 남은 역을 정거장 수·예상 분·환승 노선과 함께 준다. 어디서 끝났는지(종착/분기/한 바퀴)와 분기 갈래도 같이 준다.

## 왜
하차역 선택 화면(#265)에 필요한데, 서버에 지하철 역 순서 데이터가 없다.

## 핵심 결정
- 원천 = 운영 OTP GTFS 패턴(열린데이터 기각). 단 경의중앙선 서울역 노선은 방향이 실시간과 반대라 반전표, 급행 노선·통과 패턴은 걸러냄
- 2호선은 승차역 재등장 직전에서 끊고, 행선지(거의 다 "성수")로는 자르지 않음
- 분기점에서 멈추면 갈래 목록을 줘서 앱이 행선지를 붙여 다시 부름(승차역이 분기점이면 빈 목록 + 갈래)
- 역 ID 연결 = 이름 우선(≤500m) + 좌표(≤150m), 환승 = 이름 같고 ≤300m(좌표 50m만은 실패 10~26%·오매칭)
- 6호선은 응암에서 패턴을 갈라 상·하행 처리. OTP 실패는 503 + 낡은 캐시

## 건드는 곳
SubwayLinePatternPort, OtpSubwayLinePatternAdapter, SubwayPatternStationMatcher, StationsAheadCalculator, SubwayStationsAheadService, SubwayStationController, SubwayErrorCode, HANDOFF.md(모바일)

## 리스크
- feed 가 바뀌면 반전표·급행 필터가 조용히 틀릴 수 있음 → 실시간 행선지 대조 테스트로 먼저 깨지게 함
- 경강선·GTX-A 와 OTP 에 없는 역(1호선 천안·신창)은 지원 안 됨(422)
- API 가 OTP 런타임에 의존(24h 캐시 + 낡은 캐시로 완화)

## 검증법
PR-1: 19개 노선 연결률 + 실시간 행선지 대조 커버리지 테스트 통과(미달 노선은 목록으로 고정).
PR-2: 반례 테스트(신도림·강동·구로 분기, 의정부 급행, 가좌 반전, 6호선, 2호선 성수) + API 404/400/422/503/200 통과.
