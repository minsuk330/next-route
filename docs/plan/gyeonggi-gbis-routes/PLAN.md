# 경기 버스 형상·정류장 GBIS 전면 교체 — PLAN (L)

> 사람용 요약 = `SUMMARY.md`. 이 문서는 컨텍스트 없는 세션이 그대로 구현할 수 있게 쓴다.
> 작업 디렉터리 기준 = `tools/transit-routing-benchmark/` (아래 상대경로는 전부 여기 기준, 저장소 루트 파일만 예외 표기).

## 0. 결론과 배경

- **목표**: 운영 전국 OTP 그래프의 경기 버스 노선(GGB `BR_TAGO_GGB<9자리>[_U|_D]` + 경기 마을 `BR_3100_<9자리>`)에 대해 **형상은 전부 GBIS 공식 선으로, 정류장이 다르면 정류장도 GBIS 정류장 목록으로** 바꾼다.
- **방식**: 앱(Java) 코드는 건드리지 않는다. 그래프 입력 GTFS 의 경기 trip 만 오프라인으로 재작성 → 그래프 재빌드 → VPS 그래프 스왑. 시간표(trip 수, 출발·도착 시각)는 기존 KTDB 값을 유지하고 정류장·형상만 GBIS 로 바꾼다.
- **왜**: 현 형상은 pfaedle 가 2025-03 GTFS 정류장을 OSM 도로에 맞춘 추정선. GBIS `getBusRouteLineListv2` 와 887노선 비교(2026-10-07): 50m 내 ≥95% 일치 3%, ≥85% 62%, ≥60% 27%, <60% 7%. 광역(M/G/1100 등)은 고속도로 구간에서 최대 4.6km 다른 길, 88번은 노선 개편으로 위치 자체가 다름. 사용자가 지도로 직접 확인 후 전면 교체 결정.
- **정류장 실측(138노선 표본)**: 정류장 순서까지 완전 동일 8%, 집합 Jaccard 중앙값 0.92, <0.5 가 16%. 광역은 "GBIS 에만 있는 정류장" 이 다수(우리 쪽에만 있는 건 0) → 통과 정류장 포함 여부가 미결 1.
- 운영 그래프: `national-nojeju-202503-cal20260729_20261130-otp278-shaped-g035004114e7b` (VPS compose.yaml 4곳에 이 문자열). shaped GTFS = `data/input/national-nojeju-202503-shaped.gtfs.zip` sha256 `62a8fb2de0f3943d54c901eb3103e167bec400b144cdbba3939d156c808a9f44` (= `data/otp-national/release-manifest.json` `gtfsShapedSha256`).
- 주의: `data/otp-national/shape-build/gtfs-out/` 에는 stop_times·shapes 가 **없다**(삭제됨) → `--skip-match` 로는 재빌드 불가, zip 을 원천으로 쓴다.

## 1. 확정 사실 (구현 전제)

| 항목 | 값 |
|---|---|
| GBIS 형상 API | `https://apis.data.go.kr/6410000/busrouteservice/v2/getBusRouteLineListv2?serviceKey=&routeId=<9자리>&format=json` → `response.msgBody.busRouteLineList[{lineSeq,x,y}]`, 좌표 소수 5자리 |
| GBIS 정류장 API | `.../getBusRouteStationListv2?...` → `busRouteStationList[{stationId,stationSeq,stationName,x,y,turnSeq,turnYn,centerYn,mobileNo,regionName,districtCd,adminName}]` |
| 키 | VPS `/root/apps/nextroute/env/api.env` 의 `GYEONGGI_BUS_API`(Encoding 키). **출력·커밋 금지**. 로컬 실행 시 `GYEONGGI_BUS_API=$(ssh vps "grep ^GYEONGGI_BUS_API= /root/apps/nextroute/env/api.env | cut -d= -f2-")` |
| 한도 | 기능별 일 10,000. 대상 ~3,139노선 × 2 API ≈ 6.3k |
| GBIS 형상 모양 | 노선당 한 줄, 90% 가 시작≈끝(<300m) = 왕복 루프 |
| 우리 shape | 경기 shape 3,888개 중 2,542개 왕복형, 나머지 편도·부분 |
| route id | 9자리 = GBIS routeId. `_U`/`_D` 접미사 변형 있음(떼고 조회) |
| stop id | `BS_TAGO_GGB<GBIS stationId 9자리>` (마을 3100 노선도 정류장은 GGB 규칙) — 앱 실시간 경기 분기가 이 9자리를 그대로 GBIS 에 넘김 |
| stop_times 컬럼 | `trip_id,arrival_time,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type,drop_off_type,shape_dist_traveled,timepoint,continuous_pickup,continuous_drop_off` (pfaedle 가 shape_dist_traveled 채움) |
| 형상 게이트 | `config/otp-national/shape-gate-thresholds.json` 임계 없음(degraded 0, 사용자 결정 2026-09-30) — 유지 |

## 2. 작업 순서

### ⚠ 작업 격리 (다른 세션이 같은 저장소에서 동시 작업 중)
- `/Users/wa/golbob/개인/nextroute` 작업 트리는 **다른 세션이 쓰고 있다**(현재 브랜치·미커밋 변경 다수). 이 트리에서 `checkout`/`reset`/`stash`/커밋 **금지**, 열린 PR 머지 금지.
- 구현은 **스크래치 git clone** 에서: `git clone git@github.com:minsuk330/nextroute.git <scratchpad>/nextroute-gbis && cd 그곳 && git checkout -b feat/gyeonggi-gbis-routes origin/main`. git worktree 미사용(사용자 규칙).
- 큰 데이터(shaped zip 448MB, OSM pbf, otp jar, graph.obj)는 clone 에 복사하지 말고 **원 트리 경로를 읽기 전용으로 참조**: `tools/transit-routing-benchmark/data/input/*` 는 원 트리의 절대경로로 넘기거나 clone 의 `data/` 에 심볼릭 링크. 산출물(GBIS 캐시, 새 zip, graph.obj, coverage-index)은 **clone 쪽 data/** 에 쓴다 — 원 트리의 `data/otp-national/graph.obj`·`coverage-index.json`·`release-manifest.json` 덮어쓰기 금지(다른 세션 산출물일 수 있음).
- 이 PLAN/SUMMARY 파일은 원 트리에 untracked 로 만들어졌다 → clone 브랜치에 복사해 구현 PR 에 포함.
- `implementation_note.md` 는 원 트리에서 다른 세션이 수정 중(M) → 이 작업 기록은 clone 브랜치의 implementation_note 에 append 하고 PR 로 합친다(머지 시 tail 충돌 가능 — main superset 기준으로 풀고 내 블록 재append).
- VPS 배포 직전: `ssh vps` 로 compose.yaml 의 현재 graphVersion·최근 백업 파일·실행 중 컨테이너 이미지를 다시 확인 — 다른 세션이 그사이 배포했으면 그 상태 위에서만 치환(compose 는 git 아님, 덮어쓰면 프로덕션 파손).
- 커밋 직후 `git log -2` 로 부모 확인, 커밋 전 `git diff --cached --name-only` 로 staged 확인. 데이터 파일(json 캐시, zip, graph.obj)은 커밋 금지(.gitignore 의 data/ 규칙 확인).

### Step 0 — 미결 1 닫기 (코드 전, 실측)
- [ ] 광역 5노선(M7111 229000102, G7426 229000111, M6410 216000044, G1690 221000035, 3400 218000117)의 GBIS 정류장 목록 vs 우리 GTFS 비교: GBIS 에만 있는 정류장 이름 나열.
- [ ] 네이버/카카오 지도 노선 정차 목록과 대조 → GBIS 목록이 **통과 정류장을 포함하는지** 판정.
  - 포함 안 함(전부 실제 정차) → 계획대로 전면 교체.
  - 포함함 + 필드(centerYn/turnYn/mobileNo 공백 등)로 구별 가능 → 필터 규칙 확정하고 `apply_gbis_routes.py` 에 반영.
  - 포함함 + 구별 불가 → **사용자 확인**: 광역은 "형상만 교체, 정류장은 기존 ∩ GBIS + 기존" 으로 축소할지.
- [ ] 결과를 implementation_note `### 확정` 에 기록.

### Step 1 (PR-1) — `runner/fetch_gbis_routes.py` 신규
```
usage: fetch_gbis_routes.py --gtfs <zip|dir> --out data/input/gbis [--limit N] [--sleep 0.05]
env: GYEONGGI_BUS_API
```
- routes.txt 에서 `^BR_(TAGO_GGB|3100_)(\d{9})(_[UD])?$` 로 9자리 집합 추출(`_U/_D` 떼기).
- 노선마다 `<id>.line.json`, `<id>.stations.json` 저장. 이미 있고 resultCode==0 이면 skip(재실행 안전). urllib, timeout 10s, 실패 3회 재시도.
- `fetch-report.json`: `{fetchedAt, total, ok, empty:[ids], error:{id:msg}}`.
- 단일 실행 잠금(`fcntl.flock` on `<out>/.lock`) — 중복 실행 사고 방지 규칙.
- 검증: `--limit 5` 실행 후 파일 10개 + report 확인. 전량 실행은 1회.

### Step 2 (PR-2) — `runner/apply_gbis_routes.py` 신규 (핵심)
```
usage: apply_gbis_routes.py <in.zip> <out.zip> --gbis data/input/gbis --report <path> [--express-stop-policy full|shape-only]
```
stdlib 전용(compute_shape_quality 와 같은 이유: venv 없이 릴리스 파이프 실행). 거리 계산은 `compute_shape_quality.py:65 _to_m` 투영을 import 재사용.

처리 단계:
1. **로드**: routes/trips(경기만 선별) → trip_id→(route9, suffix, shape_id). GBIS 캐시 로드(없거나 비면 해당 노선 skip + report).
2. **GBIS 선 준비**: lineSeq 정렬 → 미터 좌표 → 누적거리. GBIS 정류장을 선에 **단조 투영**(stationSeq 순서대로 이전 투영 위치 이후 구간에서 최근접; 루프의 귀로 정류장이 출발로 구간에 붙는 오투영 방지). 결과 `station_m[i]`.
3. **trip → GBIS 구간 [a,b]** (정류장 인덱스):
   - 기존 trip 첫/끝 정류장 9자리가 GBIS 목록에 있으면 그 인덱스(첫 정류장은 앞쪽부터, 끝 정류장은 첫 인덱스 이후에서 탐색).
   - 없으면 기존 정류장 좌표에서 GBIS 정류장 최근접(≤300m) + 순서 제약.
   - 그래도 실패: suffix `_U` → [0, turnIdx], `_D` → [turnIdx, n-1], 기존 shape 가 왕복형(시작≈끝 <300m) → [0, n-1]. turnIdx = `turnSeq` 와 같은 stationSeq 의 인덱스.
   - 그래도 실패 → trip 무변경 + report `unmapped`.
4. **정류장열**: GBIS[a..b] (Step 0 정책 반영). stop_id=`BS_TAGO_GGB<stationId>`.
5. **시각 합성**: 기존 trip 정류장 중 새 열에도 있는 것(같은 stop_id, 순서 일치하는 최장 공통 부분열)=앵커, 원래 arrival/departure 유지. 첫·끝 정류장은 기존 trip 첫 출발·끝 도착 시각을 항상 앵커로 강제. 나머지는 앵커 사이 `station_m` 거리 비례 보간(arrival=departure, 초 단위 반올림). 단조 비감소 검증 실패 → trip 무변경 + report.
6. **형상**: GBIS 선을 `station_m[a]..station_m[b]` 로 잘라 새 shape `gbis_<route9>_<a>_<b>` (같은 구간은 공유). `shape_dist_traveled` = 잘린 선 시작부터 누적 m(3자리). stop_times.shape_dist_traveled = `station_m[i]-station_m[a]`.
7. **stops.txt**: 새 정류장 = GBIS 이름·좌표로 추가(나머지 컬럼은 기존 경기 정류장 행 형식 복제). 기존 `BS_TAGO_GGB*` 정류장 좌표는 GBIS 좌표로 갱신(여러 노선이 다른 값 주면 최빈값). 다른 지역 정류장 무변경.
8. **쓰기**: zip 의 다른 파일은 그대로 복사. trips.txt 는 shape_id 만 교체. stop_times.txt 는 스트리밍(1.6GB) — 경기 trip 행은 버리고 새 행 출력, 그 외 행은 원문 그대로. shapes.txt 는 경기 trip 만 쓰던 기존 shape 제거(다른 trip 이 참조하면 유지) + 새 shape 추가. transfers.txt 의 사라진 stop_id 참조 행 제거.
9. **report** (`gbis-apply-report.json`): 노선별 {mapped trips, unmapped, 정류장 추가/삭제 수, 시각 앵커 수}, 전체 분포, GBIS 누락 노선 목록.

### Step 3 — `runner/test_apply_gbis_routes.py` (unittest, stdlib)
계약당 1개 이상:
- [ ] 구간 매핑: 왕복 trip=[0,n-1], `_U`=[0,turn], `_D`=[turn,n-1], id 불일치 시 좌표 최근접 폴백, 실패→무변경.
- [ ] 단조 투영: 갈 때/올 때가 같은 도로를 공유하는 루프에서 귀로 정류장이 귀로 구간에 투영.
- [ ] 시각 보간: 앵커 시각 보존, 단조 비감소, 첫·끝 시각 = 원 trip.
- [ ] stop_id 규칙 `BS_TAGO_GGB<9>`, 다른 지역 trip/정류장 바이트 동일.
- [ ] 형상 잘라내기: shape_dist_traveled 0 시작·단조, 정류장 dist 와 일치.
- 실행: `python3 -m unittest runner/test_apply_gbis_routes.py -v`

### Step 4 (PR-3) — 파이프라인 연결 `runner/build_otp_graph.sh`
- 옵션 추가: `--from-shaped-zip`(steps 1-2 생략: 현 `$SHAPED_ZIP` 를 `$GTFS_OUT` 로 풀기; 120행 `SKIP_MATCH` 분기 옆), `--gbis DIR`(step 2b: `apply_gbis_routes.py` 실행, 출력 zip 을 `$GTFS_OUT` 로 다시 풀기).
- national 프로필에 `--gbis` 쓸 때 `SHAPED_ZIP` 출력 이름 = `data/input/national-nojeju-202503-shaped-gbis.gtfs.zip` (원본 zip 덮어쓰기 금지 — 롤백 원천).
- 이후 step 3 gate(164행) → step 4 color+그래프 빌드(178행, host JVM, otp278 jar sha 검증 그대로) → step 5 manifest 안내(214행).
- 실행: `runner/build_otp_graph.sh --profile national --from-shaped-zip --gbis data/input/gbis --thresholds config/otp-national/shape-gate-thresholds.json --modes bus,subway --graph-version national-nojeju-202503-cal20260729_20261130-otp278-shaped-gbis-g<graph sha 앞 12>`
  - graph sha 는 빌드 뒤에야 나오므로 기존 관례 확인(이전 graphVersion 의 g 접미사 산출법을 implementation_note 2178행 부근에서 확인 후 동일 적용).
- `data/otp-national/release-manifest.json` 갱신(gtfsShapedZip/Sha, graph sha, coverageIndex sha, graphVersion, `gbis` 블록: fetchedAt·노선 수·report sha) → `python3 runner/verify_release_bundle.py --manifest data/otp-national/release-manifest.json --write` → 재검증.

### Step 5 — 오프라인 검증 (배포 전 게이트)
- [ ] `python3 runner/validate_gtfs.py <out.zip>` 통과.
- [ ] 비교 재실행(2026-10-07 스크래치 compare.py 와 같은 방법): 경기 trip 형상 vs GBIS 선 50m 내 ≥99%.
- [ ] compute_shape_quality 리포트: 경기 trip residual p99 < 30m.
- [ ] 경기 외 trip: stop_times/shapes 행 해시 동일.
- [ ] 로컬 OTP 기동(host JVM, `--load`) "Graph loaded" + 88번·M7111·900번 지나는 OD 검색 → legGeometry 를 비교 지도에 겹쳐 확인.

### Step 6 — 배포 (implementation_note 2178행 runbook 재사용)
1. VPS `mkdir /srv/nextroute/otp/<새 graphVersion>/` → graph.obj + coverage-index.json scp → 양쪽 sha256 대조. (graph.obj 를 먼저 올리고 나서 up: 없으면 Docker 가 빈 디렉터리 생성 — 1716행 함정). 대용량 scp 가 에이전트 권한에서 막히면 사용자에게 명령 제공.
2. `cp compose.yaml compose.yaml.bak-<날짜>-gbis` → 기존 graphVersion 문자열 개수=4 확인 → `sed` 전역 치환 → `docker compose config -q`.
3. `docker compose up -d otp` → healthy 대기(이전 다운타임 ~20초) → `up -d api worker`.
4. 확인: api/worker 로그 `형상 마킹 ACTIVE ... graphVersion=<새>`, 경기 구간 검색 3건 200 + polyline 이 GBIS 선과 일치, `/api/arrivals/bus/<새 정류장 9자리>/<route9>` 정상.
5. 롤백: compose 백업 복원 + `up -d otp api worker` (이전 그래프 디렉터리 보존).
- 마이그레이션 없음. 앱 이미지 재빌드 없음.

## 3. 범위 밖
- 2025-03 이후 신설·폐선·배차 변경(시간표는 KTDB 유지). GBIS 에 없는 노선은 무변경 + report.
- 인천·서울·TAGO 지방 노선.
- 앱 DB 카탈로그(`regional_bus_route_stop`, PR #268) — 별도 축.

## 4. 리스크
- 통과 정류장 오정차(Step 0 로 차단).
- 정류장 교체로 OTP 환승·도보 연결이 바뀜 → 검색 결과 변동. Step 5 로컬 검색으로 확인.
- 그래프 빌드 메모리(host JVM 12g, 기존 성공 설정 그대로).

## 5. implementation_note 기록 규칙
발견 즉시 `### 확정/함정/좌표/미결/배포/검증` 에 append. 완료 선언 전 명령+결과를 `### 검증` 에.

## 6. 구현 중 확정·변경 (2026-10-07, 실측 근거는 implementation_note)
- 미결 1 해소: GBIS 통과 지점은 이름이 `…(경유)` 로 끝남 → 이것만 제외(mobileNo 공백 기준은 서울 정류장을 잘못 거름).
- 정류장 목록이 없는 노선(resultCode 4, 320개)·구간/시각 검증 탈락 trip 은 "형상만 교체"(정류장·시각 유지, GBIS 선에 단조 투영).
- 구간 선택 보강: 서로 다른 id 만 세기, 끝점 거리 우선, 순환 시작점 차이(`loopRotated`), 왕복 판정은 trip 첫·끝 정류장 기준, 새 구간 길이가 옛 trip 의 1/2.5~2.5배(+1km) 밖이면 탈락.
- 시각 보강: 옛 첫·끝 정류장이 새 목록 중간에 맞으면 고정점 제외, 고정점·trip 평균 속도 ≤25m/s.
- 파이프라인: `--from-shaped-zip --gbis DIR`. graphVersion 해시 = sha256(pfaedle zip sha + apply 스크립트 sha + 캐시 전체 sha) 앞 12자.
- 배포: OTP 는 `compose.yaml` + `/root/apps/observability/otp/compose.otp-national.yaml` 두 파일로 재생성(override 가 이미지·graph 마운트 지정), api·worker 는 `compose.yaml` 만.
