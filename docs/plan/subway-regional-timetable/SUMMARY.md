# 지방 지하철 시간표 기반 도착

## 뭘 하나
부산 1~4, 대구 1~3, 대전 1, 광주 1호선의 역 화면, 검색 결과, 내비 대기에 운영사 공식 시간표 기준 다음 열차를 보여 준다.
평일, 토요일, 공휴일(광주는 명절까지)을 노선마다 공식 출처로 구분한다. 응답은 `source: TIMETABLE`, 앱은 "시간표" 칩. 서울은 그대로.

## 왜
지방 운영사 실시간 API가 없어(KRIC 도 시간표) 지금은 경로만 나오고 도착 정보는 전부 "없음"이다.

## 핵심 결정
- 출처는 운영사 공식만: 부산 API, 대구 열차시각 API(봇 확인 때문에 사람이 브라우저 스크립트로 받아 커밋), 광주 공식 CSV(2026-11-01), 대전은 공식 엑셀과 전 시각 일치한 TAGO. OTP 시간표, KRIC, 대구 옛 파일은 낡아서 기각
- 요일 4종(평일, 토, 공휴일, 명절). 행이 없는 요일은 노선별 고정 대체표만 씀(대전 토요일 = 공휴일표처럼 공식이 같은 표일 때만)
- 서울 `subway_station` 과 분리한 새 테이블 두 개(V76), 노선 코드는 문자열(`BS1`, `DG2`). 노선 id 충돌 방지
- 방향은 종착역의 노선 순서로 정함. 내릴 역 전에 끝나는 열차(안심행, 이곡행, 녹동행)는 자동 제외
- 동해선, 대경선, 부산김해는 공식 공휴일 시간표를 못 찾아 이번 범위에서 제외. SC-6 은 지방 노선만 뒤집음, 플래그 2개 기본 off

## 건드는 곳
V76 마이그레이션, tools/regional-subway, resources/subway, application/regionalsubway, adapter/out/api/regionalsubway, RegionalSubwayTimetableScheduler, NearbyController, NearbyTransitService, SubPathResult, RouteSearchService, CurrentLegArrival, journey 예측기, application.yaml, nextroute-mobile(역 시트, 검색 카드, 내비)

## 리스크
- 대구와 광주 파일은 운영사 개정 때 사람이 다시 받아 교체 PR 필요(대구는 TAGO 비교, 광주는 수정일 감시로 경고). 대구 토요일만 바뀌는 개정은 자동으로 못 잡음
- 명절 날짜 판정(설, 추석 연휴 범위)을 광주 명절표 운영일과 맞춰야 함

## 검증법
노선마다 한 역, 요일마다 전 시각이 공식과 불일치 0(판암, 반월당, 운천, 서면). `includeRegional` 없는 근처 역 응답과 서울 경로 응답이 지금과 같음.
계약마다 테스트 1개 이상, `./gradlew test` 통과.
