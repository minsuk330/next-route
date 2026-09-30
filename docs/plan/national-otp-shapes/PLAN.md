# 전국 OTP 그래프 대중교통 형상 — PLAN

요약: [SUMMARY.md](SUMMARY.md). 선행: [otp-p4-polyline-delivery](../otp-p4-polyline-delivery/PLAN.md) (파이프라인·게이트·앱 배선), [national-otp-map-migration](../national-otp-map-migration/PLAN.md) (전국 전환).

## 0. 현재 상태 (2026-09-30 실측)

| 항목 | 값 |
|---|---|
| 운영 OTP | `local/nextroute-otp:20260920-h-only-9dbfb229b868` (ser 278), graph `national-nojeju-20260929-otp278-h-only-9dbfb229`, Xmx3500m / mem_limit 4500m (`/root/apps/observability/otp/compose.otp-national.yaml`) |
| 전국 GTFS | `/private/tmp/otp-national-no-jeju-20260929/national.gtfs.zip`. trips 320,594 (bus 306,535 / subway 12,489 / rail 882 / ferry 688). **shapes.txt 없음**. 생성 명령 미기록 |
| api | `ROUTING_OTP_SHAPES_ENABLED=true`, graphVersion·coverage-index = 수도권 `...otp276-shaped-g13d196cb4979` (bus+subway, degraded 1,533) → 마킹 ACTIVE |
| 결과 | 전국 trip이 degraded 목록에 없으면 SHAPE_OK → **정류장 직선이 형상으로 노출** (신분당선 포함) |
| 메모리 | RSS 3.80GiB, Full GC 후 힙 2.45GiB, VPS available 1.36GB |

## Phase 0 — 현 상태 유지 + 운영 설정 저장소 반영

- 정류장 직선 노출은 사용자가 없어 감수한다(2026-09-30 결정). api 형상 설정은 건드리지 않고 Phase 6에서 새 번들로 한 번에 바꾼다.
- 저장소 `compose.yaml` otp 서비스를 운영값(전국 graph 경로, Xmx3500m, 4500m, JMX)과 맞춘다. 운영 override 파일이 저장소 밖에 있는 상태를 해소.

## Phase 1 — 전국 GTFS 입력 고정 (재생성 안 함)

- 운영 그래프를 만든 09-29 피드를 그대로 pfaedle 입력으로 쓴다. calendar `20260729..20261130`이라 당분간 유효하고, trip ID가 운영 그래프와 같다.
- `/private/tmp`는 휘발성 → `tools/transit-routing-benchmark/data/input/national-nojeju-202503.gtfs.zip`으로 복사, sha256을 release-manifest `gtfsPreShapeSha256`에 기록.
- 재생성(`prepare_gtfs.py --exclude-bbox`)은 후속: 다음 GTFS 갱신 때, 늦어도 calendar 만료(11-30) 전.

## Phase 2 — 빌드 스크립트 프로파일화

- `build_seoul_graph.sh` → `build_otp_graph.sh --profile seoul|national` (기존 파일은 `--profile seoul` 호출 래퍼로 남겨 재현성 유지).
- 프로파일이 정하는 값: 입력 피드, 작업 디렉터리 `data/otp-<profile>/shape-build`, shaped 피드 출력, coverage-index 출력 경로, 그래프 빌드 방식.
- **전국 그래프 빌드는 호스트 JVM**: 09-29 무형상 전국 빌드가 힙 최대 11.6GiB(Xmx12g)를 썼다. Docker Desktop VM(7.75GB)에서는 불가 → 배포 이미지와 같은 H-only jar(sha `56aebe27…`, ser 278)를 호스트에서 `java -jar`로 돌린다. serialization id는 jar가 정하므로 VPS 이미지에서 로드된다(09-29 실험으로 확인). 스크립트가 jar sha·ser id를 검증하고 불일치면 실패.
- 수도권 프로파일은 기존대로 `compose.seoul-otp.yml`(컨테이너, `OTP_IMAGE`) 빌드.
- pfaedle 이미지 digest, OSM(`south-korea-260903.osm.pbf`, pfaedle·OTP 공용) 고정 유지.

## Phase 3 — pfaedle 전국 실행 + 분포 리포트 (`--report-only`)

1. **bus** (`--mots bus`, 30.7만 trip) → **subway** (`--mots subway`, 1.2만 trip) 순서. P4에서 확인한 대로 subway 실행은 기존 bus shape를 건드리지 않는다 — 전국에서도 bus 지표 불변으로 재확인.
2. 실행 시간·peak 메모리 기록(`docker stats`/`/usr/bin/time`). 수도권 bus는 약 2분(arm Mac, amd64 에뮬레이션). 메모리 부족 시 대안: 시도 단위로 피드를 쪼개 실행 후 shapes 병합.
3. `compute_shape_quality.py` 확장 — **지역별 분포**: route_id의 지역 코드(TAGO `SEB`/`GGB`/`ICB`/…, `BR_3100` 등)로 시도 그룹핑해 모드×지역 표 출력.

**Phase 3 실측 (2026-09-30)**
- bus 306,535 trip 전량 매칭, 20분(pfaedle ~15분), 컨테이너 peak 6.03GiB / Docker VM 7.75GiB. 수도권 bus 임계로 degraded 3.93% — 대부분 `BR_ACC3`(고속·시외, 49%)·울산(14.6%).
- subway 12,489 trip 전량 매칭(7.5분, peak 0.76GiB). `--input`으로 bus 결과 위에 이어 돌려 bus 지표 불변 확인.
- Naver 지도 육안 확인: ACC3 degraded 샘플은 고속도로를 잘 따라감(오탐), 반대로 게이트 통과 샘플 중 정류장 직선(서울호남→전주 104km 직선, 경춘선·대경선·경의중앙선 역간 직선)이 있음(미탐). pfaedle은 두 정류장 사이 망 경로가 없으면 직선으로 잇는다.
- 사용자 결정: ① 고속·시외버스(ACC3)는 게이트 결과와 무관하게 그대로 적재 ② 광역철도는 subway 그래프에 `railway=rail`을 넣은 설정(`config/otp-national/pfaedle-subway-rail.cfg`, `--pfaedle-config`)으로 재매칭 — 대경선 17.6→0.9km, 경의중앙선 15.5→3.2km, 경춘선 13.7→2.7km 등 역간 직선 해소.
- 지하철 형상 확정(2026-09-30): 재매칭 결과를 채택. 대안으로 HOT OSM 철도 추출본(`railway=rail`/`station`만, 도시철도 선로 없음)을 Naver 지도로 비교했으나 사용하지 않기로 함.

**결정 게이트 (사용자)**: 모드별·지역별 분포를 보고 ① bus/subway 임계값 ② degraded 비율이 큰 지역을 받아들일지(= 그 지역은 polyline null) 확정. 결과는 `config/otp-national/shape-gate-thresholds.json`.

## Phase 3.5 — 노선 색 `route_color` (결정 2026-09-30, #247)

색은 고정값이므로 그래프 빌드 직전에 pfaedle 출력 `routes.txt`의 `route_color`를 채워 OTP `route.color`로 내려받는다. 앱은 덮어쓰지 않는다(서울 버스 포함).
- 지하철: `route_short_name` 기준 공식색 표(수도권 23 + 부산·대구·광주·대전·대경선·동해선·경강선·부산김해).
- 서울 버스: `BR_TAGO_SEB(\d{9})` → 운영 DB `bus_route.route_type` 스냅샷 → `BusRouteTypeColors`와 같은 색. 로컬 DB 기준 737개 중 659개 매칭. 미매칭·미정의 유형은 기본 버스색.
- 나머지 버스: 수단별 기본색(route_id 접두사).
- 스냅샷 sha는 release-manifest에 기록. 앱은 `route.color`만 쓰고 색 결정 코드 제거, lane은 색 없어도 출력.
- 구현: `config/otp-national/route-colors.json`(지하철 36개 노선 전부, 서울 버스 유형 1=`E60012`·15=`0068B7`, 기본색 bus `53B332`·intercityBus(BR_ACC3) `8652A1`·rail `003DA5`·ferry `0090D2`), `config/otp-national/seoul-bus-route-types.json`(운영 DB 728개 스냅샷), `runner/apply_route_colors.py`(national 빌드 step 4에서 실행). 전국 25,513 route 전부 색 채움 확인. 서울 공항버스 `_U`/`_D` 접미사도 매칭(앱 실시간 매핑은 여전히 미매칭 → #247).

## Phase 4 — 그래프 빌드 + 번들

1. 확정 임계로 coverage-index 생성: graphVersion `national-nojeju-202503-cal<window>-otp278-shaped-g<hash>`.
2. shaped 피드로 OTP 그래프 빌드(H-only jar, 같은 OSM). 빌드 로그·시간 기록(무형상 전국 빌드 6분).
3. `data/otp-national/release-manifest.json` 작성(수도권 manifest와 같은 필드: 입력 sha, pfaedle digest·matchedTrips, OTP image digest, graph sha, coverage-index sha, graphVersion) → `verify_release_bundle.py --write` 후 재검증.

**Phase 4 실측 (2026-09-30)**
- OTP 도로망 OSM: Geofabrik `south-korea-260929` (md5 `7775afec…`)를 `osmium extract -b 124.0,33.7,132.0,39.0 --strategy complete_ways`로 제주 제외 → `south-korea-260929-nojeju.osm.pbf` (sha256 `60e68c91…`). 260903(pfaedle 입력)은 Geofabrik 보관 기간이 지나 재다운로드 불가 — 형상은 이미 GTFS에 있어 도로망 날짜와 무관.
- 첫 빌드는 OSM 파일 유실로 OTP가 경고 없이 도로망 없이 빌드(290MB) → 폐기. 스크립트에 OSM 존재·크기 검사 추가.
- 재빌드: graph.obj 909.6MB, 빌드 4m44s, 호스트 peak RSS 8.3GB. 도로망 import 경고는 09-29 무형상 빌드와 거의 동일.
- 알려진 문제(수용, 2026-09-30): OTP가 형상 일부를 거부 — `BogusShapeGeometryCaught` 443, `ShapeGeometryTooFar` 397 → 해당 구간은 정류장 직선으로 대체되지만 coverage-index는 SHAPE_OK. `BogusShapeDistanceTraveled` 3,829는 기하 투영으로 재계산(대부분 정상 복구 추정). 대상 trip 추출(빌드 리포트)은 보류.

## Phase 5 — 로컬 검증

- 그래프 로드 + 메모리: Full GC 후 힙 live set, RSS(Xmx3500m 기준). 무형상 대비 증가분 기록. live set이 2.9GiB를 넘으면 Xmx/한도 재검토 후 진행.
- 지역별 fixture 검색(서울, 경기 bbox 밖 1곳 이상(평택·이천 등), 인천, 부산, 대구·광주·대전 도시철도, 신분당선): leg마다 `legGeometry.length > 정류장 수`, SHAPE_OK/degraded가 coverage-index와 일치.
- 앱 로컬 실행으로 같은 fixture의 응답 polyline: SHAPE_OK → non-null, degraded → null.

## Phase 6 — VPS 배포 (번들 원자성)

1. graph.obj + coverage-index.json을 `/srv/nextroute/otp/<graphVersion>/`에 업로드, sha256 대조.
2. otp override의 graph 경로 교체 → `up -d --force-recreate --no-deps otp` → healthy.
3. api env `ROUTING_OTP_GRAPH_VERSION`, coverage-index 마운트를 새 번들로 바꾸고 `ROUTING_OTP_SHAPES_ENABLED=true` → api 재기동 → 로그 `형상 마킹 ACTIVE: graphVersion=<새 버전>`.
   - 2와 3 사이 수 분간은 api가 옛 graphVersion을 들고 있어 지금과 같은 정류장 직선 노출 상태다(감수).
4. 사후 확인: 동시 1/2/4건 RSS·GC(이번 기준: 한도 4500m, Full GC 0), 지역 fixture polyline, Redis `used_memory`(P4 B4 가드 32MB).
5. 롤백: api `ROUTING_OTP_SHAPES_ENABLED=false` 한 줄로 형상만 끔. 그래프 롤백은 무형상 전국 graph 경로로 되돌림.

**Phase 5·6 실측 (2026-09-30)**
- 로컬(amd64 에뮬레이션, Xmx3500m/4500m): 10개 지역 fixture 모두 bus·subway 형상 점 수 > 정류장 수, `route.color` 전량 내려옴. RSS 3.88GiB.
- VPS 배포 20:24: graph.obj(sha `cdd59908…`)·coverage-index(sha `ff72bd0c…`)를 `/srv/nextroute/otp/national-nojeju-202503-cal20260729_20261130-otp278-shaped-g035004114e7b/`에 배치. `compose.yaml`(graphVersion·coverage-index 마운트·otp 기본 graph)과 `compose.otp-national.yaml`(graph·GC 로그 경로) 수정, 백업 `*.bak-20260930-202422`. otp 재생성 ~50초 healthy, api·worker 재생성(이미지 동일 `nextroute:20260929-181906-364de2ab`) → `형상 마킹 ACTIVE … coveredModes=[bus, subway] degraded=0`.
- 앱 API(`/api/route/search`): 서울·신분당·부산·경기 외곽·광주 모두 polyline 채워짐. 지방 지하철 lane·색 null, 서울 외 버스 색 null은 앱 미수정(#247).
- 동시 1/2/4건 전부 200, 0.7~3.7s, Full GC 0, RSS 3.80GiB(한도 4.39GiB). 단 G1 mixed 후 힙 ~3.0GiB(무형상 대비 약 +150MB), 3분간 marking cycle 26회 — 힙 여유 약 0.5GiB, 부하 시 GC 관찰 필요.
- 롤백: 두 compose 파일을 `.bak-20260930-202422`로 되돌리고 otp → api·worker 순서로 재생성.
- 릴리즈 번들: `data/otp-national/release-manifest.json` (bundleHash `c3ccab14…`, `verify_release_bundle.py --manifest …` OK). 앱 색·lane 반영은 #248(머지).

## 후속 (이번 범위 밖)

- api가 OTP 실제 그래프를 확인하는 런타임 가드(예: OTP 응답의 feed/그래프 식별값과 graphVersion 대조, 불일치 시 DISABLED). 이번 사고는 설정 쌍은 맞지만 OTP가 다른 그래프를 띄운 경우라 기존 기동 게이트(PR-B2)로 못 잡았다.
- 전국 GTFS 재생성: `prepare_gtfs.py`에 `--exclude-bbox`(제주) 추가, 명령으로 재현. calendar 만료(2026-11-30) 전.
- rail(882 trip)·ferry 형상: 검색 노출 정책이 바뀔 때.
- 월별 GTFS 갱신 시 이 파이프라인을 정기 실행하는 절차.
