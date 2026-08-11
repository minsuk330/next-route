# 도보 turn-by-turn maneuver를 navigation-geometry에 추가 (PLAN)

티어: M. 브랜치: fresh main 기준 `feat/walk-maneuver-geometry` 독립 브랜치. 이 플랜 파일 2개를 구현 PR에 포함해 커밋한다.

## 0. 목표와 경계

기존 `GET /api/journeys/{journeyId}/navigation-geometry` 응답의 도보 leg에 **정적인 turn-by-turn maneuver 목록**을 추가한다. 서버는 현재 위치를 판정하지 않고, 폴리라인 위 anchor(어느 선분의 어디쯤)만 계산해 준다. GPS 매칭·다음 방향 선택은 iOS 네이티브 엔진 담당.

범위 밖(절대 하지 않는다):
- 신규 엔드포인트, DB 마이그레이션, 신규 컬럼
- 요청에서 현재 위치·GPS 좌표 수신
- 조회 시점 TMAP 재호출 (반드시 `journey_session.route_snapshot`의 `walkSteps` 사용)
- 지하철·버스 geometry 변경, iOS 쪽 작업(Dynamic Island / Live Activity / 이탈 판정 / FCM)

## 1. 사전 확인된 사실 (재조사 불필요)

| 사실 | 근거 |
| --- | --- |
| `SubPathResult.walkSteps`가 이미 존재하고 `route_snapshot` JSON에 직렬화된다 | `src/main/java/watoo/grd/nextroute/application/route/dto/SubPathResult.java:33`, `application/journey/service/JourneyStartService.java:70` (`objectMapper.valueToTree(stored.path())`) |
| WALK / TRANSFER_WALK leg는 도보 subPath와 1:1이고 `snapshot.subPathOf(legIndex)`로 접근된다 | `application/journey/service/JourneyCompiler.java:80-93`, `:134`; `JourneyNavigationGeometryService.java:389-398` |
| **WalkStep의 (x,y)는 도보 폴리라인의 정점 그 자체다.** TMAP Point feature 좌표를 폴리라인에 append 하면서 같은 feature로 WalkStep을 만든다 | `infrastructure/adapter/out/api/tmap/TmapPedestrianAdapter.java:136-157` (`appendPointToPolyline`), `:192-205` (`toWalkStep`) |
| 폴리라인은 연속 중복 좌표를 이미 제거한 상태로 저장된다 | `TmapPedestrianAdapter.java:173-182` (`appendIfNotDuplicate`) |
| `walkSteps == null`이 실제로 발생한다: ODSAY 원본은 null, TMAP 보강이 예산부족/bulkhead/timeout/upstream error로 생략될 수 있다 | `infrastructure/adapter/out/api/odsay/OdSayApiAdapter.java:399`, `application/route/service/WalkSegmentEnricher.java:112-150` |
| `LegGeometry.points`는 segment를 이어 붙일 때 **연속 중복 좌표를 dedupe** 한다 → points 인덱스와 `geometrySegmentIndex`가 어긋날 수 있다 | `JourneyNavigationGeometryService.java:233-244` (`assemble`) |
| 도보 segment는 폴리라인 인접 정점 쌍마다 1개다 (`points.size()-1`개) | `application/journey/service/GeometrySegmentAllocator.java:36-57` |
| 레포에 **실제 TMAP 응답 fixture가 없다.** 합성 테스트에 turnType 200/201/11만 등장 | `src/test/java/.../tmap/TmapPedestrianAdapterTest.java:63,75`, `application/route/service/RouteSearchDeadlineIT.java:70` |

## 2. 굳은 결정 (구현 중 바꾸지 말 것)

1. **anchor 기준 좌표열은 `sub.polyline()`** (= `allocator.allocate()`에 들어가는 리스트)이다. `LegGeometry.points()`(dedupe 결과) 기준으로 계산하면 안 된다.
2. **주 알고리즘은 정점 정확일치 단조 스캔.** WalkStep 좌표는 폴리라인 정점이므로 투영이 필요 없다. 최근접 선분 투영은 일치 실패 시 폴백으로만 쓴다 → 루프·교차 경로에서 뒤쪽 선분에 붙는 오탐이 구조적으로 사라진다.
3. **`maneuverIndex`는 응답 배열 내 0부터 재부여**한다. TMAP `WalkStep.index`를 그대로 쓰면 anchor 실패로 제외된 step 때문에 번호에 구멍이 남는다. TMAP index는 응답에 노출하지 않는다.
4. **`distanceFromLegStartMeters`는 반올림하지 않은 haversine prefix sum을 마지막에 한 번만 0.1m로 반올림**한다. segment별 반올림값을 누적하면 leg 총거리와 미세 불일치가 생긴다.
5. **anchor 실패는 전체 응답을 실패시키지 않는다.** 해당 maneuver만 제외, 최악의 경우 `maneuvers = []`. `maneuvers`는 절대 null이 아니다.
6. **`ManeuverType`에 `DEPART`를 포함**한다 (TMAP `pointType == "SP"`, turnType 200). 방향이 없는 시설 코드(육교·지하보도·계단·경사로·엘리베이터)는 별 타입을 만들지 않고 `STRAIGHT`로 접고 `instruction`(TMAP description)에 안내를 남긴다. **횡단보도(211~217)만 `CROSSWALK`로 따로 둔다** — 공식 스펙에 코드가 7개 있고 실제 응답에 흔히 나오며(문서 샘플에도 212 등장), 건너는 행동을 "직진"이라 부르면 거짓이다. 214~217처럼 방향이 붙은 횡단보도도 `CROSSWALK`로 통일한다(대각선 화살표는 문구 파싱이 필요하고, 잘못된 화살표가 없는 화살표보다 나쁘다).
7. 조회 시 TMAP 재호출 금지. snapshot만 읽는다.

## 3. 신규 파일

### 3.1 `src/main/java/watoo/grd/nextroute/application/journey/dto/ManeuverType.java`

```java
public enum ManeuverType {
    DEPART, STRAIGHT,
    SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN, ARRIVE, UNKNOWN
}
```

### 3.2 `src/main/java/watoo/grd/nextroute/application/journey/service/WalkManeuverTypeMapper.java`

`@Component`. 외부 TMAP 코드를 API 계약에 노출하지 않기 위한 경계.

```java
public ManeuverType map(String pointType, Integer turnType)
```

규칙 (우선순위 순):
1. `"EP".equals(pointType)` → `ARRIVE`
2. `"SP".equals(pointType)` → `DEPART`
3. `turnType == null` → `UNKNOWN`
4. TMAP 보행자 turnType 표 (아래) 적용
5. 미등록 코드 → `UNKNOWN`

TMAP 보행자 turnType 매핑표 — **출처는 TMAP 공식 문서 「경로안내 샘플예제」의 보행자 응답 스펙**(`https://tmap-skopenapi.readme.io/reference/경로안내-샘플예제`, 2026-08-11 확인). 자동차 경로안내 표와 코드 뜻이 다르므로 옮겨 쓰면 안 된다 — 130(토끼굴 진입)·131(1시 방향)은 자동차 전용이고 보행자에 없다. 표는 코드에 상수 Map으로 두고 테스트에서 코드↔타입을 전부 단정한다.

| turnType | 원문 | ManeuverType |
| --- | --- | --- |
| 1~7 | 안내 없음 | STRAIGHT |
| 11 | 직진 | STRAIGHT |
| 12 | 좌회전 | LEFT |
| 13 | 우회전 | RIGHT |
| 14 | U-turn | UTURN |
| 16 | 8시 방향 좌회전 | SHARP_LEFT |
| 17 | 10시 방향 좌회전 | SLIGHT_LEFT |
| 18 | 2시 방향 우회전 | SLIGHT_RIGHT |
| 19 | 4시 방향 우회전 | SHARP_RIGHT |
| 125~129 | 육교·지하보도·계단·경사로·계단+경사로 진입 | STRAIGHT |
| 184~189 | 경유지 계열 | STRAIGHT |
| 200 | 출발지 | DEPART (pointType SP로도 잡힘) |
| 201 | 목적지 | ARRIVE (pointType EP로도 잡힘) |
| 211~217 | 횡단보도 / 좌측·우측 / 8시·10시·2시·4시 방향 횡단보도 | CROSSWALK |
| 218 | 엘리베이터 | STRAIGHT |
| 233 | 직진 임시 | STRAIGHT |
| 그 외 | — | UNKNOWN |

pointType: SP(출발지)·EP(도착지)·PP·PP1~PP5(경유지)·GP(일반 안내점).

미등록 코드를 만나면 `log.debug`로 코드 숫자만 남긴다(개인정보 없음). 표를 추측으로 늘리지 말 것.

### 3.3 `src/main/java/watoo/grd/nextroute/application/journey/service/WalkManeuverAnchorer.java`

`@Component`. `GeometrySegmentAllocator`처럼 저장소를 보지 않고 아무것도 저장하지 않는 순수 계산 컴포넌트.

```java
public List<ManeuverGeometry> anchor(List<WalkStep> steps,
                                     List<CoordPoint> polyline,
                                     GeometryQuality legQuality)
```

동작:

1. 조기 종료 → `List.of()` 반환:
   - `steps == null || steps.isEmpty()`
   - `polyline == null || polyline.size() < 2`
   - `legQuality == APPROXIMATE || legQuality == FAILED` (직선 대체 geometry에 회전 안내를 얹으면 거짓 방향이 된다)
2. 정점 누적거리 prefix 배열 계산: `prefix[0] = 0`, `prefix[i+1] = prefix[i] + haversine(p[i], p[i+1])`. 반올림하지 않는다. haversine은 `GeometrySegmentAllocator`와 같은 식(반지름 6_371_000.0, asin 형태)을 쓴다 — 거리 계산 중복을 피하려면 `GeometrySegmentAllocator`에 `public double distanceMeters(GeoPoint, GeoPoint)`를 노출하거나 anchorer가 자체 private 메서드로 같은 식을 갖는다. **택: allocator의 `distanceMeters`를 package-private → public으로 올려 재사용**(식 중복 금지).
3. `steps`를 `WalkStep.index` 오름차순으로 정렬한 뒤 순회. 포인터 `ptr`(정점 인덱스, 초기 0), `lastDistance`(초기 -1) 유지.
4. 각 step:
   - 좌표 유효성: `x == 0 && y == 0` 이면 제외. `Double.isFinite` 아니면 제외.
   - **정확일치 탐색**: `ptr`부터 끝까지 스캔해 `|p.x - step.x| <= EPS && |p.y - step.y| <= EPS`인 첫 정점 idx를 찾는다. `EPS = 1e-7`(약 1cm, 좌표 직렬화 왕복 오차 흡수).
   - 못 찾으면 **투영 폴백**: `ptr`번째 선분부터 끝까지의 선분에 step을 투영해, 수직거리가 가장 짧은 선분을 고른다. 그 거리가 `MAX_OFFSET_METERS = 30.0`을 넘으면 이 step 제외.
   - anchor 값 산출:
     - 정확일치: `vertexIdx = idx`. `idx == polyline.size()-1`(마지막 정점)이면 `geometrySegmentIndex = segmentCount-1`, `segmentProgress = 1.0`. 아니면 `geometrySegmentIndex = idx`, `segmentProgress = 0.0`. 여기서 `segmentCount = polyline.size()-1`.
     - 투영: `geometrySegmentIndex = 선분 index`, `segmentProgress = clamp(t, 0.0, 1.0)`.
   - `distance = prefix[geometrySegmentIndex] + segmentLength(geometrySegmentIndex) * segmentProgress`
   - **단조성 가드**: `distance < lastDistance` 이면 이 step 제외(뒤로 가는 anchor 금지). 통과하면 `lastDistance = distance`, `ptr = geometrySegmentIndex`.
5. 통과한 step만 순서대로 `ManeuverGeometry`로 만들되 `maneuverIndex`는 0부터 재부여. `distanceFromLegStartMeters = Math.round(distance * 10.0) / 10.0`. `x`, `y`는 **원본 step 좌표**를 그대로 싣는다(앱이 지도에 핀을 찍는 값).
6. `type`은 anchorer가 정하지 않는다 — 호출자(service)가 `WalkManeuverTypeMapper`로 채우거나, anchorer가 mapper를 주입받아 채운다. **택: anchorer가 mapper를 생성자 주입받아 함께 채운다**(service의 조립 코드가 얇아진다). anchorer 단위 테스트는 실제 mapper 인스턴스를 그대로 쓴다.
7. 실패 관측: 제외된 step 수가 1개 이상이면 `log.debug("도보 maneuver anchor 실패 — legIndex 무관, 제외 {}/{}", excluded, total)` 수준으로 남긴다. 좌표 원문은 로그에 남기지 않는다(정밀 좌표 서버 비보관 결정 O10과 같은 결).

## 4. 수정 파일

### 4.1 `application/journey/dto/JourneyNavigationGeometry.java`

- `LegGeometry` 정규 생성자에 `List<ManeuverGeometry> maneuvers`를 **마지막 파라미터로** 추가. compact constructor에 `maneuvers = maneuvers == null ? List.of() : List.copyOf(maneuvers);` 추가.
- 호환 생성자 **2개** 유지 (현재 파일은 8-arg 호환 + 9-arg 정규 구조다, `:73-86`):
  - `(legIndex, type, routeId, plannedDurationSeconds, totalDistanceMeters, quality, points, segments)` → `stops = List.of()`, `maneuvers = List.of()`
  - `(… , points, segments, stops)` → `maneuvers = List.of()`
- 신규 record (같은 파일 안, 기존 `StopGeometry`/`SegmentGeometry`와 같은 위치):

```java
public record ManeuverGeometry(
        int maneuverIndex,
        ManeuverType type,
        String instruction,
        int geometrySegmentIndex,
        double segmentProgress,
        double distanceFromLegStartMeters,
        double x,
        double y
) {
}
```

- javadoc: leg 문서에 `maneuvers` 파라미터 설명 추가 — "도보 단계의 turn-by-turn 안내점. 다른 단계는 비어 있다. 서버는 위치를 판정하지 않고 폴리라인 위 위치만 준다."

### 4.2 `application/journey/service/JourneyNavigationGeometryService.java`

- 필드 추가: `private final WalkManeuverAnchorer maneuverAnchorer;` (생성자는 `@RequiredArgsConstructor`가 만든다 → 테스트 생성자 인자 순서 갱신 필요)
- `get()`의 switch (`:94-99`)에 도보 분기 추가:

```java
Built built = switch (leg.getType()) {
    case RIDE_BUS -> busLeg(leg, ownerOfOptions(legs, i), optionsByLeg, sub, routeStopsByRouteId);
    case WAIT_BUS, WAIT_SUBWAY -> new Built(waitLeg(leg, sub), null);
    case WALK, TRANSFER_WALK -> new Built(walkLeg(leg, sub), null);
    default -> new Built(snapshotLeg(leg, sub), null);
};
```

- 신규 private 메서드:

```java
/** 도보 단계. 기존 좌표 조립에 turn-by-turn 안내점만 얹는다. TMAP 을 다시 부르지 않는다. */
private LegGeometry walkLeg(JourneyLeg leg, SubPathResult sub) {
    LegGeometry base = snapshotLeg(leg, sub);
    if (sub == null) {
        return base;
    }
    List<ManeuverGeometry> maneuvers =
            maneuverAnchorer.anchor(sub.walkSteps(), sub.polyline(), base.quality());
    return withManeuvers(base, maneuvers);
}

private LegGeometry withManeuvers(LegGeometry geometry, List<ManeuverGeometry> maneuvers) {
    return new LegGeometry(geometry.legIndex(), geometry.type(), geometry.routeId(),
            geometry.plannedDurationSeconds(), geometry.totalDistanceMeters(),
            geometry.quality(), geometry.points(), geometry.segments(),
            geometry.stops(), maneuvers);
}
```

- **maneuver 유실 방지**: 아래 호출부가 `LegGeometry`를 새로 만들면서 maneuvers를 떨어뜨리지 않는지 확인한다. 지금은 도보에서 `withManeuvers`가 마지막에 붙으므로 유실이 없지만, 앞으로 순서가 바뀌면 깨진다 → `withStops`가 `stops`만 갈아끼우듯 `withManeuvers`도 나머지를 보존하게 작성했음을 주석으로 남긴다.
  - `assemble(...)` `:227-248` — 도보는 여기서 만든 뒤 `withManeuvers`로 감싼다. 시그니처 변경 없음.
  - `withStops(...)` `:250-255` — `geometry.maneuvers()`를 그대로 넘기도록 수정(버스 경로지만 안전을 위해).
  - `straightLine(...)` `:198-211` — 8-arg 호환 생성자 사용 중(`:201`). 그대로 두면 `maneuvers = []`. 의도된 값이므로 변경 불필요.
  - `waitLeg(...)` `:214-222` — 8-arg 호환 생성자 사용. 변경 불필요(대기 단계는 항상 `[]`).

### 4.3 `application/journey/service/GeometrySegmentAllocator.java`

- `private double distanceMeters(GeoPoint a, GeoPoint b)` (`:124-132`)를 `public`으로 올린다. anchorer가 같은 거리식을 쓰기 위함. javadoc 한 줄 추가: "도보 maneuver anchor 도 같은 식을 써야 leg 총거리와 어긋나지 않는다."

## 5. 작업 순서 (체크박스)

- [ ] 1. fresh main에서 `feat/walk-maneuver-geometry` 브랜치 생성 (`git fetch origin && git switch -c feat/walk-maneuver-geometry origin/main`). 작업 전 미커밋 변경 있으면 무조건 stash.
- [ ] 2. `ManeuverType` enum 추가 (§3.1). 의존 없음.
- [ ] 3. `JourneyNavigationGeometry.ManeuverGeometry` record + `LegGeometry` 필드/호환 생성자 (§4.1). 2 의존. → 이 시점에 `./gradlew compileJava` 통과해야 한다(기존 호출부는 호환 생성자로 살아 있음).
- [ ] 4. `WalkManeuverTypeMapper` + 단위 테스트 (§3.2, §6.1). TDD: 테스트 먼저.
- [ ] 5. `GeometrySegmentAllocator.distanceMeters` 가시성 변경 (§4.3).
- [ ] 6. `WalkManeuverAnchorer` + 단위 테스트 (§3.3, §6.2). TDD: 테스트 먼저. 4·5 의존.
- [ ] 7. `JourneyNavigationGeometryService` walkLeg 분기 (§4.2). 6 의존. 기존 서비스 테스트 생성자 인자 추가로 컴파일 깨짐 → 함께 수정.
- [ ] 8. 서비스 테스트 보강 (§6.3).
- [ ] 9. 컨트롤러 계약 테스트 보강 (§6.4).
- [ ] 10. `./gradlew test` 전체 통과.
- [ ] 11. 모바일 팀 전달물 작성 (§8) — `docs/plan/walk-maneuver-geometry/HANDOFF.md`.
- [ ] 12. 플랜 3파일 + 구현 커밋 → PR 생성 (main 대상, 스택 금지).

## 6. 테스트

### 6.1 `src/test/java/watoo/grd/nextroute/application/journey/service/WalkManeuverTypeMapperTest.java`

- `pointType == "SP"` → `DEPART` (turnType 200이든 null이든)
- `pointType == "EP"` → `ARRIVE` (turnType 201이든 null이든)
- 11 → STRAIGHT / 12 → LEFT / 13 → RIGHT / 14 → UTURN
- 16 → SHARP_LEFT / 17 → SLIGHT_LEFT / 18 → SLIGHT_RIGHT / 19 → SHARP_RIGHT
- 184, 185 → STRAIGHT
- 125~131 각각 → STRAIGHT (`@ParameterizedTest`)
- 미등록 코드(예: 9999, 211) → UNKNOWN
- `turnType == null` + `pointType == "GP"` → UNKNOWN
- `pointType == null` + turnType 12 → LEFT (pointType 없어도 코드로 판정)

### 6.2 `src/test/java/watoo/grd/nextroute/application/journey/service/WalkManeuverAnchorerTest.java`

- maneuver가 폴리라인 첫 정점 → `geometrySegmentIndex=0`, `segmentProgress=0.0`, `distance=0.0`
- maneuver가 중간 정점 → 해당 정점 index, progress 0.0, distance = 앞 선분 길이 합
- maneuver가 **마지막 정점** → `geometrySegmentIndex = segmentCount-1`, `progress = 1.0`, distance = 전체 길이
- 정점과 일치하지 않는 좌표(선분 중간에 떨어짐) → 투영 폴백, `0 < progress < 1`
- maneuver 여러 개 → `distanceFromLegStartMeters` 단조 증가, `maneuverIndex` 0,1,2… 연속
- 같은 선분에 maneuver 2개(투영 케이스) → progress 단조 증가
- **경로가 교차/왕복하는 폴리라인**: 뒤쪽 정점과 좌표가 같은 앞쪽 정점이 존재할 때, 이후 maneuver가 앞쪽으로 점프하지 않는다(단조 가드)
- 폴리라인에서 30m 넘게 떨어진 maneuver → 제외되고 나머지는 유지 (응답 실패 아님)
- 좌표 `(0,0)` step → 제외
- `polyline.size() < 2` → `List.of()`
- `legQuality == APPROXIMATE` → `List.of()`
- `legQuality == FAILED` → `List.of()`
- `steps == null` → `List.of()`
- **연속 중복 좌표가 섞인 폴리라인**: 길이 0 선분이 있어도 `geometrySegmentIndex`가 `allocator.allocate(polyline, …)`가 만든 segment 개수 범위(`0..size-1`) 안이고, 같은 정점 기준으로 어긋나지 않는다 (결정 1 회귀 방지)

### 6.3 `src/test/java/watoo/grd/nextroute/application/journey/service/JourneyNavigationGeometryServiceTest.java` (수정)

기존 테스트(`:84`~`:264`)는 전부 그대로 통과해야 한다. 생성자에 anchorer 추가만 반영. 추가:

- `WALK` leg + walkSteps 있는 snapshot → `legs[0].maneuvers` 비어 있지 않고 타입/거리 채워짐
- `TRANSFER_WALK` leg → 동일하게 maneuvers 포함
- `RIDE_BUS`, `WAIT_BUS`, `RIDE_SUBWAY` leg → `maneuvers` 빈 목록
- walkSteps `null`인 도보 → `maneuvers` 빈 목록, `points`/`segments`는 기존과 동일
- 좌표 없어 직선 대체(`fallsBackToStraightLine` 계열) → `quality=APPROXIMATE` 이고 `maneuvers` 빈 목록
- `survivesBrokenSnapshot` 계열 유지: snapshot 재컴파일 실패해도 200 응답 + `maneuvers` 빈 목록
- `rejectsStaleRevision` / `defaultsToCurrentRevision` 유지

### 6.4 `src/test/java/watoo/grd/nextroute/infrastructure/adapter/in/api/journey/JourneyNavigationGeometryControllerTest.java` (수정)

기존 `returnsGeometry`(`:59`)는 유지하고, 도보 leg를 포함한 케이스에 jsonPath 단정 추가:

```
$.legs[0].maneuvers[0].maneuverIndex
$.legs[0].maneuvers[0].type
$.legs[0].maneuvers[0].instruction
$.legs[0].maneuvers[0].geometrySegmentIndex
$.legs[0].maneuvers[0].segmentProgress
$.legs[0].maneuvers[0].distanceFromLegStartMeters
$.legs[0].maneuvers[0].x
$.legs[0].maneuvers[0].y
```

추가로 maneuver 없는 leg에서 `$.legs[N].maneuvers`가 `null`이 아니라 빈 배열(`hasSize(0)`)임을 단정한다.

## 7. 검증 명령

```bash
./gradlew compileJava                                        # 단계 3 직후
./gradlew test --tests '*WalkManeuverTypeMapperTest'
./gradlew test --tests '*WalkManeuverAnchorerTest'
./gradlew test --tests '*JourneyNavigationGeometry*'
./gradlew test                                               # 최종
```

완료 조건: 위 4개 전부 green. 신규 엔드포인트 0개, 마이그레이션 0개(`src/main/resources/db/migration` 무변경), 요청 파라미터 무변경.

## 8. 모바일 팀 전달물 — `docs/plan/walk-maneuver-geometry/HANDOFF.md`

1. 도보 leg를 포함한 **실제 응답 JSON fixture** (테스트에서 뽑은 것, 좌표는 실제 서울 좌표대)
2. §3.2의 turnType → ManeuverType 매핑표 그대로
3. 앱이 지켜야 할 계약 3줄:
   - `maneuvers`는 항상 배열이다(null 아님). 빈 배열은 "안내점 없음"이며 경로 자체는 유효하다.
   - `geometrySegmentIndex`는 같은 leg의 `segments` 배열 인덱스다. `segmentProgress`는 그 선분 안의 0~1 비율.
   - `distanceFromLegStartMeters`는 단조 증가한다. 앱은 이 값으로 "다음 안내까지 남은 거리"를 계산한다.
   - `type == UNKNOWN`이면 `instruction` 문구를 그대로 읽어 준다.
