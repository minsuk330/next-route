# 검색 결과 스냅샷 — 구현 계획

- 티어: **M** (포트 하나 + 어댑터 하나 + 응답 필드 3개. 바뀔 코드 모양이 미리 그려짐)
- 브랜치: `feat/route-search-snapshot` (fresh main 기준)
- 상위 맥락: [journey-navigation/PHASE-1.md](../journey-navigation/PHASE-1.md) ②
- 선행 의존: 없음 (인증 작업과 나란히 진행 가능)
- 후속 의존: `journey-persistence`

## 1. 한 줄 요약

**검색 API 가 결과를 돌려줄 때 그 결과를 Redis 에 10분 저장하고, 응답에 `searchSessionId`·`pathKey`·`expiresAt` 을 추가한다.**

## 2. 왜 필요한가

### 지금

```
GET /api/route/search?from=강남&to=판교
→ { "paths": [ 경로0, 경로1, 경로2 ] }
```

경로 하나는 `subPaths` 수십 개짜리 큰 JSON 이다. 앱이 사용자에게 보여주고 끝난다.

### 여정 기능이 붙으면

사용자가 "이 경로로 안내 시작"을 누르면 서버가 **어느 경로인지** 알아야 한다. 방법은 둘이다.

| 방법 | 문제 |
|---|---|
| 앱이 고른 경로 JSON 을 통째로 `POST` | 서버가 그게 자기가 만든 경로인지 확인할 수 없다. 앱이 임의의 좌표·시간을 넣어도 통과한다. 요청 크기도 크다 |
| 앱이 "아까 그 검색의 1번"이라고만 전달 | 서버가 원본을 갖고 있어야 한다 ← **이 작업** |

### 이 작업 후

```
GET /api/route/search?from=강남&to=판교
→ {
    "searchType": 0,
    "searchSessionId": "0b6f...",            // 신규
    "expiresAt": "2026-07-28T10:50:00Z",     // 신규
    "paths": [
      { "pathKey": "0", "pathType": 1, "info": {}, "subPaths": [] },   // pathKey 신규
      { "pathKey": "1", ... }
    ],
    "meta": { "outcome": "full", "degraded": [] }
  }
```

다음 PR(`journey-persistence`)의 여정 시작이 이렇게 된다.

```
POST /api/journeys
{ "searchSessionId": "0b6f...", "pathKey": "1" }
```

## 3. 범위

**한다**

- 검색 결과를 Redis 에 저장하고 되찾는 포트·어댑터
- `RouteSearchResult` 에 `searchSessionId`, `expiresAt` 추가
- `PathResult` 에 `pathKey` 추가

**안 한다**

- `POST /api/journeys` 자체 (→ `journey-persistence`)

## 4. 설계

### 4.1 저장 형태

| 항목 | 값 |
|---|---|
| Redis 키 | `route:snapshot:{searchSessionId}` |
| `searchSessionId` | 서버가 만드는 UUIDv4. 앱이 정하지 않는다 |
| 값 | 보강까지 끝난 최종 `RouteSearchResult` 를 JSON 으로 직렬화한 것 |
| TTL | 기본 600초 (`RouteSnapshotProperties.ttlSeconds`) |
| `pathKey` | `paths` 리스트의 인덱스를 문자열로 쓴 것 (`"0"`, `"1"`, …) |

`pathKey` 에 서명을 붙이지 않는다. `searchSessionId` 가 이미 서버가 만든 추측 불가능한 값이고 값은 서버가 저장한 원본이다. 앱이 `pathKey` 를 바꿔도 **같은 검색 결과 안의 다른 경로**를 고르는 것뿐이라 얻을 게 없다.

키에 `userId` 를 넣지 않는다. `searchSessionId` 가 검색 1회마다 새로 만드는 UUID 라 이미 전역에서 겹치지 않고, 익명 검색은 `userId` 자체가 없어 키를 조립할 수 없다. 소유자는 §4.2 처럼 값 안에 담는다.

### 4.1.1 폴리라인 포함 — 크기는 나중에 최적화

**검색 결과를 자르지 않고 통째로 저장한다.** 폴리라인(`SubPathResult.polyline`, `laneGraphics`)도 그대로 담는다.

Redis 상주량은 대략 `스냅샷 크기 × 초당 검색 수 × TTL` 이다. 스냅샷이 50KB 이고 초당 10건이면 6000개가 항상 떠 있어 300MB 쯤 된다. 이 서버에는 Redis·PostgreSQL·관측 스택이 함께 올라가 있으므로 무시할 수 없는 양이다.

그래도 1차에서는 자르지 않는다. 실제 응답 크기를 재기 전에 최적화하면 근거 없는 추측이 된다. §6.3 에서 크기를 측정해 기록하고, 문제가 되면 D13 의 선택지 중에서 고른다.

### 4.2 소유자 기록

`/api/route/search` 는 로그인하지 않아도 호출할 수 있다. `RouteSearchController.java:36` 이 `@AuthenticationPrincipal Long userId` 를 받되 익명이면 `null` 이다.

그래서 스냅샷에 `ownerUserId` 를 nullable 로 함께 저장한다. 실제 검증은 여정을 시작하는 `journey-persistence` 가 한다.

| `ownerUserId` | 여정 시작 |
|---|---|
| `null` (익명 검색) | 누구나 사용 가능 |
| 값이 있고 인증 `userId` 와 같음 | 사용 가능 |
| 값이 있고 인증 `userId` 와 다름 | `403 SNAPSHOT_OWNER_MISMATCH` |

### 4.3 저장 실패 시 검색은 성공시킨다

`/api/route/search` 는 이 서비스에서 호출량이 가장 많은 엔드포인트다. 여정 기능 때문에 느려지거나 실패하면 안 된다.

- 저장은 응답 반환 직전 Redis `SET` 한 번. 재시도하지 않는다
- Redis 장애로 저장이 실패해도 응답은 `200`
- `searchSessionId` 를 비우고 `meta.degraded` 에 `{"component":"journey_snapshot","reason":"upstream_error"}` 를 추가한다

`meta.degraded` 는 이미 있는 계약이다(`application/route/dto/SearchMeta.java`). `component`·`reason` 은 지표 태그로 쓰이므로 종류가 적은 고정 문자열이어야 한다.

## 5. 작업 순서

마이그레이션 없음.

### 5.1 포트·설정

- [ ] `application/route/port/out/RouteSearchSnapshotPort.java`

```java
public interface RouteSearchSnapshotPort {

    /** 검색 결과를 저장하고 서버가 만든 searchSessionId 를 반환한다. */
    String save(Long ownerUserId, RouteSearchResult result, Instant expiresAt);

    /** searchSessionId + pathKey 로 경로 하나를 조회한다. 만료·불일치면 empty. */
    Optional<StoredPath> find(String searchSessionId, String pathKey);

    record StoredPath(Long ownerUserId, PathResult path, Instant expiresAt) {}
}
```

- [ ] `application/route/config/RouteSnapshotProperties.java`

```java
@ConfigurationProperties(prefix = "route.snapshot")
public class RouteSnapshotProperties {
    private boolean enabled = true;
    private long ttlSeconds = 600;
}
```

같은 폴더에 설정 클래스가 이미 11개 있다. 이름·위치를 맞춘다.

### 5.2 어댑터

- [ ] `infrastructure/adapter/out/cache/route/RedisRouteSearchSnapshotAdapter.java`

```java
private static final String KEY_PREFIX = "route:snapshot:";
```

기존 관례를 그대로 따른다. 참고할 어댑터는 `infrastructure/adapter/out/cache/subway/RedisSubwayRealtimeCacheAdapter.java` 다.

- `RedisTemplate<String, String>` 과 `ObjectMapper` 를 생성자 주입한다. 두 빈 모두 이미 있다(`common/config/RedisConfig.java` — 키·값 모두 `StringRedisSerializer`)
- 저장: `objectMapper.writeValueAsString(...)` 후 `redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttl))`
- 조회: `opsForValue().get(key)` → `null` 이면 `Optional.empty()`, 아니면 역직렬화 후 `pathKey` 를 `int` 로 파싱해 `paths` 인덱스 접근. 범위 밖·파싱 실패면 `Optional.empty()`
- **예외를 밖으로 던지지 않는다.** 저장 실패는 반환값으로, 조회 실패는 `Optional.empty()` 로 흡수하고 `log.warn` 만 남긴다

`RedisSubwayRealtimeCacheAdapter.saveSnapshot` 은 직렬화 실패 시 `RuntimeException` 을 던지지만, 이 어댑터는 **던지지 않는다.** 검색 응답을 실패시키지 않는 것이 §4.3 의 요구사항이기 때문이다.

`infrastructure/adapter/out/api/bus/CachedSearchTimeBusAdapter.java` 는 Redis 어댑터가 아니라 캐시 포트를 쓰는 데코레이터다. 참고 대상이 아니다.

### 5.3 서비스

- [ ] `application/route/service/RouteSearchSnapshotRecorder.java`

```java
/**
 * 검색 결과에 searchSessionId·pathKey·expiresAt 을 채워 반환한다.
 * 저장이 실패하면 예외를 삼키고 원본에 degraded 표시만 추가한다(§4.3).
 */
public RouteSearchResult attach(Long ownerUserId, RouteSearchResult result);
```

### 5.4 응답 DTO

- [ ] `application/route/dto/PathResult.java` — `String pathKey` 추가

```java
public record PathResult(
        String pathKey,              // 신규. 스냅샷 저장 실패 시 null
        int pathType,
        PathInfo info,
        List<SubPathResult> subPaths,
        List<LaneGraphicResult> laneGraphics
) {}
```

호출부는 **19곳**이다(main 5, test 14).

```
main:
  application/route/service/RoutePolylineEnricher.java:123
  application/route/service/RouteSearchService.java:198
  application/route/service/WalkSegmentEnricher.java:304
  application/route/service/TransferArrivalEnricher.java:708
  infrastructure/adapter/out/api/odsay/OdSayApiAdapter.java:242
test: RouteSearchBulkheadIT, WalkSegmentEnricherTest, RoutePolylineEnricherTest,
      RouteSearchServiceTest, RouteSearchRealtimeDeadlineIT, TransferArrivalEnricherTest(5),
      RouteSearchTravelTimeEstimatorTest(3)
```

**19곳을 고치지 않는다.** `pathKey` 없이 만드는 보조 생성자를 두어 기존 호출부가 그대로 컴파일되게 하고, 스냅샷 저장 시점에 `withPathKey(String)` 로 채운다. `RouteSearchResult` 가 `meta` 에 이미 같은 방식을 쓴다.

```java
/** pathKey 없이 만드는 기존 호출부용. 스냅샷 저장 시 withPathKey 로 채운다. */
public PathResult(int pathType, PathInfo info,
                  List<SubPathResult> subPaths, List<LaneGraphicResult> laneGraphics) {
    this(null, pathType, info, subPaths, laneGraphics);
}

public PathResult withPathKey(String pathKey) {
    return new PathResult(pathKey, pathType, info, subPaths, laneGraphics);
}
```

- [ ] `application/route/dto/RouteSearchResult.java` — `searchSessionId`, `expiresAt` 추가 + `withSnapshot(...)`

`@JsonInclude(NON_NULL)` 을 붙여 값이 없으면 응답에서 필드가 사라지게 한다. 저장에 실패해도 기존 앱에 영향이 없다.

### 5.5 컨트롤러 연결

- [ ] `infrastructure/adapter/in/api/route/search/RouteSearchController.java:36-44`

```java
RouteSearchResult result = searchRouteUseCase.search(request);
result = snapshotRecorder.attach(userId, result);       // 신규
if (userId != null) {
    recordSearchHistoryUseCase.record(userId, request, result);
}
return ResponseEntity.ok(result);
```

`RouteSearchService.search(...)` 내부가 아니라 **컨트롤러**에서 붙인다. `withMeta` 가 컨트롤러 직전에 채워지는 기존 위치와 같고(`RouteSearchService.java:63`), 내부 중간 결과나 다른 호출부(`RouteSearchService.java:199`)를 오염시키지 않는다.

### 5.6 설정

- [ ] `application.yaml`

```yaml
route:
  snapshot:
    enabled: true
    ttl-seconds: 600
```

### 5.7 테스트용 Redis 컨테이너

**새 의존성을 추가하지 않는다.** 이미 있는 `org.testcontainers:junit-jupiter` 의 `GenericContainer` 로 충분하다.

```java
@Container
static GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

@DynamicPropertySource
static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
}
```

`com.redis:testcontainers-redis` 모듈은 쓰지 않는다. 버전 확인과 의존성 추가가 필요한데 얻는 건 편의 API 뿐이다.

## 6. 검증

### 6.1 테스트

- [ ] `RedisRouteSearchSnapshotAdapterTest` (Testcontainers Redis) — 저장·조회 왕복, TTL 만료 후 `empty`
- [ ] `RedisRouteSearchSnapshotAdapterTest` — 범위 밖 `pathKey`(`"99"`), 숫자 아닌 `pathKey`(`"abc"`) → `empty`
- [ ] `RouteSearchSnapshotRecorderTest` — Redis 예외 시 원본 결과 그대로 반환 + `journey_snapshot` degraded 표시
- [ ] `RouteSearchSnapshotRecorderTest` — `enabled=false` 면 저장 시도 없이 통과
- [ ] `RouteSearchControllerTest` — 익명·로그인 각각 `searchSessionId` 발급, 기존 응답 필드 불변
- [ ] 기존 `RouteSearchServiceTest` 등 — 회귀 없음

### 6.2 명령

```bash
./gradlew compileJava
./gradlew test --tests '*Snapshot*' --tests '*RouteSearch*'
./gradlew test          # 전체 회귀
```

### 6.3 수동 확인

- [ ] Redis 를 내린 상태에서 `/api/route/search` 가 `200` 을 유지하고 `meta.degraded` 에 `journey_snapshot` 이 보임
- [ ] TTL 만료 후 같은 `searchSessionId` 조회가 `empty`
- [ ] **스냅샷 1건의 직렬화 크기 측정 후 기록** — 대표 검색(경로 3~5개, 폴리라인 포함) 기준. D13 판단 근거

## 7. 리스크

| 리스크 | 대응 |
|---|---|
| `PathResult` 필드 추가로 기존 생성자 호출부 다수 컴파일 오류 | 보조 생성자로 흡수. `RouteSearchResult` 가 이미 쓰는 방식 |
| 직렬화·저장이 검색 응답 p95 를 늘림 | `SET` 한 번, 재시도 없음. 회귀 측정으로 확인 |
| Redis 장애가 검색을 실패시킴 | 예외를 어댑터에서 흡수. `RouteSearchSnapshotRecorderTest` 로 고정 |

## 8. 열린 결정

| # | 결정 | 기본안 |
|---|---|---|
| D3 | TTL | 600초. 검색 후 여정 시작까지의 현실적 상한 |
| D4 | 익명 검색 스냅샷으로 여정 시작 허용 | 허용 (§4.2) |
| D13 | 스냅샷 크기 줄이기 | **1차에서는 하지 않는다.** 폴리라인 포함해 통째로 저장하고, §6.3 측정값을 보고 나중에 결정 |

D13 을 다시 열 때의 선택지:

| 선택지 | 효과 | 대가 |
|---|---|---|
| 폴리라인·`laneGraphics` 빼고 저장 | 크기 대폭 감소 | 여정이 폴리라인을 쓰게 되면 다시 조회해야 함 |
| TTL 600 → 180초 | 상주량 1/3 | 검색 후 3분 안에 시작해야 함 |
| 압축 저장 | 50~70% 감소 | CPU 약간 |

`journey-persistence` 의 `JourneyCompiler` 는 정류장 식별자·소요시간만 쓰고 폴리라인은 보지 않는다. 지도 표시는 앱이 검색 응답으로 이미 받아 둔 값을 쓴다. 그래서 첫 번째 선택지가 유력하지만, 측정 전에는 고정하지 않는다.
