# 합성 모니터링 + 5xx 1건·외부 API 실패 알림

## 뭘 하나
alert-analyzer 가 운행 시간(05:30~24:00) 10분마다 앱과 같은 순서로 "경로 검색 → 첫 탈것 구간 도착 조회"를 지하철·버스 두 쌍으로 직접 호출하고, 실패를 Grafana 규칙으로 Slack 에 보낸다. 함께 5xx 는 1건만 나도(uri 표시), OTP 외 외부 API 실패도 알린다.

## 왜
검색 하루 약 20건, 5xx 7일 0건이라 실사용자 요청만으로는 고장이 나도 울리지 않고, 사용자가 떠나면 가짜 RESOLVED 가 된다.

## 핵심 결정
- 호출 주체 = 분석기 컨테이너: 앱 바깥, 새 컨테이너 없음(blackbox_exporter 는 이어 부르기 불가로 기각, worker 는 자기 감시로 기각)
- 실패 3층: HTTP·시간초과 critical / 경로 0개·탈것 없음·도착 대상 못 만듦 warning / 10초+ 느림은 기록만. 도착 "없음"은 정상
- 실패 시 60초 뒤 1회 재확인 후 게시(감지 약 11분), 분석기 사망은 하트비트 끊김으로 critical
- 공개 도메인으로 호출(nginx·TLS 까지 사용자와 같은 길), 헤더 `X-Nextroute-Synthetic` 로 퍼널 기록 생략 + 메트릭 태그로 통계에서 제외
- /metrics 는 직접 작성(prometheus_client 의존성 안 늘림)

## 건드는 곳
RouteSearchController, 신규 SyntheticRequest·SyntheticAwareServerRequestObservationConvention, alert-analyzer(probe.py, app.py, config.py, weekly.py, tests), prometheus.yml, rules.yaml, usage.json, spring.json, observability README, implementation_note.md

## 리스크
- OTP 부하 하루 약 220검색 추가(힙 3.3/3.5G) → 첫 이틀 GC·p95 관찰
- 외부 API 실패 규칙 소음 → 7일 실측으로 문턱 정함
- OD 후보(서울역→강남역, 신사역→소공동)가 시간대별로 탈것 구간을 안 낼 수 있음 → 구현 중 실검색으로 확정

## 검증법
gradle 관련 테스트·pytest 통과. 운영에서 정상 시 outcome 전부 0, 고장 주입 1회로 Slack failed→RESOLVED, 분석기 정지로 stalled 알림 확인. 하루 뒤 route_search_event 합성 검색 0행.
