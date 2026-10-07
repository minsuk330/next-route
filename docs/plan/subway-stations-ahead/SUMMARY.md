# 노선·방향 기준 남은 역 목록 API (#283)

## 뭘 하나
`GET /api/subway/stations/{stationId}/stations-ahead?direction=…[&destination=…]` 신설.
승차역부터 그 방향으로 남은 역을 가까운 순으로 주고, 정거장 수·예상 분·환승 노선을 붙인다.

## 왜
하차역 선택 화면(#265)에 필요한데, 서버에 지하철 역 순서 데이터가 없다.

## 핵심 결정
- 순서·시간 원천 = 운영 OTP GTFS 패턴(열린데이터 표 기각) → 새 표·마이그레이션 없이 분 단위 시간까지 나옴
- 순환선 = 승차역이 다시 나오기 직전에서 끊음 → 한 바퀴 규칙 하나로 여러 바퀴 도는 패턴까지 처리
- 분기 + 행선지 모름 = 주 패턴들이 갈라지기 전까지(공통 앞부분), 회차 패턴은 제외
- 역 ID 연결 = 좌표 50m 최근접, 실패해도 역을 빼지 않음(정거장 수가 틀어지지 않게)
- PR 2개: ① OTP 연동 + 전 노선 연결률 측정 ② 계산기·API(#284 머지 후)

## 건드는 곳
SubwayLinePatternPort, OtpSubwayLinePatternAdapter, SubwayPatternStationMatcher, StationsAheadCalculator, SubwayStationsAheadService, SubwayStationController, SubwayErrorCode, HANDOFF.md(모바일)

## 리스크
- 6호선은 feed 에 방향 구분이 없음 → 잠정 범위 제외(codex 논의)
- 노선이 여러 route 로 쪼개진 곳(1호선·경의중앙선)과 급행 패턴은 PR-1 측정 전까지 불확실
- API 가 OTP 런타임에 의존함(캐시 24h 로 완화, 실패 처리 방식 미결)

## 검증법
PR-1: 19개 노선 전부 연결률 커버리지 테스트 통과(미달 노선은 목록으로 고정).
PR-2: 계산기 테스트(순환·분기·회차·행선지·자정) + API 404/400/503/200 통과, 배포 후 강남 2호선 내선 한 바퀴와 5호선 분기 실호출 확인.
