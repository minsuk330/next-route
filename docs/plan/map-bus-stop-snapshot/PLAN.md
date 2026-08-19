# 지도 버스정류장 스냅샷 API (map-bus-stop-snapshot)

티어: **M** (엔드포인트 1개 + 캐시 + DTO, PR 1개, 마이그레이션 없음)
브랜치: `feat/map-bus-stop-snapshot` (fresh main 기준)

---

## 배경 / 문제

클라(네이버 지도 SDK)가 지도 화면 이동마다 `GET /api/nearby/bus-stops?lat&lng&limit` 를 호출해
정류장 마커를 그린다. 이 API 는 지도용 계약이 아니라서 마커가 팬/줌마다 끊겨 보인다.

실측 원인 (코드 좌표는 아래 `### 좌표` 참조):

1. `LIMIT :lim` — "중심에서 가까운 20개"(기본 20, 최대 50) 시맨틱. 지도가 필요한 건 "화면 안 전부".
   줌아웃하면 화면은 넓은데 중심 20개만 와서 가장자리가 빈다.
2. 반경이 줌과 무관 (500m 고정 시작). 줌아웃 = 화면 중앙 한 점, 줌인 = 화면 밖 것도 옴.
3. `searchWithExpanding` 이 결과가 비지 않으면 즉시 반환 → 반경이 정류장 밀도에 따라 500m~3km 로
   요청마다 달라진다. 결과 집합 크기가 튄다.
4. 클라가 응답을 전체 교체 → 100m 팬 하면 다른 20개가 와서 아까 보이던 마커가 사라진다.
5. bbox/좌표가 매 요청 유니크 → 캐시 불가, 응답 순서 뒤집히면 과거 응답이 최신 화면을 덮는다.

## 결정: 정적 데이터는 네트워크에서 뗀다

VPS 실측 (2026-08-19, `bus_stop`):

```
total 12986 | geom not null 12986 | ars_id null 0 | stop_name null 0
deleted_at not null 0 | max(updated_at) 2026-08-15 11:40:11
compact 페이로드 추정 753 kB (gzip 후 ~250~300 kB)
```

서울 전용 1.3만 행 = 앱이 통째로 들고 있어도 되는 크기. 앱 시작 1회 받고 로컬에 두면
팬/줌 요청이 0회가 되어 위 1~5가 전부 사라진다.

### 기각한 대안

- **벡터 타일 MVT (`ST_AsMVT`)** — 네이버 지도 SDK 가 외부 벡터 타일 소스를 지원하지 않는다.
- **bbox 엔드포인트 + 타일 스냅 캐시** — 스냅샷이 성립하는 크기라 서버 왕복 자체가 불필요.
  전국 데이터로 넓히면 이쪽으로 승격. (아래 `## 나중에` 참조)
- **CSV / `text/csv` 본문** — 최소 크기지만 정류장명 콤마·쿼트 이스케이프 규칙을 모바일이
  직접 구현해야 한다. JSON array-of-arrays 가 크기 대비 파싱 리스크가 낫다.
- **Redis 캐시** — 페이로드가 정적이고 api 컨테이너 1개. 힙 1MB 미만이라 홉만 늘어난다.
- **`ShallowEtagHeaderFilter`** — 매 요청 본문 전체를 만들고 MD5 를 돌린다. 304 로 **본문 생성을
  건너뛰는 것**이 이 기능의 핵심이라 정반대. ETag/If-None-Match 를 직접 처리한다.

---

## 계약 (사람이 반드시 볼 부분)

### 요청

```
GET /api/map/bus-stops/snapshot
If-None-Match: "12986-1786000000"      (선택)
```

인증 없음. `SecurityConfig` 의 `anyRequest().permitAll()` 로 떨어진다
(`common/security/SecurityConfig.java:64`). 공개 정적 데이터라 의도된 것 — **매처를 추가하지 말 것.**

### 응답 200

```json
{
  "version": "12986-1786000000",
  "count": 12986,
  "columns": ["stopId", "arsId", "stopName", "lat", "lng"],
  "stops": [
    ["100000001", "01001", "종로2가사거리", 37.57062, 126.98814]
  ]
}
```

헤더:

```
ETag: "12986-1786000000"
Cache-Control: public, max-age=3600, must-revalidate
Content-Type: application/json
```

- `stops` 는 **array-of-arrays**. 키 반복을 없애 크기를 줄이면서 표준 JSON 파서로 읽힌다.
  순서는 `columns` 가 정의한다. `columns` 를 응답에 넣는 이유 = 클라가 인덱스를 하드코딩하지
  않고 검증할 수 있게.
- `lat`/`lng` 는 **소수 5자리 절삭**(≈1.1m). 마커 배치 정확도에 충분하고 페이로드가 줄어든다.
- 행 순서는 `stop_id ASC` 고정. 같은 데이터면 바이트가 같아 ETag 가 안정적이고 클라 디핑이 쉽다.

### 응답 304

`If-None-Match` 가 현재 `version` 과 같으면 본문 없이 `304`.
**이때 정류장 조회 쿼리도, 직렬화도 하지 않는다.** (버전 프로브 쿼리만 탄다)

### version 규칙

```
version = "<활성 정류장 수>-<max(updated_at) epochSecond>"
```

- 프로브 쿼리: `SELECT count(*), coalesce(extract(epoch FROM max(updated_at))::bigint, 0) FROM bus_stop WHERE deleted_at IS NULL AND latitude IS NOT NULL AND longitude IS NOT NULL`
- 행 추가/수정/소프트삭제 전부 이 둘 중 하나를 움직인다 (`BaseEntity` 의 `@LastModifiedDate`,
  `common/entity/BaseEntity.java:28`).
- `max(updated_at)` 이 null (표가 빔) 이면 version = `"0-0"`, `stops` 는 빈 배열, 200.
- epoch 는 SQL 에서 계산한다 — `timestamp without time zone` 을 Java 로 꺼내면 존 해석이 끼어든다.

### 스냅샷 대상 행 (필터 계약)

```sql
WHERE deleted_at IS NULL
  AND latitude IS NOT NULL
  AND longitude IS NOT NULL
```

기존 `findNearby` 는 `deleted_at` 을 안 보고 `geom IS NOT NULL` 을 본다
(`domain/bus/repository/BusStopRepository.java:25`). 스냅샷은 `geom` 을 쓰지 않으므로
좌표 컬럼으로 판단한다. 현재 프로덕션은 12986행 전부 geom·좌표 있고 소프트삭제 0건이라 두
필터의 결과가 같다 — **다르면 스냅샷 쪽(`deleted_at IS NULL`)이 맞다.** 기존 API 는 이 PR 에서
건드리지 않는다.

### 건드리지 않는 것

`/api/nearby/bus-stops`, `/api/nearby/subway-stations`, `BusStopResolveController` — 검색/resolve
용도로 계속 쓴다. 지도용과 용도를 분리하는 것이 이 PR 의 결정이다.

---

## 작업 순서

- [ ] **0. 브랜치** — `git fetch origin && git checkout -b feat/map-bus-stop-snapshot origin/main`
      커밋 직후 `git log -2` 로 부모가 origin/main 인지 확인 (작업 트리 공유 시 브랜치 드리프트 있음)

- [ ] **1. 리포지토리 쿼리 2개 추가** — `src/main/java/watoo/grd/nextroute/domain/bus/repository/BusStopRepository.java`

  ```java
  @Query(value = """
          SELECT stop_id AS stopId, ars_id AS arsId, stop_name AS stopName,
                 latitude, longitude
          FROM bus_stop
          WHERE deleted_at IS NULL AND latitude IS NOT NULL AND longitude IS NOT NULL
          ORDER BY stop_id
          """, nativeQuery = true)
  List<BusStopSnapshotProjection> findAllForSnapshot();

  @Query(value = """
          SELECT count(*) AS stopCount,
                 coalesce(extract(epoch FROM max(updated_at))::bigint, 0) AS maxUpdatedEpochSec
          FROM bus_stop
          WHERE deleted_at IS NULL AND latitude IS NOT NULL AND longitude IS NOT NULL
          """, nativeQuery = true)
  BusStopSnapshotVersionProjection probeSnapshotVersion();
  ```

  신규 projection 2개 (같은 패키지, `NearbyBusStopProjection` 와 같은 모양):

  ```java
  public interface BusStopSnapshotProjection {
      String getStopId();  String getArsId();  String getStopName();
      Double getLatitude();  Double getLongitude();
  }

  public interface BusStopSnapshotVersionProjection {
      long getStopCount();  long getMaxUpdatedEpochSec();
  }
  ```

  `updated_at` 은 `BaseEntity` 가 `LocalDateTime` 이라 DB 컬럼이 `timestamp without time zone` 이다.
  Java 로 시각 객체를 꺼내면 존 해석이 끼어들고 드라이버 매핑도 흔들리므로 **epoch 변환을 SQL 에서**
  끝낸다 (`extract(epoch from ...)`). 표가 비면 `coalesce` 로 0 → version `"0-0"`.
  이 매핑이 실제로 되는지는 6단계 IT 로 확인할 것 — 추측으로 넘기지 말 것.

- [ ] **2. 도메인 서비스 경유** — `domain/bus/service/BusDataService.java` 에 위 두 쿼리를 위임하는
      메서드 추가. 기존 `findNearbyStops` 가 이미 이 패턴이라 계층을 맞춘다.

- [ ] **3. application 계층** — 신규 패키지 `application/map/`

  - `port/in/GetBusStopSnapshotUseCase.java`
    ```java
    public interface GetBusStopSnapshotUseCase {
        /** 현재 스냅샷 버전만 계산 (본문 생성 없음). 304 판단용. */
        String currentVersion();
        /** version + 직렬화된 JSON 바이트. 버전이 같으면 캐시된 바이트를 그대로 준다. */
        BusStopSnapshotPayload snapshot();
    }
    ```
  - `dto/BusStopSnapshotPayload.java` — `record BusStopSnapshotPayload(String version, byte[] json)`
  - `service/BusStopSnapshotService.java`
    - `AtomicReference<BusStopSnapshotPayload> cached`
    - 버전 프로브 결과를 **60초 memoize** (`AtomicReference<VersionProbe(version, Instant probedAt)>`).
      304 응답이 요청마다 DB 를 때리지 않게 하는 것이 목적.
    - `snapshot()`: 현재 버전과 `cached.version` 이 같으면 그대로 반환, 다르면 재빌드 후 CAS 저장.
    - 직렬화: `ObjectMapper` 로 **한 번** 만들어 `byte[]` 로 캐시. 요청마다 12986행을 다시
      직렬화하지 않는다 (이게 DTO 대신 바이트를 캐시하는 이유).
    - 좌표: `Math.round(v * 1e5) / 1e5`.
    - `columns` 는 상수 `List.of("stopId","arsId","stopName","lat","lng")`.
    - `arsId` 가 null 이면 JSON `null` 로 그대로 둔다 (빈 문자열로 바꾸지 않는다 — 클라가 구분해야 함).
  - `service/BusStopSnapshotWarmup.java` — `@Profile("!worker")` + `ApplicationReadyEvent` 리스너로
    `snapshot()` 1회 호출. worker 프로필(compose `SPRING_PROFILES_ACTIVE: prod,worker`)에서는
    쓰지 않으므로 힙만 먹는다. 워밍업 실패는 `warn` 로그만 남기고 부팅을 막지 않는다.

- [ ] **4. 컨트롤러** — `infrastructure/adapter/in/api/map/MapBusStopController.java`

  ```java
  @RestController
  @RequestMapping("/api/map/bus-stops")
  @RequiredArgsConstructor
  @Tag(name = "[공용] 지도 정류장 스냅샷")
  public class MapBusStopController {

      private static final CacheControl CACHE_CONTROL =
              CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic().mustRevalidate();

      private final GetBusStopSnapshotUseCase useCase;

      @GetMapping("/snapshot")
      @Operation(summary = "서울 전체 버스정류장 스냅샷 (ETag 재검증)")
      public ResponseEntity<byte[]> snapshot(
              @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
          String version = useCase.currentVersion();
          if (matches(ifNoneMatch, version)) {
              return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                      .eTag(quote(version)).cacheControl(CACHE_CONTROL).build();
          }
          BusStopSnapshotPayload payload = useCase.snapshot();
          return ResponseEntity.ok()
                  .eTag(quote(payload.version())).cacheControl(CACHE_CONTROL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(payload.json());
      }
  }
  ```

  `matches` 는 `W/` 프리픽스와 쿼트를 벗기고 비교. `*` 도 매치로 취급.
  프로브 시점과 빌드 시점 사이에 버전이 바뀔 수 있으니 **응답 ETag 는 `payload.version()` 을 쓴다**
  (프로브 값이 아니라). 그러면 클라가 다음 요청에 실제 본문의 버전을 보낸다.

- [ ] **5. gzip 활성화** — `src/main/resources/application.yaml`

  ```yaml
  server:
    compression:
      enabled: true
      mime-types: application/json
      min-response-size: 2KB
  ```

  ⚠ **전역 side effect**: 2KB 넘는 모든 JSON 응답이 gzip 된다 (경로검색 응답 등 포함).
  일반적으로 이득이지만 리뷰에서 의식적으로 확인할 항목. 현재 `application.yaml` 에 `server:`
  블록이 없으므로 새로 추가한다. nginx 가 앞단에서 이미 gzip 하면 이중압축은 아니다(Spring 이
  `Content-Encoding` 있는 응답을 다시 압축하지 않음).

- [ ] **6. 테스트** — 계약 하나당 최소 1개

  - `src/test/java/.../infrastructure/adapter/in/api/map/MapBusStopControllerTest.java`
    (`@WebMvcTest(MapBusStopController.class)` + `@AutoConfigureMockMvc(addFilters = false)`,
    `@MockBean GetBusStopSnapshotUseCase` — 기존 `BusArrivalControllerTest` 와 같은 모양)
    - 200: `version`/`count`/`columns`/`stops[0][0]` jsonPath, `ETag` 헤더, `Cache-Control` 헤더
    - `If-None-Match` 일치 → 304 + 본문 빈 문자열 + `snapshot()` **미호출** 검증 (`verify(never())`)
    - `If-None-Match` 불일치 → 200
    - `If-None-Match: W/"<version>"` (weak) → 304
  - `src/test/java/.../application/map/service/BusStopSnapshotServiceTest.java` (순수 단위, repo mock)
    - version 포맷 `"<count>-<epochSec>"`
    - 좌표 5자리 절삭
    - 버전 동일한 두 번째 호출에서 `findAllForSnapshot()` 이 **한 번만** 호출됨 (캐시 히트)
    - 버전 변경 시 재빌드됨
    - 표가 빌 때 `"0-0"` + `stops: []`
    - `arsId` null 이 JSON null 로 나감
  - `src/test/java/.../domain/bus/repository/BusStopSnapshotQueryIT.java`
    — `deleted_at IS NOT NULL` 행과 좌표 null 행이 스냅샷에서 빠지는지, `stop_id ASC` 정렬,
    그리고 **`probeSnapshotVersion()` 의 `Instant` 매핑이 실제로 되는지** 확인.
    `testsupport/AbstractJourneyOutboxPostgresIT` 와 같은 정적-컨테이너 패턴으로 새 base 를 만들고
    (`postgres:17`, geom 불필요) init script `src/test/resources/db/it/bus-stop-it-schema.sql` 에
    `bus_stop` 표만 만든다. **DDL 복제본이 되지 않게 컬럼은 실제 마이그레이션과 대조할 것.**

- [ ] **7. 모바일 인계 문서** — `docs/plan/map-bus-stop-snapshot/HANDOFF.md`
      (아래 `## 모바일 인계 내용` 그대로 옮겨 작성)

- [ ] **8. 구현 노트** — 루트 `implementation_note.md` 에 append.
      `### 확정` 계약/버전 규칙, `### 함정` 발견한 것, `### 좌표`, `### 검증` 실행 증거.

- [ ] **9. 커밋 & PR** — 플랜 파일 3개 포함해 커밋. PR 본문에 계약(요청/응답/304/version 규칙)과
      전역 gzip side effect 명시.

---

## 검증

```bash
./gradlew compileJava compileTestJava
./gradlew test --tests '*MapBusStopControllerTest*' \
               --tests '*BusStopSnapshotServiceTest*' \
               --tests '*BusStopSnapshotQueryIT*'
```

수동 확인 (로컬 dev 프로필 기동 후):

```bash
# 200 + 크기 + gzip 실측
curl -s -H 'Accept-Encoding: gzip' -D /tmp/h1 -o /tmp/snap.gz \
  http://localhost:8080/api/map/bus-stops/snapshot
grep -iE 'etag|content-encoding|content-length|cache-control' /tmp/h1
# 304 확인 (위 ETag 값 사용)
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'If-None-Match: "<위 ETag>"' http://localhost:8080/api/map/bus-stops/snapshot
```

완료 기준:
- 위 테스트 전부 green (기존부터 깨져 있던 실패는 "기존 무관" 라벨로 구분)
- 200 응답의 `count` 가 프로덕션 12986 과 같은 자리수, gzip 후 `Content-Length` 400 kB 이하
- 같은 ETag 재요청이 304, 그때 정류장 조회 쿼리 로그가 안 찍힘

배포: **마이그레이션 없음.** VPS 수동배치 대상 아님. api 컨테이너 재배포만.

---

## 모바일 인계 내용 (HANDOFF.md 로 옮길 것)

서버는 정적 스냅샷만 준다. 끊김을 없애는 나머지 절반은 클라 렌더링이다.

1. **앱 시작 1회 fetch → 로컬 영속화**(파일 또는 SQLite). 이후 기동 시 저장된 `version` 을
   `If-None-Match` 로 보내 304 면 로컬 것 사용. 지도 화면 진입마다 다시 받지 말 것.
2. **메모리 그리드 인덱스** — 셀 크기 0.005°(≈500m)로 `stopId` 를 담은 해시맵. 카메라 idle 이벤트에서
   화면 bbox 와 겹치는 셀만 꺼내 그린다. 네트워크 호출 없음.
3. **마커는 `stopId` upsert** — clear-and-redraw 금지. 화면+패딩(1.5배) 밖으로 나간 마커만 제거.
   이게 "끊겨 보임"의 직접 원인이었다.
4. **줌 게이팅 필수** — 12986개 마커 동시 렌더는 불가. 낮은 줌에서는 숨기거나 클러스터.
   안드로이드 네이버 SDK 는 클러스터링 유틸(`Clusterer`)이 있으니 **버전 지원 여부를 먼저 확인**하고,
   iOS 에 대응 API 가 없으면 위 2번 그리드 셀로 직접 묶는다. 임계 줌 값은 실기기에서 프레임 보고 정한다.
5. **도착정보는 분리** — 마커는 정적, 도착정보는 마커 탭 시 기존 arrival API 단건 조회.
6. `/api/nearby/bus-stops` 는 지도 마커용으로 **더 이상 호출하지 않는다**. 검색/resolve 경로에서만 유지.

---

## 좌표

- `infrastructure/adapter/in/api/nearby/NearbyController.java:29` — 기존 지도용으로 오용된 엔드포인트
- `application/nearby/service/NearbyTransitService.java:42` — `getNearbyBusStops`
- `application/nearby/service/NearbyTransitService.java:119` — `searchWithExpanding` (밀도 의존 반경)
- `application/nearby/service/NearbyTransitService.java:27-30` — `INITIAL_RADIUS 500 / MAX 3000 / LIMIT 20`
- `domain/bus/repository/BusStopRepository.java:25` — `findNearby` (`geom IS NOT NULL`, `LIMIT`)
- `domain/bus/entity/BusStop.java` — stopId/stopName/arsId/latitude/longitude (geom 은 엔티티에 없는 DB 전용 컬럼)
- `common/entity/BaseEntity.java:28` — `@LastModifiedDate updatedAt`, `:31` `deletedAt`
- `common/security/SecurityConfig.java:64` — `anyRequest().permitAll()`
- `compose.yaml:74-108` — api(`prod,api`) / worker(`prod,worker`) 프로필 분리

## 나중에 (이 PR 범위 아님)

- 전국 정류장으로 넓히면 스냅샷이 안 맞는다 → 타일 스냅 bbox 엔드포인트(`ST_MakeEnvelope`) +
  Redis/HTTP 캐시로 승격. 그때도 클라의 upsert 병합·줌 게이팅은 그대로 재사용된다.
- 지하철역도 같은 스냅샷 형태로 낼 수 있다 (행 수가 훨씬 적어 더 쉽다). 별건 PR.
