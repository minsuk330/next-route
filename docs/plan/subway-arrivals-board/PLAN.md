# 역 도착 응답 노선도 확장 (#317, #318 1·2번 흡수) — PLAN

티어: **L** (API 계약 확장 4건, PR 4개). 리뷰: codex 대신 **Opus 에이전트**(사용자 지시 2026-10-09) — 조건부 승인, 지적 17건 반영 완료(§9).

## 0. 결론

`GET /api/subway/stations/{stationId}/arrivals` 응답을 **추가 필드만** 넓힌다(기존 필드·의미 불변, 앱 구버전 무해).
새 외부 호출 없음 — 이미 Redis·OTP 패턴 캐시에 있는 데이터를 조인한다. worker 변경은 PR-1(스냅샷 DTO 에 원천 필드 2개 보존)·PR-4(기록기) 두 곳.

| PR | 범위 | 원천 | 배포 |
|---|---|---|---|
| PR-1 | `trains[]` 위치(`currentStationId/Name`·`status`·`observedAt`·`positionSource`) + 반대 방향 열차 제거 | `SubwayTrainLogPort.lastSeen`(위치 수집 `subway:pos:*`), 없으면 도착 행의 `arvlCd`·`statnFid`·`arvlMsg3` | api+worker |
| PR-2 | `directions[]` 에 `stationsBehind`(N=4)·`nextStation`·`boardDwellSeconds`, `stations-ahead` 각 역에 `secondsFromBoard`·`runSeconds`·`dwellSeconds` | OTP GTFS 패턴(같은 방향) | api |
| PR-3 | `trains[].segment` 앵커 + 이미 지난 열차 제거 | `SubwaySegmentTimePort`(#316 실측) → 없으면 PR-2 GTFS `runSeconds` | api |
| PR-4 | 위치 피드 없는 노선의 `departed` 대체 | 도착 피드에서 이 역 행이 사라진 열차(worker 신규 기록기, 별도 키) | api+worker |

의존: PR-2 → PR-1(노선 순서 `SubwayLineOrder`), PR-3 → PR-1·PR-2, PR-4 → PR-1(위치 해석·출발 조회 통합). 사실상 ①→②→③, ①→④. **각 PR 은 fresh main 기준 독립 브랜치**(스택 금지). PR-3 은 PR-1·2 머지 후 착수.
마이그레이션 없음.

## 1. 확정된 결정 (사용자 2026-10-09)

1. 앵커 형식 = 이슈의 `segment` 객체(`departedAt`→`expectedAt` 선형 보간). 링 형식(`segmentProgress`/`progressBaseAt`) 기각.
2. PR-1 은 **방향 불일치만** 거른다. "이 역을 이미 지난 열차" 판정은 역 순서가 필요해 PR-3 에서.
3. `SubwayRingCalculator` 재사용 안 함 — 여정 전용 상태기계(승차 index·이전 상태 필요). 대신 #316 구간 실측 포트를 직접 쓴다.
5. **위치 `trainSttus=3`(전역출발) = 그 행 `statnId` 역의 바로 전역을 출발했다**(사용자 확정 2026-10-09). 열차는 전역(X 의 직전 역)→X 구간을 달리는 중이다. 도착 API 코드 3 과 같은 의미.
6. **떠난 열차는 원천과 무관하게 전부 고를 수 있어야 한다**(사용자 결정 2026-10-09). PR-4 의 도착 피드 출처 열차도 열차 선택(#293/#304)이 받아야 한다 → 출발 조회를 한 곳으로 모은다(§6).
4. 열차번호 형식은 **이미 같다** — btrainNo↔trainNo 16/16 일치 실측(implementation_note.md:442), 운영 링이 그대로 equals 조인(SubwayRingSignalReader.java:150). 정규화 불필요.

## 2. 현재 코드 좌표 (origin/main 705a590d 기준)

- 컨트롤러 `infrastructure/adapter/in/api/subway/SubwayStationController.java:29-38`
- 서비스 `application/arrival/service/SubwayStationArrivalQueryService.java`
  - `getArrivals` :67-75, `departed()` :83-120, `toDeparted` :122-135 (이미 `lastSeen` 조인 사용 — 같은 `TrainIdentity` 체계), `group()` :181-209, `toTrain` :244-254
- DTO `application/arrival/dto/SubwayStationArrivalsResponse.java` (`Direction` :24, `Departed` :34, `Train` :42)
- 도착 reader `application/subway/service/SubwayRealtimeArrivalReader.java` (`select` :95-114, `hasPassed` :161, `adjustedSeconds` :180)
- 도착 열차 DTO `application/subway/dto/SubwayRealtimeTrain.java` — `prevStationName`·`nextStationName`·`arrivalCode`·`receivedAt` 보유
- 위치 로그 포트 `application/subway/port/out/SubwayTrainLogPort.java` (`lastSeen` :24, `findDepartures` :42), 어댑터 `infrastructure/adapter/out/cache/subway/RedisSubwayTrainLogAdapter.java:63`
- `TrainIdentity(serviceDate, lineId, direction, trainNo)` `application/subway/dto/TrainIdentity.java`; 운행일 `ServiceDateResolver`
- `SubwayTrainObservation(stationId, stationName, status, receivedAt, terminalId, express)`; `SubwayTrainStatus` = ENTERING/ARRIVED/DEPARTED/LEFT_PREVIOUS/UNKNOWN
- 구간 실측 포트 `application/subway/port/out/SubwaySegmentTimePort.java` (`departedAt` :28, `recentSeconds` :31). 구간 이름 = **도착하는 역**. 기록기 `SubwaySegmentTimeRecorder` (worker, `RealtimeSubwayCollector.java:108` 에서 호출)
- 남은 역 `application/subway/service/SubwayStationsAheadService.java` (패턴 해석 캐시 `resolvedPatterns`), 계산 `StationsAheadCalculator.java` (`Stop(key, stationId, name, arrivalSec, departureSec, gtfsStopId)` :57, `tails` :206, `minutes` :199). 응답 `application/arrival/dto/SubwayStationsAheadResponse.java`
- 도착 수집 `application/subway/service/RealtimeSubwayCollector.java:95-111` (스냅샷 저장 직후 기록기 호출 지점)
- 위치 수집 `RealtimeSubwayPositionCollector` (@Profile worker, 15s) → `SubwayTrainTransitionTracker` → `subway:pos:{serviceDate}:{lineId}`
- 위치 출발 사건 있는 노선: 1001~1009·1032·1065·1077·1092. 없는 노선: 1063·1067·1075·1081·1093·1094 (메모리 #299 기록)

## 3. PR-1 — 열차 위치 구조화 + 반대 방향 제거

브랜치 `feat/subway-arrivals-train-position`. **이 PR 에 `docs/plan/subway-arrivals-board/{PLAN,SUMMARY}.md` 포함.**

### 계약 (DTO `SubwayStationArrivalsResponse.Train` 에 추가, 전부 nullable)
```java
public record Train(
        String trainNo, String destinationName, String trainType,
        Integer arrivalSeconds, Integer stationsAway, String currentMessage,
        // #317-1
        String currentStationId,      // 지금 있는/막 지난 역. 모르면 null
        String currentStationName,
        TrainStatus status,           // APPROACHING | ARRIVED | DEPARTED, 모르면 null
        OffsetDateTime observedAt,    // 그 위치의 원천 수신 시각(recptnDt). 앱 보간 앵커
        PositionSource positionSource // POSITION | ARRIVAL, 위치 없으면 null
) {}
public enum TrainStatus { APPROACHING, ARRIVED, DEPARTED }
public enum PositionSource { POSITION, ARRIVAL }
```

### 노선 순서 `SubwayLineOrder` (신규, PR-1 에서 도입·PR-2/3 재사용)
```java
// application/subway/service — SubwayLinePatternService.cachedPatterns(:76) 만 읽는다(적재 없음, 응답 지연 0)
Optional<String> prevOf(String lineId, String direction, String stationId); // 직전 역 id. 패턴들 사이에 답이 둘 이상이면 empty
Optional<String> nextOf(String lineId, String direction, String stationId); // 같은 규칙
```
- 패턴 정차 → 역 id 연결은 `SubwayStationsAheadService.resolvedPatterns` 캐시를 공유한다(같은 계산 두 번 금지). ~~`SubwayResolvedPatternCache` 로 추출~~ 구현 시 편차: 같은 패키지라 `SubwayLineOrder` 가 `resolvedPatterns` 를 직접 부른다(추출 이득 없음, implementation_note 2026-10-09).
- 다수결 대신 **유일할 때만** 답한다 — 1호선 구로·2호선 지선 합류처럼 직전 역이 갈래마다 다르면 모름.
- 캐시 미스 → empty + 비동기 갱신 트리거(§4 와 같은 single-flight).

### 원천 필드 보존 (worker, PR-1 에 포함)
- `SubwayRealtimeTrain` 에 `currentStationName`(`arvlMsg3`, 99 운행중 열차의 현재 역 — implementation_note.md:445) · `prevStationId`(`statnFid`) 추가. `SubwayArrivalInfo` 엔 있는데 스냅샷 변환(`RealtimeSubwayCollector.java:121-142`)에서 버려지고 있다(`SeoulSubwayApiAdapter.java:332`).
- 스냅샷 JSON 은 `@JsonInclude(NON_NULL)` 이라 구 api 가 새 스냅샷을, 새 api 가 구 스냅샷을 읽어도 안전(필드 null). **배포 순서 무관**, 다만 ARRIVAL 대체는 worker 배포 후에야 채워진다.

### 위치 결정 규칙 (`SubwayTrainPositionResolver` 신규, application/arrival/service, 순수 함수 + 포트 1개)
1. **위치 피드 우선**: `trainLogPort.lastSeen(new TrainIdentity(serviceDate, lineId, direction, trainNo))`
   - 운행일은 `departed()` 와 같이 `serviceDateResolver.resolve(now)` (자정 전후는 `resolve(now-10m)` 도 시도).
   - 신선도 **300s**(`SubwayTrainTransitionTracker` freshnessWindow :49 와 같은 값, 상수 공유). 주의: `lastSeen.receivedAt` 은 "마지막으로 본 시각"이 아니라 **recptnDt 가 바뀐 마지막 시각**이다(트래커 :199-208) — 오래 정차한 열차는 살아 있어도 오래돼 보인다. 그래서 180s 기각.
   - status 매핑: ENTERING→(X, APPROACHING), ARRIVED→(X, ARRIVED), DEPARTED→(X, DEPARTED), **LEFT_PREVIOUS(3)→(prev(X), DEPARTED)** — 결정 5. `prev(X)` 는 `SubwayLineOrder.prevOf(lineId, direction, X)`. 못 구하면(캐시 미스·합류역 직후라 직전 역이 둘) 위치 피드 결과를 버리고 규칙 2 로 넘긴다. UNKNOWN → 규칙 2.
   - `SubwayTrainStatus.LEFT_PREVIOUS` javadoc(:21-27)을 확정 의미로 고친다. 출발 **사건**은 여전히 만들지 않는다(M7 트래커·링은 이 PR 범위 밖, §8).
2. **도착 행 대체** (`SubwayArrivalCode`): 0 진입 → (이 역, APPROACHING), 1 도착 → (이 역, ARRIVED), 3 전역출발 → (`prevStationId`/`prevStationName`, DEPARTED), 4 전역진입 → (prev, APPROACHING), 5 전역도착 → (prev, ARRIVED), 99 운행중 → (`currentStationName`(arvlMsg3), status null). `observedAt` = 도착 행 `receivedAt`.
   - 이름만 있고 id 가 없으면 캐시된 `SubwayLineStationIndex.candidates(lineId)` 에서 이름 매칭(DB 질의 금지 — 레포에 노선+이름 조회 메서드도 없다).
   - 한계(HANDOFF 기재): `statnFid` 는 합류역 직후 역에서 다른 갈래의 전역을 가리킬 수 있다.
3. 둘 다 없으면 5필드 null.

### `observedAt` 계약
"그 위치·상태가 **확인된** 원천 시각". POSITION 은 상태가 바뀐 시각, ARRIVAL 은 상태가 그대로여도 계속 갱신된다(SubwayRingSignalReader.java:117-118) → **사건 시각이 아니므로 보간 앵커로 쓰지 말 것**. 앵커는 PR-3 `segment.departedAt` 하나. DTO javadoc·HANDOFF 에 명시.

### 반대 방향 열차 제거
- `directionsOf(lineId)` 크기가 2 이고 이 방향이 그 안에 있을 때만 "반대 방향"을 계산한다.
- 반대 방향 `lastSeen` 이 신선(≤300s)하고 **이 방향 `lastSeen` 이 없거나 반대 쪽 receivedAt 보다 오래됐을 때만** 뺀다. 로그 `debug` 한 줄.
- 수원 `병점행 · 진위` 사례: 회차 직후 열차가 아직 하행 도착 행에 남아 있는 것 — 위치 피드는 이미 상행.
- 판단 근거가 없으면(위치 피드 없는 노선) 빼지 않는다.

### 성능
- 열차마다 `lastSeen` 2회(두 방향) 대신 노선 해시 `subway:pos:{date}:{line}` 를 **HMGET 1회**로 읽는 포트 메서드 `lastSeenAll(serviceDate, lineId, List<field>)` 추가(`RedisSubwayTrainLogAdapter`).

### 실패 경로
- 위치 조회 예외 → 위치 null(도착 행 대체 시도), 목록은 유지. `departed()` 와 같은 try/catch 정책. 응답을 깨뜨리지 않는다.

### 작업
- [x] `SubwayLineOrder` + 테스트(일반·급행 건너뜀·합류역 모름·순환·캐시 미스)
- [ ] `SubwayRealtimeTrain` 필드 2개 + 수집기 변환 + 테스트
- [ ] DTO 필드·enum 추가 (`SubwayStationArrivalsResponse.java`)
- [ ] `lastSeenAll` 포트·어댑터 + IT
- [ ] `SubwayTrainPositionResolver` + `SubwayTrainPositionResolverTest` (규칙 1·2·3, 신선도 경계 300s, LEFT_PREVIOUS→prev(X) DEPARTED, prev 모름→대체, 99→arvlMsg3, 예외)
- [ ] `SubwayStationArrivalQueryService.group()` 이 `Arrival→Train` 변환에 resolver 사용, 반대 방향 제거. `group(List<Arrival>)` static 테스트 진입점은 resolver 없는 오버로드 유지 또는 테스트 수정
- [ ] `SubwayStationArrivalQueryServiceTest` 계약 테스트: 위치 조인 성공/오래됨/반대방향 제거(양쪽 receivedAt 비교)/도착행 대체/예외 시 목록 유지
- [ ] `SubwayStationControllerTest` JSON 필드 이름 + **null 직렬화** 1건(DTO 에 `@JsonInclude` 없음 — 새 필드는 null 로 나간다, 앱 하위호환 유지)
- [ ] `docs/plan/subway-arrivals-board/HANDOFF.md` 생성(필드 의미·null 조건·observedAt 주의)

검증: `./gradlew compileJava && ./gradlew test --tests '*SubwayStationArrival*' --tests '*SubwayTrainPositionResolver*' --tests '*SubwayStationController*' --tests '*RealtimeSubwayCollector*'`

## 4. PR-2 — 노선도용 역 목록 + 초 단위 운행 시간

브랜치 `feat/subway-arrivals-line-strip`.

### 계약
`SubwayStationArrivalsResponse.Direction` 에 추가:
```java
List<StripStation> stationsBehind,   // 이 방향 열차가 오는 쪽, 먼 역 → 가까운 역(마지막이 직전 역), 최대 4. 모르면 빈 목록
StripStation nextStation,            // 이 역 다음 역. 종착·분기점·모름이면 null
Integer boardDwellSeconds,           // 이 역 정차 계획 초. 모르면 null
BehindEnd behindEnd                  // FULL(4개 다 참) | ORIGIN(기점이라 짧음) | BRANCH(갈래 합류점에서 끊음) | UNKNOWN

public record StripStation(String stationId, String stationName,
                           Integer secondsToHere,   // behind: 이 역 출발 → 조회역 도착 계획 초. next: 조회역 출발 → 이 역 도착
                           Integer runSeconds,      // 직전 역(진행 순서) 출발 → 이 역 도착
                           Integer dwellSeconds) {} // 이 역 도착 → 출발
public enum BehindEnd { FULL, ORIGIN, BRANCH, UNKNOWN }
```
- 직전 역 → 조회역 운행 초 = `stationsBehind.last().secondsToHere` (따로 안 싣는다).
- `SubwayStationsAheadResponse.Station` 에 `Integer secondsFromBoard, Integer runSeconds, Integer dwellSeconds` 추가(#318-1). `minutes` 유지.

### 계산 (`StationsAheadCalculator` 에 추가, 순수 함수)
```java
public record Behind(List<Stop> stops /*먼→가까운*/, BehindEnd end) {}
public static Behind behind(List<Pattern> patterns, String boardKey, int n);
static Integer seconds(Integer from, Integer to); // null·음수면 null (minutes() 와 같은 규칙)
```
- 같은 방향 패턴(`linePatternService.patterns(lineId, direction)` — #286 에서 OTP 방향 라벨 반전 4노선 보정 완료)에서 승차역 **앞쪽** 꼬리(head)를 뽑는다. `tails()` 의 거울.
- 순환 패턴(2호선, 한 패턴 다회전): 승차역 등장 중 **앞에 n개 이상 정차가 있는 첫 등장**을 기준으로 head 를 자른다. head 안에 승차역이 다시 나오면 그 뒤만.
- 갈래: tails 와 같은 순서로 **`withoutSubsequences` → `withoutMinor`(5%)** 적용 후(StationsAheadCalculator.java:146-147) head 들의 **공통 접미사**(가까운 쪽부터 맞춤). 순서를 지키지 않으면 9호선·1호선 급행 head(완행의 부분수열)가 거짓 `BRANCH` 를 만든다. 길이 < n 이고 head 들이 더 길면 `BRANCH`, 모든 head 가 짧으면 `ORIGIN`.
- 이슈의 열차별 `branchStations` 는 **넣지 않는다**. HANDOFF 규칙: `currentStationId` 가 strip 에 없는 열차는 `stationsAway`/`arrivalSeconds` 로 strip 왼쪽 끝 바깥 '더 먼 곳' 칸에 둔다.
- `secondsToHere`·`runSeconds` 는 **대표 패턴(최다 trip)** 기준. 자정 넘는 OTP 초는 `minutes()` 와 같은 규칙(음수면 null).
- `nextStation` = `calculate(patterns, board, null).stations()` 첫 역(BRANCH 로 비면 null).
- `Ahead` 에 `secondsFromBoard`·`runSeconds`·`dwellSeconds` 채움(기존 `minutes` 계산 옆).

### 서비스 배선
- `SubwayStationsAheadService` 의 패턴 해석부(`resolvedPatterns`·`resolve`)를 재사용해야 한다. 새 포트 `application/subway/port/in/GetSubwayLineStripUseCase`:
  ```java
  Optional<LineStrip> strip(String lineId, String direction, String stationId); // 실패·모름·캐시 미스 = empty
  record LineStrip(List<StripStop> behind, BehindEnd end, StripStop next, Integer boardDwellSeconds,
                   List<String> aheadKeys /*조회역 다음 3역 키, PR-3 지난 열차 제거용·응답 미노출*/,
                   Map<String, String> nextOf /*역 키 → 같은 방향 다음 역 키, PR-3 segment 용*/) {}
  ```
  `SubwayStationsAheadService` 가 구현(패턴 해석 캐시 `resolvedPatterns` 공유). arrival 서비스는 이 포트만 안다.
- 도착 서비스: 방향마다 `strip(...)` 호출. **예외·빈 결과면 빈 목록/null 로 응답** — 노선도 때문에 도착 목록이 실패하면 안 된다. 방향 키는 도착 응답의 `direction` 원문(상행/하행/내선/외선) 그대로 — `SubwayRoutePatternAssembler.isKnownDirection` 통과 못 하면 생략.
- 지연: 도착 경로는 **`SubwayLinePatternService.cachedPatterns()`(:76, 적재 없음)만** 쓴다. 비어 있으면 빈 strip 을 주고 비동기 갱신을 트리거한다(라인·방향 키 single-flight, 전용 단일 스레드 executor — commonPool 블로킹 금지). 300ms `orTimeout` 안 기각: 원 작업을 취소하지 않고 동시 미스 시 OTP 중복 적재로 번진다. 패턴 TTL 24h 라 부팅 워밍업만으로는 매일 미스가 나므로 이 트리거가 사실상 재적재 경로다.

### 작업
- [ ] `StationsAheadCalculator.behind` + 초 필드 + `StationsAheadCalculatorTest`(일반·기점·갈래 합류·2호선 다회전·초 null)
- [ ] `GetSubwayLineStripUseCase` + `SubwayStationsAheadService` 구현 + 테스트
- [ ] `SubwayStationsAheadResponse.Station` 초 필드 + 컨트롤러 테스트
- [ ] 도착 응답 `Direction` 확장 + 예산·실패 테스트
- [ ] 테스트 추가: 9호선 급행 head 가 BRANCH 를 만들지 않음, 캐시 미스 → 빈 strip + 갱신 1회만 트리거
- [ ] 실측: 강남 내선·신도림 하행(갈래)·수원 하행·방화 하행에서 `stationsBehind` 로컬 호출(OTP 는 `localhost:18080` = **운영 OTP 터널**, 조회만) → 노트 `### 검증`. **GTFS 초 분포 확인** — 분 단위·정차 0 이면 `runSeconds`/`dwellSeconds` 는 거짓 정밀도라 노트·HANDOFF 에 남긴다

검증: `./gradlew test --tests '*StationsAhead*' --tests '*SubwayStationArrival*' --tests '*SubwayStationController*'`

## 5. PR-3 — 열차별 구간 앵커 + 지난 열차 제거

브랜치 `feat/subway-arrivals-train-segment` (PR-1·2 머지 후 fresh main).

### 계약 (`Train` 에 추가)
```java
Segment segment // 달리는 중일 때만. 정차·모름이면 null
public record Segment(String fromStationId, String fromStationName,
                      String toStationId, String toStationName,
                      OffsetDateTime departedAt,   // from 출발 시각
                      OffsetDateTime expectedAt,   // to 도착 예정. 모르면 null
                      SegmentSource source) {}     // MEASURED(오늘 실측 중앙값) | SCHEDULED(GTFS 계획)
```

### 규칙 (`SubwayTrainSegmentResolver` 신규)
- 입력: PR-1 위치(역·status·observedAt), PR-2 패턴 순서(역 → 다음 역, 같은 방향).
- status=DEPARTED 일 때만 segment 생성. from = 현재 역, to = `SubwayLineOrder.nextOf(from)`. 위치 코드 3 은 PR-1 이 이미 from=prev(X) 로 바꿔 두므로 to=X 가 되고, 원천이 말한 X 와 다르면 X 를 믿는다.
- `departedAt` = `segmentTimePort.departedAt(line, dir, toStationId, trainNo)` 있으면 그것(도착 API 같은 원천, #316), 없으면 위치 `observedAt`(POSITION 은 상태가 바뀐 시각이라 출발 시각 근사로 쓸 수 있다. ARRIVAL 의 receivedAt 은 계속 갱신되므로 쓰지 않고 segment 의 departedAt=null).
- `expectedAt` = departedAt + `median(segmentTimePort.recentSeconds(line, dir, toStationId))` → `MEASURED`; 표본 없으면 + GTFS `runSeconds` → `SCHEDULED`; 둘 다 없으면 null.
- `expectedAt < now` 여도 그대로 준다(늦는 중) — 화면 역행 금지는 앱 책임(이슈 명시).
- 링 원칙 준수: 정적 중위값 금지, 오늘 실측은 OK([[project-subway-ring-segment-history]]). GTFS 계획은 **표본 없을 때만** + source 로 구분.

### 지난 열차 제거 (결정 2 의 후속)
- 현재 역이 `aheadKeys`(조회역 다음 3역)에 있고 상류(`stationsBehind` + 조회역)에 없으면 뺀다. 순환선은 상·하류가 겹치므로 겹치면 빼지 않는다.
- **위치가 "조회역 DEPARTED"** 이고 위치 observedAt 이 도착 행 receivedAt 보다 최신이면 뺀다 — reader 는 도착 행 arvlCd=2 만 뺀다(SubwayRealtimeArrivalReader.java:161-167), 도착 행이 아직 0/1 인 사이에 남는다.

### 작업
- [ ] resolver + 테스트(실측/계획/없음, 정차 중 null, 패턴에 없는 역)
- [ ] 지난 열차 제거 + 테스트(일반·순환·위치 DEPARTED@조회역 vs 도착행 0/1)
- [ ] 로컬 실호출 2호선 강남·4호선 1건 → 노트 `### 검증`

## 6. PR-4 — 위치 피드 없는 노선의 방금 떠난 열차

브랜치 `feat/subway-arrivals-departed-fallback`.

### 규칙
- worker 도착 수집 회차마다(`RealtimeSubwayCollector.java:108` 옆, 같은 try/catch 격리) 신규 `SubwayArrivalDepartureRecorder.record(trains)`:
  - 직전 회차 (line|dir|station|trainNo) → arvlCd 맵은 `SubwaySegmentTimeRecorder.previousCodes`(:41-76) 와 같은 것 — **한 곳에서 만들어 두 기록기가 공유**(중복 맵 금지). worker 1개 전제.
  - **부분 응답 가드**: 대상 노선 행 수가 직전 회차 대비 절반 미만이면 그 회차는 "사라짐" 기록을 건너뛴다(거짓 출발 방지).
  - 직전 코드 ∈ {0,1,2} 이고 이번 회차에 **그 역 행이 사라졌거나 코드 2 를 처음 봄** → 출발로 기록. 시각 = 코드 2 의 `receivedAt`, 없으면 (직전 receivedAt + 이번 수집 시각)/2.
  - 재시작 첫 회차는 직전 상태가 없으므로 기록하지 않는다(#316 배포 함정과 같은 원인).
- 대상 노선: **위치 출발 사건이 없는 노선만** — 설정 `realtime.subway.arrival-departure.line-ids` (기본 `1063,1067,1075,1081,1093,1094`). 위치 피드가 있는 노선과 섞지 않는다(중복·품질 혼선).
- 저장: **별도 키** `subway:adep:{serviceDate}:{lineId}:{direction}:{stationId}` ZSet, TTL 30m, 값 = trainNo·destinationName·trainType·departedAt. M7 탑승 판정이 읽는 `subway:dep:*` 와 **분리** — 품질 낮은 사건이 탑승 판정 후보로 섞이지 않게(리뷰 포인트).
- 포트 `SubwayArrivalDepartureLogPort { void append(...); List<Row> find(serviceDate, line, dir, station, from, to); }` + Redis 어댑터.
- **출발 조회 통합** `SubwayDepartureLookup`(신규, application/subway/service): 위치 출발 사건(`findDepartures`, source=POSITION) + 도착 피드 출발(`SubwayArrivalDepartureLogPort`, source=ARRIVAL)을 한 목록으로 합친다. 같은 열차가 양쪽에 있으면 POSITION. **역 도착 응답 `departed()` 와 열차 선택 `SubwayTrainSelectionService.departedServiceDate`(:133-153) 가 둘 다 이것만 쓴다** — 화면에 보인 떠난 열차는 반드시 고를 수 있다(결정 6).
- 선택 후 링: 고정 행은 (운행일·노선·방향·trainNo) 만 가진다. 위치 피드 없는 노선의 링 신호는 도착 API 경로(`SubwayRingSignalReader`)라 그대로 돈다. M7 자동 판정(`SubwayBindingMatchService.java:228`)은 **바꾸지 않는다** — 자동 판정이 낮은 품질 후보를 집는 건 다른 문제다.
- 도착 서비스 `departed()`: 위치 출발 사건이 비면 이 포트로 채운다(같은 10분·3대 규칙, `currentStationName` 은 PR-1 위치 해석 재사용).
- **계약 추가** `Departed.source`: `POSITION | ARRIVAL` — 정보용(출발 시각 정확도가 다르다). **둘 다 선택 가능.**
- 플래그 `realtime.subway.arrival-departure.enabled` 기본 **true**(worker 전용·기록만·응답은 비어 있던 배열을 채울 뿐). 끄기 = env `REALTIME_SUBWAY_ARRIVAL_DEPARTURE_ENABLED=false`.

### 작업
- [ ] recorder + 테스트(사라짐·코드2·첫 회차 무기록·대상 노선 외 무시)
- [ ] 포트·Redis 어댑터 + IT(`RedisSubwaySegmentTimeAdapterIT` 패턴)
- [ ] `SubwayDepartureLookup` + 테스트(병합·중복 시 POSITION·한쪽 예외면 다른 쪽만)
- [ ] 도착 서비스 `departed()`·선택 서비스 `departedServiceDate` 를 lookup 으로 교체 + 테스트: **ARRIVAL 출처 열차 선택 → 200**(기존 422), 선택 후 고정 행 운행일 = 출발의 운행일
- [ ] `SubwayTrainSelectionServiceTest` 기존 케이스 회귀 확인
- [ ] 부분 응답 가드 테스트
- [ ] 배포 후 수인분당 1역 `departed` 실호출 → 노트 `### 검증`

## 7. 배포

- 마이그레이션 없음. PR-1 = api+worker(스냅샷 필드), PR-2·3 = api, PR-4 = api+worker.
- 배포는 `reference-vps-deploy` 절차(로컬 빌드 jar, `deploy.sh`, rollback-api/worker). 플래그 실효값은 컨테이너 env 로 확인.
- 앱 인계: 각 PR 머지 시 `docs/plan/subway-arrivals-board/HANDOFF.md` 에 필드 의미·null 조건 누적(#317 이슈 코멘트로도 링크).

## 8. 남은 확인 (구현 중 실측)

1. 위치 코드 3 확정 의미(결정 5)를 M7 트래커·링(`SubwayRingCalculator.java:289-290`)은 아직 안 쓴다 — 쓰면 출발 판정이 빨라질 수 있다. **별건 이슈 후보**, 이 플랜 범위 밖.
2. GTFS 초 해상도(분 단위 여부) — PR-2 실측.
3. 반대 방향 제거 건수 — 실호출로 수원 하행 사례 재현 여부.

## 9. Opus 리뷰 반영 (2026-10-09, 판정: 조건부 승인 → 전부 반영)

| # | 지적 | 반영 |
|---|---|---|
| 🔴1 | LEFT_PREVIOUS→DEPARTED 는 한 칸 어긋남 | ~~대체로 넘김~~ 사용자 확정(결정 5): 3 = X 의 전역 출발 → (prev(X), DEPARTED), segment prev(X)→X |
| 🔴2 | ARRIVAL departed 선택 시 422 | ~~선택 불가 표시~~ 사용자 결정(결정 6): `SubwayDepartureLookup` 으로 선택도 받게 |
| 🟡3 | 180s·receivedAt 의미 | 300s + 반대방향 receivedAt 비교 |
| 🟡4 | 300ms orTimeout | `cachedPatterns` + 비동기 single-flight |
| 🟡5 | 도착행 대체 좁음 | arvlMsg3·statnFid 보존(worker) |
| 🟡6 | observedAt 의미 | 계약 문구 + 앵커는 segment 만 |
| 🟡7 | 급행 거짓 BRANCH | withoutSubsequences 선행 + 테스트 |
| 🟡8 | strip 밖 열차 | HANDOFF '더 먼 곳' 규칙 |
| 🟡9 | 지난 열차 구멍 | aheadKeys + 위치 DEPARTED@조회역 |
| 🟡10 | 의존 표기 | PR-4 → PR-1, PR-2 → PR-1(SubwayLineOrder) |
| 🔵11~17 | 실측 삭제·방향 계산 조건·HMGET·GTFS 해상도·statnFid 한계·null 직렬화·맵 공유/부분응답 가드 | 각 절 반영 |
