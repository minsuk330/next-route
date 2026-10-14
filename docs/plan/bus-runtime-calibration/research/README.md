# 조사 원자료 (2026-10-13, 조사 에이전트 3개)

스크립트는 조사 당시 스크래치 경로(`/private/tmp/.../scratchpad/bus-calib/…`)를 가리킨다. 다시 돌릴 때는 경로만 바꾸면 된다. 큰 원자료(구간 277만 건 parquet, ODsay·OTP 원응답, GTFS 스캔 csv)는 커밋하지 않았다.

| 폴더 | 무엇 | 결과 파일 |
|---|---|---|
| `measured/` | 우리 실측(서울 208개 노선 라벨 + 위치) vs GTFS. VPS 에는 SELECT 만 실행했다. `run_all.sh` 로 전체 재현 | `summary.txt` |
| `odsay/` | OTP vs ODsay 버스 구간 짝짓기(서울·경기·인천·부산 30 OD). ODsay 키는 VPS 안에서만 쓴다 | `stats_out.txt`, 쿼터로 못 받은 OD = `ods_pending_quota.txt` |
| `gtfs/` | KTDB GTFS 버스 시간표 성격(출처별 시간대 변화·정차·속도) + 도시별 공식 속도 대비 | `summary.md`, `segments.md`, `city_speed.md` |

출처: KTDB 교통접근성 보고서 https://www.ktdb.go.kr/DATA/pblcte/20250123015832423.pdf (p.119~120), e-나라지표 시내버스 평균 통행속도 https://www.index.go.kr/unity/potal/eNara/sub/showStblGams3.do?stts_cd=507901&idx_cd=5079&freq=Y&period=N, 서울 열린데이터광장 OA-21217 https://data.seoul.go.kr/dataList/OA-21217/S/1/datasetView.do
