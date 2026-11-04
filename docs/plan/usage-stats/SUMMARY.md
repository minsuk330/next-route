# 사용 통계 대시보드

## 뭘 하나
Grafana 에 나만 보는 "사용 통계" 대시보드를 만든다. 일별 사용자 수, 신규 설치, 여정 시작·완료·취소, 경로 검색, 앱 버전, 재방문을 본다.
DAU 를 세기 위해 서버가 "이 사용자가 오늘 앱을 썼다"를 하루 1행씩 남긴다.

## 왜
지금은 사용자 수를 셀 근거가 없다(접속 로그엔 사용자 ID 가 없고 휴대폰 IP 는 바뀐다).

## 핵심 결정
- 기록은 그날 첫 요청 한 번만, 비동기 — 실패해도 사용자 요청은 영향 없음
- DB 는 Grafana 전용 읽기 전용 계정, 필요한 표 3개만 SELECT(검색 수는 Prometheus), 쿼리 10초 제한
- 대시보드에 목적지·좌표·이름은 싣지 않음 — 사용자는 해시 6자로만
- 탈퇴하면 활동 기록도 같이 지움
- 서버 관리자 페이지 신설은 기각(보안·배포 부담)

## 건드는 곳
V74 마이그레이션, DailyActivityRecorder, DailyActivityFilter, SecurityConfig, WithdrawService, WithdrawalDeletionBoundaryIT, Grafana datasources.yaml, 관측 compose.yaml, dashboards/usage.json, grafana_ro.sql

## 리스크
- VPS 관측 compose 는 저장소와 다름 → 줄 삽입만, 통째로 덮지 않음
- DAU 는 배포한 날부터만 쌓임(과거 소급 불가)

## 검증법
기록기·필터 단위 시험, 중복 무시·탈퇴 삭제 IT 통과. 배포 뒤 오늘 행이 생기고, 대시보드 패널 10개가 값을 그리면 완료.
