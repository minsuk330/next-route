# PLAN — 노선·방향 기준 남은 역 목록 API (#283, L 티어)

> 실행용. SUMMARY.md 는 이 문서의 파생물. 새 세션은 이 문서 + 루트 `implementation_note.md` 만 읽고 시작할 수 있어야 한다.

## 배경
- 모바일 단독 추적(#265) 하차역 선택 화면(`/ride-alight`, "어디서 내리세요?")이 탄 노선·방향의 남은 역을 세로 노선도로 보여 준다(모바일 `docs/plan/home-ride-tracking/DESIGN.md` 3-1절). 지금은 자리표시 데이터.
- 서버에 지하철 역 순서 데이터가 없다. `subway_station` = 좌표·이름·노선만(`domain/subway/entity/SubwayStation.java`).
- 링 진행률(`application/journey/service/SubwayRingStationResolver.java:55` `chainOf`)은 경로 검색 결과의 역 이름 목록을 빌려 쓴다 → 경로 없는 단독 추적엔 못 쓴다.
- 사용자 결정(2026-10-07):
  - 원천 = **OTP GTFS 패턴** (열린데이터 표·혼합 기각)
  - 순환선 = **한 바퀴, 승차역 직전까지**
  - 분기 + destination 없음 = **분기점까지**

## 계약
```
GET /api/subway/stations/{stationId}/stations-ahead?direction=내선[&destination=성수]
```
- `stationId`: 서울 실시간 역 ID(`/api/nearby/subway-stations`, #277 와 같은 체계)
- `direction`: 실시간 `updnLine` 값(`상행`·`하행`·`내선`·`외선`). #277 응답 `direction` 그대로
- `destination`(선택): 탄 열차 행선지(#277 `destinationName`)

```jsonc
{
  "lineId": "1002",
  "boardStationId": "1002000222",
  "boardStationName": "강남",
  "direction": "내선",
  "destinationApplied": false,      // destination 을 받아 갈래 선택·자르기에 실제로 썼는지
  "endReason": "LOOP",              // TERMINAL | BRANCH | LOOP
  "branches": [],                   // endReason=BRANCH 일 때만: 갈래마다 하나
  "stations": [
    { "stationId": "1002000223",   // 연결 실패면 null
      "stationName": "역삼",        // 연결되면 DB statn_nm, 아니면 OTP 이름(괄호 노선 접미 제거)
      "stopsAway": 1,
      "minutes": 2,                 // 승차역 출발 기준 예상 분. 모르면 null
      "transfers": [ { "lineId": "1075", "lineName": "수인분당선" } ] }
  ]
}
```
- `branches[]` = `{ "destinationName": "마천", "nextStationName": "둔촌동" }`. 앱은 이 값을 `destination` 으로 붙여 다시 부른다.
- 가까운 순. 끝:
  - 종착 = `TERMINAL`
  - 분기점 = `BRANCH`. 승차역이 곧 분기점이면 `stations: []` + `branches`
  - 순환선 한 바퀴 직전 = `LOOP`
- 연결 실패 역도 **빼지 않는다**. 빼면 stopsAway 가 조용히 틀린다.
- 오류(`global/error/code/SubwayErrorCode.java`, #284 에서 생긴 enum 에 추가):
  - 없는 역 → 404 `SUBWAY_STATION_NOT_FOUND`
  - 모르는 방향(값 공간 밖, 또는 그 노선에 그 방향 route 없음) → 400 `SUBWAY_DIRECTION_INVALID`
  - 승차역이 OTP 그래프에 없음(예: 1호선 천안·신창, feed 가 서동탄·병점까지) → 422 `SUBWAY_STATION_NOT_ON_ROUTE`
  - OTP 호출 실패 + 캐시 없음 → 503 `SUBWAY_ROUTE_UNAVAILABLE`. 캐시를 새로 고치다 실패하면 **낡은 캐시를 그대로 준다**.
  - 200 + 빈 목록으로 실패를 감추지 않는다. 앱이 "남은 역 없음(종착)"과 구분하지 못한다.

## 실측 근거 (2026-10-07, 운영 OTP `127.0.0.1:18080` 읽기 전용 GraphQL)
- feed 는 **노선×방향(×지선)마다 route 를 따로** 둔다. fixture `src/test/resources/otp/gtfs-rail-routes.json`(61노선):
  - `1:RR_ACC1_S-1-02-1I` `서울2호선(본선)<내선>` / `-1O` 외선 / `-2I·2O` 성수지선 / `-3I·3O` 신정지선
  - `1:RR_ACC1_S-1-05-1U/1D` `서울5호선<상행/하행>`
  - `1:RR_ACC1_S-1-SB-1U/1D` `신분당선<상행/하행>`
  - `1:RR_ACC1_S-1-KJ-1U/1D/2U/2D` `경의중앙선(용산|서울역)<상행/하행>`
  - `1:RR_ACC1_S-1-06-00` `서울6호선` — **방향 없음**(응암 순환)
- 방향 라벨 = `SubwayDirectionNormalizer.labelFrom(longName)`(`application/subway/service/SubwayDirectionNormalizer.java:131`), 커버리지 테스트 `GtfsRailDirectionCoverageTest`.
- 🔴 2호선 본선 내선 패턴 25개, **패턴 하나가 여러 바퀴**(정차 87~517개, 예 `성수→…→성수` 431정차).
- 🔴 5호선 하행 패턴 15개:
  - 주 패턴: 방화→마천(trips 95), 방화→하남검단산(trips 91)
  - 그 밖은 회차·구간 패턴(방화→군자, 방화→화곡, 방이→마천 등)
- 신분당선 하행 패턴 3개: 신사→광교(159), 신사→정자(4), 정자→광교(1).
- stoptimes 있음: `scheduledArrival/scheduledDeparture` = 서비스일 자정 기준 초(예 신도림 19800 → 문래 19920/19950).
- 🔴 `SubwayLineIdMapper.resolveOdsay`(`application/subway/service/SubwayLineIdMapper.java:49`)는 숫자만 뽑아 `인천2호선`도 1002 로 읽는다 → OTP route 선택에 쓰지 말 것. **shortName 명시표**를 쓴다.
- 🔴 **경의중앙선 서울역 route 는 방향 라벨이 실시간과 반대다.**
  - 실시간: 가좌 `상행`=문산행 18건, 서울 `상행`=문산 6건
  - OTP: `KJ-2<하행>` = 서울→문산, `KJ-1(용산)<하행>` = 문산→용문(실시간과 일치)
  - 리뷰 실측 + 본 세션 재확인
- 🔴 **2호선 실시간 행선지는 거의 다 `성수`**다. 강남 내선 성수 34건/신도림 3건, 외선 성수 28건. 순환선에서 destination 으로 자르면 바퀴가 잘린다.
- 🔴 급행이 섞여 있다.
  - 별도 route: `서울9호선(급행)`, `서울1호선(용산-동인천급행)`
  - 일반 route 안 통과 패턴: `1-1D` 동두천→인천 52정차 vs 61정차. 52정차는 보산·덕계·녹양·가능·망월사·도봉·방학·녹천·월계를 건너뛴다.
  - 걸러내지 않으면 의정부 하행 공통 접두사 = [회룡] 1역
- 🔴 **50m 좌표 매칭 실패율 10~26%.** 실패/정차:
  - 1-1D 7/65(의정부 140m), 5호선 5/56(종로3가 291m), 6호선 5/39(합정 131m)
  - 9호선 10/38(한성백제 185m), 경의중앙 10/53, 공항철도 4/14
  - 공항철도 인천공항2터미널은 최근접이 공항화물청사(870m) = **오매칭**
- 🔴 이름 기준 환승은 거짓 환승을 만든다.
  - 양평(5호선) ↔ 양평(경의중앙) 55km
  - `신촌(경의중앙선)` ↔ 신촌(2호선) ~670m
- 6호선: OTP 는 왕복 패턴 하나(`봉화산→…→봉화산` 71정차 62trips). 실시간 상행 행선지 = 전부 `응암순환(상선)`(공덕·합정·봉화산 각 23), 하행 = 봉화산·신내. 역촌(순환 구간 안)도 하행=봉화산(16).
- feed shortName: `우이신설경전철`(우이신설선 아님). `신림선`(1094) 있음. 경강선 route 0개, GTX-A 는 `XA-2` 운정중앙-서울만.
- tripCount 는 route 끼리 비교 불가: 2호선 본선 trip 은 여러 바퀴라 trips 2~5, 지선은 110~113.
- 응답 크기·지연: 2호선 내선 318KB(stoptimes 제외), 5호선 하행 543KB(전 trip stoptimes 포함), 60~70ms.
- 운영 `subway_segment`(270행, seq·travel_time)가 있다. 원천 아님, minutes 교차검증용으로만.
- 리뷰 질의 원본·결과: 스크래치 `opus-review/`(세션 한정, 필요하면 다시 채집).
- 🔴 OTP POST 는 `SimpleClientHttpRequestFactory` 로만 보낸다. `JdkClientHttpRequestFactory` 는 h2c 업그레이드에서 멈춘다(`infrastructure/adapter/out/api/otp/OtpRoutingAdapter.java:38-44`, `:131` `sendOnce`). 설정은 `OtpRoutingProperties` 재사용.

## 설계

> **PR-1 구현 중 바뀐 것(2026-10-07, 실측 근거)** — 아래 본문보다 이 박스가 우선한다.
> - 포트는 가공 없는 원천만: `SubwayRoutePatternPort { subwayRoutes(); patterns(routeGtfsId); }`. 노선 선택·반전·6호선 분할은 순수 `SubwayRoutePatternAssembler`, 캐시·낡은 값 제공은 `SubwayLinePatternService.patterns(lineId, direction)` → `List<DirectedRailPattern>`. 커버리지 테스트가 운영 코드를 그대로 돌리게 하려고 나눴다.
> - **반전 노선이 넷**: 경의중앙 서울역(KJ-2), 2호선 성수지선(02-2, 내선↔외선), 9호선(09-*), 공항철도(AP-1). 실시간 행선지 전수 대조로 찾았다.
> - 매칭: 같은 이름이면 **5km** 안(500m 아님) — OTP 1호선 도봉·도봉산 좌표가 서로 바뀌어 있고(1.1km), 인천공항2터미널은 역 마스터와 ~3km. 이름이 다르면 150m(개명 7건 목록).
> - 경강선·GTX-A 도 feed 에 노선이 있다(미지원 목록 비어 있음). 4호선 진접 연장 3역은 역 마스터에 없다(정적 일배치 off).
> - "통과" 정차(pickup·dropoff 둘 다 NONE)는 실측 0건. 한쪽 NONE 은 패턴 양 끝(각 442건)이라 남긴다.
> - 2호선 승차역 재등장 컷은 **순환(내선·외선)일 때만**. 6호선 하행은 응암이 두 번 나오는 게 정상이다.


### 1. OTP 포트 (PR-1)
`application/subway/port/out/SubwayLinePatternPort.java`
```java
public interface SubwayLinePatternPort {
    /**
     * lineId·실시간 방향 라벨의 모든 route(본선+지선, 급행 route 제외, 방향 반전 반영) 패턴.
     * route 없으면 빈 목록. 호출 실패는 예외.
     */
    List<LinePattern> patterns(String lineId, String direction);

    record LinePattern(String routeGtfsId, String routeLongName, int tripCount, List<PatternStop> stops) {}
    record PatternStop(String name, double lat, double lon,
                       Integer arrivalSec, Integer departureSec) {} // 대표 trip 1개 시각, 없으면 null
}
```
`infrastructure/adapter/out/api/otp/OtpSubwayLinePatternAdapter.java`
- 질의 ① `routes(transportModes:[SUBWAY]) { gtfsId shortName longName }`, 24h 캐시.
  - shortName 명시표로 lineId 매칭
  - longName 에 `급행` 이 있으면 제외
  - 방향 = `labelFrom(longName)` 에 **route별 반전표**를 적용한 값(`KJ-2` 서울역 route: 상행↔하행)이 요청 direction 과 같은 route 들
- 질의 ② route 마다 `route(id){ patterns{ stops{ name lat lon } trips{ stoptimes{ scheduledArrival scheduledDeparture pickupType dropoffType } } } }`.
  - 대표 trip = 그 패턴의 첫 trip
  - pickupType·dropoffType 둘 다 NONE 인 정차는 뺀다
  - 크기는 실측상 부담 없음(≤543KB, 60~70ms)
- 결과 캐시: (lineId, direction) TTL 24h, `ConcurrentHashMap` + 적재 시각. 새로 고치다 실패하면 이전 값을 유지한다. 신규 라이브러리 금지.
- shortName 명시표 (`OtpSubwayRouteNames`, 상수 Map; 확정은 `src/test/resources/otp/gtfs-rail-routes.json` 대조):
  - `서울1호선`…`서울9호선` → `1001`…`1009`
  - `경의중앙선` 1063, `공항철도` 1065, `경춘선` 1067, `수인분당선` 1075, `신분당선` 1077
  - `우이신설경전철` 1092, `서해선` 1093, `신림선` 1094
  - 경강선(1081, route 0개)·GTX-A(1032, 부분 구간만)는 커버리지 미달 목록
- 6호선(`1:RR_ACC1_S-1-06-00`, 방향 없음): 패턴을 **응암 첫 등장 지점에서 둘로 나눈다**.
  - 응암 첫 등장 이전 + 응암 포함 = `상행`
  - 응암부터 끝 = `하행`
  - 단일 route 라 반전표 대신 이 분할 규칙을 어댑터가 적용한다.

### 2. OTP 정차 → statn_id (PR-1)
`application/subway/service/SubwayPatternStationMatcher.java`
- `subway_station` 노선 행을 `SubwayStationRepository.findByLineId`(`domain/subway/repository/SubwayStationRepository.java:20`)로 읽어 메모리에 둔다(노선당 수십 행).
- 매칭 규칙(순서대로):
  1. 같은 노선 안에서 정규화 이름(괄호 노선 접미 제거 + `DestinationNormalizer.normalize`)이 같고 ≤500m 면 채택
  2. 이름이 다르면(`응암`↔`응암순환(상선)` 등) 좌표 최근접 ≤150m 로 대체
  3. 둘 다 실패면 `stationId=null`, 이름 = OTP 이름(괄호 노선 접미 제거). `구의(광진구청)` 처럼 노선 아닌 괄호는 남긴다.
- 좌표만 쓰는 50m 규칙(`OtpSeoulIdentityResolver`)은 쓰지 않는다. 실패율 10~26%에 오매칭(인천공항2터미널→공항화물청사)까지 난다.
- #280 의 `findNearbyOnLine` 에 의존하지 않는다(독립 PR).

### 3. 순수 계산기 (PR-2)
`application/subway/service/StationsAheadCalculator.java` (I/O 없음)
입력: 매칭된 패턴들(정차마다 statn_id|null, name, arr, dep), route 별 tripCount, boardStationId, destination(nullable), route 가 순환인지(`<내선>/<외선>` 라벨).
1. 승차역을 포함한 패턴마다 tail = 승차역 **첫 등장 다음**부터 **승차역이 다시 나오기 직전**까지(다회전·순환 처리). 정차 키 = statn_id, null 이면 정규화 이름.
2. 통과·구간 패턴 제거: 같은 route 의 다른 tail 의 **부분수열**(접두사 포함)인 tail 을 뺀다. 급행 통과 패턴과 회차 패턴이 함께 빠진다.
3. destination 있음:
   - **순환 route(2호선)**: destination 은 route 선택에만 쓰고 **자르지 않는다**. 지선 역이나 지선 별칭(`성수지선`, `신도림지선` — 실측 행선지 값)이면 지선 route, 그 밖(`성수` 포함)은 본선 한 바퀴.
   - 그 밖: destination(`DestinationNormalizer.normalize`, `application/subway/service/DestinationNormalizer.java:24`)이 들어 있는 tail 중 같은 route 안 tripCount 최다 → destination(포함)까지. `destinationApplied=true`.
   - 들어 있는 tail 이 없으면 4 로 떨어진다(`destinationApplied=false`).
4. destination 없음: 남은 tail 들의 **최장 공통 접두사**.
   - trip 가중: 같은 route 안 tripCount 합 5% 미만 소수 패턴이 접두사를 깎으면 무시한다.
   - 공통 접두사가 어떤 tail 보다 짧으면 `endReason=BRANCH`. `branches` = 갈라진 tail 마다 {끝 역 이름, 분기 다음 역}.
   - 승차역이 분기점이면 `stations=[]` + `branches`(신도림 내선, 성수 외선, 1호선 구로 하행, 5호선 강동 하행).
   - 순환 route 의 한 바퀴면 `LOOP`, 그 밖 끝이면 `TERMINAL`.
5. minutes:
   - 대표 = 결과 구간을 포함한 tail 중 같은 route 안 tripCount 최다 패턴의 대표 trip
   - 승차역은 tail 을 만든 **같은 occurrence 인덱스**의 dep 를 기준으로 잡는다(다회전 패턴)
   - `round((arr_i − dep_board) / 60)`. OTP scheduled 는 서비스일 기준 초라 86400 을 넘어도 음수는 안 된다 → 음수면 null(assert 성격 로그)
   - 시각 없으면 null
6. stopsAway = 1부터. tripCount 는 route 끼리 비교하지 않는다.

### 4. 환승 (PR-2)
- 같은 정규화 이름 **그리고** 좌표 ≤300m 인 다른 lineId 의 `subway_station` 행 → `{lineId, lineName=search_line_name}`. 자기 lineId 는 제외한다.
- 반례 테스트: 양평(5호선↔경의중앙 55km), 신촌(경의중앙↔2호선 ~670m) → 환승 아님.
- 전체 696행(운영 실측)을 메모리 1회 적재(`SubwayRingStationResolver.idsOf` 방식).

### 5. 서비스·컨트롤러 (PR-2)
- `application/arrival/port/in/GetSubwayStationsAheadUseCase.java` → `SubwayStationsAheadResponse getStationsAhead(String stationId, String direction, String destination)`
- `application/arrival/dto/SubwayStationsAheadResponse.java` (record + 중첩 `Station`, `Transfer`, `Branch`, enum `EndReason`)
- `application/subway/service/SubwayStationsAheadService.java`
  - `findByStationId` → 404
  - direction 값 검증 → 400
  - port → 빈 목록이면 400 (그 노선에 그 방향 없음)
  - 승차역이 어느 패턴에도 없음 → 422
  - matcher → calculator → 환승 붙이기
  - OTP 예외 → 낡은 캐시 있으면 그 값, 없으면 503
- `infrastructure/adapter/in/api/subway/SubwayStationController.java`(#284)에 `@GetMapping("/{stationId}/stations-ahead")` 추가.
- `SubwayErrorCode` 에 `SUBWAY_DIRECTION_INVALID`(400), `SUBWAY_STATION_NOT_ON_ROUTE`(422), `SUBWAY_ROUTE_UNAVAILABLE`(503) 추가.
- `trainType` 파라미터는 받지 않는다. 받을 자리(선택 파라미터)만 문서에 예약한다.

## 작업 순서

### PR-1 `feat/subway-stations-ahead-pr1` (fresh main) — 측정·포트 ✅ 구현 완료(2026-10-07)
- [x] 1. 운영 OTP 에서 fixture 채집(읽기 전용, ssh `vps` → `curl 127.0.0.1:18080/otp/routers/default/index/graphql`; 로컬 `localhost:18080` 은 운영 터널이다):
  - 전 SUBWAY route 목록
  - 대표 노선 패턴: 2호선 본선 내선, 5호선 하행, 신분당선 하행, 경의중앙선, 1호선, 6호선
  - 저장 위치: `src/test/resources/otp/subway-patterns/*.json`
- [x] 2. 응답 크기·지연 측정(노선별 바이트·ms) → 노트 `### 검증`. 크면 질의 축소안 결정.
- [x] 3. `SubwayLinePatternPort` + `OtpSubwayLinePatternAdapter` + 명시표. fixture 파싱 단위 테스트(MockRestServiceServer 또는 문자열 주입).
- [x] 4. `SubwayPatternStationMatcher` + 테스트.
- [x] 5. **커버리지 테스트** `SubwayPatternCoverageTest`(GtfsRailDirectionCoverageTest 방식):
  - 19노선 × 방향마다: route 존재, 패턴 존재, 정차→statn_id 매핑률
  - **실시간 대조**: 운영 `subway_arrival_event` 의 (line_id, direction, destination_name) 고유값을 fixture 로 저장한다(SELECT DISTINCT, 읽기 전용). 각 방향의 행선지가 그 방향 route tail 안에 있는지 검증한다. 방향 반전(KJ-2)·6호선 분할이 여기서 잡혀야 한다.
  - 운영 `subway_station` 노선 행은 fixture 로 저장(`subway-stations.json`, 696행 SELECT 결과)
  - 매칭 반례: 인천공항2터미널 오매칭 금지, 의정부·종로3가·합정·한성백제 이름 매칭 성공
  - 기준선 미달 노선(경강선·GTX-A 등)을 목록으로 박는다. 이 목록이 PR-2 범위가 된다.
- [x] 6. 노트 기록, PR. 이 PR 은 API 를 노출하지 않는다(빈 등록만).
- 검증: `./gradlew test --tests '*OtpSubwayLinePattern*' --tests '*SubwayPatternStationMatcher*' --tests '*SubwayPatternCoverage*'`

### PR-2 `feat/subway-stations-ahead-api` (fresh main, **#284 와 PR-1 머지 후**) ✅ 구현 완료(2026-10-07, PR 은 #284·#286 머지 후 rebase 해서 연다)
- [x] 7. `StationsAheadCalculator` 테스트 먼저(입력은 PR-1 fixture):
  - 순환 한 바퀴 컷(강남 내선 → 역삼…교대, `LOOP`)
  - 다회전 패턴 + minutes 가 같은 occurrence 기준
  - 2호선 destination=`성수` 여도 자르지 않음
  - 신도림 내선: destination 없음 → `stations=[]` + `branches`(본선/신정지선), `까치산` → 신정지선, `성수` → 본선 한 바퀴
  - 분기 공통 접두사(5호선 방화 하행 → 강동까지 `BRANCH`), 강동 하행 승차 → 빈 목록 + branches
  - 1호선 구로 하행(분기점 승차), 의정부 하행(통과 패턴 제거 → 회룡에서 멈추지 않음)
  - 회차 패턴 제거, destination 갈래(`마천` / `하남검단산`), destination 이 tail 에 없으면 공통 접두사로
  - 경의중앙선 가좌 하행(서울행): 반전된 KJ-2 와 KJ-1 이 섞여도 문산 쪽 역 안 섞임
  - 9호선 일반(급행 route 무시)
  - 6호선 공덕 상행 → …응암, 역촌 하행 → …봉화산
  - 연결 실패 역 유지
- [x] 8. 계산기 구현.
- [x] 9. 환승 + 서비스 + 에러코드 + 컨트롤러, 테스트(404/400/503/200 JSON).
- [x] 10. HANDOFF.md(모바일: `use-alight-stops.ts` `loadAlightStops` 교체, `AlightStop` 대응표, `frequent` 는 앱이 붙임). 노트, PR.
- 검증: `./gradlew test --tests '*StationsAhead*' --tests '*SubwayStationController*'`

## 결정 기록 (Opus 5.5 리뷰, 2026-10-07)
codex 대신 Opus 5.5 서브에이전트가 운영 OTP·DB 읽기 전용 실측으로 검토했다. 반영 결과:
1. 6호선 → 응암 첫 등장 지점 분할(제외 안 함)
2. OTP 실패 → 503 + 낡은 캐시 우선(200+빈 목록 기각: 종착과 구분 불가)
3. route 합치기 → 라벨 + route별 반전표(KJ-2) + 실시간 행선지 대조 테스트
4. 급행 → route(longName "급행") + 패턴(부분수열) 두 단계 필터. trainType 은 범위 밖
5. 대표 trip 시각 근사 → 허용. 같은 occurrence 기준, +86400 보정 대신 음수=null

## 미결
- 지선 별칭 목록(`성수지선`, `신도림지선` 외)은 PR-1 실시간 대조 fixture 로 확정
- `subway_segment` 로 minutes 교차검증 할지(선택)

## 배포
마이그레이션 없음(OTP 런타임 조회 + 메모리 캐시). api 이미지 교체. 플래그 없음(새 경로).
