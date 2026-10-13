# 계수 맞춤·평가 (2026-10-14)

ODsay 구간 시간에 맞춰 `config/otp-national/bus-runtime-coefficients.json` 을 만든 스크립트다. 조사 당시 작업 폴더(`scratchpad/bus-calib/loop/`) 기준 상대 경로라서, 다시 돌릴 때는 `../odsay/raw_odsay/*.json`(ODsay 원응답)과 `../odsay/ods*.txt` 를 같은 배치로 두면 된다. ODsay 원응답·GTFS 추출 csv(약 1GB)는 커밋하지 않았다.

| 순서 | 파일 | 하는 일 |
|---|---|---|
| 1 | `segments.py` | ODsay 원응답(60 OD) → 버스 구간 1273개(`segments.json`) |
| 2 | `extract.py GTFS_DIR` | 같은 번호·`busLocalBlID` 노선의 stop_times 만 뽑음 |
| 3 | `match.py` | 구간 ↔ GTFS trip 짝(승하차 200m, 정류장 수 ±2), 14시 전후 trip 의 주행·정차 초 |
| 4 | `fit2.py`·`fit3.py`·`fit4.py`·`fit5.py` | 모델 비교(OD 단위 LOO), 유형 분리, 고정항(ODsay 공식 확인), L1 |
| 5 | `final_fit.py` | 홀드아웃(H01~H18) 평가 + 최종 계수 |
| 6 | `e2e.py fetch <port\|vps> DIR` / `e2e.py eval DIR` | 실제 OTP 응답(BUS-only 14시)의 버스 구간을 ODsay 와 비교 |
| 7 | `itin.py`·`decomp.py` | 같은 버스 조합 경로의 전체 소요 비교와 도보·탑승·대기 분해 |
| 8 | `fit6.py`·`fit7.py` | 기각한 개선안: 구간 거리 꺾은선, 빠른 버스(직행좌석·광역·좌석) 별도 그룹 — LOO 1%p 이내 |

결과 = `fit_results.txt`(맞춤), `e2e_results.txt`(실제 OTP 응답). OD 목록: 1일차 = `../odsay/ods.txt`, 2일차 홀드아웃 = `ods_holdout.txt`, 쿼터로 못 받은 것 = `ods_pending_quota2.txt`(ODsay 키 하루 30회).

품질 필터: 첫 lane 만(같은 구간 다른 노선은 sectionTime 이 첫 노선 기준), 심야 제외, GTFS 거리/ODsay 거리 0.8~1.25, 정류장 수 차 ≤1, GTFS 시간이 ODsay 의 3배 이하.
