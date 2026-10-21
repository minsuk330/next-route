# 지하철 급행 표시·추적 (L 티어)

기준: origin/main `16fdb444` (2026-10-21). 줄 번호는 이 커밋 기준이다. 앱은 jette-app origin/main `29d5d66`.
조사 근거: 2026-10-21 세션의 웹 조사(코레일·운영사 공지, 뉴스, 위키 교차 확인), 서울 시간표 API 실측, 실시간 스냅숏 실측, OTP GraphQL 실측.
정차역 정답표(사람용): https://claude.ai/artifact/8RhB7TrNqyNjNoZ6kNw9do

## 0. 결정 사항 (사용자 확정 2026-10-21)

| # | 결정 |
|---|---|
| D1 | 급행 열차 추적을 바로 지원한다. 급행 표시 작업과 같이 하고, 앱 업데이트는 한 번에 묶는다 |
| D2 | 경로 검색은 같은 노선의 급행·일반을 둘 다 보여 준다. 사용자 옵션으로 급행을 끌 수 있다 |
| D3 | 공항철도 직통은 경로에 넣지 않는다. 추적도 하지 않는다 |
| D4 | ITX-청춘은 경로 검색에는 남기고 추적은 하지 않는다 |
| D5 | 탑승역·하차역에 서지 않는 급행도 목록에서 숨기지 않고 알아볼 수 있게 표시한다 |
| D6 | OTP 그래프는 수동 보정한다(지금 KTDB GTFS가 최신판) |
| D7 | 급행 표(카탈로그)는 저장소에서 출처·시행일과 함께 관리하고, 시간표 개정 공지가 나오면 갱신한다 |
| D8 | 배지는 빨간 "급행" 하나로 시작한다 |
| D9 | 급행을 탔을 때 'N개 역'·남은 역·다음 역은 그 열차가 서는 역만 센다(지나치는 역 제외) |
| D10 | 급행 끄기 + 결과 0건 → 필터를 풀고 `meta.degraded`에 `express_filter` 를 남긴다. 앱은 "급행 포함 결과" 안내를 보여 준다 |
| D11 | 지하철 보정(PR-G1)과 급행 기능(PR-S1~S5, 앱)을 지금 함께 진행한다. 배포만 G1 이 먼저(시간표 11-30 만료) |

### 기본값으로 둔 것 (SUMMARY 검토 때 확인)
- E2 서지 않는 급행: 목록에는 흐리게 + 사유("강남역 안 서요")로 보이되 고를 수 없다. 서버도 `select` 에서 422 `TRAIN_SKIPS_STATION` 으로 막는다(구버전 앱 대비).
- E3 재탐색도 급행 끄기를 따른다(PR-S5, 같은 릴리스).
- E4 앱 요청에 `X-App-Version` 헤더를 붙인다. 서버는 이번에는 기록만 한다.

## 1. 정차역 정답 (카탈로그 원천)

모든 목록은 하행(또는 노선 기점→종점) 순서. 상행은 역순. `(일부)` 는 열차별 예외.

| id | 노선(lineId) | 열차 판별 | 정차역 |
|---|---|---|---|
| `1001-gyeongin` | 1호선(1001) | 급행 + 경인 구간(구로 서쪽) 열차. trainNo 범위는 PR-S1 도구로 확정 | 용산 노량진 대방 신길 영등포 신도림 구로 개봉 역곡 부천 송내 부평 동암 주안 제물포 동인천 |
| `1001-gyeongbu-a` | 1001 | K19xx 중 B 아님 | 청량리 제기동 신설동 동묘앞 동대문 종로5가 종로3가 종각 시청 서울 남영 용산 노량진 대방 신길 영등포 신도림 구로 가산디지털단지 금천구청(일부) 안양 금정 의왕(일부) 성균관대 수원 병점 오산 서정리 평택 성환 두정 천안 봉명 쌍용 아산 탕정 배방 온양온천 신창 |
| `1001-gyeongbu-b` | 1001 | trainNo 1902·1904(상행), 1945(하행) | 서울 영등포(상행만) 금천구청 안양 군포 의왕 성균관대 수원 병점 오산 서정리 평택 성환 두정 천안 봉명 쌍용 아산 탕정 배방 온양온천 신창 |
| `1001-gyeongwon` | 1001 | K16xx | 동두천 동두천중앙 지행 덕정 덕계 양주 의정부 회룡 도봉산 창동 광운대 → 이후 인천까지 각역 |
| `1004-ansan` | 4호선(1004) | K44xx | 불암산~금정 각역 → 금정 산본 상록수 중앙 초지 안산 정왕 오이도 |
| `1009-express` | 9호선(1009) | 급행 전부 | 김포공항 마곡나루 가양 염창 당산 여의도 노량진 동작 고속터미널 신논현 선정릉 봉은사 종합운동장 석촌 올림픽공원 중앙보훈병원 |
| `1075-bundang` | 수인분당(1075) | 급행 + 오이도 북쪽 | 청량리(일부) 왕십리 서울숲 압구정로데오 강남구청 선정릉 선릉 한티 도곡 구룡 개포동 대모산입구 수서 복정 가천대 태평 모란 야탑 이매 서현 수내 정자 미금 오리 죽전 기흥 망포 수원시청 수원 고색 |
| `1075-suin` | 1075 | 급행 + 오이도~인천 | 오이도 소래포구 인천논현 원인재 연수 인하대 인천 |
| `1063-seoul` | 경의중앙(1063) | K25xx | 문산 금촌 운정 야당 탄현 일산 백마 대곡 행신 디지털미디어시티 가좌 신촌 서울 |
| `1063-gyeongui` | 1063 | K57xx | 문산 금촌 금릉 운정 야당 탄현 일산 백마 대곡 행신 디지털미디어시티 가좌 홍대입구 공덕 용산 → 이후 종점까지 각역 |
| `1063-jungang` | 1063 | K58xx (K5851 은 팔당 종착) | (용산 서쪽 각역) 용산 이촌 옥수 왕십리 청량리 회기 상봉 구리 도농 덕소 도심 [팔당: K5851 종착] 양수 양평 용문 |
| `1067-express` | 경춘(1067) | 급행(directAt 1) | 청량리 회기 상봉 신내 갈매 별내 퇴계원 사릉 평내호평 마석 청평 가평 강촌 남춘천 춘천 |

- 경부 A급행 금천구청·의왕 정차 열차: 상행 1906 1908 1940 1942 1944, 하행 1903 1905 1907 1937 1939 1941 1943 1947 (서울 시간표 API 실측).
- 실시간 trainNo 는 1호선 등 코레일 노선이 숫자만 온다(`1932`). 카탈로그는 숫자로 적고 비교 때 `K` 를 뗀다. 공항철도는 `A1031` 처럼 접두가 붙는다.
- 직통(1065, A1001–A1056)과 ITX-청춘(1067, `trainType=ITX`/directAt 9)은 카탈로그에 넣지 않는다. 분류기가 DIRECT/ITX 로 따로 판정한다.
- 출처: 코레일 보도자료 2026-05-20(경인 특급 폐지), 트윅 2026-02-24(덕계), 코레일 급행운행현황, 9호선 열차이용안내, 나무위키 역 문서(신내 2024-12-20, 갈매·별내 2020-03-02). 카탈로그 json 에 `source`·`effectiveFrom` 으로 남긴다.

## 2. PR 구성 (서버 next-route, fresh main 독립 브랜치)

| PR | 내용 | 의존 |
|---|---|---|
| PR-G1 | OTP GTFS 지하철 보정 + 시간표 기간 연장 + 그래프 재배포 | 없음. S 계열과 동시에 시작, **배포는 먼저**(11-30 만료) |
| PR-S1 | 급행 카탈로그 + 열차 종류 분류기 + **공용 정차 패턴 해석기**(완행 경로·정차역 자르기·종착 범위) + 번호·방향 정규화 계약 (동작 변경 없음) | 없음 |
| PR-S2 | 경로 검색: trainKind 응답, 급행·일반 둘 다, 급행 끄기(공용 필터 정책), 여정 단계 trainKind (V71), geometry `allStops` | S1 |
| PR-S3 | 실시간 표시: 열차 종류 정정, 도착판·열차 고르기·하차역 고르기의 정차 여부·종착 범위. **급행 선택은 플래그 뒤**(`subway.express.tracking.enabled=false`) | S1 |
| PR-S4 | 급행 추적: 바인딩 열차 종류(V72, 기존 행 재분류), 급행 링 체인, 통과역 신호(별도 의미), 급행 구간 시간 | S1, S3 |
| PR-S5 | 재탐색이 급행 끄기를 따름 (V73, snapshot port 계약) | S2 |
| 앱 A1–A3 | jette-app (4절) | S2–S4 계약 |

배포 순서: G1 → S1 → S2·S3 → S4 → (녹화 재생 검증 후) `subway.express.tracking.enabled=true` → S5 → 앱 제출. 서버 변경은 모두 새 칸 추가(nullable)라 구버전 앱과 함께 돈다. S3 가 먼저 나가도 플래그가 꺼져 있으면 급행은 지금처럼(추적 불가 표시 없이) 선택만 막힌다 — 아래 S3 참조.

## 3. 서버 상세

### PR-G1 OTP GTFS 보정 (tools/transit-routing-benchmark)
현재 파이프라인: `runner/build_otp_graph.sh --profile national` → zip→zip 단계(pfaedle 형상 → 2b GBIS → 2d 버스시간 `calibrate_bus_runtimes.py` → 2c 가상역) → gate → `apply_route_colors.py` → OTP 빌드(맥 로컬, Temurin 25 `-Xmx12g`, jar `data/input/otp-h-only-9dbfb229.jar`) → `verify_release_bundle.py --write`. VPS 는 graph.obj 만 `/srv/nextroute/otp/<graphVersion>/` 에 파일 마운트(이미지 `local/nextroute-otp:20260920-h-only-9dbfb229b868` 그대로).
운영 zip `data/input/national-nojeju-202503-shaped-gbis-busrt-entrances.gtfs.zip`(sha256 `0d491355a7b32055…`)은 2026-10-21 에 data/input 으로 복사해 두었다.

- [ ] **시간표 기간**: 그래프 calendar.txt 는 서비스 `B1` 하나, 20260729–20261130. 기존 기간 생성기(`prepare_gtfs.py:117`)는 원본 기간과의 **교집합**을 만들 뿐 연장하지 못한다 → 독립 연산 `extend_calendar`(calendar.txt end_date·calendar_dates 재작성, 이전/이후 범위 리포트, 테스트)로 만든다. 다음 만료일을 manifest 와 운영 문서에 적는다.
- [ ] 신규 `runner/apply_subway_corrections.py` (표준 라이브러리, zip→zip, 바꾸지 않는 파일은 바이트 복사, `--report data/otp-national/subway-corrections-report.json`)
- [ ] 신규 `config/otp-national/subway-corrections.json` (커밋, sha 를 manifest 에 기록)
- [ ] 연산과 대상:
  - `retemplate` 경인 특급 → 급행: 하행 `1:RR_ACC1_S-1-01-4D::03` 8편(`_Ord036,043,045,058,076,080,083,085`), 상행 `4U::05` 7편(`_Ord019,021,032,036,040,045,055`). 기준 급행 패턴 `4D::01`/`4U::03` 의 stop_id·seq·shape_dist·시각 차·shape_id 를 복사하고 첫 출발 시각만 유지(+5분).
  - `insert_stop` 덕계 `RS_ACC1_S-1-1910` 를 덕정 `-1911`·양주 `-1909` 사이에: 패턴 `1D::01`(Ord080), `1D::07`(Ord024,035,054,059,067,074,086), `1U::02`(Ord005,038,051,060,067,073), `1U::07`(Ord057). **방향별로 계산**: 하행은 덕정→덕계→양주, 상행은 양주→덕계→덕정(운영 ZIP `stop_times.txt:12702326` 양주 10:29, `:12702327` 덕정 10:36:30). 앞 역 출발 + 완행 구간 중앙값으로 도착, 정차 30초, 뒤 역 +60초. shape_dist 는 각 방향 shape 위 덕계 위치. 설정의 OTP id(`1:` 접두)와 ZIP 원본 id 변환을 명시.
  - `insert_terminal` K5851: `1:RR_ACC1_S-1-KJ-1D_Ord069`(문산 18:17 출발)에는 **팔당 행이 없다**(도심 `stop_times.txt:12879099` 다음이 양수). 팔당 `RS_ACC1_S-1-1210` 을 도심 다음에 넣고(도착 = 도심 출발 + 완행 중앙값 240초, shape_dist 도심+4,160m) 그 뒤 행을 지우고 종점 pickup_type=1. **실행 조건: 실시간으로 5851 이 팔당행이고 시각이 Ord069 와 맞는 것을 확인했을 때만**. 미확인이면 설정에서 끄고 PR 에 적는다.
  - `rename` 당고개→불암산(`RS_ACC1_S-1-0409`+`VST_`+`VEX_` 5개), 화전→한국항공대(`RS_ACC1_S-1-1268`+`VST_`+`VEX_` 2개). id 는 그대로.
  - `assert_absent` 공항철도 직통 패턴 없음.
- [ ] 멱등: 연산마다 이전/이후 역 목록. 이미 이후면 건너뜀(`alreadyApplied`), 둘 다 아니면 실패. 예상 편수(8/7/8/7/1) 불일치 시 실패. 재실행 바이트 동일.
- [ ] 가드: 패턴 안 추월(calibrate 로직 재사용), 시각·seq 단조, trip 수 불변, 지하철 외 행 바이트 동일.
- [ ] 테스트 `runner/test_apply_subway_corrections.py` (unittest, cwd `tools/transit-routing-benchmark`).
- [ ] `build_otp_graph.sh` 에 `--subway-corrections JSON`(2d 다음 2c 앞, 접미사 `-subwayfix`)과 `--from-zip <path>`(1–2c 건너뜀) 추가. `--skip-match` 만 쓰면 pfaedle zip 을 덮어쓰는 함정이 있다.
- [ ] 검증: 압축 diff 가 stops 7행, trips 15행(shape_id), stop_times 31편뿐. 로컬 OTP `--load` GraphQL: 4D/4U 에 11역 패턴 없음, 1D/1U 급행이 덕계 정차, `stops(name:"불암산")`/`"한국항공대"`, Ord069 팔당 종착, planConnection 대방→동인천 급행·덕계→청량리 급행, `docs/plan/otp-station-entrances/PLAN.md` §5 회귀.
- [ ] 배포: VPS `mkdir /srv/nextroute/otp/<ver>/` 먼저 → graph.obj·coverage-index.json scp(921MB, 사용자 터미널 필요할 수 있음) → sha 대조 → `/root/apps/nextroute/compose.yaml`(graphVersion 4곳)과 `/root/apps/observability/otp/compose.otp-national.yaml`(1곳) `.bak-<ts>-pre-subwayfix` 백업 → 치환 → `config -q` → otp 재생성(약 55초 검색 불가) → api·worker 재생성 → `/api/route/search` 점검. 롤백: .bak 복원 후 같은 순서.

### PR-S1 카탈로그와 분류기 (동작 변경 없음)
- [ ] `src/main/resources/subway/express-catalog.json`
  ```json
  {"version":"2026-10-21","services":[{"id":"1009-express","lineId":"1009","label":"급행",
    "match":{"kind":"EXPRESS"},
    "stops":[{"name":"김포공항"},…],
    "source":["https://www.metro9.co.kr/kor/sub01_04.do"],"effectiveFrom":"2018-12-01"},
   {"id":"1001-gyeongbu-b","lineId":"1001","match":{"trainNos":["1902","1904","1945"]},
    "stops":[{"name":"서울"},{"name":"영등포","directions":["상행"]},{"name":"금천구청"},…]},
   {"id":"1001-gyeongbu-a","lineId":"1001","match":{"trainNoRange":["1901","1999"],"excludeTrainNos":["1902","1904","1945"]},
    "stops":[…,{"name":"금천구청","onlyTrains":["1903","1905","1906","1907","1908","1937","1939","1940","1941","1942","1943","1944","1947"]},…]}]}
  ```
  - `allStopsBetween`(예: 경원 광운대 이남, 4호선 불암산~금정, 경의 용산 동쪽)는 `{"allStops":{"from":"광운대","to":"인천"}}` 항목으로 쓴다.
  - 이름은 역 마스터 정규화명. `서울(지상)` 도 마스터에는 `서울` 하나.
- [ ] 신규 `application/subway/dto/TrainKind` {NORMAL, EXPRESS, DIRECT, ITX, UNKNOWN} — 경로·실시간 공용. `label()`: 일반/급행/직통/ITX-청춘.
- [ ] **번호·방향 정규화 계약**(`application/subway/service/SubwayTrainIdentity`): 저장 identity(실시간 원문 trainNo, 링 조회 `SubwayRingSignalReader:330` 은 원문 완전 일치 유지)와 카탈로그 비교용 번호(`K` 제거·선행 0 제거, 공항철도 `A` 접두 유지)를 분리. 방향은 `SubwayDirectionNormalizer:34,55` 의 9호선·공항철도 반전 규칙을 따른다. 행선지는 역 마스터 정규화명 + 별칭(예: `서울역`/`서울`). fixture: K/숫자/선행 0/A 접두, 양방향, 행선지 별칭. 실시간 원문 번호 표본은 구현 전에 다시 실측한다.
- [ ] **공용 정차 패턴 해석기**(`application/subway/service/SubwayStopPatternResolver`, S2·S3·S4 공용 — S4 에서 S1 로 앞당김):
  - `List<String> allStopPath(lineId, direction, boardId, alightId)` (신규 `SubwayLineOrder.allStopPath`: 두 역을 순서대로 지나는 캐시 패턴 중 사이 역 최다, 없으면 empty)
  - `ResolvedPattern resolve(lineId, direction, trainNo, destination|terminalId, currentStationId)` → 서비스·정차역 목록·**종착역까지로 자른 운행 범위**
  - `trim(allStops, pattern)` → 정차역만, 각 정차역의 `passingBefore`
  - 위치 기반 출발 기록은 `destinationName=null`, `terminalId` 만 있다(`SubwayDepartureLookup.java:63`) → terminalId 도 받아 정규화
- [ ] 신규 `application/subway/service/SubwayExpressCatalog` (`SubwayDoorSideIndex`/`SubwayFastAlightIndex` 처럼 지연 `ClassPathResource` 적재 + `static of(...)`):
  - `Optional<ExpressService> resolve(String lineId, String direction, String trainNo, String destinationName, String currentStationId)`
  - `SubwayStopPattern pattern(String serviceId, String direction, String trainNo)` → `List<String> stopIds`, `boolean stopsAt(String stationId)`
  - 이름→id 해석은 첫 사용 때 `SubwayLineStationIndex.candidates(lineId)`. 해석 실패 서비스는 끄고 경고 로그.
- [ ] 신규 `application/subway/service/SubwayTrainKindClassifier.classify(lineId, trainNo, String trainType, SubwayExpressType directAt)`: ①1065 & A1001–A1056 → DIRECT ②"ITX" 또는 directAt 9 → ITX ③"급행"·"특급" 또는 directAt 1·7 → EXPRESS ④"일반"/0 → NORMAL ⑤ 그 밖 UNKNOWN.
- [ ] `SubwayExpressType` 은 바꾸지 않는다(롤백 안전: `RedisSubwayTrainLogAdapter.java:180,197,242,303,321` 의 `valueOf`). 대신 S3 에서 원시 `directAt` 코드를 별도 호환 필드(`directAtRaw` 문자열)로 위치·출발 기록에 함께 저장해(`SeoulSubwayApiAdapter.java:218` 에서 enum 으로 축약되기 전 값, `RedisSubwayTrainLogAdapter.java:283,308`) 분류기가 9=ITX 를 알 수 있게 한다. 구버전 reader 가 새 필드를 무시하는지 테스트.
- [ ] 1호선 trainNo 범위 확정 도구 `tools/subway-express/fetch_trainnos.py`: 서울 시간표 API(`SearchSTNTimeTableByIDService`, 도착시각 `00:00:00` = 통과, 시발역 예외)로 1001·1004 급행 trainNo 를 서비스별로 뽑아 카탈로그 `match` 를 갱신한다. 키 `SEOUL_SUBWAY_MASTER`. 결과 diff 를 사람이 검토해 커밋.
- [ ] 테스트 `SubwayExpressCatalogTest`: 서비스 12개 파싱(직통·ITX 없음), 모든 정차역이 `otp/subway-patterns/subway-stations.json` 의 해당 노선에서 하나로 해석, 정차 순서가 `routes-with-patterns.json` 완행 패턴의 부분수열, 예외 trainNo 겹침 없음. `SubwayTrainKindClassifierTest`.
- [ ] 삭제: `application/journey/dto/SubwayBindingCandidate.java` (참조 0, `isNormalTrain` 죽은 코드).

### PR-S2 경로 검색 (V71)
- [ ] `src/main/resources/otp/planConnection.graphql:47` `trip { gtfsId }` → `trip { gtfsId pattern { code } }`. `:6-21` 에 `$preferences: PlanPreferencesInput` 추가.
- [ ] `OtpPlanConnectionMapper.toLeg:130-182`: `sourceRouteLongName`(지금 `:154-157` 에서 방향 판정 후 버림), `patternCode` 를 RoutingLeg 에 담는다. 판정은 하지 않는다.
- [ ] `RoutingLeg:27-47`: `sourceRouteLongName`, `patternCode`, `TrainKind trainKind` 추가. 기존 19인자 생성자는 호환 생성자로 유지. `withEnrichment:95-99` 가 새 칸 보존. `withTrainKind(TrainKind)` 추가.
- [ ] 신규 `application/route/routing/RailServiceClassifier`(카탈로그 기반): `TrainKind classify(RoutingLeg)`, `RoutingResult annotate(RoutingResult)`, `Set<String> expressRouteIds()`.
  1. route id/longName 에 "급행" → EXPRESS (9호선 `1:RR_ACC1_S-1-09-2D/U`, 1호선 `-01-4D/U`, `-01-6D/U`)
  2. ITX route id(`1:RR_ACC4_NATZ200_D/U`) → ITX
  3. 정차 패턴형 노선: leg 의 승·하차 사이 정차열을 카탈로그 완행 순서와 비교, 1역 이상 건너뛰면 EXPRESS(구간 기준이라 왕십리→선릉은 NORMAL)
  4. 나머지 SUBWAY/RAIL → NORMAL, 버스·도보 null
  - `expressRouteIds` 는 시작 시 OTP 노선 목록과 대조해 없으면 경고(그래프 재빌드 때 id 변동 대비).
- [ ] 적용 지점 한 곳: `OtpRoutingAdapter.searchOnce:195` → `return classifier.annotate(mapper.map(...))` (본·추가·재탐색 검색 모두). 생성자 `:86-96`.
- [ ] `SubPathResult:5-44` 끝에 `@JsonInclude(NON_NULL) String trainKind` ("NORMAL"/"EXPRESS"/"DIRECT"/"ITX", 철도 구간만). 기존 Integer `trainType`(`:17`, ODsay 기차 등급)은 재사용하지 않는다. **호환 생성자를 두지 않는다**(복사 경로에서 소리 없이 사라지는 것 방지).
  - main 7곳: `LegacyRouteResponseMapper:221`(`:168-176` 철도 분기에서 `leg.trainKind()`), `RouteSearchService:377`, `OdSayApiAdapter:285`(null), `TransferArrivalEnricher:770`, `WalkCoordResolver:95`, `WalkSegmentEnricher:319`, `JourneyRevisionComposer:239` — 뒤 4곳은 `sp.trainKind()` 복사.
  - test 17곳: `RemainingRouteSearchServiceTest:293`, `SubPathFixture:154`, `RouteSearchTravelTimeEstimatorTest:57,69,79`, `RouteSearchBulkheadIT:151`, `RouteSearchRealtimeDeadlineIT:136`, `RouteSearchServiceTest:100`, `TransferArrivalEnricherTest:72,86,176`, `WalkCoordResolverTest:37,54,227`, `WalkSegmentEnricherTest:50,69`, `OdsayRoutingAdapterTest:54`.
- [ ] `ItineraryRanker.fingerprint:59-69`: 키 `mode|name` + (kind 가 NORMAL/null 이 아니면 `|kind`). javadoc `:20-22`. `ItineraryRankerTest:58-64` 기대를 뒤집고 "kind null 과 NORMAL 은 한 경로" 추가.
- [ ] 일반 열차 되찾기: OTP 는 급행이 지배하면 일반 열차를 버린다(김포공항→고속터미널 08:00 실측, 급행 4건만). **총 후보 수(기존 `RouteSearchService:251,259` 의 고유 경로 5개 중단 조건)와 별개로** "급행 leg 이 있는 경로마다 같은 노선 일반 쌍이 있는가"를 검사한다. 없으면 **원래 수단을 유지한 채**(버스 환승 포함) 해당 노선형 급행 route 만 제외하고 1회 더 묻는다(+0.05~0.6s, `supplementaryBudgetMs` 2000 안). 정차 패턴형 급행(수인분당·경의중앙 등)은 OTP 가 노선 단위로만 거를 수 있어 보장하지 않는다 — 한계로 문서화(이 노선들의 급행은 출퇴근 위주라 지배 빈도가 낮음).
- [ ] 급행 끄기: `RouteSearchRequest:13-27` 에 `Boolean excludeExpress`(null=false) + 7인자 호환 생성자. 컨트롤러 `RouteSearchController:40-50` GET 바인딩이라 테스트만. `RoutingQuery:15-29` 에 `Set<String> excludedRouteIds`(`withModes:41-44` 복사). 생성 지점 `RouteSearchService:191-195`, `RemainingRouteSearchService:156-159`. `OtpRoutingAdapter.requestBody:198-218` 비어 있지 않으면 `preferences.transit.filters[].exclude.routes`.
  - 신규 공용 정책 `application/route/routing/ExpressFilterPolicy`(본 검색·추가 검색·재탐색 공용): 노선형 급행은 OTP 필터, 정차 패턴형 급행은 사후 필터(EXPRESS leg 든 경로 제거), DIRECT 는 옵션과 무관하게 항상 제거(안전장치). 사후 필터는 `runOtpSearch:201` 본 결과와 `supplementIfShort:277` 결과에 걸고, 고유 경로 수(`:257,278`)는 필터 뒤 값.
  - D10(0건 해제)은 "일반 대안 확보 시도"를 먼저 한 뒤에만: 사후 필터로 0건 → 노선형 급행 route 제외 + 원래 수단으로 1회 재검색(같은 예산) → 그래도 0건이면 필터를 풀고 `meta.degraded` 에 `express_filter`. 재검색이 실패·시간 초과면 `express_filter_unverified`. 진짜 무경로(필터 전에도 0건)와 구분한다.
- [ ] 여정 단계(V71): `V71__journey_leg_train_kind.sql` `ALTER TABLE journey_leg ADD COLUMN train_kind varchar(16);` — 원천은 revision snapshot 의 subPath.trainKind 이고, 이 칸은 단계 조회용 **비정규화 사본**이다(재탐색 개정본은 새 subPath 에서 다시 채움).
  - `JourneyLegGuidance:16-68` 에 `trainKind`+`withTrainKind`. **모든 `with*`(`:38,43,48,53,58`)와 `withEndFrom:64-68` 이 trainKind 를 보존**하는지 테스트. `JourneyLeg` 필드(`:165-166` 옆), `getGuidance:194-197`, `applyGuidance:199-208`.
  - `JourneyCompiler.emitTransit:188-244`: 지하철 안내 값(`:197-199`)에 `.withTrainKind(sub.trainKind())` (WAIT_SUBWAY·RIDE_SUBWAY).
  - `JourneyLegView:19-40` 에 `String trainKind`(+호환 생성자 패턴 `:48-67`), `JourneyQueryService.toView:134-154`.
  - `RerouteRouteFingerprint:31-32`: kind 가 일반이 아닐 때만 `:EXPRESS` 덧붙임(기존 지문 해시 불변).
- [ ] 여정 경로 모양(geometry) 응답(`JourneyNavigationGeometryService.java:266`): **기존 `stops` 는 유효 정차역 그대로 둔다**(구버전 앱 `navigation-steps.ts:206,297` 이 모든 stops 를 세고 역산함). 새 앱용 별도 필드 `allStops`(완행 전체 역 + 역마다 `trainStops`)를 추가한다.
  - 미바인딩: `stops` = 저장된 계획 정차열(지금과 같음), `allStops` = S1 해석기의 완행 경로에 계획 정차열 표시.
  - 바인딩 후(S4): `stops`·`allStops` 를 묶인 열차의 해석된 패턴으로 교체.
  - geometry 버전 키 = (routeRevision, 바인딩 version, 카탈로그 version). 바뀌면 앱이 다시 요청.
- [ ] ITX: trainKind=ITX 로만 표시(배지 없음, 급행 끄기 대상 아님). trafficType 은 지금처럼 1.
- [ ] 단독 추적(`TrackLegFactory:111`, `TrackJourneyStartService:101`)은 어댑터를 안 거치므로 `classifier.classify(leg)` 를 직접 부른다.
- [ ] 테스트: 신규 `RailServiceClassifierTest`(9·1호선 노선형, 수인분당 `SD-1D::05` 죽전→고색 EXPRESS, 왕십리→선릉 NORMAL, ITX, 공항철도 NORMAL). 수정 `OtpPlanConnectionMapperTest`, `OtpRoutingAdapterHttpTest`/`BudgetTest`, `LegacyRouteResponseMapperTest`, `RouteSearchServiceOtpWiringTest`(제외 id 전파·사후 필터·일반 되찾기 호출/생략·0건 해제), `RouteSearchControllerTest`, Transfer/WalkCoord/WalkSegment(복사 보존), `JourneyCompilerTest`, `JourneyLegGuidanceCopyTest`, `JourneyRevisionComposerTest`, `RedisRouteSearchSnapshotAdapterTest`(trainKind 없는 옛 JSON), `RemainingRouteSearchServiceOtpTest`.
- 범위 밖(후속): 알림 정적 검색 `RouteSearchTravelTimeEstimator:46-50` 은 그대로(급행 기본 포함).

### PR-S3 실시간 표시
- [ ] `SubwayTrainSelectionService.toCandidate:419-425`: `contains("급행")` → 분류기(특급 누락, ITX 를 일반으로, 직통을 급행으로 보는 문제 해결).
- [ ] `departedCandidate:293-296` `express=false, trainType=null` 고정 → `SubwayDepartureLookup.Departure`(`:49-52`)의 express·trainType·destinationName 전달. `:315-321` 을 위해 `SubwayRealtimeArrivalReader.justDepartedTrainNos:76-90` → `List<SubwayRealtimeTrain> justDeparted(...)`.
- [ ] `SubwayTrainCandidate:17`: **기존 `Kind kind`(DEPARTED 판정, 앱 `train-picker-model.ts:118,126`)와 `express` 는 그대로 둔다.** 새 필드는 `TrainKind trainKind, String trainLabel, boolean trackable, Boolean stopsAtBoarding, Boolean stopsAtAlighting, String expressServiceId`. 구버전 앱 응답 fixture 테스트 추가.
  - `candidates():109-128` 에서 탑승역 `waitLeg.startStopId`, 하차역 = 링 체인 마지막 canonicalKey 로 S1 해석기의 정차 여부·**종착 범위**(하차역이 열차 종착 이후면 false) 계산.
  - trackable = NORMAL 이거나 (EXPRESS 이고 해석 성공 이고 탑승·하차역 정차 이고 종착 범위 안 이고 `subway.express.tracking.enabled`).
- [ ] `select():177-229` 재분류 후 422: ITX·DIRECT·해석 실패 급행·플래그 꺼진 급행 → `TRAIN_NOT_TRACKABLE`, 탑승·하차역 미정차 또는 종착 이후 하차 → `TRAIN_SKIPS_STATION`.
- [ ] 원시 `directAt` 보존(`directAtRaw`, S1 참조): `SeoulSubwayApiAdapter.java:218`, `RedisSubwayTrainLogAdapter.java:283,308`. 위치 기록만 남은 ITX 도 표시·선택 거절이 되는지 테스트.
- [ ] 도착판 `SubwayStationArrivalQueryService:172-187,198-209` ITX 매핑. `SubwayStationArrivalsResponse` Departed(`:80-87`)·Train(`:107-121`)에 `trainKind`, `trainLabel`, `Boolean stopsHere`. 통과 열차는 지우지 않고 `stopsHere=false`.
- [ ] 하차역 고르기: `SubwayStationController:33-43` `@RequestParam(required=false) String trainNo` → `GetSubwayStationsAheadUseCase.getStationsAhead(stationId, direction, destination, trainNo)` → `SubwayStationsAheadService:49-105` 가 응답 최상위 `train{kind,label,serviceId,stopsAtBoard}`, `SubwayStationsAheadResponse.Station(:48-50)` 에 `Boolean stopsHere`, `Integer expressStopsAway`. `stopsAway` 는 완행 기준 유지(앱 키 호환). `docs/plan/subway-stations-ahead/HANDOFF.md` 의 "범위 밖: 급행 정차역" 줄 삭제.
- [ ] `SubwaySegmentTimeRecorder:52-75`: trainType≠"일반" 행은 구간 시간 표본에서 제외.
- [ ] 테스트: `SubwayTrainSelectionServiceTest`, `SubwayTrainSelectionIT`(종류·정차 플래그·422 두 종), `SubwayStationArrivalQueryServiceTest`, `SeoulSubwayApiAdapterPositionTest`(9), `SubwayStationsAheadServiceTest`, `SubwayStationControllerTest`.

### PR-S4 급행 추적 (V72)
- [ ] `V72__subway_binding_train_kind.sql`
  ```sql
  ALTER TABLE subway_leg_binding ADD COLUMN train_kind varchar(10), ADD COLUMN train_service_id varchar(40),
    ADD COLUMN catalog_version varchar(20);
  ALTER TABLE subway_leg_binding ADD CONSTRAINT subway_leg_binding_train_kind_check
    CHECK (train_kind IS NULL OR train_kind IN ('NORMAL','EXPRESS','UNKNOWN'));
  ALTER TABLE subway_leg_binding ADD CONSTRAINT subway_leg_binding_express_service_check
    CHECK (train_kind IS DISTINCT FROM 'EXPRESS' OR train_service_id IS NOT NULL);
  ```
  - **기존 행 처리**: 지금도 급행이 바인딩된다(`SubwayTrainSelectionService.java:192,218` 이 급행을 거르지 않음). 그래서 null 을 일반으로 보지 않는다. null = 미분류 → 링 갱신 때 trainNo·실시간으로 한 번 분류해 저장하고, 근거가 없으면 `UNKNOWN`(CHECK 에 포함)으로 두고 계획 체인으로 동작(지금 동작 유지). 기존 급행 바인딩 상태에서 S4 배포·롤백 테스트.
  - `train_service_id` 와 함께 `catalog_version` 을 저장한다. 여정 도중 카탈로그가 바뀌어도 바인딩 때 해석한 서비스를 계속 쓴다.
  - 열차별 예외 정차는 trainNo 로 실행 시점 재계산.
- [ ] `SubwayLegBinding`: 필드 2개, `selected(...):128`·`selectByUser(...):158` 인자, `assignTrain:223`, `inheritTo:200-221` 복사. `ChosenTrain`(`SubwayTrainSelectionService:218`, `SubwayTrainSelectionTxService:35`)에 `kind, serviceId`. `bind():47-53` 감사 payload `{"trainKind","serviceId"}`.
- [ ] 링 체인 주입 지점 하나: `SubwayRingUpdateService.chainOf:230-234`. 신규 `application/journey/service/SubwayRingChainBuilder.build(lineId, direction, plannedNames, ResolvedPattern|null)` — S1 의 `SubwayStopPatternResolver` 를 소비한다:
  1. 계획 이름으로 탑승·하차역 id
  2. `allStopPath` 로 완행 경로(없으면 계획 체인)
  3. 패턴이 있으면 `trim` 으로 정차역만 남기고 순번 재부여(종착 범위로 자름)
  - 부수 효과로 기존 버그 해결: OTP 계획이 급행 trip 이면 계획 체인이 급행 정차역뿐인데, 완행을 고르면 중간역 관측이 `passedChain` 으로 빠져 하차역 PASSED 로 뛰던 문제.
- [ ] `SubwayRingChain.Station:25` 에 `List<String> passingBefore`, `int sequenceOfPassing(String key)`.
- [ ] `SubwayRingSignalReader`·`SubwayRingCalculator` — 통과역은 정차·도착으로 쓰지 않고 진행 근거로만, 앞으로만 민다. **`position(DEPARTED, k-1)` 로 바꾸면 안 된다**: 목표가 k 면 `SubwayRingCalculator.java:382` 의 `seen < target` 에서 `keep()` 으로 무시되고, 목표가 뒤처졌으면 통과역 관측 시각부터 급행 구간 전체 시간을 새로 시작한다(`:399,419`).
  - 새 관측 의미 `PASSING(k, passIndex)` 를 신호에 추가하고, 계산기에서 **목표 전진**(목표를 k 로 당김)과 **시간 앵커 갱신**(통과역까지의 진행 비율로 남은 시간 보정, 구간 시작 시각은 유지)을 분리한다.
  - `fromPosition:380-390`, `positionAhead:118-126`, `passedChain:404-417`(통과역 열차를 구간 밖으로 보지 않음 — 지금은 9호선 급행이 등촌에 있으면 하차역 PASSED), `departedFromPosition:222-223`(`fromKey` = 마지막 통과역)이 이 의미를 쓴다.
  - `fromArrival:279-282` 목표 역의 4·5(전역 진입·도착)는 `passingBefore` 가 있으면 PASSING. 3(전역 출발)은 실측 그대로. **가정 → 녹화로 검증, 그 전까지 `subway.express.tracking.enabled=false`**
  - 테스트: 도착 행 없음, 첫 관측이 통과역, 연속 통과역, 통과 직후 정차역 도착.
- [ ] `SubwayRingApproachReader:90-99` `behind` 는 정차역만 셈.
- [ ] 급행 구간 시간: OTP 급행 GTFS 패턴(9호선 급행 가양→염창 210초 vs 완행 합−정차 310초 — 완행 합 방식은 20~60% 과대).
  - `SubwayLinePatternService.cachedExpressPatterns(lineId, dir)`(키 `line:dir:express`, `load:117-125` 에서 `SubwayRoutePatternAssembler.selectExpressRoutes`). `selectRoutes:108` 의 급행 제외는 유지.
  - `SubwayLinePatternWarmup` 에 카탈로그 노선 추가.
  - `SubwayLineOrder.segmentTimes(line, dir, from, to, ResolvedPattern)`·`arrivalGapSeconds(..., ResolvedPattern)`: 정차열이 정확히 같은 패턴만, 없으면 empty(지어내지 않음). 연결: `SubwayRingUpdateService.java:219`(typicalOf), `SubwayRingAlightingEstimator.java:93,142`.
  - **실측 표본 오용 방지**: `SubwayRingSignalReader.java:271,304` 는 열차 종류 없이 도착역 기준 실측 구간 시간을 읽는다. 관측의 실제 출발역·목표역·정차 패턴을 조회에 넘기고, 표본의 구간(출발역→도착역)이 관측 구간과 정확히 같지 않으면 쓰지 않는다(마지막 통과역 출발 시각과 급행 구간 전체 시간은 다른 구간).
  - `SubwayRoutePatternAssembler:70-75` 반전표에 `01-4`·`01-6` 방향 라벨 검증 추가.
- [ ] 라벨: `SubwayRingState` 에 `String trainLabel`(롤백 안전: 새 필드는 기본 매퍼가 무시), `SubwayGuidance.of():179`, `SubwayRingPushPayloadFactory:93-131`. `SubwayGuidance` 에 `trainKind`(응답 노출) 추가. 링 `lineName` 은 바꾸지 않는다(앱이 배지를 따로 그림).
- [ ] 해석 실패 급행(예: 실측 `1615 인천행 급행`) → trackable=false + 지표 `subway.express.unresolved`.
- [ ] 테스트: 신규 `SubwayRingChainBuilderTest`(정차역 잘라내기, passingBefore, 계획 체인이 급행일 때 완행 확장, 미정차 탑승·하차역 → 빈 체인). 수정 `SubwayRingSignalReaderTest`(통과역 위치, passedChain 오판, 4·5 변환, fromKey), `SubwayRingAlightingEstimatorTest`, `SubwayLineOrderTest`, `SubwayRoutePatternAssemblerTest`, `SubwayLinePatternServiceTest`, `SubwayPatternCoverageTest`, `SubwayBindingSchemaIT`, `SubwayLegBindingSelectionTest`, `SubwayBindingRerouteInheritanceIT`, `SubwayRingPushPayloadFactoryTest`, `SubwayRideScenarioIT`(`SubwayScenarioWorld` 9호선 급행 승차). `tools/subway-replay/record.py` 로 실제 9호선·1호선 급행 탑승 녹화 → `SubwayRingReplayIT`.

### PR-S5 재탐색이 급행 끄기를 따름 (V73)
- [ ] `V73__journey_session_exclude_express.sql` `ALTER TABLE journey_session ADD COLUMN exclude_express boolean NOT NULL DEFAULT false;` — D10 으로 필터가 풀렸어도 **사용자 선호(원래 요청값)를 저장**한다.
- [ ] snapshot port 계약: `RouteSearchSnapshotPort` save/find 가 `excludeExpress` 를 함께 저장·반환하도록 바꾼다. `StoredSnapshot`(`RedisRouteSearchSnapshotAdapter:106`)만 늘리면 `:92` 의 `StoredPath` 반환에서 사라진다. → 여정 시작 트랜잭션(`JourneyStartService:49-67`) → 세션 저장 → `RemainingPathCandidateProvider:263` → `RemainingRouteSearchService.search(…, excludeExpress)`.
- [ ] 재탐색은 결과를 바로 자른다(`RemainingRouteSearchService.java:160,171`) → S2 의 `ExpressFilterPolicy` 를 같은 순서(DIRECT 제외 → 패턴형 사후 필터 → 0건 처리)로 적용한 뒤 자른다.
- [ ] 테스트: 옛 스냅샷 JSON(칸 없음) → false. 재탐색 IT 에 옵션 전파. 필터 해제 후에도 세션 값 유지.

## 4. 앱 (jette-app, 한 번에 제출)
모든 새 필드는 optional·nullable(구서버·테스트 고정 데이터 호환). Swift 도 `String?`/`Bool?`(엄격 enum 이면 스냅샷 디코딩 전체가 깨짐).

- A1 표시·옵션
  - 신규 `src/features/transit/train-kind.ts`(`@/` 별칭 없이): `isExpressBadge`, `kindOf(kind, legacyTrainType)`(`alight-model.ts:93` 정규식 대체), `displayLineName(name, kind)`, `effectiveStops(stops, kind)`. 테스트 `train-kind.test.ts`, `package.json:67-72` 스크립트.
  - 타입: `transit-api.ts:27-93` subPath `trainKind?`, `:197-206`·`:408-425`·`:429-438` 열차 `trainKind?`·`stopsHere?`, `:478-502` StationsAhead `stopsHere?`·`expressStopsAway?`·`train?`, `:504-508` `fetchStationsAhead` 에 `trainNo`, `:238-255` `fetchRouteSearch` 에 `excludeExpress`. `journey/types.ts` **단계(`JourneyLeg`) `trainKind?`**(서버 `JourneyLegView.trainKind` 와 같은 위치 — RouteOption 이 아님), SubwayGuidance `trainKind?`, geometry 의 새 `allStops?`(역마다 `trainStops`). 열차 고르기 후보의 새 필드는 `trainKind`(기존 `kind` 는 DEPARTED 판정용 그대로).
  - 배지(빨강 "급행", 문자열에 섞지 않고 별도 요소): `route-result-card.tsx:104,145`, `route-detail-screen.tsx:102-109`, 실시간 행 `route-result-card.tsx:147-155,206-211`, `navigation-step-list.tsx:89`, `navigation-live-arrivals.tsx:102-107`. `route-results-model.ts:20-25` RouteSegment `express`, `:243` 표시 이름.
  - `route-results-screen.tsx:30-35` `routeIdentity` 에 trainKind(빠지면 급행/일반 쌍이 같은 정체성).
  - 급행 끄기: 신규 `route-preferences.ts`(`expo-sqlite/kv-store` 동기 API), 설정 `account-screen.tsx:102-108` 스위치 + `:64` 초기화, 결과 화면 `RouteResultsControls`(`route-results-screen.tsx:201-227`) "급행 제외 중" 칩, 요청 `:86`·쿼리 키 `:90`. `meta.degraded` 에 `express_filter` 면 "급행 포함 결과" 안내.
  - `client.ts:52-57` `X-App-Version` 헤더.
  - ITX: `route-live-arrivals.ts:121-122`·`navigation-live-arrivals.tsx:52-59` 도착 조회 안 함, `use-subway-trackability.ts:11-19` trackable=false.
- A2 열차 고르기·역 시트·노선 띠·하차역
  - `train-picker-model.ts:4-12` `trainKind?`·`stopsAtBoarding?`·`stopsAtAlighting?`·`trackable?`, `:123-135` "급행" 칩, `candidateBlock` 신설("강남역 안 서요"), 서지 않는 열차 아래로. `train-picker-sheet.tsx:119-135` 흐리게·누를 수 없게·사유, `:73-88` `TRAIN_SKIPS_STATION`/`TRAIN_NOT_TRACKABLE`.
  - `subway-line-diagram.ts:11-22,204-232` `trainKind`·`stopsHere`, `:131-145` 보간을 `toStationId` 위치 기준(급행 다역 건너뜀). `subway-line-strip.tsx:126-192` 배지·`unpickable`·접근성 문구. `subway-station-sheet.tsx:24,59-96,162-164,209`. `use-subway-line-arrivals.ts:27-37`.
  - 하차역: `alight-model.ts:26,30-50,91-95`, `use-alight-stops.ts:28-51,99`, `alight-select-screen.tsx:42-49,70,265-298`(미정차 역 흐리게·"안 서요").
- A3 안내·Live Activity (D9)
  - 개수·다음 역은 실제 열차 기준 정차역만: `navigation-steps.ts:206-220`(effectiveStops), `:292-303` `subwayPassedStopSeq`(호출부 `navigation-screen.tsx:485`), `navigation-step-list.tsx:140-142`, `:116-122` `lineNameOf`, `navigation-screen.tsx:37-55` `namedRoute`, `active-journey-banner.tsx:14-20`, `tracking-progress-card.tsx:32-33`. 실제 열차 = `subwayGuidance.trainKind` > 계획 trainKind. geometry 의 `trainStops` 사용, 바인딩 바뀌면 geometry 재요청.
  - 개수 계산은 새 앱만 `allStops` + `trainStops` 를 쓰고, 없으면(구서버) 기존 `stops` 를 그대로 센다.
  - Swift: `JourneyGuidanceModels.swift` **`GuidanceLeg:59`** 에 `trainKind`(CodingKeys·init·**encode** 모두), geometry `allStops`/`trainStops: Bool?`, `GuidanceSubwayGuidance:137-174` `trainKind`. 미바인딩 여정의 JSON 왕복·배지 테스트. `JourneyGuidanceLiveActivity.swift:876-897` `selectedRouteName`, `:66-96` 페이로드 `trainKind`, `:374-391` 시그니처, `:325-326` stopCount. `JourneyGuidanceEngine.swift:1250` spans. 위젯 `navigation-live-activity.tsx:58-112` `trainKind` prop, 배지는 `:379-382`·`:462-468` 만(펼친 화면 왼쪽 lineLimit 1).
  - 서버 링 푸시와 앱 로컬 갱신이 같은 `trainKind` 를 만들어야 Live Activity 가 깜빡이지 않는다.
- 앱 테스트(`node --experimental-strip-types --test`): `train-picker-model.test.ts:84-86`, `alight-model.test.ts`, `subway-line-diagram.test.ts`, `navigation-steps.test.ts:87-97`, 신규 `train-kind.test.ts`. Swift `fixtures/JourneyGuidanceModelsFixture.swift` 디코딩→인코딩 왕복(`swiftc -parse-as-library`, `docs/plan/bus-stop-pass/PLAN.md:16` 방식).

## 5. 검증
- 서버 PR 마다: `./gradlew compileJava compileTestJava`, 관련 단위 테스트, `./gradlew test --tests '*Test' --continue`, IT 는 로컬 env(`set -a; . env/db.env; . env/auth.env; . env/api.env; set +a; export POSTGRES_HOST=localhost REDIS_HOST=localhost`)로 `--tests '*IT'`.
- PR-S2 운영 확인: 마곡나루→신논현 응답에 `trainKind:"EXPRESS"` + 일반 대안 존재, `excludeExpress=true` 면 일반만, 선릉→수원 수인분당 급행·일반.
- PR-S4 운영 확인: 9호선·1호선 경부 급행 실제 탑승 녹화 재생에서 남은 역·다음 역이 정차역 기준, 통과역에서 PASSED 오판 없음, 하차 예정 오차.
- 앱: 위 테스트 + 실기기에서 급행 끄기·배지·열차 고르기 미정차 표시·Live Activity 라벨.

## 6. 리스크
1. OTP 판정과 카탈로그 불일치: KTDB 가 낡아 정차 패턴형 급행 판정이 틀릴 수 있다. PR-G1 보정 + 카탈로그 무결성 테스트로 줄인다.
2. 열차별 예외 매칭: KTDB trip 에 열차번호가 없다. 경로 검색은 패턴 기준, 추적은 실시간 trainNo 기준으로 나눈다.
3. 통과역 신호 가정(4·5 코드): 녹화로 검증 전까지 `subway.express.tracking.enabled=false`. 이 플래그가 꺼져 있으면 급행 선택 자체가 `TRAIN_NOT_TRACKABLE` 이라 잘못된 추적이 생기지 않는다.
4. 같은 노선 급행·일반 쌍이 노출 상한 10 중 2칸을 써서 버스 대안이 밀릴 수 있다.
5. 구버전 앱: 배지 없음, 서지 않는 열차를 고르면 서버 422. ITX 는 지하철로 보임(현행과 같음).
6. 마이그레이션 번호 V71–V73 는 머지 직전 다시 확인한다(다른 브랜치 선점 가능).
7. route id 는 그래프 재빌드 때 바뀔 수 있다 → 시작 시 `expressRouteIds` 검증.
8. 정차 패턴형 급행(수인분당·경의중앙 등)은 일반 대안을 보장하지 못한다(OTP 필터가 노선 단위).

## 7. codex 검토 반영 (2026-10-21)
막는 문제 6, 고칠 것 10, 권장 2 를 위 본문에 반영했다: 후보 필드명 `trainKind`(기존 `kind` 유지), 통과역 별도 관측 의미, 급행 구간 실측 표본 오용 방지, V72 기존 급행 바인딩 재분류, 공용 정차 패턴 해석기를 S1 로 이동 + 급행 선택 플래그, 덕계 방향별 삽입, geometry 는 `stops` 유지 + `allStops` 추가, 미바인딩 계획 정차열 보존, 종착 범위 검사, 원시 directAt 보존, 일반 대안 확보 조건, D10 해제 조건, S5 snapshot port 계약, 앱 단계 trainKind 위치, 번호·방향 정규화 계약, calendar 독립 연산·K5851 팔당 삽입, 마이그레이션 역할, `with*` 보존 테스트.
