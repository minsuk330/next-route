# 경기 버스 실시간 도착 연결 (GBIS) — PLAN

티어: **M**. 브랜치: fresh `main` 기준 `feat/gyeonggi-bus-arrival` (스택 금지).

## 배경 (실측, 2026-09-29 ~ 10-05)

- 길찾기(OTP) 응답의 경기 노선 버스 구간은 `startLocalStationID`·`busLocalBlID` 가 null → 프론트가
  `GET /api/arrivals/bus/{stopId}/{routeId}` 를 못 부른다. 재현: 잠실역 → 테크노마트, 5600.
- 원인: `OtpSeoulIdentityResolver` 정규식이 `BS_TAGO_SEB`/`BR_TAGO_SEB` 만 받는다(`OtpSeoulIdentityResolver.java:41-42`).
  GGB 는 설계상 스코프 밖. 서울 안 탑승지점 50,967 중 23% 가 실시간 불가, 그중 20%p 가 경기 노선.
- 경기도 버스도착 API(`https://apis.data.go.kr/6410000/busarrivalservice/v2`) 실측:
  - `stationId`·`routeId` = OTP `1:BS_TAGO_GGB<9d>`·`1:BR_TAGO_GGB<9d>` 의 9자리 **그대로**.
    `getBusArrivalItemv2?stationId=123000001&routeId=228000184&format=json` → 5600 정상(`predictTime1=1`, `locationNo1=1`, `staOrder=62`).
  - **순번(ord) 불필요** → 서울처럼 `bus_route_stop` 카탈로그가 없어도 된다. 경기 노선 DB 적재 안 함.
  - 응답 타입 불안정: 값 있으면 숫자, 없으면 `""`(예 `predictTime1: ""`). `routeName` 은 `5600`(숫자) 또는 `"5700(예약)"`(문자).
  - `predictTime` 단위 = **분**. `locationNo` = 몇 정류장 전. `crowded` 1~4. `flag` 예 `PASS`.
  - 일일 트래픽 한도 기능별 10,000.
- 버스 정적 일배치(`STATIC_DATA_ENABLED`)는 사용자가 의도적으로 꺼 둠 — 이 작업과 무관, 켜지 않는다.

## 확정 결정

1. **엔드포인트·응답 계약 불변.** 프론트 수정 없이 같은 `/api/arrivals/bus/{stopId}/{routeId}` 를 부른다.
   서버가 provider 를 고른다: `bus_route_stop(routeId, stopId)` 에 서울 행이 있으면 서울(기존 경로), 없으면 경기.
   기각: 응답에 provider 필드 추가(프론트 수정 필요).
2. **식별은 별도 타입.** `SeoulBusIdentity` 의미(서울 DB 존재 검증됨)를 흐리지 않게 `GyeonggiBusIdentity` 를 새로 둔다.
   GGB 는 DB 존재 검증을 하지 않는다(카탈로그 없음). 잘못된 ID 면 경기 API 가 빈 결과를 줄 뿐 오염 없음.
3. **route id 접미사 `_U`/`_D` 제거** (OTP GGB 노선 1,138 중 28개, 예 `GGB241000830_U`).
4. **인천(ICB)·기타 지역은 이번 범위 밖** — 계속 null.
5. **검색 응답 `transferArrivals` 는 손대지 않는다.** 실시간은 프론트가 별도 API 로 부른다는 원칙
   ([[feedback-server-static-search-front-realtime]]). 경기 구간 status 는 `STOP_MAPPING_FAILED` 또는
   `UNSUPPORTED_ROUTE`(정류장이 서울 DB에 있으면)로 남는다 — 프론트는 `lanes[].busLocalBlID` 폴백을 쓴다.
6. **캐시·차단.** 어댑터 안 15초 TTL 메모리 캐시 + `SingleFlight`(키 `stationId:routeId`).
   한도 초과/키 오류 응답 → `ApiCallBlocker.block("gyeonggi-bus-arrival")` 로 자정까지 차단 → 빈 결과.
   기각: 기존 Redis `BusRealtimeCache`(키가 `stop:route:ord` 라 ord 없는 경기와 안 맞음).
7. **키는 env 로만.** `GYEONGGI_BUS_API`(포털 *Encoding* 키, `%2F` 포함 그대로 URI 에 붙임). 저장소·문서·커밋에 키 원문 금지.

## 응답 매핑 (경기 → `BusArrivalResponse`, 서울 의미에 맞춤)

| BusArrivalResponse | 서울 원천 | 경기 원천 → 변환 |
|---|---|---|
| `routeId` | busRouteId | `routeId` (문자열화) |
| `predictTime1/2` | traTime(초) | `predictTime`(분) × 60. `""` → null |
| `arrivalMsg1/2` | arrmsg (`2분26초후[0번째 전]`, `곧 도착`, `운행종료`, `출발대기`) | predict ≤1 → `곧 도착`; 값 있음 → `{분}분후[{locationNo}번째 전]`; 비어 있으면 flag 로 `STOP`→`운행종료`, `WAIT`→`출발대기`, 그 외 `도착 정보 없음` |
| `congestionNum1/2` | reride_Num(혼잡도 코드 3 여유·4 보통·5 혼잡·6 매우혼잡, 0 정보없음) | `crowded` 1→3, 2→4, 3→5, 4→6, 그 외/`""` → 0 |

flag 값 집합은 실측 `PASS` 만 확인 — 모르는 flag 는 `도착 정보 없음` 으로 폴백하고 로그 debug.

## 작업 순서

- [ ] **0. 사전 확인 (CLAUDE.md 규칙)**: `ssh vps` 로 `/root/apps/nextroute/compose.yaml` 의 api 서비스 env 주입 방식 확인
  (`SEOUL_BUS_API` 가 어디서 들어오는지 = 같은 곳에 `GYEONGGI_BUS_API` 추가). 값은 출력하지 말 것(redact).
- [ ] **1. 식별 확장** — `application/route/mapping/`
  - 신규 `GyeonggiBusIdentity.java`: `record GyeonggiBusIdentity(String stationId, String routeId, String routeName)`.
    `SeoulTransitIdentity` 는 구현하지 않는다(sealed 확인됨 — 서울 전용 의미 유지).
  - `OtpSeoulIdentityResolver` 에 `Optional<GyeonggiBusIdentity> resolveGyeonggiBus(String stopGtfsId, String routeGtfsId, String routeName)` 추가.
    정규식 `^\d+:BS_TAGO_GGB(\d{9})$`, `^\d+:BR_TAGO_GGB(\d{9})(?:_[UD])?$`. DB 조회 없음.
    기존 `resolveBus`·`resolve` 동작은 **변경 금지**(다른 호출자: `OtpLegCoverageResolver`, `OtpBusRealtimeTimingLookup`).
  - 검증: `OtpSeoulIdentityResolverTest` — 기존 "GGB → resolveBus empty" 유지 + 신규 케이스(아래 테스트 목록).
- [ ] **2. 검색 응답 매퍼** — `LegacyRouteResponseMapper.java:156-174`
  - BUS leg: 서울 `resolveBus` 실패 시 `resolveGyeonggiBus` 시도 → `startLocalStationID`/`busLocalBlID`/`endLocalStationID` 채움.
  - `routeDisplayColor`: 경기 노선은 DB 에 없으므로 null 유지(현행과 같음).
  - 검증: `LegacyRouteResponseMapperTest`(있으면 확장, 없으면 신규) — 5600 fixture.
- [ ] **3. 경기 도착 어댑터** — 의존: 없음(1·2 와 병렬 가능)
  - 신규 포트 `application/arrival/port/out/GyeonggiBusArrivalPort.java`:
    ```java
    BusQueryResult<GyeonggiBusArrival> getArrival(String stationId, String routeId);
    ```
    (`BusQueryResult`/`Outcome` 은 `SearchTimeBusQueryPort` 의 것을 재사용)
  - 신규 DTO `application/arrival/dto/GyeonggiBusArrival.java`:
    `record GyeonggiBusArrival(String routeId, Integer predictMin1, Integer locationNo1, Integer crowded1, Integer remainSeat1, Integer predictMin2, Integer locationNo2, Integer crowded2, Integer remainSeat2, String flag)`
  - 신규 어댑터 `infrastructure/adapter/out/api/gyeonggi/GyeonggiBusArrivalAdapter.java`:
    - `@Primary RestClient`(connect 3s/read 5s — 기본 `RestTemplate` 은 read 30s 라 사용자 대기 경로에 부적합), `exchange()` 로 4xx 본문도 직접 읽음. `URI.create(baseUrl + "/getBusArrivalItemv2?serviceKey=" + key + "&stationId=..&routeId=..&format=json")` (키 재인코딩 금지).
    - `JsonNode` 로 읽고 숫자/`""` 모두 허용하는 `intOrNull(node)` 헬퍼.
    - `msgHeader.resultCode`: `0` → OK(1건), `4`(결과 없음) → OK(빈 리스트), 그 외 → ERROR.
      본문이 JSON 이 아니거나 data.go.kr 공통 오류(XML `OpenAPI_ServiceResponse`, returnReasonCode 22 한도초과·30 미등록키 등) → `block` + BLOCKED.
    - 차단 중이면 호출 없이 BLOCKED. 15초 TTL 캐시(성공만) + `SingleFlight`.
  - `application.yaml` `seoul.api` 옆에 `gyeonggi.api.bus-key: ${GYEONGGI_BUS_API:}`, `gyeonggi.api.bus-arrival-base-url: https://apis.data.go.kr/6410000/busarrivalservice/v2`.
    키 미설정이면 어댑터는 호출 안 하고 ERROR(부팅은 성공).
  - 검증: `GyeonggiBusArrivalAdapterTest`(`MockRestServiceServer.bindTo(RestClient.Builder)`) — 실측 JSON 을 fixture 로.
- [ ] **4. 도착 조회 분기** — 의존: 3
  - `BusArrivalQueryService.getArrivals`: `findByRouteIdAndStopId` 결과가 **0건**이면 경기 포트 호출 → `BusArrivalResponse` 매핑(위 표).
    1건 → 기존 서울 경로. 2건 이상(순환 모호) → 기존대로 빈 결과(경기로 넘기지 않음).
  - 매핑은 서비스 안 private 메서드 또는 `GyeonggiArrivalMapper`(순수 함수, 테스트 용이) — 후자 권장.
  - 검증: `BusArrivalQueryServiceTest` 확장.
- [ ] **5. 전체 검증**: `./gradlew compileJava compileTestJava` + 아래 테스트 클래스 실행. 결과를 노트 `### 검증` 에 붙임.
- [ ] **6. 실 API 스모크(로컬, 키는 env)**: `GYEONGGI_BUS_API=... ./gradlew test --tests '*GyeonggiBusArrivalAdapterLiveTest*'` 같은
  수동 테스트는 만들지 않는다. 대신 배포 후 `curl /api/arrivals/bus/123000001/228000184` 로 확인.
- [ ] **7. PR**: 플랜 파일 포함. 커밋 전 `git diff --cached --name-only` 로 staged 검증(작업 트리에 무관한 변경 다수 존재).
- [ ] **8. 배포(사용자 승인 후)**: VPS env 에 `GYEONGGI_BUS_API` 추가(compose 는 additive 외과 삽입 + 백업, 통째 덮기 금지) →
  deploy.sh 로 api 재생성 → `docker exec nextroute-api-1 printenv GYEONGGI_BUS_API | wc -c` 로 주입 확인(값 출력 금지) →
  `curl localhost:<port>/api/arrivals/bus/123000001/228000184` 와 잠실역→테크노마트 검색 응답 확인.

마이그레이션 없음.

## 테스트 목록 (계약당 1개 이상)

| 계약 | 테스트 |
|---|---|
| GGB stop+route → `GyeonggiBusIdentity(123000001, 228000184)` | resolver |
| `_U`/`_D` 접미사 제거 (`GGB241000830_U` → `241000830`) | resolver |
| ICB·SEB 는 `resolveGyeonggiBus` empty | resolver |
| 기존 `resolveBus` 는 GGB 에 여전히 empty (다른 호출자 보호) | resolver(기존 유지) |
| 매퍼: 5600 leg → `startLocalStationID=123000001`, `busLocalBlID=228000184`, `endLocalStationID=104000279` | mapper |
| 매퍼: 서울 노선(2412 `SEB123000001`/`SEB100100209`) 결과 불변 | mapper |
| 어댑터: 숫자 응답 파싱(predict 1분, locationNo 1, crowded 1) | adapter |
| 어댑터: `""` 응답 → null 필드, OK | adapter |
| 어댑터: resultCode 4 → OK 빈 리스트 | adapter |
| 어댑터: XML 공통 오류(한도초과) → BLOCKED + 이후 호출 안 함 | adapter |
| 어댑터: 키 미설정 → ERROR, HTTP 호출 없음 | adapter |
| 어댑터: 15초 안 같은 키 재호출 → HTTP 1회 | adapter |
| 서비스: 서울 매핑 1건 → 서울 포트만 호출(경기 미호출) | service |
| 서비스: 매핑 0건 → 경기 포트 호출 + 변환(초·메시지·혼잡도) | service |
| 서비스: 매핑 2건 → 빈 결과, 경기 미호출 | service |
| 서비스: 경기 BLOCKED/ERROR → 빈 리스트 | service |

## 리스크·주의

- **일일 한도 10,000.** 15초 캐시 기준으로 한 (정류장, 노선)을 하루 종일 폴링하면 5,760회. 사용자 늘면 부족 →
  로그 `[GyeonggiBus] call` 카운트로 관측, 필요 시 TTL 상향 또는 운영계정 증량 신청.
- **여정(journey) 영향.** `JourneyCompiler.java:257-282` 가 `busLocalBlID`/`startLocalStationID` 를 원천 ID 로 쓴다.
  지금까지 null 이던 경기 구간에 9자리 ID 가 들어간다. `JourneyTransitIdEnricher.java:93` 의 `resolveRoute` 는 DB 에 없어 empty(현행과 같음),
  정류장은 서울 DB 에 있으면 해석된다(개선). 여정 관련 기존 테스트가 깨지지 않는지 5단계에서 같이 돌린다.
- 서울 노선인데 서울 DB 에 없는 0.6%(노선 4개 등)는 경기로 넘어가 빈 결과 — 현행(빈 결과)과 같아 악화 없음.

## 관련 좌표

- `OtpSeoulIdentityResolver.java:41-42, 72-82` / `LegacyRouteResponseMapper.java:156-174`
- `BusArrivalQueryService.java:30-55` / `BusArrivalController.java:23`
- `TransferArrivalEnricher.java:308-330, 460-473` (손대지 않음)
- `ApiCallBlocker.java` / `SingleFlight.java` (`application/route/concurrent/`)
- `TagoSubwayApiAdapter.java:56` (data.go.kr 키를 URI 에 붙이는 기존 패턴)
