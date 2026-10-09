# #326 지도 정류장 전국(GTFS) 타일 — PLAN (L)

브랜치: `feat/map-bus-stop-tiles` (fresh main `3044abed` 기준, 단일 PR). 이슈 #326.
선행(머지·배포됨): #321(근처 직선거리), #327(같은 정류장 합치기 `TrackStopSiblings`).

## 배경·결정 (2026-10-10)
- 지도는 v1 `GET /api/map/bus-stops/snapshot` 을 그린다. 원천은 서울 DB `bus_stop` 12,986행이다.
  - 그래서 경기·인천·지방이 지도에 없다.
  - 근처·노선·추적은 전국 GTFS 라서 지도와 어긋난다.
- 실측(운영 OTP):
  - 버스 stop 210,029개. 합치기 후 205,947개.
  - 한 파일로 내리면 gzip 2.8MB. OTP `stops` 전체 응답은 24MB(1.7초).
- 0.1° 타일: 1,158개. 타일당 gzip p50 1.5kB · p90 5.3kB · 최대 31kB.
- 사용자 결정:
  - 로딩 적은 쪽 = **타일 분할**
  - 마커 키 = **gtfsStopId**
  - 인증 정책은 범위 밖(로그인 제거 진행 중)
- v1 은 그대로 둔다(구버전 앱용).

## 계약 (v2, 공개 경로 — SecurityConfig 는 `/api/map/**` 를 막지 않는다)
### `GET /api/map/bus-stops/v2/tiles` (manifest)
```json
{"version":"<12hex>","tileSize":0.1,"count":205947,"tiles":{"374_1270":"<12hex>", "...":"..."}}
```
- `ETag: "<version>"`. `If-None-Match` 가 같으면(W/·콤마·`*` 허용) 304.
- Cache-Control 은 v1 과 같다(`max-age=7d, public, must-revalidate`).
- 아직 빌드 전이면 **503** + `Retry-After: 30`.

### `GET /api/map/bus-stops/v2/tiles/{key}`
```json
{"key":"374_1270","version":"<12hex>","columns":["gtfsStopId","name","lat","lng","arsId"],
 "stops":[["1:BS_TAGO_SEB121000009","신분당선강남역",37.4958,127.029,"22010"]]}
```
- `ETag: "<tileVersion>"`, 304 지원.
- key 형식이 틀리면 400, manifest 에 없으면 404, 빌드 전이면 503.
- `arsId` 는 서울(SEB) 정류장만, 나머지는 null(빈 문자열 아님).
- 좌표는 소수 5자리 반올림(v1 `round` 와 같다).
- stops 는 gtfsStopId 순으로 정렬한다. 같은 데이터면 같은 바이트가 나와 해시가 안정적이다.

### 규칙
- **key** = `floor(lat/0.1) + "_" + floor(lng/0.1)`. 정규식 `^\d{2,3}_\d{3,4}$`. 강남(37.4958, 127.029) → `374_1270`.
  - 부동소수 경계: `Math.floor(lat * 10)` 을 쓴다(나눗셈 대신). 테스트로 고정한다.
- **tileVersion** = 타일 본문(JSON bytes) SHA-256 hex 앞 12자.
  - 본문에 자기 version 이 들어가므로, 해시는 `stops` 배열 직렬화 bytes 기준으로 계산한 뒤 본문에 넣는다.
- **version** = `key=tileVersion` 를 key 순으로 `\n` 이어 붙인 문자열의 SHA-256 앞 12자.
- **합치기**: `TrackStopSiblings` 규칙(수도권 SEB/GGB/ICB, 같은 뒤 숫자, 대표에서 300m). 대표 SEB → GGB → ICB.
  - 묶음은 **대표 좌표의 타일**에만 들어간다.
  - 300m 밖 멤버는 따로 낸다.

## 작업 순서
- [ ] 1. OTP 포트 — `StopsInBox`
  - `src/main/resources/otp/trackPatterns.graphql`: `StopsByIds`(:20) 아래에 추가한다.
    ```graphql
    query StopsInBox($minLat: Float!, $minLon: Float!, $maxLat: Float!, $maxLon: Float!) {
      stopsByBbox(minLat: $minLat, minLon: $minLon, maxLat: $maxLat, maxLon: $maxLon) { gtfsId name lat lon vehicleMode }
    }
    ```
  - `TransitPatternPort`(:28 `stops` 아래): `List<TransitStop> stopsInBox(double minLat, double minLon, double maxLat, double maxLon, RoutingDeadline deadline);`
  - `OtpTransitPatternAdapter`(:73 `stops` 아래) 구현.
    - 파서 `parseStopsInBox(JsonNode)`: `data.stopsByBbox` 배열을 `toStop`(:198) 으로 변환하고, null 원소·gtfsId 없는 원소는 건너뛴다.
  - fixture: 운영 OTP 에서 강남 0.1° 박스(37.4~37.5, 127.0~127.1)를 `StopsInBox` 로 받아 `src/test/resources/otp/track/stops-in-box-gangnam.json` 으로 저장한다.
    - 너무 크면 0.02° 박스로 줄인다. 단 SEB/GGB/ICB121000009 는 포함되게 한다.
  - 검증: `OtpTransitPatternAdapterTest` 에 파싱·operationName 테스트를 추가한다.
- [ ] 2. 타일 계산(순수) — `application/map/service/BusStopTiles.java`
  - `static String key(double lat, double lon)`
  - `static List<TransitStop> mergeSiblings(List<TransitStop> busStops)`
    - `TrackStopSiblings.groupKey/representativeRank/within`(:54/:66/:72) 를 쓴다. 규칙은 `TrackSelectionQueryService.siblingGroups`(:87) 와 같다.
    - 반환은 대표 + 묶이지 않은 것.
  - `static Map<String, List<TransitStop>> byTile(List<TransitStop>)` — TreeMap.
  - `static String sha12(byte[])`
  - 검증: `BusStopTilesTest`
    - key: 강남 `374_1270`, 경계 37.5 → `375`, 제주 33.9 → `339`
    - 합치기: SEB 대표, 300m 밖 분리, 지방(ADB/TSB) 미합치기
    - 대표 타일 배치
    - 해시 안정성
- [ ] 3. 서비스 — `application/map/port/in/GetBusStopTilesUseCase.java` + `application/map/service/NationalBusStopTileService.java`
  - DTO `application/map/dto/BusStopTileSnapshot`
    - `record(String version, String graphVersion, int count, Map<String,String> tileVersions, Map<String, byte[]> tileBodies, byte[] manifestJson)`
  - UseCase
    - `Optional<BusStopTileSnapshot> current()` — 없으면 빈 값 → 컨트롤러가 503
    - `void rebuild()`
  - 빌드 단계:
    - 블록 루프: 위도 33~39, 경도 124~132 를 1°씩 돈다(48칸).
      - 칸마다 `RoutingDeadline.of(clock, 10_000)` 을 만들고 `OtpConcurrencyGate.tryAcquire(deadline, "api")` 를 잡는다(:32, release :35).
      - 못 잡으면 200ms 쉬고 최대 50회 재시도한다. 그래도 못 잡으면 빌드를 실패로 끝낸다.
      - `port.stopsInBox(...)` 를 부르고 BUS 만 남긴다.
      - 블록 경계 중복은 gtfsId 로 제거한다(stopsByBbox 는 경계 포함).
    - `BusStopTiles.mergeSiblings` → `byTile` → 타일 본문을 만든다.
      - arsId: `BusStopRepository.findAllForSnapshot()`(:59) 로 `stopId→arsId` 를 1회 만든다.
      - SEB id 는 `OtpGtfsIds` 역방향(접두사 `1:BS_TAGO_SEB` 제거 → 9자리)으로 서울 id 를 얻는다.
    - manifest 직렬화(ObjectMapper).
    - `AtomicReference` 에 교체해 넣는다. 로그: `버스정류장 타일 빌드 graph={} tiles={} stops={} bytes={} ms={}`.
  - single-flight: `synchronized rebuild`. 이미 같은 graphVersion 스냅샷이 있으면 건너뛴다.
  - 실패(`TransitPatternUnavailableException`·게이트 포화): 이전 스냅샷을 유지하고 WARN 로그만 남긴다.
  - graphVersion 변경 감지: `current()` 가 `OtpRoutingProperties.getGraphVersion()`(:43) 과 다르면 비동기 재빌드를 건다(그동안은 옛 스냅샷을 준다).
  - 검증: `NationalBusStopTileServiceTest`(포트·게이트 mock)
    - 정상 빌드 → manifest·타일
    - 한 블록 실패 → 이전 스냅샷 유지
    - graphVersion 변경 → 재빌드
    - 경계 중복 제거
- [ ] 4. 워밍업 — `application/map/service/NationalBusStopTileWarmup.java`
  - `BusStopSnapshotWarmup` 패턴: `@Profile("!worker")`, `ApplicationReadyEvent`.
  - 별도 스레드(`CompletableFuture.runAsync` 또는 기존 TaskExecutor)에서 `rebuild()` 를 돌린다. 부팅을 막지 않는다.
- [ ] 5. 컨트롤러 — `MapBusStopController` 에 추가
  - `GET /v2/tiles`, `GET /v2/tiles/{key}`.
  - 기존 `matches`(:68)·`normalizeETag`(:81)·`quote`(:92)·`CACHE_CONTROL`(:39) 를 재사용한다.
  - 503 은 `ResponseEntity.status(503).header("Retry-After","30")`.
  - 검증: `MapBusStopControllerTest`(기존 있으면 확장, 없으면 WebMvcTest)
    - manifest 200·ETag·304
    - 503
    - 타일 200·304·404·400
    - 컬럼 순서
- [ ] 6. 문서
  - `docs/plan/map-bus-stop-tiles/HANDOFF.md`(모바일):
    - 시작 시 manifest 를 ETag 로 받는다.
    - 화면 bbox + 패딩에서 key 를 계산한다.
    - 로컬 tileVersion 이 다른 타일만 받는다.
    - 타일별로 로컬 저장한다(LRU 상한 권장).
    - 마커 키는 gtfsStopId 이고, 노선은 `/api/journeys/track/stops/{gtfsStopId}/patterns`(#327) 로 조회한다.
    - #160 HANDOFF 클라 4항목을 그대로 적용한다.
    - v1 은 당분간 유지한다.
  - `implementation_note.md` 에 섹션을 append 한다(확정·함정·검증).
- [ ] 7. 전체 관련 테스트 → 커밋(staged 검증) → PR(#326 링크) → 머지 → api 배포
  - 배포 절차: 클론에서 bootJar → 마이그레이션 개수 diff → `app.jar` 백업 → scp → `DEPLOY_REVISION=<sha> ./deploy.sh api`.

## 검증 명령
```
./gradlew test --tests '*BusStopTile*' --tests '*MapBusStop*' --tests '*OtpTransitPatternAdapterTest' --tests '*Track*'
```
- 컨텍스트 IT 가 필요하면 일회용 DB/Redis 로 돌린다: `POSTGRES_PORT=55432`(st-pg), `REDIS_PORT=56379`(st-redis), 기본값 없는 env 는 더미.

배포 후 운영 확인:
- manifest `tiles` 개수 ≈ 1,158, `count` ≈ 205,947
- `374_1270` 에 `1:BS_TAGO_SEB121000009` 만 있고 GGB/ICB 는 없음
- 304 동작
- 빌드 로그 ms
- api 힙(Grafana `jvm_memory_used_bytes{area="heap"}`) 피크 < 600MB

## 리스크·가드
- **힙:** 블록 조회로 피크를 줄인다. 스냅샷 상주는 약 11MB 다. 배포 후 Grafana 로 확인하고, 문제가 있으면 블록 크기를 0.5° 로 줄인다.
- **OTP 경합:** 빌드 중 검색 permit 을 1개 점유한다(블록당 짧게). 피크 시간 재빌드는 graphVersion 변경 때만 일어난다.
- **지방 피드 중복:** TSB(시외) 등 다른 피드가 같은 위치에 겹치는 것(15m 안 6,956쌍)은 이번 범위 밖이다. 마커가 겹쳐 보일 수 있다 → 후속 이슈.
- 마이그레이션 없음. 플래그 없음(엔드포인트 추가뿐).

## Opus 리뷰 반영 (2026-10-10, 판정: 수정 후 승인) — 위 본문보다 우선한다
1. **(High) graphVersion 감지 삭제.** env 정적값이라 런타임에 바뀌지 않는다.
   - 대신 `@Scheduled(fixedDelay=60s)` 점검 1개를 둔다(`@EnableScheduling` 은 이미 있다, `@Profile("!worker")`).
   - 스냅샷이 없으면 빌드한다. 마지막 빌드에서 24시간이 지났으면 다시 빌드한다.
   - 부팅 워밍업은 이 점검을 즉시 1회 실행하는 것으로 갈음한다.
   - 내용 해시라 바뀐 게 없으면 클라이언트 비용은 0이다.
2. **(High) 첫 빌드 실패.** 1번 점검이 60초마다 다시 시도하므로 503 은 스스로 복구된다.
   - 테스트: 첫 빌드 실패 → 다음 점검에서 게시.
3. **(High) 게이트.** `tryAcquire` 는 deadline 까지 대기하는 방식이다(`RedisOtpConcurrencyGateAdapter:73-104`).
   - acquire 는 `RoutingDeadline.of(clock, 2_000)` 로 1회만 시도한다. role 은 `"map-build"` 다.
   - 잡은 뒤 OTP 호출에는 새 `RoutingDeadline.of(clock, 10_000)` 을 쓴다(lease 12초보다 작다).
   - 블록당 acquire 실패는 최대 5회 재시도(1초 간격)하고, 그래도 안 되면 그 빌드를 포기한다. 다음 점검이 다시 시도한다.
   - 블록 사이에 100ms 를 쉬어 검색 permit 을 독점하지 않는다.
4. **(High) 이상치 가드.**
   - 합계 < 150,000 이거나 이전 count 의 80% 미만이면 게시하지 않는다. WARN 을 남기고 이전 스냅샷을 유지한다.
   - 테스트로 고정한다(가드 하한은 서비스 생성자 상수로 두고, 테스트에서는 작게 주입한다).
5. **(Medium) 캐시.**
   - manifest: `Cache-Control: no-cache` + ETag(매번 304 재검증).
   - 타일: `GET /v2/tiles/{key}?v={tileVersion}`.
     - `v` 가 현재 tileVersion 과 같으면 `max-age=31536000, public, immutable`.
     - `v` 가 없거나 다르면 `no-cache` + ETag 로 현재 본문을 준다.
6. **(Medium) single-flight.** `AtomicBoolean building.compareAndSet` 으로 이미 빌드 중이면 건너뛴다. synchronized 대기는 쓰지 않는다.
7. **(Medium) 반올림 먼저.** 좌표를 5자리로 반올림한 뒤에 key 를 계산한다(`Math.floor(lat*10)`).
   - HANDOFF 에 "`*10` 후 floor, `/0.1` 금지"를 적는다.
   - 숫자는 `BigDecimal.valueOf(Math.round(x*1e5)).movePointLeft(5)` 로 직렬화해 JDK 의 Double.toString 차이를 피한다.
8. **(Medium) arsId.** 역방향 함수를 새로 만들지 않는다. `findAllForSnapshot()` 각 행에 `OtpGtfsIds.seoulBusStop(stopId)` 를 적용해 `gtfsId → arsId` 맵을 만든다.
9. **(Low)**
   - HANDOFF 에 적는다: "manifest 에 있는 key 만 요청한다(빈 타일은 404)", "노선 조회 `/api/journeys/**` 는 현재 인증 필요(SecurityConfig:60)".
   - 루프 범위 상수 옆에 실측 bbox 를 주석으로 단다.
   - 300m 밖 멤버를 따로 내는 것은 근처 목록과 같은 의도된 동작이다.
   - 컨트롤러 테스트에 `W/` ETag 304 를 넣는다.
   - 기존 `MapBusStopControllerTest` 에 새 UseCase MockBean 을 추가한다.

## 구현 메모 (실제 반영)
- 4번 워밍업 클래스는 만들지 않았다. `NationalBusStopTileService.check()` 의 `@Scheduled(initialDelay=0, fixedDelay=60s)` 가 부팅 직후 빌드를 겸한다.
- 빌드는 전용 데몬 스레드 1개(`bus-stop-tiles`)에서 돈다. 공용 스케줄러 풀(3)을 막지 않는다.
- 타일 key 검증 정규식은 `BusStopTileSnapshot.isKey` 에 있다(컨트롤러가 service 를 import 하지 않게).
