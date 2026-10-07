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
  "direction": "내선",
  "stations": [
    { "stationId": "1002000223",   // 연결 실패면 null
      "stationName": "역삼",
      "stopsAway": 1,
      "minutes": 2,                 // 승차역 출발 기준 예상 분. 모르면 null
      "transfers": [ { "lineId": "1075", "lineName": "수인분당선" } ] }
  ]
}
```
- 가까운 순. 끝 = 종착 / 분기점 / 순환선 한 바퀴 직전.
- 연결 실패 역도 **빼지 않는다**. 빼면 stopsAway 가 조용히 틀린다.
- 오류:
  - 없는 역 → 404 `SUBWAY_STATION_NOT_FOUND`(#284 `global/error/code/SubwayErrorCode.java` 재사용)
  - 모르는 방향(값 공간 밖, 또는 그 노선에 그 방향 route 없음) → 400 `SUBWAY_DIRECTION_INVALID`
  - OTP 호출 실패 → 503 `SUBWAY_ROUTE_UNAVAILABLE` (미결 2 확정 전 잠정)

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
- 🔴 OTP POST 는 `SimpleClientHttpRequestFactory` 로만 보낸다. `JdkClientHttpRequestFactory` 는 h2c 업그레이드에서 멈춘다(`infrastructure/adapter/out/api/otp/OtpRoutingAdapter.java:38-44`, `:131` `sendOnce`). 설정은 `OtpRoutingProperties` 재사용.

## 설계

### 1. OTP 포트 (PR-1)
`application/subway/port/out/SubwayLinePatternPort.java`
```java
public interface SubwayLinePatternPort {
    /** lineId·방향 라벨의 모든 route(본선+지선) 패턴. route 없으면 빈 목록. 호출 실패는 예외. */
    List<LinePattern> patterns(String lineId, String direction);

    record LinePattern(String routeGtfsId, int tripCount, List<PatternStop> stops) {}
    record PatternStop(String name, double lat, double lon,
                       Integer arrivalSec, Integer departureSec) {} // 대표 trip 1개 시각, 없으면 null
}
```
`infrastructure/adapter/out/api/otp/OtpSubwayLinePatternAdapter.java`
- 질의 ① `routes(transportModes:[SUBWAY]) { gtfsId shortName longName }` → 명시표로 lineId 매칭 + `labelFrom(longName).equals(direction)`. route 목록은 길게 캐시(24h).
- 질의 ② route 마다 `route(id){ patterns{ stops{ name lat lon } trips{ stoptimes{ scheduledArrival scheduledDeparture } } } }`.
  - 대표 trip = 첫 trip 의 stoptimes.
  - 응답 크기가 크면(2호선) `trips` 대신 다른 필드로 줄인다 → PR-1 측정에서 결정.
- 결과 캐시: (lineId, direction) TTL 24h, `ConcurrentHashMap` + 적재 시각. 신규 라이브러리 금지.
- shortName 명시표 (`OtpSubwayRouteNames`, 상수 Map): `서울1호선`…`서울9호선` → `1001`…`1009`, `경의중앙선` 1063, `공항철도` 1065, `경춘선` 1067, `수인분당선` 1075, `신분당선` 1077, `경강선` 1081, `우이신설선` 1092, `서해선` 1093, `GTX-A` 1032.
  - 실제 feed shortName 은 PR-1 측정으로 확정한다. `src/test/resources/otp/gtfs-rail-routes.json` 에 다 있다.

### 2. OTP 정차 → statn_id (PR-1)
`application/subway/service/SubwayPatternStationMatcher.java`
- `subway_station` 노선 행을 `SubwayStationRepository.findByLineId`(`domain/subway/repository/SubwayStationRepository.java:20`)로 읽어 메모리에 둔다(노선당 수십 행). 좌표 haversine 최근접 ≤ 50m.
  - 근거: `OtpSeoulIdentityResolver.SUBWAY_RADIUS_M` 주석, OTP 와 역 좌표 실측 ~3m.
- #280 의 `findNearbyOnLine` 에 의존하지 않는다(독립 PR).
- 실패 → `stationId=null`, 이름 = OTP 이름에서 괄호 노선 접미 제거(`강남(2호선)` → `강남`). `구의(광진구청)` 처럼 노선 아닌 괄호는 남긴다. 실패 판별은 매핑률 측정 후.

### 3. 순수 계산기 (PR-2)
`application/subway/service/StationsAheadCalculator.java` (I/O 없음, static 또는 무상태 빈)
입력: 매칭된 패턴들(정차마다 statn_id|null, name, arr, dep, tripCount), boardStationId, destination(nullable)
1. 승차역을 포함한 패턴마다 tail = 승차역 **첫 등장 다음**부터 **승차역이 다시 나오기 직전**까지.
   - 다회전 패턴과 순환선 한 바퀴가 이 규칙 하나로 처리된다.
2. destination 있음:
   - tail 안에 destination(`DestinationNormalizer.normalize`, `application/subway/service/DestinationNormalizer.java:24`)이 있는 tail 중 tripCount 최다 → destination(포함)까지.
   - 하나도 없으면 3 으로 떨어진다.
3. destination 없음:
   - 다른 tail 의 접두사인 tail 을 제거한다(회차·구간 패턴).
   - 남은 tail 들의 **최장 공통 접두사**를 쓴다(statn_id 기준, null 이면 이름 기준) = 분기점까지.
   - 순환선은 남은 tail 이 같은 한 바퀴라 그대로 나온다.
4. minutes:
   - 대표 패턴 = 결과 구간을 접두사로 가진 tail 중 tripCount 최다.
   - `round((arr_i − dep_board) / 60)`. 차이가 음수면 +86400(자정 넘김). 시각 없으면 null.
5. stopsAway = 1부터.

### 4. 환승 (PR-2)
- 같은 `DestinationNormalizer.normalize(statn_nm)` 을 가진 다른 lineId 의 `subway_station` 행 → `{lineId, lineName=search_line_name}`.
- 전체 696행(운영 실측)을 메모리 1회 적재(`SubwayRingStationResolver.idsOf` 방식).

### 5. 서비스·컨트롤러 (PR-2)
- `application/arrival/port/in/GetSubwayStationsAheadUseCase.java` → `SubwayStationsAheadResponse getStationsAhead(String stationId, String direction, String destination)`
- `application/arrival/dto/SubwayStationsAheadResponse.java` (record + 중첩 `Station`, `Transfer`)
- `application/subway/service/SubwayStationsAheadService.java`
  - `findByStationId` → 404
  - direction 값 검증 → 400
  - port → 빈 목록이면 400 (그 노선에 그 방향 없음)
  - matcher → calculator → 환승 붙이기
- `infrastructure/adapter/in/api/subway/SubwayStationController.java`(#284)에 `@GetMapping("/{stationId}/stations-ahead")` 추가.
- `SubwayErrorCode` 에 `SUBWAY_DIRECTION_INVALID`(400), `SUBWAY_ROUTE_UNAVAILABLE`(503) 추가.

## 작업 순서

### PR-1 `feat/subway-stations-ahead-pr1` (fresh main) — 측정·포트
- [ ] 1. 운영 OTP 에서 fixture 채집(읽기 전용, ssh `vps` → `curl 127.0.0.1:18080/otp/routers/default/index/graphql`; 로컬 `localhost:18080` 은 운영 터널이다):
  - 전 SUBWAY route 목록
  - 대표 노선 패턴: 2호선 본선 내선, 5호선 하행, 신분당선 하행, 경의중앙선, 1호선, 6호선
  - 저장 위치: `src/test/resources/otp/subway-patterns/*.json`
- [ ] 2. 응답 크기·지연 측정(노선별 바이트·ms) → 노트 `### 검증`. 크면 질의 축소안 결정.
- [ ] 3. `SubwayLinePatternPort` + `OtpSubwayLinePatternAdapter` + 명시표. fixture 파싱 단위 테스트(MockRestServiceServer 또는 문자열 주입).
- [ ] 4. `SubwayPatternStationMatcher` + 테스트.
- [ ] 5. **커버리지 테스트** `SubwayPatternCoverageTest`(GtfsRailDirectionCoverageTest 방식):
  - 19노선 × 방향마다: route 존재, 패턴 존재, 정차→statn_id 매핑률
  - 운영 `subway_station` 노선 행은 fixture 로 저장(`subway-stations.json`, 696행 SELECT 결과)
  - 기준선 미달 노선을 목록으로 박는다. 이 목록이 PR-2 범위가 된다.
- [ ] 6. 노트 기록, PR. 이 PR 은 API 를 노출하지 않는다(빈 등록만).
- 검증: `./gradlew test --tests '*OtpSubwayLinePattern*' --tests '*SubwayPatternStationMatcher*' --tests '*SubwayPatternCoverage*'`

### PR-2 `feat/subway-stations-ahead-api` (fresh main, **#284 와 PR-1 머지 후**)
- [ ] 7. `StationsAheadCalculator` 테스트 먼저:
  - 순환 한 바퀴 컷(2호선 fixture: 강남 내선 → 역삼…교대, 42역 근처)
  - 다회전 패턴
  - 분기 공통 접두사(5호선 하행 fixture, 방화 출발 → 강동까지)
  - 회차 패턴 제거
  - destination 갈래(`마천` / `하남검단산`)
  - destination 이 tail 에 없으면 공통 접두사로
  - 연결 실패 역 유지
  - minutes 자정 보정
- [ ] 8. 계산기 구현.
- [ ] 9. 환승 + 서비스 + 에러코드 + 컨트롤러, 테스트(404/400/503/200 JSON).
- [ ] 10. HANDOFF.md(모바일: `use-alight-stops.ts` `loadAlightStops` 교체, `AlightStop` 대응표, `frequent` 는 앱이 붙임). 노트, PR.
- 검증: `./gradlew test --tests '*StationsAhead*' --tests '*SubwayStationController*'`

## 미결 (codex 논의 안건)
1. **6호선**(방향 없는 route 하나): ① destination 필수 ② realtime 이전·다음 역으로 패턴 방향 고르기 ③ 범위 제외(400). 잠정 = ③.
2. **OTP 실패**: 503 vs 200 + 빈 목록 + 사유 필드. 앱 화면은 자리표시로 떨어질 수 있음.
3. **route 가 여럿으로 쪼개진 노선**(1호선·경의중앙선 용산/서울역): 같은 lineId+방향 route 를 모두 합쳐 tail 계산하면 되는지 PR-1 측정으로 확인.
4. **급행**(trainType): 이번 범위 밖. 일반 기준. 급행 패턴이 따로 있으면 공통 접두사가 짧아질 수 있다 → PR-1 측정에서 확인.
5. 대표 trip 시각이 시간대(첨두/심야)마다 다른 문제 — 근사로 허용할지.

## 배포
마이그레이션 없음(OTP 런타임 조회 + 메모리 캐시). api 이미지 교체. 플래그 없음(새 경로).
