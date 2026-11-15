# 대구도시철도 1~3호선 시간표 (timetable.json)

- 출처: 대구교통공사 열차시각 Open API `https://www.dtro.or.kr/open_content_new/ko/OpenApi/stationTime.php`
  (안내 페이지 https://www.dtro.or.kr/index.do?menu_id=00001480)
- 받은 날짜: 2026-11-16 01:21 KST (`fetchedAt` 2026-11-15T16:21:52.667Z)
- 받은 방법: 브라우저 콘솔에서 `tools/regional-subway/daegu_export.js` 실행.
  이 API 는 서버 호출(curl, RestClient)에 봇 확인 스크립트를 돌려주므로 서버에서 직접 부르지 않는다.
- 범위: 역 94개(1호선 35, 2호선 29, 3호선 30) × UP/DOWN × WEEKDAY/SAT/HOLIDAY = 564건, 이름 실패 0
- sha256: `ac33478742164c9cf5ae465a705ea68c73bc7ef5a4ef44bccddf98f279e6d1e4`

## 검증 (2026-11-16)
- 평일, 공휴일: 전 역 × 방향 360건이 TAGO `GetSubwaySttnAcctoSchdulList`(01, 03)와 분 단위 일치.
- 반월당(2호선) 상행 토요일 140편, 공휴일 124편이 따로 받은 공식 응답과 일치. 화원(1호선) 평일 상행 06:12 … 24:02, 영남대(2호선) 공휴일 막차 23:18(이곡) 일치.
- 토요일은 공휴일과 다르다(같은 시각 비율 평균 16%).

## 형식 메모
- 종착역의 반대 방향은 `schedule: ["-"]` 한 칸이다(열차 없음). 파서에서 버린다.
- `23:45:00(이곡)` 의 괄호 = 그 열차의 종착역. 괄호 없으면 방향 끝 역. `24:02:00` 처럼 24시를 넘는 표기가 있다.
- `sttCd` = TAGO 역 id 뒤 3자리(`MTRDG20230` → `230`). 예외: 중앙로(STT_CD 131) TAGO id 는 `MTRDG13140`.

## 다시 받기
서버의 주 1회 비교(TAGO 평일·공휴일 대비)가 "대구 파일 다시 받기" 경고를 내면 위 방법으로 다시 받아 이 파일과 sha256, 날짜를 바꾼다.
토요일만 바뀌는 개정은 비교로 잡히지 않으니 대구교통공사 공지(시간표 개정)를 볼 때도 다시 받는다.
