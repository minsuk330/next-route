# OTP 수도권 shadow 배포 — 에이전트 실행 계획 (L)

작성일: 2026-09-15. 티어: L (다PR·세션 분리·codex 논의 완료).
상위 계약: `docs/plan/national-otp-map-migration/PHASE-6.md` (P6-A 실행 가능한 배포 구성).
codex 리뷰 반영본 (session 01a0a2e2). SUMMARY.md 는 이 파일의 파생물.

## 0. 목표와 완료 계약

H+F 최적화 OTP 서버를 VPS 에 **dormant shadow** 로 배포한다. 트래픽 shadow 아님 — OTP 컨테이너를 기동만 하고, `otpEnabled=false`/`defaultProvider=ODSAY` 라 앱은 OTP HTTP 를 호출하지 않는다. 기존 운영(ODsay 검색, api/worker, postgres, observability)에 영향 0.

완료 = ① OTP 컨테이너가 VPS 에서 graph load + GraphQL `{ feeds }` readiness 통과 ② api/worker 재생성 없이 배포 ③ `mem_limit 3000m / -Xmx1900m` 에서 c1/c2/c4 + 30분 soak 통과 ④ rollback(otp 중단) 시 앱 무영향 확인 ⑤ deployment-runbook.md + release-manifest 정합.

**전환(OTP 실제 provider 화)은 이 작업 범위 아님.** 게이트만 배선한다. 전환은 별건(PHASE-6 P6-B + concurrency semaphore 선행).

## 1. 확정 사항 (코드만 봐선 안 나오는 결정)

- **빌드**: H+F 커스텀 OTP. 저장소 `/Users/wa/golbob/개인/OpenTripPlanner`, branch `codex/otp-phase0-3`, baseline `02c0b4b`. 미커밋 = **프로덕션 4 + 테스트 3** (B/C/E2 아님, codex 확인):
  - H: `TripPatternForDates.java`
  - F: `DestinationArrivalPaths.java`, `PathConfig.java`, `McRangeRaptorConfig.java`
  - 테스트: `TripPatternForDateTest.java`, `RaptorRoutingRequestTransitDataCreatorTest.java`, `TripPatternForDatesTest.java`
  - untracked: `test/performance/seoul-metro/` harness.
  - 배포 JAR = **H+F** = `otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` (179M), 문서상 SHA `54bbdbcf...db7f`. **단 이 JAR 은 dirty worktree 산출 → 배포 artifact 로 직접 쓰지 마.** PR-0 확정 커밋에서 깨끗이 재빌드한 JAR 을 씀. **성능 gate `P4_FORMAL_PENDING` — "완료" 표기 금지.**
- **버전 정합성 (확정)**: 2.9.0 운영 graph = serialization id **249**, H+F JAR(2.10.0-SNAPSHOT) = id **276**. OTP 는 id 불일치 시 graph load 거부 → 2.9 graph 재사용 불가, **재빌드 필수**. 재빌드는 배포할 정확한 JAR/image 로.
- **롤아웃**: dormant shadow. 전환 게이트 = env **2개** 동시 필요 — `ROUTING_PROVIDER_DEFAULT_PROVIDER=OTP` + `ROUTING_PROVIDER_OTP_ENABLED=true` (`RoutingProviderSelector.select(false)` 는 otpEnabled=false 면 ODSAY 강제).
- **메모리 (사용자 확정)**: `mem_limit 3000m`, `memswap_limit 3000m`(swap 0), `JAVA_TOOL_OPTIONS -Xmx1900m`. 근거: VPS 관측 c4 peak RSS 1.98~2.09G(단 4500m/-Xmx3500m 조건) + 완충. 검증값 아님 — soak gate 로 확정.
- **이미지 전략**: GHCR push (`ghcr.io/minsuk330/nextroute-otp`), digest 고정. VPS 는 pull 만. (VPS 로컬 빌드 기각 — 운영 자원 경쟁.)
- **graph 빌드 장소**: Mac 로컬 재빌드 후 scp. (VPS 재빌드 기각 — 7.7G 메모리·22G 디스크 경쟁.)
- **readiness**: GraphQL `{ feeds }` POST (파일 존재 검사 기각 — 잘못된 graph/deserialize 실패/index중 못 잡음).
- **V49 마이그레이션 제외**: `V49__create_transit_identity_mapping` 읽는 repository/adapter 없음 + `realtimeEnabled=false`. shadow 에 DB 안 건드림.
- **raw GTFS/OSM runtime mount 제외**: graph 만 read-only. raw 는 재빌드에만.

## 2. 함정 (증상 ← 원인 / 가드)

- api/worker 죽음 ← PR-3 에서 api/worker environment 바꾸고 `docker compose up -d` 하면 Compose 가 운영 컨테이너 재생성. **가드: shadow 최초 배포 = `docker compose up -d otp` 만.** baseUrl env 는 다음 앱 재배포(deploy.sh) 때 자연 반영.
- 이웃 컨테이너 OOM-kill ← OTP 폭주 시 커널이 최대 RSS(postgres 2.1G) 죽임. **가드: mem_limit 3000m + memswap 3000m(swap 스래싱 차단).**
- 요청 중 OTP OOM-kill ← -Xmx 를 cgroup(3000) 전체로 두면 non-heap 자리 0. **가드: -Xmx1900m (< 3000, non-heap ~1.1G 확보).**
- graph load 거부 ← serialization id 249≠276. **가드: PR-2 에서 배포 JAR 로 재빌드, manifest 에 id 276 대조.**
- 무경로 응답 ← 서비스 날짜 창 `2026-09-06~2026-11-07` 만료. **가드: runbook 에 windowEnd 21/7일전 알림 + 재빌드 교체 절차.**
- limit 합산 worst-case = api1.2+worker1.2+otp3.0+pg2.1+obs0.5 = 8.0G > 7.7G. 전부 동시 상한 칠 때만 OOM(actual RSS ~5.9G). **가드: 런타임 모니터 host MemAvailable<1G 중단.**
- healthcheck 무동작 ← Temurin base 에 curl 없을 수 있음. **가드: Dockerfile 에 curl 설치 확인, start_period ≥180s.**
- healthcheck 오판(항상 UP) ← `feeds` 는 scalar list 라 `{feeds{feedId}}` 는 GraphQL error(HTTP200). **가드: query `{feeds}`, data.feeds 존재 + errors 키 없음 확인.**
- 전환 시 OTP 호출이 `localhost:18080` 로 감(연결 실패) ← shadow api/worker 에 base URL env 미반영. **가드: 전환=app 재생성하며 base URL + 토글 2개 3값 동시.**

## 3. 좌표 (기존 코드)

- `src/main/java/watoo/grd/nextroute/application/route/config/OtpRoutingProperties.java:15,23` — baseUrl 기본 `localhost:18080`, graphVersion. (PR-2 에서 graphVersion 갱신.)
- `src/main/java/watoo/grd/nextroute/application/route/config/RoutingProviderProperties.java:16,18` — defaultProvider=ODSAY, otpEnabled=false.
- `src/main/java/watoo/grd/nextroute/infrastructure/adapter/out/api/otp/OtpRoutingAdapter.java:41` — GraphQL path `/otp/routers/default/index/graphql`, RestClient 전송.
- `compose.yaml:75,85` — api/worker 서비스 (앵커 `*app-service`). `compose.yaml:38` services.
- `deploy.sh` — app 전용 role-based 재생성(image .env 태그). **OTP 는 이 스크립트 밖, 직접 compose.**
- `tools/transit-routing-benchmark/config/otp-seoul/{build-config.json,otp-config.json}` — graph 빌드 config.
- `tools/transit-routing-benchmark/data/input/{seoul-bbox-202503.gtfs.zip,south-korea-seoul-metro.osm.pbf}` — 빌드 입력.
- `tools/transit-routing-benchmark/data/otp-seoul/release-manifest.json` — 기존 2.9.0 manifest (갱신 대상).

## 4. 작업 순서 (PR 분리, 각 fresh main, stack 금지)

### PR-0 — H+F fork commit 확정 (저장소: OpenTripPlanner)
- [ ] `codex/otp-phase0-3` 미커밋 = **프로덕션 4(H:TripPatternForDates + F:DestinationArrivalPaths/PathConfig/McRangeRaptorConfig) + 테스트 3** + `test/performance/seoul-metro/` harness 를 한 커밋. commit message 에 H+F 항목 + 성능 gate `P4_FORMAL_PENDING` 명시.
- [ ] **깨끗한 worktree/CI 에서 확정 커밋으로 shaded JAR 재빌드** (dirty worktree JAR `54bb...` 직접 배포 금지).
- [ ] manifest 4값 고정: fork commit SHA, JAR SHA-256, `otp-project-info.properties` build/revision, serialization id 276.
- [ ] 검증: `mvn -pl otp-shaded -am package` 성공, JAR SHA 재현. Raptor/application 테스트 통과(기존 8,225).
- 산출: fork commit SHA, JAR SHA-256, revision.

### PR-1 — OTP 컨테이너 이미지화 (저장소: nextroute 또는 OTP fork — 배치 위치 §6)
- [ ] `Dockerfile.otp` 신설. **exec-form entrypoint** (codex 확인 — `sh -c "java $JAVA_TOOL_OPTIONS ..."` 금지: launcher 가 JAVA_TOOL_OPTIONS 자동 read):
  ```dockerfile
  FROM eclipse-temurin:25-jre   # 실제 tag/배포판/digest 는 PR-1 에서 확정
  RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
  COPY otp-shaded-2.10.0-SNAPSHOT.jar /opt/otp/otp.jar
  COPY config/otp-seoul/otp-config.json /var/opentripplanner/otp-config.json
  WORKDIR /var/opentripplanner
  ENTRYPOINT ["java", "-jar", "/opt/otp/otp.jar"]
  CMD ["--load", "/var/opentripplanner"]   # build 시 이 인자만 override
  ```
  - graph·build-config 는 이미지에 굽지 않음(런타임 volume). **otp-config(feature flags)는 이미지에 구움** — 런타임에 graph.obj 만 file-mount 하면 image 의 otp-config 유지(dir 통째 mount 하면 가려짐).
  - **`-XX:MaxMetaspaceSize=256m` 넣지 마** (codex — 관측근거 없음, Metaspace OOM 위험).
- [ ] 로컬 build → GHCR push `ghcr.io/minsuk330/nextroute-otp:<date>-<forkSHA>`. **x86 타겟**(`--platform linux/amd64`). OCI label 에 fork revision + JAR SHA 넣음.
- [ ] image digest 고정.
- [ ] 검증: `eclipse-temurin:25-jre` 에 curl 존재 여부 확인(없으면 apt 설치 확정). 로컬에서 이미지로 OTP 기동(2.10 graph 필요 → PR-2 후) → `{ feeds }` 응답.
- 산출: image digest, base-image digest.

### PR-2 — 2.10 호환 graph 재빌드 (Mac 로컬)
- [ ] staging dir 구성 (build-config 가 `file:///var/opentripplanner/...` 절대 URI 고정 → 이 경로로 mount 필수):
  ```text
  <stage>/  build-config.json  otp-config.json  seoul-bbox-202503.gtfs.zip  south-korea-seoul-metro.osm.pbf
  ```
- [ ] **PR-1 image 와 동일 identity 로** 빌드 (`--save` 뒤 경로 = output 아닌 base directory positional):
  ```sh
  docker run --rm -v "<stage>:/var/opentripplanner" "<otp-image@sha256:...>" --build --save /var/opentripplanner
  ```
- [ ] serialization id **276** 확인 (graph header = `OpenTripPlannerGraph;0000276;`):
  ```sh
  test "$(head -c 28 graph.obj | tail -c 7)" = "0000276"
  ```
- [ ] `graph.obj` SHA-256 기록. `OtpRoutingProperties.java:23` graphVersion 갱신. image digest + graph SHA 를 release-manifest 에 동시 고정.
- [ ] 검증: 로컬 컨테이너에서 graph load → `{ feeds }` 비지 않음 + errors 없음 → `planConnection` smoke 1건(수도권 OD).
- 산출: graph.obj (versioned), graph SHA-256, serialization id 276, graphVersion. **graph 대용량은 Git PR 에 안 넣음.**

### PR-3 — compose otp 서비스 + app 배선 (저장소: nextroute)
- [ ] `compose.yaml` 에 `otp` 서비스 추가:
  ```yaml
  otp:
    image: ${OTP_IMAGE}  # GHCR digest
    volumes:
      - /srv/nextroute/otp/<graphVersion>/graph.obj:/var/opentripplanner/graph.obj:ro
    environment:
      TZ: Asia/Seoul
      JAVA_TOOL_OPTIONS: -Xms256m -Xmx1900m -XX:+UseG1GC   # MaxMetaspaceSize 넣지 마(근거없음)
    mem_limit: 3000m
    memswap_limit: 3000m
    ports:
      - "127.0.0.1:18080:8080"   # 호스트 디버그용. 컨테이너끼리는 내부 DNS
    healthcheck:
      # feeds = scalar list(Iterable<String>) → {feeds{feedId}} 아님, {feeds}. GraphQL error 도 HTTP200 → data.feeds 비지 않음 + errors 없음 확인.
      # R=응답 1회 캡처 후 판정(feeds 값 존재 && errors 키 없음). 구현 시 jq 있으면 jq 로 정밀화.
      test: ["CMD-SHELL", "R=$(curl -sf -X POST http://localhost:8080/otp/routers/default/index/graphql -H 'Content-Type: application/json' -d '{\"query\":\"{feeds}\"}') && echo \"$R\" | grep -q '\"feeds\":\\[\"' && ! echo \"$R\" | grep -q '\"errors\"'"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 180s
    restart: always
    logging: { driver: json-file, options: { max-size: "10m", max-file: "5" } }
  ```
- [ ] api/worker environment 에 배선 추가 (앵커 `*app-environment` 또는 서비스별):
  - `ROUTING_OTP_BASE_URL: http://otp:8080` (Spring relaxed binding → `routing.otp.base-url`. application.yaml 수정 불필요.)
  - 토글 준비(shadow 기본값 명시): `ROUTING_PROVIDER_OTP_ENABLED: "false"`, `ROUTING_PROVIDER_DEFAULT_PROVIDER: ODSAY`.
- [ ] `deploy.sh` 는 손대지 않음(app 전용). OTP 는 문서화된 직접 compose 명령.
- [ ] 검증: 로컬 `docker compose config` 유효. otp 서비스만 up 가능 확인.
- 산출: compose.yaml diff. **주의: api/worker environment 변경분은 다음 앱 재배포 때 반영 — shadow 배포 시 재생성 금지.**

### PR-4 — runbook + release manifest (저장소: nextroute)
- [ ] `docs/plan/otp-vps-shadow-deploy/deployment-runbook.md` 작성:
  - graph 전달(원자): `scp graph.obj vps:/srv/nextroute/otp/<graphVersion>/` → VPS 에서 SHA 검증 → compose 경로 갱신. 덮어쓰기 금지, 이전 graph/image 보존(blue/green 불가, 중단교체).
  - 기동: `docker compose up -d otp` (**otp 만**). health: `{ feeds }` 통과 대기. smoke: `planConnection` 1건.
  - 자원 실측: `docker stats` + `free -m` peak 기록. 중단기준 host MemAvailable<1G / swap 증가 / 이웃 restart / OTP OOM.
  - soak gate: **compose 로 뜬 실 otp 컨테이너 대상** (codex — `run_isolated.py` 재사용 금지: 자기 컨테이너 별도 기동 → 두번째 OTP JVM, 7.7G 에서 금지 + compose entrypoint/cgroup 검증 못함). 대안:
    - 부하 `compare_graphql.py --base-url http://127.0.0.1:18080`, 메모리 `collect_process_memory.py --container <otp> --pid 1`, 요약 `summarize_run.py`.
    - collector 가 프로세스 종료까지 sampling → **부하 종료 후 collector 정상종료+JSONL 묶는 작은 wrapper 필요** (PR 경계·위치는 §6 확정).
    - c1/c2/c4 짧은 run + 30분 soak(c4, otp 하나만). 전 run 동일 3000m/3000m/-Xmx1900m + service window 안 `--when` 고정. 결과에 image digest/graph SHA/JAR SHA/resolved compose config/cgroup limit 기록.
  - rollback: `docker compose stop otp` (앱 무영향, shadow). 전환 후 rollback = 이전 image digest + 이전 graph + 이전 manifest 짝.
  - **전환 절차(별건 참조)**: "env 2개 토글"은 **base URL 이 이미 앱 컨테이너에 반영됐다는 전제에서만** 성립. shadow 중 api/worker 는 `ROUTING_OTP_BASE_URL` 없어 default `localhost:18080` 사용(otpEnabled=false 라 무해). 전환 = **api/worker 재생성**하며 3값 동시 검증: `ROUTING_OTP_BASE_URL=http://otp:8080` + `ROUTING_PROVIDER_DEFAULT_PROVIDER=OTP` + `ROUTING_PROVIDER_OTP_ENABLED=true`. + OTP adapter in-flight semaphore 선행(§6).
  - 서비스 창 만료(2026-11-07): 21/7일전 재빌드 알림 + 검증후 교체 + 실패시 ODSAY 유지.
- [ ] `release-manifest.json` 갱신: fork commit SHA, JAR SHA-256, otp-project-info revision, image digest, base-image digest, Java 25, serialization id 276, graph SHA-256/크기, GTFS/OSM/config SHA-256, graphVersion, build 시각 + transitServiceEnd 산출기준, service window.
- [ ] 검증: runbook 명령이 compose/manifest 와 일치.

## 5. 배포 (VPS 수동 — runbook 실행)

1. GHCR pull (digest 지정) — `.env` 에 `OTP_IMAGE=<digest>`.
2. graph scp → `/srv/nextroute/otp/<graphVersion>/graph.obj`, SHA 검증.
3. `docker compose up -d otp` (**otp 만, api/worker 재생성 금지**).
4. `{ feeds }` health 통과 + `planConnection` smoke.
5. `docker stats`/`free -m` peak + swap 관측. soak.
6. rollback 시험: `docker compose stop otp` → 앱 검색(ODsay) 정상 확인.

## 6. 미결 (구현 세션 착수 전 확정)

- [ ] 이미지/Dockerfile 배치 저장소: nextroute repo vs OTP fork. (JAR 이 fork 산출물이라 fork 가 자연스러우나, 배포 config 는 nextroute. → build 는 fork, Dockerfile+CI 는 nextroute 권장.)
- [ ] otp-config(feature flags) 이미지 굽기 확정(codex 권장: image 에 굽고 런타임 graph.obj 만 file-mount).
- [ ] VPS host port `127.0.0.1:18080` 충돌 여부 재확인.
- [ ] **soak wrapper 신설** — `run_isolated.py` 재사용 금지(두번째 JVM). compose otp 대상 `compare_graphql.py`+`collect_process_memory.py`+collector 정상종료 orchestration wrapper. 위치/출력계약/PR경계 확정(harness 는 OTP fork 소유 → wrapper 도 fork? or nextroute tools?).
- [ ] eclipse-temurin:25-jre 실제 tag/배포판/digest 확정 + curl 존재 여부.
- [ ] OTP adapter in-flight semaphore — 전환 전 별 PR(이 배포 범위 밖, runbook 에 선행조건으로 명시).

## 7. 검증 (완료 판정)

- [ ] OTP graph load 성공 + `{ feeds }` errors 없음/feeds 비지 않음 (파일 존재 아닌 실제 query).
- [ ] api/worker 기존 ODsay 검색 무영향 (otpEnabled=false, 재생성 없음).
- [ ] 3000m/-Xmx1900m 에서 c1/c2/c4 + 30분 soak: OOM·swap증가·이웃 restart 없음.
- [ ] rollback(otp stop) 시 앱 정상.
- [ ] release-manifest ↔ 실제 image digest/graph SHA/serialization id 276 대조 일치.
