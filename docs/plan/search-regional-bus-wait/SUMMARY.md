# 검색 결과의 경기·TAGO 버스 대기

## 뭘 하나
경로 검색 응답의 버스 대기가 서울 버스에서만 채워지는 것을 고친다. PR-0 모르는 enum 관용 읽기(먼저 배포) → PR-1a 버스 구간 계획 시각 →
PR-1b 실시간을 못 물은 버스는 시간표 대기(③) → PR-2 경기·TAGO 실시간(①) → PR-3 앱 이슈. 앞 버스 없을 때 뒤 버스 실시간 조회(②)는 하지 않는다.

## 왜
대기 보강이 서울 정류장 표·서울 API 만 써서 경기·TAGO 버스는 대기가 없고 그 뒤 버스까지 빠진다(운영 재현). 앱 ETA 는 OTP 환승 대기를 두 번 더한다(실측 4분).

## 핵심 결정
- 시간표 대기는 새 source `SCHEDULE` — 출시된 앱은 무시(하위 호환), 저장된 스냅샷·여정은 PR-0(모르는 값 → 기본값)을 먼저 배포해 보호
- "묻지 못한" 버스만 시간표로 채운다 — 물어보고 차 없음(NO_VEHICLE)·계획 버스 놓침·60분 초과·출발시각 지정 검색은 안 채움
- 연쇄(②)는 외부 호출 증가 리스크로 제외 — 시간표 값은 다음 버스 계산에 넘기지 않고, "앞 버스 결과 없음" 버스는 OTP 계획 환승 대기로만 채움(conditional)
- 경기·TAGO 실시간은 검색 전용 몫(캐시 miss 만 셈)·전용 동시성·기한 안에서만, 기본 꺼짐. TAGO 는 공급자 일일 한도부터 확인
- 서버 알림 추정기는 SCHEDULE 을 낮은 신뢰로(신뢰도 상승 금지)

## 건드는 곳
Jackson 설정, TransferArrival, SubPathResult(+생성 지점 7곳), LegacyRouteResponseMapper, TransferArrivalEnricher, TransferArrivalProperties, RouteSearchMetrics, RouteSearchTravelTimeEstimator, 경기·TAGO 도착 어댑터, 검색용 지역 조회(신규), application.yaml, 시험

## 리스크
- 지역 API 에 기한이 없고 TAGO 자리를 정류장 화면·여정과 나눈다 — 검색 전용 동시성 2, 기한 넘으면 포기
- 빌드 되돌리기는 PR-0 아래로 못 내린다(저장된 SCHEDULE 을 못 읽음)

## 검증법
PR-1b 뒤 운영 검색(수원역→광교, 청주)에서 경기·TAGO 버스와 그 뒤 버스에 시간표 대기, 서울 실시간 결과·검색 지연은 그대로.
PR-2 를 켜면 같은 검색이 실시간 대기, 몫·지연이 한도 안.
