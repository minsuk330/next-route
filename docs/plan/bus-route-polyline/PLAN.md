# 서울시 버스 노선 Polyline 일회성 적재 계획

> **2026-07-23 후속 변경:** 일회성 적재 구조는 정적 데이터 매 실행 upsert와
> 정류장 순서 제약 구간 생성으로 대체됐다. 현재 계획은
> [`docs/plan/bus-route-segment-polyline/PLAN.md`](../bus-route-segment-polyline/PLAN.md)를 따른다.

> 서울시 `getRoutePath` API를 `BusApiPort`에 추가하고, DB에 이미 존재하는 모든 `bus_route.route_id`를 기준으로 노선 Polyline을 한 번 적재한다.

## 1. 결정 사항

- 버스 Polyline은 ODsay 지하철 Polyline과 출처·식별자·생명주기가 다르므로 **`BusRoutePolyline` 전용 엔티티/테이블**로 분리한다.
- 데이터는 정적 초기 데이터로 취급하고 **주기 스케줄러에 넣지 않는다**.
- 별도 one-shot backfill을 설정으로 한 번 활성화해 실행한다.
- 재실행 안전성을 위해 이미 적재된 `routeId`는 건너뛴다.
- 메시지 브로커, 영속 job 테이블, lease, 주기 갱신은 이번 범위에서 제외한다.
- 1차 저장 형식은 기존 ODsay 구현과 같은 **JSONB 좌표 배열**을 사용한다. 현재 프로젝트에 Hibernate Spatial 의존성이 없으므로 LineString 엔티티 매핑은 후속 과제로 둔다.

## 2. 현재 구조에서 확인한 내용

```text
BusStaticDataService
  → BusApiPort.getBusRouteIds()
  → 새 routeId의 기본정보를 bus_route에 저장
  → 전체 routeId의 정류장 정보를 동기화

SeoulBusApiAdapter
  → http://ws.bus.go.kr/api/rest
  → XML BusApiResponse<T> 공통 파서 사용

ODsay Polyline
  → 별도 entity/table + JSONB 저장 패턴 보유
  → collection job은 동시 claim/lease가 없어 이번 구현에서 재사용하지 않음
```

주의할 점:

- `StaticDataScheduler`는 시작 시 및 매일 실행되므로 Polyline 전체 적재를 여기에 붙이면 부팅시간과 외부 API 호출량이 커진다.
- 경로 검색 응답의 `LaneResult.busLocalBlID`에는 서울시 노선 ID가 이미 보존되어 있어, 후속 조회 시 `bus_route_polyline.route_id`와 직접 연결할 수 있다.
- `SEOUL_BUS_API` 값은 현재 URL-encoded key를 전제로 URL에 직접 붙인다. 새 구현에서 키를 다시 encode하여 `%2F`를 `%252F`로 만들면 안 된다.

## 3. 목표 흐름

```text
설정으로 one-shot 활성화
  → 로컬 bus_route에서 아직 Polyline이 없는 routeId 조회
  → routeId 순서대로 BusApiPort.getRoutePath(routeId) 호출
  → no 오름차순 정렬 및 좌표 검증
  → BusRoutePolyline JSONB 저장
  → 요청 간 간격 적용
  → 성공/스킵/빈 응답/실패 요약 로그
```

API:

```http
GET /busRouteInfo/getRoutePath
    ?serviceKey={encoded-key}
    &busRouteId={routeId}
```

응답 필드 매핑:

| XML | 애플리케이션 | 의미 |
|---|---|---|
| `no` | `sequence` | 노선 좌표 순번 |
| `gpsX` | `longitude` / `x` | 경도 |
| `gpsY` | `latitude` / `y` | 위도 |

## 4. 데이터 모델

Flyway `V30__create_bus_route_polyline.sql`:

```sql
CREATE TABLE bus_route_polyline (
    id          BIGSERIAL PRIMARY KEY,
    route_id    VARCHAR(255) NOT NULL,
    point_count INT          NOT NULL,
    polyline    JSONB        NOT NULL,
    source      VARCHAR(30)  NOT NULL DEFAULT 'SEOUL_BUS',
    fetched_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    deleted_at  TIMESTAMP,
    CONSTRAINT uk_bus_route_polyline_route UNIQUE (route_id),
    CONSTRAINT fk_bus_route_polyline_route
        FOREIGN KEY (route_id) REFERENCES bus_route (route_id)
);
```

`point_count >= 2` 불변식은 이미 적용된 `V30`을 변경하지 않고
`V31__add_bus_route_polyline_point_count_check.sql`에서 추가한다.

저장 JSON 예시:

```json
{
  "points": [
    {"x": 126.8375725836, "y": 37.5085538548},
    {"x": 126.8376200000, "y": 37.5086100000}
  ]
}
```

설계 규칙:

- `route_id`는 문자열 FK로 둔다. 기존 `BusRouteStop`과 같은 방식이며 불필요한 JPA 연관관계를 만들지 않는다.
- 배열 순서가 원본 `no` 순서를 나타내므로 저장 좌표에는 `sequence`를 중복 저장하지 않는다.
- 한 노선당 현재 유효본 하나만 저장한다.
- 빈 응답이나 검증 실패 결과는 저장하지 않는다.

## 5. 코드 변경 계획

### Step 1 — API 계약과 XML 파싱

1. `application/bus/dto/BusRoutePathPoint.java`
   - 파싱 실패 좌표를 검증 단계에서 제거할 수 있도록 nullable wrapper를 쓰는
     `record BusRoutePathPoint(Integer sequence, Double longitude, Double latitude)` 추가.
2. `BusApiPort`
   - `List<BusRoutePathPoint> getRoutePath(String busRouteId)` 추가.
3. `infrastructure/.../bus/dto/BusRoutePathItem.java`
   - XML의 `no`, `gpsX`, `gpsY`를 문자열로 수신.
4. `SeoulBusApiAdapter`
   - 정확한 경로 `/busRouteInfo/getRoutePath`로 요청.
   - 기존 `BusApiResponse<T>` 파서를 재사용하고 application DTO로 변환.
   - API 제한 코드 `7`은 빈 결과로 간주하지 않고 backfill 전체를 중단할 수 있는 예외로 전달.

완료 조건:

- 샘플 XML이 순번/경도/위도로 정상 변환된다.
- 생성 URI의 key가 이중 인코딩되지 않는다.
- 존재하지 않는 routeId의 코드 `4`는 빈 결과로 처리해 미적재 목록에 남기고 다음 노선으로 진행한다.
- 호출 제한 코드 `7`은 남은 routeId 호출을 즉시 중단한다.

### Step 2 — 전용 엔티티와 저장 서비스

1. `V30__create_bus_route_polyline.sql` 추가.
2. `domain/bus/entity/BusRoutePolyline.java` 추가.
   - `routeId`, `pointCount`, `polyline`, `source`, `fetchedAt` 보유.
   - JSONB 문자열 필드는 기존 엔티티처럼 `@ColumnTransformer(write = "?::jsonb")` 사용.
3. `BusRoutePolylineRepository` 추가.
   - `existsByRouteId`, `findByRouteId` 제공.
   - backfill 대상 조회는 `bus_route LEFT JOIN bus_route_polyline`으로 아직 없는 routeId만 한 번에 가져온다.
4. `BusRoutePolylineDataService` 추가.
   - 좌표 데이터를 JSON 직렬화하고 저장.
   - unique constraint 충돌은 중복 적재가 아니라 이미 완료된 노선으로 취급.

완료 조건:

- 동일 `routeId`가 두 번 저장되지 않는다.
- JSON 역직렬화 결과가 입력 좌표 순서를 보존한다.
- `bus_route`에 없는 ID는 FK로 차단된다.

### Step 3 — one-shot backfill

1. `LoadBusRoutePolylineUseCase`와 `BusRoutePolylineBackfillService` 추가.
2. 대상은 외부 노선 목록을 다시 받지 않고 **로컬 `bus_route`에서 Polyline 미적재 routeId만 조회**한다.
3. 각 routeId에 대해 다음을 수행한다.
   - API 호출
   - `sequence` 숫자 변환 및 오름차순 정렬
   - 비정상 좌표 제거
   - 연속 중복 좌표 제거
   - 유효 좌표가 2개 이상일 때만 저장
4. 노선 하나의 저장은 짧은 독립 트랜잭션으로 처리한다. 전체 API 루프를 하나의 DB 트랜잭션으로 감싸지 않는다.
5. 요청 사이 기본 200ms 간격을 두고 interrupt를 존중한다.
6. 결과를 `total/success/skipped/empty/invalid/failed`로 요약한다. API key와 전체 요청 URI는 로그에 남기지 않는다.
7. `BusRoutePolylineBackfillRunner`는 다음 설정에서만 실행한다.

```yaml
batch:
  bus-route-polyline:
    enabled: ${BUS_ROUTE_POLYLINE_BACKFILL_ENABLED:false}
    request-interval-ms: ${BUS_ROUTE_POLYLINE_REQUEST_INTERVAL_MS:200}
```

운영 실행:

1. `bus_route`가 먼저 적재되어 있는지 확인한다.
2. 한 인스턴스에서 `BUS_ROUTE_POLYLINE_BACKFILL_ENABLED=true`로 한 번 실행한다.
3. 완료 로그와 DB 건수를 확인한다.
4. 설정을 다시 `false`로 내린다.

`true` 상태에서 재시작해도 미적재 노선만 대상으로 삼으므로 기존 노선은 API를 재호출하지 않는다.

### Step 4 — 지도/경로 검색 연결은 후속 PR

이번 PR은 수집과 적재까지만 담당한다. 다음 PR에서:

- `LaneResult.busLocalBlID`로 `BusRoutePolyline` 조회
- 승차 정류장부터 하차 정류장까지 Polyline slice
- Naver Map에 `[longitude, latitude]` 좌표로 렌더링
- 서울시 경로와 네이버 베이스맵의 수 미터 차이는 시각적 오차로 허용하되, 필요하면 map matching을 별도 적용

## 6. 검증 계획

### 단위 테스트

- `SeoulBusApiAdapterTest`
  - `/busRouteInfo/getRoutePath` URI와 파라미터 검증
  - XML 단건/복수 `itemList` 파싱
  - `gpsX → longitude`, `gpsY → latitude` 축 뒤바뀜 방지
  - 코드 `4`와 코드 `7` 처리 분리
  - encoded key 이중 인코딩 방지
- `BusRoutePolylineBackfillServiceTest`
  - 순번 정렬, 좌표 범위 검증, 연속 중복 제거
  - 빈/1개 좌표 미저장
  - 한 노선 실패 후 다음 노선 계속
  - 제한 코드 `7`에서 전체 중단
  - 이미 저장된 노선 API 미호출
- `BusRoutePolylineDataServiceTest`
  - JSON round-trip과 unique/FK 계약

### 통합 확인

```sql
SELECT count(*) FROM bus_route;
SELECT count(*) FROM bus_route_polyline;

SELECT route_id, point_count, fetched_at
FROM bus_route_polyline
ORDER BY point_count DESC
LIMIT 20;

SELECT br.route_id, br.route_name
FROM bus_route br
LEFT JOIN bus_route_polyline p ON p.route_id = br.route_id
WHERE p.route_id IS NULL
ORDER BY br.route_id;
```

완료 판정:

- `bus_route` 중 API가 정상 데이터를 제공하는 노선은 모두 한 건씩 적재된다.
- `point_count`와 JSON `points` 길이가 일치한다.
- 실패 노선 목록이 routeId와 사유로 남아 재실행 대상을 식별할 수 있다.
- 재실행 시 기존 적재 노선에 대한 외부 API 호출과 중복 INSERT가 없다.

## 7. PR 분리

1. **PR 1 — API + persistence**
   - `BusApiPort.getRoutePath`, XML DTO/adapter, migration, entity/repository/data service.
2. **PR 2 — one-shot backfill**
   - 대상 조회, 검증, 요청 간격, runner, 요약 로그, 테스트.
3. **PR 3 — navigation integration (후속)**
   - 경로 검색 bus leg slice 및 Naver Map 표시.

## 8. 이번 범위에서 하지 않는 것

- `StaticDataScheduler`에 Polyline 적재 추가
- 매일/매주 자동 갱신
- ODsay collection job 테이블 재사용
- 다중 worker 병렬 수집
- 실패 job 영속화 및 자동 재시도 스케줄링
- PostGIS LineString/map matching
- Polyline 단순화 또는 encoded-polyline 압축
