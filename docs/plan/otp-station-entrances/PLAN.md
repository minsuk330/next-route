# 역 출구를 OTP 그래프에 넣기 — 역에서 출발·도착할 때 출구 기준 도보 (L 티어)

상태: 플랜 승인(2026-10-12). codex 논의 → 구현은 새 세션. 브랜치 `feat/otp-station-entrances`(fresh main `baf12a48` 기준).
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

## 3. 작업 순서

### PR-1. GTFS 전처리 도구 (의존 없음)
- [ ] `R/runner/add_station_entrances.py` 신규
  ```
  python3 add_station_entrances.py --in <shaped-gbis.gtfs.zip> --entrances src/main/resources/subway/entrances.csv \
      --out <...-entrances.gtfs.zip> --report <report.json> [--exit-speed-mps 1.0] [--exit-fixed-sec 60] [--group-radius-m 500] [--exit-radius-m 350]
  ```
  1. **역 묶음**: `routes.txt` route_type 1(지하철)/2(철도)를 타는 stop(실측상 `RS_` 접두) 대상. 키 = 기본 이름(괄호 안 노선 표기 제거, 예 "잠실(2호선)"→"잠실") + 서로 500m 안. `transfers.txt` 에 쌍으로 있으면 같은 묶음 보강. 부모 `stop_id` = `STN_<대표stop>`(결정적), `location_type=1`, 좌표 = 자식 중심, 자식 `parent_station` 채움
  2. **출구**: entrances.csv 행 중 label 에 `<기본이름>역` 이 들어 있고 묶음 중심 350m 안 → `stop_id=ENT_<osm_id>`, `location_type=2`, `parent_station`=묶음, `stop_name`=label(예 "잠실역 5번출구"). 한 출구가 두 묶음에 맞으면 가까운 쪽
  3. **pathways.txt**(없으면 생성): 
     - 승강장↔출구 전부, `pathway_mode=1`, `is_bidirectional=1`, `traversal_time = round(dist_m/exit_speed + exit_fixed_sec)`, `length=dist_m`
     - 같은 묶음 승강장끼리: `traversal_time` = transfers.txt `min_transfer_time`(양방향 중 작은 값), 없으면 `dist/1.0+60`
     - **출구 0개 묶음은 pathway 를 만들지 않음**(승강장이 거리망 직접 연결로 남도록 — 고립 방지)
  4. 리포트 JSON: 묶음 수, 묶음당 자식·출구 수 분포, 출구 0 묶음 목록, 매칭 안 된 출구 수, 모든 pathway 승강장이 출구 ≥1 개와 연결됐는지(불변식)
  5. zip 의 나머지 파일은 바이트 그대로 복사(shapes·trips 등 손대지 않음)
- [ ] `R/runner/test_add_station_entrances.py`(기존 `test_apply_gbis_routes.py` 스타일): 이름 정규화, 500m 경계, transfers 보강, 출구 매칭(label·거리), 출구 0 묶음 pathway 없음, 승강장 간 pathway 시간=min_transfer_time, 결정적 id
- [ ] `R/runner/build_otp_graph.sh`: GBIS 단계 뒤·shape gate 앞에 `--station-entrances <csv>` 옵션 단계 추가(기본 off — 옛 빌드 재현성 유지)
- 검증: `python3 -m pytest R/runner/test_add_station_entrances.py` + 실제 입력 zip 으로 리포트(692역 근처 기대)

### PR-2. 앱: 역이면 역 ID 로 검색 (PR-1 그래프가 있어야 의미, 코드는 병행 가능)
- [ ] **역 판별** 신규 `B/infrastructure/adapter/out/api/otp/OtpStationResolver.java`
  - OTP GraphQL `{ stations { gtfsId lat lon stops { gtfsId lat lon vehicleMode } } }` 를 graphVersion 단위로 캐시(앱 기동 후 첫 사용 시 로드, 실패 시 빈 캐시 = 좌표 그대로)
  - `Optional<String> stationFor(double lat, double lon)`: 자식 승강장(SUBWAY/RAIL) 중 하나와 `station-snap-meters` 안이면 부모 gtfsId
  - N 기본 80m — 구현 시 장소 검색(네이버·카카오) 역 좌표 vs GTFS 승강장 거리를 수도권 주요역 20곳 실측해 확정, 노트에 기록
- [ ] `OtpRoutingAdapter.requestBody`(`:150-`): origin/destination 각각 `stationFor` 결과가 있으면 `{location:{stopLocation:{stopLocationId}}}`, 없으면 지금처럼 coordinate
  - 역 ID 검색이 `routingErrors`/GraphQL 오류면 좌표로 1회 재검색(예산 안)
- [ ] 설정 `B/application/route/config/OtpRoutingProperties.java`: `stationSnapEnabled=true`, `stationSnapMeters=80`
- [ ] 매핑 확인·보정: `OtpPlanConnectionMapper`(origin leg 의 `from` 이 stop/역), `B/application/route/service/LegacyRouteResponseMapper.java:76`(`toLegacy`) — legacy 응답(앱)이 첫·마지막 subPath 를 도보로 전제하는지 확인. 전제하면 대중교통으로 바로 시작할 때 0m 도보 subPath 를 넣음. pathway 도보 leg 의 geometry 가 직선일 수 있음(지도 표시 확인)
- [ ] 출구 안내 충돌 확인: `JourneyGuidanceInfoEnricher.java:158,172`(SubwayEntranceIndex 기반 출구 안내)와 OTP step 의 출입구 표기가 엇갈리지 않게
- [ ] 재탐색(`RemainingRouteSearchService`)도 같은 어댑터 → 열차 안 사용자가 역에서 재탐색하면 역 출발이 됨(의도와 맞는지 경계 리뷰)
- 테스트: `OtpRoutingAdapterHttpTest`(역 근처 좌표 → stopLocation, 먼 좌표 → coordinate, 킬스위치 off → coordinate), `OtpStationResolverTest`(경계 80m, 빈 캐시), 매퍼(첫 leg 대중교통일 때 legacy 형태)
- 검증: `./gradlew test --tests '*OtpRoutingAdapter*' --tests '*OtpStationResolver*' --tests '*OtpPlanConnectionMapper*' --tests '*LegacyRouteResponseMapper*' --tests '*RouteSearchService*' --tests '*RemainingRouteSearch*'`

### PR-3. 그래프 재빌드·배포 (PR-1·PR-2 머지 후, 사용자 확인)
- [ ] `build_otp_graph.sh … --station-entrances src/main/resources/subway/entrances.csv --graph-version national-nojeju-202503-cal20260729_20261130-otp278-shaped-gbis-entrances-g<sha12>`
- [ ] 로컬 OTP(같은 jar)로 코퍼스 전후 비교(아래 검증) → 결과 노트
- [ ] 배포(기존 절차, `implementation_note.md` :2178·:3177·:3835): `/srv/nextroute/otp/<ver>/` 에 graph.obj·coverage-index.json(sha 대조) → VPS `compose.yaml` graphVersion 4곳 + `/root/apps/observability/otp/compose.otp-national.yaml` 1곳 교체(백업) → `docker compose config -q` → `up -d otp`(healthy 대기) → `up -d api worker`
- 되돌리기: compose graphVersion 옛 값 복구 → `up -d otp api worker`. 앱 킬스위치 `ROUTING_OTP_STATION_SNAP_ENABLED=false`

## 4. 경계 리뷰
- 계약: 앱 요청·응답 형식 불변. 역 출발·도착 검색의 경로 구성·도보 시간이 바뀜(의도)
- 상태: pathway 붙은 승강장은 출구로만 접근 → 출구 좌표·매칭이 틀린 역은 접근이 나빠질 수 있음(리포트 불변식 + 코퍼스)
- 실패: 역 판별 실패·역 ID 검색 오류 → 좌표 폴백. 킬스위치
- 그래프: 배포 포크 jar 로만 빌드(ser.id 불일치 = OTP 기동 실패, 과거 함정)
- 환승: 같은 묶음 승강장 간 pathway 로 지금 환승 시간 유지(출구 경유로 나빠지면 안 됨)

## 5. 검증 코퍼스 (PR-3 전후, 같은 시각)
| OD | 기대 |
|---|---|
| 잠실역(8호선 좌표)→강남역(신분당선 좌표) | 360·2000 직통 등장, 2호선 도보 ≤10분, `4319>341` 같은 한 정거장 조합 사라짐 |
| 사당 4→2, 동대문역사문화공원 4→2, 충무로 4→3 | 환승 시간 악화 없음 |
| 서울역→잠실 | 4호선→2호선 등장 여부(미결 해소 기대) |
| 역 아닌 좌표 출발(강남대로 중간 등) | 변화 최소 |
| 기존 6개 OD(강남→홍대, 신촌→강남, 노원→강남, 수원→강남, 부평→서울, 홍천→인제) | 회귀 없음, 고유 5개 유지 |

## 6. L 절차
codex 와 PLAN.md 논의 → 구현은 새 세션(이 문서 기준) → /verify + 리뷰. 발견 사항은 `implementation_note.md` 에 append.
