# 지하철 고정 벌점 제거 + 노선 없는 역 이름 = 모든 노선 역 (L 티어, slug `otp-station-all-lines`)

## Context

사용자 시뮬레이터(2026-10-12 23:15): 현재 위치(강남역 앞) → "서울역" 검색 결과에 지하철 0개. 네이버·ODsay 는 2호선>4호선 35분이 1위.

조사(implementation_note "강남역 부근 → 서울역 지하철 0개"):
1. **OTP build-config `subwayAccessTime` 기본 2분** (`BuildConfig.DEFAULT_SUBWAY_ACCESS_TIME_MINUTES`). `AddTransitEntitiesToGraph` 가 SUBWAY 패턴 정류장에 `streetToStopTime=120` 을 박고, `StreetTransitEntityLink.buildState` 가 거리↔승강장 링크마다 시간 +120s·비용 +120 을 더한다. KTDB 노선별 승강장은 서로 다른 stop 이라 환승 = 승강장→거리→승강장 = +4분. 1회 환승 지하철 = 고정 +8분, 버스는 0. 실측: 사당 2→4 276m 513s(ODsay 3분), 강남 출발 64m 173s
2. **"서울역" 장소 = KTX 역사 좌표** — 지하철 승강장 159~245m, 역 판별 반경 150m 밖 → 좌표 검색. 반경만 늘리면 최근접 1호선 가상 역(VST_0150)에 붙어 4호선 도착 후 출구까지 298m 더 걸어 오히려 악화. 노선 없는 역 이름은 "아무 노선 승강장 도착 0"이어야 네이버와 같다
3. GTFS 버스 낙관(421 OTP 25분 vs ODsay 34분) — 별건, 이번 범위 밖

시험(로컬 그래프, subwayAccessTime 0 만): 19구간 중 서울역 4호선·혜화→낙성대 지하철 1위, 왕십리→여의도 5호선 29분(ODsay 28), 버스가 맞는 강남대로→광화문은 버스 1위 유지, 나머지 동일. 사용자 케이스("서울역")는 지하철 3위 → 2번까지 필요.

사용자 결정(2026-10-13 00:0x): **A+B 한 번에** — 두 가지를 구현하고 그래프 한 번 빌드해 함께 배포.

## 설계

### A. build-config `subwayAccessTime: 0`
- `tools/transit-routing-benchmark/config/otp-national/build-config.json` 에 `"subwayAccessTime": 0`
- 근거: 거리→승강장 시간은 출발·도착 도보에 이미 들어 있고(KTDB 승강장 좌표는 지상 역 중심), 환승은 OSM 보행망 거리로 걷는다. 고정 2분은 지하철만 벌점
- 기각: `OTPFeature.MinimumTransferTimeIsDefinitive`(otp-config, 재빌드 불필요) — 탑승 시각만 당겨지고 도보 구간 8.6분·비용 그대로, 대기 -1분 경로가 나옴(로컬 실측)

### B. 노선 없는 역 이름 → 모든 노선 가상 역 `VSA_`
그래프(`add_station_entrances.py`): 기존 VST(승강장마다)는 그대로 두고, **출구가 있고 승강장이 2개 이상인 역 묶음**마다 추가:
- 부모 `VSA_<group.key>` (location_type 1, 이름 = 묶음 이름, 좌표 = 묶음 승강장 중심)
- 승강장 복제 `VSA_P_<platform stop_id>` (location_type 0, 운행 없음, 승강장 좌표·이름 그대로) — 묶음의 **모든** 승강장(지하철 + 일반철도 `RS_TAGO_N…` 포함)
- 출구 복제 `VSA_X_<osm_id>_<group.key>` (location_type 0, 출구 좌표, stop_code=출구번호)
- 왜 복제인가: GTFS 는 stop 하나에 parent_station 하나 — 실제 승강장은 이미 `VST_<P>` 자식. 복제 정류장은 실제 승강장과 같은 점이라 거리 링크로 몇 m 걸어 연결되고, A(subwayAccessTime 0) 덕에 시간 ≈ 0
- 왜 2개 이상만: 승강장 1개 역은 VST 와 뜻이 같다 → 앱이 VST 로 폴백
- 일반철도 승강장 포함 이유: "서울역" 장소(KTX 역사)는 KTX 승강장(RS_TAGO_NAT010000) 옆 10m → 반경 150m 안에서 판별된다. KTX 로 도착해도 0
- 리포트에 `allLineStations`, `allLinePlatformCopies`, `allLineExitCopies` 추가

앱(`OtpStationResolver`):
- 이름 파싱 `parse(placeName) → Optional<Name(key, hasLine)>`:
  - 정규식 확장: 이름 뒤 괄호 `서울역 (고속철도)`, 노선 꼬리 `…호선|…선|…라인|…철도|GTX-A`
  - hasLine = 노선 꼬리가 있을 때(`잠실역 8호선`, `서울역 공항철도`). 괄호(`(고속철도)`, `(이수)`)는 노선 아님
- 적재: `stations` 응답에서
  - `VSA_` 부모: 자식 중 gtfsId 에 `:VSA_P_` 가 있는 것 = 전체 노선 후보(ALL)
  - 그 밖(VST): 자식 중 RAIL_MODES = 노선 후보(LINE) — 지금과 같음
- `snap`: hasLine → LINE 만(지금 동작). 노선 없음 → ALL 최근접(반경 안) → 없으면 LINE 최근접(승강장 1개 역)
- 같은 역 판정(stationName 비교)·좌표 폴백·킬스위치 그대로

### 바뀌지 않는 것
- 좌표 검색: A 만큼 바뀜(지하철 출발·도착 −2분, 환승 −4분). 의도된 변화
- 노선 있는 이름(네이버 장소 대부분 "잠실역 8호선"): 그대로 VST
- VSA_P·VSA_X 는 노선이 없어 `stopsByBbox` 소비처에서 이미 걸러진다: 단독 추적 주변 정류장(`parseStopsNear` 노선 없는 정류장 제외, #393), 지도 타일(`NationalBusStopTileService` BUS 만). `StopsByIds`·`PatternsAtStop` 은 id 지정이라 무관

## 작업 순서
- [ ] 1. build-config `subwayAccessTime: 0`
- [ ] 2. `add_station_entrances.py` VSA 추가 + 테스트(`test_add_station_entrances.py`): 2승강장 역에 VSA·복제 생성, 1승강장 역은 VSA 없음, 일반철도 승강장 복제 포함·VST 없음, 출구 복제 id, 리포트 수치
- [ ] 3. `OtpStationResolver` 파싱·적재·snap + `OtpRoutingAdapterHttpTest` 계약 테스트: 노선 없는 이름 → VSA, 노선 있는 이름 → VST, VSA 없으면 VST 폴백, "서울역 (고속철도)"·"서울역 공항철도"·"서울역 GTX-A" 파싱, VSA 자식 좌표로 거리 판정
- [ ] 4. 그래프 빌드(`build_otp_graph.sh --profile national --from-shaped-zip --gbis <캐시> --station-entrances … --thresholds … --modes bus,subway --graph-version …`) → 리포트 확인
- [ ] 5. 로컬 OTP + 코퍼스 19구간(운영 vs 새 그래프, 앱 흉내 스크립트 `corp_sw.py` 에 VSA 판별 반영) + ODsay 대조
- [ ] 6. release-manifest·coverage-index·bundleHash, PR, 머지
- [ ] 7. 배포: graph 업로드 → compose graphVersion 5곳 → OTP 재생성(관측 compose 겹침) → 앱 api·worker → 운영 API 검증

## 검증
- 단위: python 테스트, `./gradlew test --tests '*OtpRoutingAdapterHttpTest' --tests '*OtpTransitPatternAdapterTest' --tests '*OtpRoutingAdapterBudgetTest'`
- 코퍼스 목표: 현재 위치→"서울역" 지하철 1위(낮), 서울역 4호선·혜화→낙성대 1위 유지, 강남대로→광화문 버스 1위 유지, 단거리·지방 동일
- 운영: 같은 코퍼스 `/api/route/search`, api 로그 역 적재 수, ERROR 0

## 경계 리뷰
- 계약: 앱 요청·응답 형식 불변. 노선 없는 역 이름 검색의 출발·도착 승강장이 바뀜(의도)
- 상태: 없음(그래프·캐시만)
- 실패/롤백: 역 id 오류 → 좌표 재검색(기존). 그래프 롤백 = compose 백업 + 옛 그래프 디렉터리, 앱 롤백 = jar 백업 또는 `ROUTING_OTP_STATION_SNAP_ENABLED=false`
- 플래그 기본값: 변화 없음
- 순서 독립: 새 앱 + 옛 그래프 = VSA 없음 → VST(지금과 같음). 옛 앱 + 새 그래프 = VSA 자식은 vehicleMode 없어 무시 → VST
