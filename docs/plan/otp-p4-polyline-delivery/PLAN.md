# P4 — 검색 응답 형상 배선 + 지하철 pfaedle Stage2

> 에이전트 실행용. 컨텍스트 없이 이 문서만 읽고 구현 가능하게 쓴다.
> 티어 L (2트랙, 그래프 재빌드+프로덕션 재배포 포함, PR 3개).
> 선행 상태: P3 배포 완료(2026-09-17). 프로덕션 graphVersion=`seoul-bbox-202503-cal20260707_20261108-otp276-shaped-gb75848ddb4ee`, `ROUTING_OTP_SHAPES_ENABLED=true`, 버스 형상 라이브.

## 0. 문제 정의 (실측 근거)

프로덕션 검색 응답 `paths[].subPaths[].polyline` 이 **모든 leg 에서 null** 이다(2026-09-17 VPS 실측, 버스·지하철·도보 전부). 원인 3개:

1. **대중교통**: `LegacyRouteResponseMapper.toSubPath()` 가 polyline 자리를 명시적으로 null 로 둔다(`LegacyRouteResponseMapper.java:152-158`, 주석 "대중교통 형상은 P3 OSM relation 이 채운다(지금 null)"). OTP 는 이미 `RoutingLeg.geometry()` 에 인코딩된 형상을 들고 있는데 쓰지 않는다.
2. **지하철**: P3 Stage1 이 버스만 대상이라 coverage-index 의 coveredModes=[bus]. 지하철은 형상 자체가 없어 OTP 가 정류장 직선(3점)을 준다 → 채우면 건물 관통선이라 intent §5 위반.
3. **도보**: `WalkSegmentEnricher` 가 TMAP 보행경로를 `SubPathResult.polyline` 에 주입하는 배선이 이미 있고 프로덕션에 `TMAP_APP_KEY` 도 있으나, 이 enricher 는 ODSAY 형태 입력(인접 subPath 의 startX/Y 좌표)을 요구한다. OTP 경로에서는 walk subPath 의 startX/Y 가 null(legacy mapper 가 `leg.stops()` 에서 좌표를 뽑는데 도보 leg 는 stops 가 비어 있음)이라 조용히 건너뛴다.

목표: 앱 지도에 실제 노선 형상이 그려지게 한다. 계약(`subPaths[].polyline`)은 이미 존재하므로 **응답 스키마 변경 없이 값만 채운다**.

## 1. 트랙 구성과 의존

- **Track B (검색 응답 배선)** — 버스·도보. Track A 와 독립. 먼저 머지·배포해서 지금 바로 선이 나오게 한다.
- **Track A (pfaedle Stage2)** — 지하철/철도 형상 생성 + 그래프 재빌드 + 재배포. 완료되면 coverage-index 에 subway 가 들어가고 **Track B 코드 변경 없이** 지하철 선이 자동으로 합류한다(B2 의 조건이 `coveredModes` 를 보기 때문).
- 순서: B → A. (사용자 결정: 버스·도보 먼저 내보낸다.)

## 2. Track B — 검색 응답 polyline 배선

### B1. 인코딩 폴리라인 디코더 (신규)

- 신규: `src/main/java/watoo/grd/nextroute/application/route/routing/EncodedPolyline.java`
  ```java
  public final class EncodedPolyline {
      /** Google encoded polyline(precision 5) → 좌표열. 빈 문자열/null 은 빈 리스트. */
      public static List<CoordPoint> decode(String encoded);
  }
  ```
- **경계 규칙**: OTP `legGeometry.points` 는 위도·경도 순서로 인코딩된다. `CoordPoint(x, y)` 는 **x=경도(lon), y=위도(lat)** 다(`CoordPoint.java`, 기존 ODSAY 계약과 동일). 변환은 이 클래스 한 곳에서만 하고 다른 곳에서 뒤집지 않는다.
- **fail-closed 계약(codex 리뷰 반영)**: 디코더는 **절대 예외를 밖으로 던지지 않는다**. 잘린 문자열·깨진 varint·범위를 벗어난 좌표는 빈 리스트로 반환하고, 호출부는 빈 리스트/1점을 `polyline=null` 로 처리한다(선은 2점부터 의미가 있다). 이유: 검색 경로(`RouteSearchService.java:176-200`)에 이 매핑을 감싸는 방어가 없어 예외가 그대로 전파되면 **형상 결함이 검색 500 으로 번진다**. 형상은 부가정보지 검색 실패 사유가 아니다.
- 검증: `EncodedPolylineTest` — ①OTP 실응답 fixture 디코드 후 첫/끝 좌표가 leg 의 from/to 좌표와 50m 이내 ②빈 문자열/null → 빈 리스트 ③점 개수가 `legGeometry.length` 와 일치 ④**malformed 입력(중간 절단·비정상 문자) → 예외 없이 빈 리스트** ⑤**1점짜리 입력 → 호출부에서 null**.
  - fixture 는 `src/test/resources/otp/` 에 이미 있는 planConnection 응답 재사용(`planconnection-stopcall-times.json` 계열). 없으면 VPS 에서 한 건 받아 추가한다.

### B2. 대중교통 leg 형상 주입

- 변경: `src/main/java/watoo/grd/nextroute/application/route/service/LegacyRouteResponseMapper.java`
  - `toSubPath(RoutingLeg leg, ...)` 의 polyline 인자(현재 null, 154행 `null, lanes, stations` 앞의 3번째 인자 자리 아님에 주의 — `SubPathResult.java:24` 의 `List<CoordPoint> polyline` 위치를 정확히 확인하고 채울 것)에 아래 규칙으로 값을 넣는다.
  - **규칙(가짜 형상 금지)**:
    - `leg.isTransit()` 이면 `leg.geometry().quality() == GeometryQuality.SHAPE_OK` 일 때만 `EncodedPolyline.decode(leg.geometry().displayPoints())` 를 넣는다.
    - `STREET_FALLBACK`/`MISSING` 은 **null 유지**. OTP 가 직선 points 를 줘도 채우지 않는다(`OtpPlanConnectionMapper.java:237-247` 의 판정 계약과 동일한 이유 — points 존재 ≠ 형상).
    - 디코드 결과가 **2점 미만이면 null**(B1 fail-closed 계약).
    - 도보는 B3 에서 처리.
  - 이 규칙 덕분에 지하철은 Stage2 배포 전까지 자동으로 null 이고, 배포되면 coverage-index 가 subway 를 커버하는 순간 값이 채워진다. **코드 재변경 불필요.**
- 검증: `LegacyRouteResponseMapperTest` 에 계약 테스트 3개 추가 — ①SHAPE_OK 버스 leg → polyline 비어있지 않음 ②STREET_FALLBACK 버스 leg → null ③지하철 leg(현행 STREET_FALLBACK) → null.

### B3. 도보 leg 형상

- 변경: `LegacyRouteResponseMapper` — 도보 leg(`RoutingMode.WALK`, trafficType=3)에도 `EncodedPolyline.decode(leg.geometry().displayPoints())` 를 넣는다. 도보는 OTP 가 OSM 보행 네트워크로 실제 경로를 계산하므로 quality 게이트 대상이 아니다(`OtpPlanConnectionMapper.java:246` 에서 도보는 항상 OTP_STREET 로 표기되지만 이는 "미검증 대중교통"과 다른 의미 — 주석 참조).
- **TMAP enricher 와의 관계 결정**: `WalkSegmentEnricher`(`WalkSegmentEnricher.java:189-`)는 OTP 경로에서 입력 좌표 부재로 이미 무동작이다. 이번 스코프에서 **되살리지 않는다** — OTP 도보 경로가 이미 OSM 기반 실경로라 TMAP 호출은 중복 비용이다. enricher 코드는 ODSAY 폴백 경로용으로 그대로 둔다(제거하지 않음).
- 검증: 도보 leg → polyline 비어있지 않음 + 점 개수 ≥ 2.

### B4. 응답 크기 + Redis 스냅샷 용량 가드 (codex 리뷰로 범위 확대)

측정 대상이 **HTTP 응답 바이트만이 아니다**. 검색 결과는 응답 직전에 Redis 스냅샷으로도 저장된다(`RouteSearchController.java:40-49` 의 `snapshotRecorder.attach`, `RouteSnapshotProperties` 기본 `enabled=true` / `ttlSeconds=600`). 즉 polyline 이 커지면 **모든 검색 × 10분 상주**로 Redis 를 먹는다.

- **프로덕션 제약(실측)**: Redis `--maxmemory 128mb --maxmemory-policy allkeys-lru`, 현재 사용량 9.7MB. 이 Redis 는 실시간 버스 도착 캐시와 **공유**한다([[project-bus-realtime-cache]]) → 스냅샷이 부풀면 LRU 가 남의 키를 밀어내 실시간 캐시 적중률이 조용히 떨어진다. 이게 응답 크기보다 위험하다.
- 측정 항목(전부 before/after, 노트 `### 검증` 에 기록):
  1. 검색 응답 바이트(3개 OD: 버스단독/지하철포함/장거리환승).
  2. 스냅샷 1건 직렬화 크기(평균·최대).
  3. TTL 600초 기준 예상 Redis 상주량 = 스냅샷 크기 × 피크 검색률 × 600s. 피크 검색률은 Prometheus 에서 실측.
- **가드 기준(사전 합의)**: 예상 상주량이 maxmemory 의 25%(32MB)를 넘으면 그 PR 에서 대응한다. 대응 순서 = ①스냅샷에서 polyline 제외(스냅샷은 여정 시작용이라 형상이 필요 없는지 먼저 확인) ②표시용 단순화(Douglas-Peucker) ③TTL 단축. **기준 미만이면 아무것도 하지 않는다(선제 최적화 금지).**
- 참고 실측: 버스 leg 1개 인코딩 문자열 185자 ≈ 56점. 디코드하면 좌표 배열이라 JSON 은 훨씬 커진다(≈ 점당 30~40바이트) — **인코딩 문자열 길이로 추정하지 말 것.**

### B5. (미결 — 사용자 결정 대기)

- `subPaths[]` 에 `geometryQuality` 를 같이 내릴지. 지금은 "서버가 아직 안 채움"과 "검증된 형상 없음"이 둘 다 null 이라 클라가 구분 못 한다. 필드 추가는 legacy 계약 확장이라 별도 결정 필요. **이번 PR 에 넣지 않는다.**

## 3. Track A — pfaedle Stage2 (지하철/철도)

### A0. 모드 일반화 — rail 누락 차단 (codex 리뷰 반영, A1 보다 먼저)

현재 게이트 스크립트는 **버스 전용으로 하드코딩**돼 있다: `compute_shape_quality.py:409` 가 `"coveredModes": ["bus"]` 를 고정으로 쓰고 counts 도 `busTrips` 뿐이다. 반면 앱은 `modeToken()` 에서 `BUS→"bus" / SUBWAY→"subway" / RAIL→"rail"` 세 토큰을 판정한다(`OtpPlanConnectionMapper.java:255-262`). 이 상태로 Stage2 를 돌리면 인덱스가 subway/rail 을 선언하지 못해 **형상을 만들어놓고도 leg 는 계속 null** 이 된다.

- 변경: `compute_shape_quality.py` — 대상 mode 를 인자로 받고(`--modes bus,subway`), `coveredModes`·counts 를 그 인자에서 파생시킨다. 하드코딩 제거.
- **Stage2 범위 결정**: 1차는 **subway 만**. 피드의 rail 은 2개 노선뿐이고(`release-manifest.json` feedCounts `rail_2: 2`) 매칭 특성이 지하철과 다르다 — A1 분포를 보고 별도 판단한다. 스크립트를 일반화해두면 rail 추가는 인자만 바꾸면 되므로 코드 재변경이 없다.
- 검증: `--modes bus` 로 돌린 결과가 기존 coverage-index 와 **바이트 단위로 동일**해야 한다(회귀 0 증명). 그 다음 `--modes bus,subway` 로 진행.

### A1. 매칭 품질 측정 (커밋 전 선행)

- 근거: OSM 에 수도권 지하철 선형이 실재함을 Overpass 로 실측 확인(2026-09-17, bbox 126.5,37.2~127.4,38.0): `railway=subway|light_rail` way **1,928개**, `route=subway|light_rail|train` 릴레이션 **266개**. 입력 PBF(`data/input/south-korea-260903.osm.pbf`)에 이미 포함.
- 변경: `tools/transit-routing-benchmark/runner/build_seoul_graph.sh` — 현재 pfaedle MOT 가 bus 고정이다. `--mots` 인자를 받게 하고 기본값은 bus 유지.
- 실행: `--report-only` 로 `-m subway` 전량 매칭 → `compute_shape_quality.py --modes bus,subway` 가 지표 분포(residual_p90/residual_max/anchor_err/detour_ratio)를 mode 별로 출력.
- **rail 고유 위험 확인 포인트**: GTFS 역 좌표가 역사중심인지 승강장인지에 따라 anchor_err 가 버스보다 클 수 있다([[project-subway-station-radius]] 에서 같은 좌표 기준 차이가 이미 문제였음). 분포를 보고 **지하철 전용 임계**를 따로 둘지 결정한다(버스 임계 재사용 금지).
- 산출물: `docs/plan/otp-p4-polyline-delivery/handoffs/STAGE2-DISTRIBUTION.md` — 분포표 + 임계 제안 + degraded 예상 비율.

### A2. 게이트·그래프·번들

- `config/otp-seoul/shape-gate-thresholds.json` — mode 별 임계 구조로 확장(`{"bus": {...}, "subway": {...}}`). 기존 버스 임계값(p90>70/max>190/anchor>170/detour>3.0)은 그대로 보존.
- `compute_shape_quality.py` — A0 에서 일반화한 `--modes` 로 mode 별 임계를 적용해 coverage-index 생성(`coveredModes=["bus","subway"]`).
- 그래프 재빌드:
  ```bash
  OTP_IMAGE=nextroute-otp:h-only-20260917 \
    docker compose -f compose.seoul-otp.yml --profile build run --rm \
    -e JAVA_TOOL_OPTIONS="-Xmx5g -XX:+UseStringDeduplication" otp-build
  ```
  **🔴 이미지 고정 필수**: graph.obj 는 OTP serialization id 로 잠긴다. 스톡 `opentripplanner:2.9.0`(id 249)로 만들면 배포본(2.10.0-SNAPSHOT, **id 276**)이 로드를 거부한다. 배포 시점의 VPS 이미지를 `docker save|load` 로 가져와 쓸 것. Mac(arm64)에서는 amd64 에뮬레이션이라 `-Xmx11g` 면 Docker VM(7.75GB)에서 OOM → **-Xmx5g**.
- 재스탬프: coverage-index 의 `graphVersion` + `release-manifest.json`(graphVersion/otp.imageDigest/graph.sha256/coverageIndexSha256) 갱신 후 `python3 runner/verify_release_bundle.py --write` → 재실행으로 `RELEASE BUNDLE OK` 확인.
- `OtpPlanConnectionMapper.modeToken()`(256-260행)이 SUBWAY→"subway" 를 이미 반환하므로 앱 코드 변경 불필요.

### A3. 프로덕션 배포

P3 배포(2026-09-17)와 동일 절차. 실측 소요: otp 스왑 다운타임 21초.

1. `scp graph.obj coverage-index.json vps:/srv/nextroute/otp/<newGraphVersion>/` → 양쪽 sha256 대조.
2. `cp compose.yaml compose.yaml.bak-<date>-stage2` 백업 후 `ROUTING_OTP_GRAPH_VERSION` + coverage-index 마운트 경로를 새 graphVersion 으로 갱신. **compose `<<:` 는 volumes 를 병합하지 않으므로 `x-app-service` 와 `worker` 두 곳 모두 수정.**
3. `docker compose config -q` PASS 확인 → `docker compose up -d otp` → healthy 대기 → `docker compose up -d api worker`.
4. 기동 로그에 `형상 마킹 ACTIVE: graphVersion=<new> coveredModes=[bus, subway]` 확인.
5. 롤백: 이전 그래프 디렉터리 보존 + compose 백업 복원.

**앱 이미지는 건드리지 않는다.** 형상 설정은 전부 `routing.otp.*` env 주입이며, 프로덕션 이미지 `nextroute:20260917-170550-simpleclient` 는 미머지 PR #204(SimpleClientHttpRequestFactory fix)를 포함하므로 main 으로 재배포하면 OTP 가 502 로 되돌아간다.

## 4. PR 분할 (per-task-pr: 각각 fresh main 기준 독립 브랜치)

- **PR-1** = B1 + B2 + B3 + B4. 브랜치 `feat/otp-p4-search-polyline`. 지하철은 인덱스 미커버라 자동 null → 회귀 0.
- **PR-2** = A1 산출물 + A2. 브랜치 `feat/otp-p4-subway-shapes`. 코드 변경은 tools/config 만.
- **PR-3** = (필요 시) B4 측정 결과에 따른 크기 가드, B5 결정 반영.

플랜 파일(이 문서 + SUMMARY.md)은 PR-1 에 포함해 커밋한다.

## 5. 작업 순서 (체크박스)

- [ ] B1 디코더(fail-closed) + 테스트 5종 → `./gradlew test --tests "*EncodedPolyline*"`
- [ ] B2 대중교통 주입 + 계약 테스트 3개
- [ ] B3 도보 주입 + 테스트
- [ ] `./gradlew compileJava compileTestJava` + `./gradlew test --tests "*Otp*" --tests "*LegacyRouteResponse*"`
- [ ] B4 응답 바이트 + **스냅샷 크기/Redis 예상 상주량** before/after 실측 → 32MB 기준 판정 → 노트 `### 검증` 기록
- [ ] PR-1 오픈 → 리뷰 → 머지 → 배포(로컬 bootJar → scp → `DEPLOY_REVISION=<sha> ./deploy.sh all`) → 실검색에서 버스·도보 polyline non-null 확인 + **배포 후 Redis `used_memory` 추이 확인**
- [ ] A0 스크립트 mode 일반화 → `--modes bus` 결과가 기존 인덱스와 동일함 확인(회귀 0)
- [ ] A1 측정 실행 → STAGE2-DISTRIBUTION.md 작성 → **지하철 임계 결정(사용자 확인)** + rail 포함 여부 판단
- [ ] A2 게이트 확장 + 그래프 재빌드 + 번들 verify PASS
- [ ] PR-2 오픈 → 머지
- [ ] A3 배포 → `coveredModes=[bus, subway]` 로그 확인 → 실검색에서 지하철 polyline non-null 확인

## 6. 검증 (완료 판정)

- 계약당 테스트 최소 1개: SHAPE_OK→채움 / STREET_FALLBACK→null / 도보→채움 / 디코더 좌표순서 / **malformed→예외 없이 null**.
- 배포 후 실측: VPS 검색 응답에서 버스·도보(그리고 A3 후 지하철) leg 의 polyline 이 non-null 이고 점 개수가 정류장 수보다 크다.
- 회귀: 기존 검색 계약(필드 구성·`meta`) 무변경, 실검색 200 유지, **Redis `used_memory` 가 가드 기준(32MB) 아래**.
- 지도 실표시는 모바일 몫 → `handoffs/HANDOFF.md` 에 API 예시 응답과 좌표 순서(x=lon, y=lat) 명시.

## 7. 미결

- B5: `geometryQuality` 를 subPath 에 노출할지(클라가 "선 없음"을 구분 가능하게).
- 여정 내비 API(`/api/journeys/{id}/navigation-geometry`)의 버스 소스는 여전히 `bus_route_segment_polyline` DB(ODSAY 유래, 26% REVIEW 구멍)다. 검색과 내비가 서로 다른 형상을 쓰는 상태 → OTP 로 통일할지 별건 결정.
- degraded 버스 trip 1477개(1.01%)는 계속 null. 사용자 체감상 "일부 노선만 선이 없음"으로 보인다.
