# 역 출구를 OTP 그래프에 넣기 — 역에서 출발·도착할 때 출구 기준 도보 (L 티어)

상태: 플랜 승인(2026-10-12) → Opus 설계 리뷰 → **설계 변경(가상 역, §2.5)** 후 같은 세션 구현. 브랜치 `feat/otp-station-entrances`(fresh main `baf12a48` 기준).
경로 접두사 `B` = `src/main/java/watoo/grd/nextroute`, `T` = `src/test/java/watoo/grd/nextroute`, `R` = `tools/transit-routing-benchmark`.
조사 근거: `implementation_note.md` "직통 버스가 안 보이는 원인 — 잠실역(8호선)→강남역(신분당선) 네이버 대조 (2026-10-12)".

## 1. 배경

네이버(잠실역 8호선 → 강남역 신분당선, 14:46): 2호선 21분, 버스>2호선, 8>9>신분당, **2000 직통 32분, 360 직통 36분**, 341 직통 …
우리(같은 좌표): 4319>341 / 341 / 2호선 / 3413>340 / 4319>0411 — 2000·360 직통 없음.

원인 = 출발·도착 좌표가 **역 안의 한 점**(장소 검색 좌표 = 8호선 승강장 중심, 신분당선 승강장 중심).
- OTP 360 직통: 출발 도보 672m·9분(네이버 130m·2분, 잠실역 5번 출구에서 출발), 도착 825m·11분(네이버 97m) → gc 5557, 상위 5 밖
- 2000 직통: 버스만 검색 LIST_ALL 에도 없음(잠실역6번출구까지 도보 비용 > 4319 한 정거장 + 환승)
- 2호선: 도보 17분(네이버 8분). 강남(2호선)→도착점 569m, 직선 188m(지하 연결이 그래프에 없음)
- 노선 데이터는 정상(2000 #59 잠실역6번출구→#69 강남역12번출구A, 360 #8→#21, 3412 #40→#57)
- 실험: 출발·도착을 네이버 출구 좌표(127.0989137,37.5131457 / 127.0285723,37.4984732)로 옮기면 **360 21분·2000 24분·7007 24분 직통 + 2호선 21분(도보 10)** 등장. 도보 가중치(1.0/1.5)는 효과 없음

사용자 결정(2026-10-12): **그래프에 역 출구를 넣는다**(네이버 방식). 서버에서 출구별 다중 검색·기록만은 기각.

## 2. 확인된 사실 (설계 전제)

OTP 포크 `/Users/wa/golbob/개인/OpenTripPlanner`(dev-2.x, 배포 jar ser.id 278):
- GTFS `location_type=2` → `Entrance`(`GTFSToTransitDataImportMapper.java:253-262`), 그래프에서 `TransitEntranceVertex`(`AddTransitEntitiesToGraph.java:137-142`), 거리망 연결 `StreetLinkerModule.linkTransitEntrances`(`:246-266`)
- 승강장↔출구 간선은 **`pathways.txt` 행으로만** 생성(`AddTransitEntitiesToGraph.java:176-225`, length 없으면 직선거리 `:189-191`). 자동 연결 설정 없음
- **pathways 가 있는 승강장은 거리망에 직접 연결되지 않음**(`StreetLinkerModule.java:106-109`) + `streetToStopTime=0`(`StreetTransitStopLink.java:34-37`). pathways 없는 지하철 승강장엔 `subwayAccessTime`(기본 2분) 페널티
- OSM `railway=subway_entrance`(`includeOsmStationEntrances`)는 `StationEntranceVertex`(길안내 표시용)일 뿐 역과 경로 연결 없음 → 사용 불가
- 출발지 = 역(parent station) ID(`PlanLocationInput.stopLocation`, `schema.graphqls:5097-5108`) → 자식 승강장 전부 비용 0 시작 → 한 번의 도보 탐색으로 pathways·출구·주변 정류장(`LinkingContextFactory.java:320-339`, `AccessEgressRouter.java:43-70`, `StreetNearbyStopFinder.java:157-187`). 좌표 여러 개 출발 API 없음(`@oneOf`)
- `transitRouteToStationCentroid`(`BuildConfig.java:457`)는 반대 효과(중심 한 점) → 켜지 말 것

데이터:
- KTDB GTFS: 노선별 승강장 별개 stop(`1:RS_ACC1_S-1-0216` 잠실(2호선), `1:RS_ACC1_S-1-2815` 잠실(8호선)), 211,145개 전부 `location_type=0`·`parent_station` 없음, pathways·entrances 없음, 노선 간 환승은 `transfers.txt`(`min_transfer_time`)
- 출구: `src/main/resources/subway/entrances.csv`(`osm_id,lat,lon,ref,label`, OSM `south-korea-260929-nojeju`, ODbL). 출구 번호 있는 것 4,462, 696역 중 692역이 350m 안 출구 보유(없음: 연천·광명·도농·인천공항2터미널). 생성 `tools/subway-entrances/build_entrances.sh`(+`to_csv.py`). 역 ID 연결 없음 — label 의 역 이름 + 350m(`B/application/subway/service/SubwayEntranceIndex.java` `exitToward`)
- 그래프 빌드: `R/runner/build_otp_graph.sh --profile national --from-shaped-zip --gbis data/input/gbis --thresholds config/otp-national/shape-gate-thresholds.json --modes bus,subway --graph-version <ver>`(Mac 호스트 JVM `-Xmx12g`, `otp-h-only-9dbfb229.jar` sha·ser.id 278 검사, `config/otp-national/build-config.json`, OSM `south-korea-260929-nojeju.osm.pbf` bbox 124.0,33.7,132.0,39.0). 순서: pfaedle(재사용) → 2b GBIS(`apply_gbis_routes.py`) → shape gate(`compute_shape_quality.py` → coverage-index.json) → `apply_route_colors.py` → OTP build → release bundle(`verify_release_bundle.py --write`)
- ⚠ 입력 GTFS 캘린더 20260729..20261130 — 11-30 만료 전 재생성 별건(`docs/plan/national-otp-shapes/PLAN.md:96`)
- 앱: `RouteSearchRequest`/`RoutingQuery` 는 좌표·이름만. `B/infrastructure/adapter/out/api/otp/OtpRoutingAdapter.java:150-155` 가 항상 `coordinate` 로 보냄
- 역 ↔ GTFS: `SubwayGtfsStopIndex.gtfsStopId(lineId, stationId)`(런타임, 캐시된 패턴 기반, warmup 전 비어 있음)

## 2.5 설계 변경 — 승강장마다 가상 역 (2026-10-12, 서울 로컬 그래프 실측)

처음 설계(역 하나 부모 + 출구 location_type 2 + pathways)는 실측에서 두 문제가 나와 버렸다.
- **역 하나 부모로 출발** = 모든 노선 승강장에 비용 0 → 잠실역(8호선)→강남 2호선 도보 0 **11분**(네이버 21분). 승강장 id 출발로 바꾸면 출구·다른 노선까지 통로로 걸어 17분이지만 아래 문제가 남음
- **pathways = 역 관통 지름길**: 출구→승강장→출구 통로가 거리보다 짧아 개찰구 없이 역을 가로지름(서울역 서부→3번→9번 출구, 영등포 북↔남). 또 pathway 붙은 승강장은 거리망에서 끊겨 **역과 무관한 좌표 검색까지 바뀜**

채택 = 가상 역. 지하철 승강장 P 마다:
- 부모 `VST_<P>`(location_type 1, 이름 = 역 기본이름, 좌표 = P)
- 자식 = P(`parent_station` 채움) + 그 역 출구마다 `VEX_<osm_id>_<P>`(location_type **0**, 운행 없음, `stop_code`=출구번호, 이름 = label 또는 "<역>역 N번 출구")
- pathways.txt 없음 → 승강장·환승(transfers.txt)·좌표 검색은 그대로. 바뀌는 건 `VST_` 로 출발·도착하는 검색뿐
- 출구가 location_type 2 가 아닌 이유: OTP station childStops 는 RegularStop 만 → Entrance 는 출발점이 아님. 일반 stop 이라 OTP 가 거리망에 붙인다

결과(서울 로컬, 잠실역 8호선 → 강남역 신분당선): 2호선 19분(도보8) / 360 21분(도보4) / 2000 25분(도보3) / 7007 25분(도보3). 서울역 서부·영등포·강남대로→광화문 = 기준 그래프와 같음.

Opus 리뷰 반영: 역 판별에 **장소 이름 조건**(좌표만이면 출구·정류장에 선 GPS 도 역에 붙음), 재탐색은 이름 없음 → 좌표, 같은 역 양끝은 좌표, 일반철도(RS_TAGO) 승강장 제외, 출구번호 stop_code, 묶음 폭 상한·근접 미병합 쌍 리포트, 역 목록 6시간 갱신. 0m 도보 보정 불필요(앱이 첫 구간 도보를 전제하지 않음 — `LegacyRouteResponseMapper` 확인).

## 3. 작업 순서 (구현 = 한 브랜치, PR 하나 + 그래프 배포)

### A. GTFS 전처리 도구 ✅
- [x] `R/runner/add_station_entrances.py`
  ```
  python3 add_station_entrances.py --in <shaped-gbis.gtfs.zip> --entrances src/main/resources/subway/entrances.csv \
      --out <...-entrances.gtfs.zip> --report <report.json> [--rail-prefix RS_] [--metro-prefix RS_ACC1_] \
      [--group-radius-m 500] [--transfer-radius-m 1000] [--exit-radius-m 300] [--max-group-span-m 600] [--dry-run]
  ```
  - 묶음 = `RS_` 승강장, 기본이름(괄호 노선 표기·끝 "역" 제거) 500m 안 + transfers.txt 쌍(1000m 안). 묶음 폭 > 600m 면 실패. 입력에 이미 parent 있으면 실패
  - 출구 = 가장 가까운 승강장 300m 안의 묶음. label 이 범위 안 다른 역 이름이면 그쪽
  - 가상 역은 `RS_ACC1_`(도시·광역철도) 승강장만. 출구 없는 묶음은 손대지 않음
  - 리포트: groups, groupsWithExits, exitsAssigned/Unmatched, virtualStations, exitStops, metroPlatformsWithoutStation, maxGroupSpanM, nearUnmergedPairs, params
  - 나머지 파일 바이트 복사(shapes.txt 포함)
- [x] `R/runner/test_add_station_entrances.py` 15개: `python3 -m unittest runner/test_add_station_entrances.py -v`(cwd `R`)
- [x] `R/runner/build_otp_graph.sh --station-entrances <csv>`: step 2c(GBIS 뒤, gate 앞), 출력 zip 접미사 `-entrances`(옛 zip = 롤백 원천)
- 전국 dry-run(shaped zip 62a8fb2d): 묶음 1140, 출구 있는 묶음 932, 출구 4427/4462, 가상 역 1071, 출구 정류장 5700, 최대 폭 434m

### B. 앱 ✅
- [x] `B/infrastructure/adapter/out/api/otp/OtpStationResolver.java`(신규): `Optional<Snap> snap(lat, lon, placeName)`, `Snap(stationId, stationName)`
  - 장소 이름이 역 모양(`STATION_PLACE`)이고 그 이름의 RAIL 계열 승강장이 `station-snap-meters`(150) 안이면 가장 가까운 승강장의 부모 id
  - 목록 = OTP `{ stations { gtfsId name stops { gtfsId name lat lon vehicleMode } } }`, 백그라운드 적재(성공 6h, 실패 5분, 빈 목록 30분 후 재확인). 검색은 기다리지 않음 → 목록 없으면 좌표
- [x] `OtpRoutingAdapter`: `endpoints(q)` → 역이면 `{location:{stopLocation:{stopLocationId}}}`. 양끝이 같은 역 이름이면 둘 다 좌표. 역 id 검색이 `GRAPHQL_ERROR`/`LOCATION_NOT_FOUND` 면 좌표로 1회 재검색
- [x] `OtpRoutingProperties`: `stationSnapEnabled=true`(킬스위치 `ROUTING_OTP_STATION_SNAP_ENABLED`), `stationSnapMeters=150`
- [x] `OtpTransitPatternAdapter.parseStopsNear`: 노선 없는 정류장(= 출구 지점)을 자르기 전에 제외 — 단독 추적 주변 정류장 자리를 차지하지 않게. 지도 타일은 BUS 만 거르므로 그대로
- 테스트: `./gradlew test --tests '*OtpRoutingAdapter*' --tests '*OtpTransitPatternAdapterTest' --tests '*TrackSelectionQueryServiceTest' --tests '*RouteSearchService*' --tests '*RemainingRouteSearch*'` + 전체

### C. 그래프 재빌드·배포 (사용자 확인 후)
- [ ] GBIS 재수집(VPS `/root/gbis-fetch`, flock) 완료 → 로컬로 복사(키는 VPS 밖으로 안 나감, 캐시만)
- [ ] `build_otp_graph.sh --profile national --from-shaped-zip --gbis <cache> --station-entrances src/main/resources/subway/entrances.csv --thresholds config/otp-national/shape-gate-thresholds.json --modes bus,subway --graph-version national-nojeju-202503-cal20260729_20261130-otp278-shaped-gbis-entrances-g<sha12>`(JDK 25, -Xmx12g, 배포 포크 jar)
- [ ] 로컬 전국 OTP 로 §5 코퍼스 + 출구 연결 점검(VEX 가 거리망에 붙었는지, VST 출발 도보 성공)
- [ ] 배포(기존 절차, `implementation_note.md` :2178·:3177·:3835): `/srv/nextroute/otp/<ver>/` 에 graph.obj·coverage-index.json(sha 대조) → VPS `compose.yaml` graphVersion 4곳 + `/root/apps/observability/otp/compose.otp-national.yaml` 1곳(백업 후 그 줄만) → `docker compose config -q` → `up -d otp`(healthy) → 앱 배포 + `up -d api worker`
- 되돌리기: compose graphVersion 옛 값 → `up -d otp api worker`. 앱만 끄려면 `ROUTING_OTP_STATION_SNAP_ENABLED=false`

## 4. 경계 리뷰
- 계약: 앱 요청·응답 형식 불변. 이름이 역인 장소에서 출발·도착하는 검색의 도보·경로 구성이 바뀜(의도). 응답 첫 구간이 도보 없이 대중교통일 수 있음(승강장에서 바로 탐)
- 상태: 없음(그래프 + 무상태 캐시)
- 실패: 역 목록 적재 실패·빈 목록 → 좌표. 역 id 검색 오류 → 좌표 재검색. 킬스위치
- 플래그 기본값: `station-snap-enabled=true` — 옛 그래프에선 부모 역이 없어 자동 무동작
- 그래프: 배포 포크 jar 로만 빌드(ser.id 불일치 = OTP 기동 실패, 과거 함정). 출구 정류장 5,700개가 `stopsByBbox` 에 노선 없이 나옴 → 소비처 2곳 확인(지도 타일 BUS 필터, 단독 추적 제외 처리)

## 5. 검증 코퍼스 (배포 전 로컬 전국 OTP, 배포 후 운영 API)
| OD | 기대 |
|---|---|
| 잠실역 8호선 → 강남역 신분당선 | 360·2000 직통 등장, 2호선 도보 ≤10분 |
| 강남역→홍대입구역, 신촌역→강남역 | 2호선 1위 유지 |
| 서울역→잠실역, 혜화역→낙성대역 | 회귀 없음 |
| 서울역 서부 좌표 ↔ 동쪽, 영등포 북↔남 | 역 관통 지름길 없음(기준과 같음) |
| 역 아닌 좌표(강남대로→광화문) | 기준과 같음 |
| 노원→강남, 부평→서울, 수원→강남, 홍천→인제 | 회귀 없음, 고유 경로 유지 |

## 6. L 절차
Opus 설계 리뷰(사용자 지시 "opus 논의 진행하고 끝까지 구현") → 같은 세션 구현 → 테스트 + 로컬 전국 코퍼스 → 배포 전 사용자 확인. 발견 사항은 `implementation_note.md` "역 출구를 그래프에 — 구현" 에 append.
