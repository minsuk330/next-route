# 경기 버스 형상·정류장 GBIS 전면 교체

## 뭘 하나
OTP 그래프의 경기 버스 노선(GGB+경기 마을) 형상을 경기도 공식 API(GBIS) 선으로 전부 바꾸고, 정류장이 다르면 정류장 목록도 GBIS 로 바꾼다. 그래프를 다시 빌드해 VPS 에 스왑한다.

## 왜
지금 형상은 pfaedle 추정선이라 광역·개편 노선이 실제와 수 km 다르다(887노선 중 34%가 일부 이상 불일치, 사용자 지도 확인).

## 핵심 결정
- 앱 코드 무변경, 그래프 입력 GTFS 의 경기 trip 만 오프라인 재작성 — 배포가 그래프 스왑뿐이라 롤백 쉬움.
- 시간표(trip 수·시각)는 기존 유지, 새 정류장 시각은 공통 정류장 사이 거리 비례 보간 — GBIS 엔 정류장별 시각 없음.
- 왕복 한 줄 GBIS 선을 trip 의 첫·끝 정류장(또는 회차점)에서 잘라 쓰고, 정류장은 순서대로 단조 투영 — 루프 오투영 방지.
- stop_id 규칙 `BS_TAGO_GGB<stationId>` 유지 — 실시간 도착 조회가 그대로 맞는다.
- 원본 shaped zip 은 보존하고 새 zip 으로 빌드 — 롤백 원천.
- GBIS 통과 지점(`…(경유)`)은 정류장에서 제외, 정류장 목록이 없거나 시각이 안 맞는 trip 은 형상만 교체 — 틀린 시간표보다 옛 정류장이 낫다.
- 다른 세션이 같은 저장소 작업 중 → 구현은 스크래치 clone 에서, 원 트리·원 산출물 무변경, VPS 배포 직전 현재 상태 재확인.

## 건드는 곳
runner/fetch_gbis_routes.py, runner/apply_gbis_routes.py, runner/test_apply_gbis_routes.py, runner/build_otp_graph.sh, data/otp-national/release-manifest.json, VPS compose.yaml(graphVersion)

## 리스크
- (해소) 광역 통과 정류장 — GBIS 가 이름 `(경유)` 로 구별해 줌.
- 정류장 목록이 없는 노선 중 44개는 옛 정류장이 새 선에서 150m 넘게 떨어져 OTP 가 직선으로 그린다.
- 정류장 교체로 환승·검색 결과가 바뀐다 → 배포 전 로컬 그래프로 검색 확인.

## 검증법
경기 trip 형상이 GBIS 선과 50m 내 ≥99%, 정류장-형상 잔차 p99 <30m, 경기 외 데이터 해시 동일. 배포 후 `형상 마킹 ACTIVE`(새 graphVersion) + 경기 구간 검색·실시간 도착 정상.
