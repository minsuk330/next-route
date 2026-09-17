# national-otp P3 — pfaedle 대중교통 형상 (L, codex 반영 rev2)

에이전트 실행용. 선행 스파이크·결정은 [[project-otp-p3-geometry]] 메모리 + `implementation_note.md` 2026-09-16 P3 블록 참조. intent = `docs/plan/national-otp-map-migration/PHASE-3.md`.

## 배경 (코드만 봐선 안 나오는 것)
- KTDB GTFS(202503)에 `shapes.txt` 없음 → OTP가 대중교통 leg를 정류장 직선으로 그림(intent §5 금지).
- 방향 확정: **pfaedle**(OSM 도로 map-matching → shapes.txt), NOT OSM route relation(스파이크 실패). 실측: pfaedle shape vs VPS `bus_route_segment_polyline` 평균 4~6m 일치.
- OTP `planConnection.graphql`이 이미 `legGeometry{ length points }`(encoded polyline) 반환. `OtpPlanConnectionMapper.toGeometry`(src/main/.../otp/OtpPlanConnectionMapper.java:204-213)가 파싱, 지금 transit=`STREET_FALLBACK` 하드코딩.
- **핵심 안전 계약(codex)**: OTP는 shape 없는 trip도 직선 `points`를 반환한다 → "points 존재 = 진짜 형상"으로 판단 금지. shape 여부/품질을 **외부 per-trip 인덱스**로만 판정.

## 설계 원칙 (codex 7 findings 반영)
1. **per-trip 게이트** (route-level·config-블랜킷 금지). 통과 trip만 SHAPE_OK.
2. **게이트 = pfaedle 자체 지표** (VPS를 oracle로 안 씀). VPS 비교=telemetry 리포트만.
3. **staged**: Stage1=**버스만**. 지하철/철도/ferry=STREET_FALLBACK 유지(측정 후 후속).
4. **원자 릴리즈**: graph↔graphVersion↔coverage-index 해시 묶음.

## 작업 순서

### PR-A1 — 형상 생성 + 게이트 파이프라인 (tools/, 오프라인)
- [ ] `tools/transit-routing-benchmark/runner/build_seoul_graph.sh` 신규: `prepare_gtfs.py`(기존, 표준 route_type·calendar창) → **pfaedle** → 게이트 → zip → `compose.seoul-otp.yml otp-build` 를 한 스크립트로.
  - pfaedle: `docker run -v <dir>:/data -w /data ghcr.io/ad-freiburg/pfaedle@sha256:<digest> -m bus -x sk-roads.osm.pbf <gtfs-dir>` (⚠ ENTRYPOINT=pfaedle라 명령어 재입력 금지). 출력 기본 `gtfs-out/`(shapes.txt 포함).
  - OSM 입력 = `data/input/south-korea-260903.osm.pbf`(전국 도로 273M). OTP build의 metro PBF(walk용)와 별개.
  - **이미지 digest 고정**(not `:latest`). calendar date args 스크립트에 고정.
- [ ] `runner/compute_shape_quality.py` 신규: shaped GTFS(shapes.txt+trips.txt+stop_times.txt+stops.txt)만으로 trip별 지표 계산(OTP 없이):
  - shape 참조(trip에 shape_id 유무), 정류장→최근접 shape점 잔차(p90/max), 전방 단조성(정류장 순서대로 shape_dist_traveled 증가), 끝점 앵커오차(첫/막 정류장 vs shape 양끝), 우회비율(shape 길이/정류장 직선합).
  - 임계 초과 trip = DEGRADED. 미shape trip = MISSING.
  - 산출 `data/otp-seoul/coverage-index.json` = `{ "graphVersion": "...", "coveredModes": ["bus"], "degradedTripIds": ["BR_TAGO_SEB..._Ord001", ...] }`. 통과 trip은 인덱스에 없음(앱 기본 SHAPE_OK). `degradedTripIds` = DEGRADED ∪ MISSING.
  - 임계값은 **전량 실행 분포 보고 확정**(첫 실행에서 히스토그램 출력, 사용자와 임계 결정).
- [ ] `runner/compare_pfaedle_vps.py` 신규(스파이크 `compare.py` 생산화): pfaedle shape vs VPS geom(ssh export) 노선별 mean/p90/Hausdorff → `results/pfaedle-vs-vps.json`. **리포트 전용, 게이트 아님**(oracle 회피).
- [ ] zip(shapes 포함) → `data/input/seoul-bbox-202503.gtfs.zip`. graph 재빌드 → 새 graphVersion `seoul-bbox-202503-cal<...>-shaped-g<hash>`.
- [ ] `data/otp-seoul/release-manifest.json` 갱신: pfaedle 이미지 digest + OSM PBF sha + shaped-GTFS sha + coverage-index sha + calendar date args 기록(재현 pin).
- 검증: pfaedle exit0, shapes.txt 노선/점수, 게이트 통과/DEGRADED %, otp-build exit0 + `docker compose -f compose.seoul-otp.yml up -d otp` + planConnection 쿼리로 버스 leg `legGeometry.points` 직선 아님 육안(스파이크 노선 143·152·502).

### PR-B1 — 앱 형상 품질 마킹 (src/main)
- [ ] enum 값 추가: `application/route/routing/GeometrySource.java`에 `GTFS_SHAPE`, `application/route/routing/GeometryQuality.java`에 `SHAPE_OK`.
- [ ] `ShapeCoverageIndex` 신규(application/route/routing/ 또는 config): graphVersion + coveredModes + degradedTripIds(Set) 로드. 리소스 = graph 배포와 함께 오는 `coverage-index.json`(경로 config). **graphVersion mismatch 시 전부 fallback**(오마킹 0).
- [ ] config `OtpRoutingProperties.shapesEnabled`(기본 false) + coverage-index 경로.
- [ ] `OtpPlanConnectionMapper.toGeometry`(:204-213) 개정: transit leg → `leg.trip.gtfsId`(이미 파싱) 로 index 조회.
  - shapesEnabled && index.graphVersion==config.graphVersion && mode∈coveredModes && tripId∉degradedTripIds → `GeometrySource.GTFS_SHAPE` + `GeometryQuality.SHAPE_OK` + points.
  - else → 현행 `STREET_FALLBACK`.
- [ ] warnings 로직(:115-117): SHAPE_OK leg는 `GEOMETRY_STREET_FALLBACK` 미발행. 남은 STREET_FALLBACK transit leg 있으면 경고 유지.
- [ ] **legacy 경로 무변경**: `LegacyRouteResponseMapper` polyline=null 유지(프론트 별도 조회, [[feedback-server-static-search-front-realtime]]). 형상은 v2 DTO(`RouteSearchV2Mapper`).
- 검증: `./gradlew compileJava compileTestJava` + 계약 테스트 — ①index-covered 버스 trip=SHAPE_OK ②degraded trip=fallback ③graphVersion mismatch=전량 fallback ④비버스(지하철)=fallback. `*Otp*`/geometry 무회귀.

### PR-B2 — 배포 원자성 (codex #7)
- [ ] 불변 릴리즈 번들: graph.obj + OTP 이미지 digest + shaped-GTFS + coverage-index + app graphVersion 을 한 manifest 해시. VPS shadow swap 후 단위 검증.
- [ ] app 기동 검증: coverage-index.graphVersion == `OtpRoutingProperties.graphVersion` 확인(불일치=형상 전량 fallback + 경고 로그). 라이브 graph 실검증(P2-C 미결)은 별건 연계.
- 배포 = shadow 갱신 절차([[project-otp-shadow-deploy]]), **dormant 유지**(otp-enabled=false라 형상 변경 프로덕션 노출 0까지 회귀 없음).

## 스코프 밖
- **P3-C 모바일**(Naver 표시 spike): nextroute-mobile 필요 → API/fixture/지침 HANDOFF만.
- **Stage2 지하철/철도**: 측정 후 별도. 나쁘면 기존 좌표(`odsay_route_polyline`/`subway_segment`) 폴백 유지.

## 마이그레이션
없음.

## 재현 도구 (스크래치패드 스파이크)
osmvenv(osmium 4.3.1/shapely 2.1.2), pfaedle `ghcr.io/ad-freiburg/pfaedle:latest`(digest 고정 필요), gtfs_subset.py(참조폐쇄), compare.py. VPS: user=myuser db=mydatabase, `bus_route_segment_polyline`(route_id=SEB strip 9자리, geom LineString4326, quality_status OK/REVIEW).

## 미결
- 게이트 임계값 = 전량 분포 후 확정.
- degradedTripIds 용량 크면 인덱스 형식 재검토(shape_id 키 vs trip 키).
- 지하철/철도 rail MOT 매칭 품질 미측.
