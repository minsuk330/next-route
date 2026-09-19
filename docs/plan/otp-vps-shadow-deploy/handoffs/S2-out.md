# S2 → S3 핸드오프 (PR-1 이미지 + P2 graph 재빌드 산출)

작성: 2026-09-15. 세션: S2 (Dockerfile.otp + GHCR push + 2.10 graph 재빌드). PR: minsuk330/next-route#195 (Dockerfile.otp, base main).
다음 세션 S3 = PR-3(compose otp 서비스 + app env + OtpRoutingProperties.graphVersion bump) + PR-4(runbook + release-manifest). 이 파일 값이 S3 입력.

## 확정 아티팩트 (S3 에서 그대로 사용)

| 항목 | 값 |
|---|---|
| **GHCR image** | `ghcr.io/minsuk330/nextroute-otp:20260915-8dbad55` |
| **image digest (amd64)** | `sha256:77c240387c804135700bb466fb61ee7c1b0d969998a6bd2420f58099416eaa5a` |
| image mediaType | `application/vnd.docker.distribution.manifest.v2+json` (단일 arch amd64) |
| **base image** | `eclipse-temurin:25-jre` |
| base manifest-list digest | `sha256:15090d159279e5c158473eccb48cd87f57b3e3a47511a797eb5a7a7ea6f86b0f` |
| base amd64 digest (FROM 고정값) | `sha256:65655f706a97bcd4c45ebee8fc2848ab277ae9309ad59d70e39e991b9f230fd7` |
| fork commit / JAR SHA / ser id | `8dbad55` / `dcd08ded...` / `276` |
| **graph.obj SHA-256** | `4e098383e95566805f88a5de77e1cff9fe5838ef970cfa12345236d97e7ed72c` |
| **graph.obj 크기** | `229,616,986` bytes |
| graph serialization id | `276` (header `OpenTripPlannerGraph;0000276;`) |
| graph 통계 | \|V\|=808,616 \|E\|=2,068,184 \|Stops\|=43,783 \|Patterns\|=3,936 \|ConstrainedTransfers\|=284 |
| GTFS 입력 SHA | `0cb23b0d9d370c6c59a2e7374c1702a58f7306a60c339b0b422f66461f5016e6` (기존 manifest 와 동일) |
| OSM 입력 SHA | `0be8f32b094def1004296e8a7edf68070ca1ede91d99679918e0cc4910bd2170` (기존 manifest 와 동일) |
| **OTP transitService 창** | `[2026-09-14, 2026-11-16)` (빌드일 2026-09-15, `-P1D`~`P2M`) → 마지막 서비스일 **2026-11-15** |
| GTFS calendar 창 | `20260707..20261108` (입력 gtfs 동일 → 기존과 불변) |
| graphVersion 제안 (S3 확정) | `seoul-bbox-202503-cal20260707_20261108-otp276-g4e098383` |
| **로컬 graph.obj 경로** (S3 scp 원본) | `tools/transit-routing-benchmark/data/otp-seoul/graph-otp276-4e098383.obj` (untracked, 커밋 금지) |

## ⚠ 핵심 발견 — S3 PR-3 healthcheck 쿼리 정정 (PLAN 오류)

**PLAN §2/§3 의 healthcheck query `{feeds}` 는 이 이미지(OTP 2.10.0-SNAPSHOT)에서 항상 실패한다.**
- 실측: `{feeds}` → `Validation error (SubselectionRequired@[feeds]) : Subselection required for type '[Feed]'`.
- 2.9 에선 `feeds` 가 scalar list(`Iterable<String>`)였으나 **2.10 에선 `[Feed]` object type** 로 바뀜 → subselection 필수.
- **정답 쿼리 = `{feeds{feedId}}`**. 실측 응답 `{"data":{"feeds":[{"feedId":"1"}]}}` (errors 없음, feeds 비지 않음).
- **PR-3 compose healthcheck 수정 필수**:
  ```yaml
  test: ["CMD-SHELL", "R=$(curl -sf -X POST http://localhost:8080/otp/routers/default/index/graphql -H 'Content-Type: application/json' -d '{\"query\":\"{feeds{feedId}}\"}') && echo \"$R\" | grep -q '\"feedId\"' && ! echo \"$R\" | grep -q '\"errors\"'"]
  ```
- 판정: `data.feeds` 에 feedId 존재 + `errors` 키 없음. (PLAN §7 검증도 동일 쿼리로.)

## S2 검증 (실행 증거)
- **이미지 빌드/푸시**: `docker buildx build --platform linux/amd64 -f Dockerfile.otp --push` 성공. 원격 digest `77c24038...` 확인. OCI label = revision 8dbad55 / jar-sha dcd08ded / ser-id 276 / base 65655f70 (`org.opencontainers.image.version 26.04`·`created` 는 temurin base 상속, 무해).
  - ⚠ GHCR 최초 로컬 push 라 write 권한 없어 403 → 사용자가 `docker login ghcr.io -u minsuk330` (classic PAT `write:packages`) 후 재push 성공. (gh CLI 토큰엔 write:packages 없음.)
- **graph 재빌드**: PR-1 이미지(`--build --save`)로 3m26s, header `0000276` 확인, ser id 일치 = load 거부 위험 해소.
- **readiness smoke** (graph.obj file-mount only, 베이크된 otp-config 사용, `-Xmx1900m`):
  - `{feeds{feedId}}` = `{"data":{"feeds":[{"feedId":"1"}]}}` (errors 없음) ✅
  - `planConnection` (서울시청 37.5663,126.9779 → 강남 37.4979,127.0276) = 실경로 반환(버스 143/640, 472/144, WALK 다구간) ✅
  - post-load idle+1req RSS ≈ **1.693 GiB** (mem_limit 미설정 로컬 참고치; 3000m/-Xmx1900m soak 은 S3/배포 gate).

## 빌드 재현 절차
```sh
# 이미지 (컨텍스트 = otp.jar + otp-config.json stage dir)
docker buildx build --platform linux/amd64 -f Dockerfile.otp \
  --build-arg OTP_FORK_REVISION=8dbad55ba40a36606e47f98688db958b4c3eeab0 \
  --build-arg OTP_JAR_SHA256=dcd08ded...  --build-arg OTP_SERIALIZATION_ID=276 \
  --build-arg OTP_BASE_IMAGE=eclipse-temurin@sha256:65655f70... \
  -t ghcr.io/minsuk330/nextroute-otp:20260915-8dbad55 --provenance=false --push <stage>
# graph (stage = build-config.json + otp-config.json + gtfs.zip + osm.pbf 를 /var/opentripplanner 로)
docker run --rm --platform linux/amd64 -e JAVA_TOOL_OPTIONS=-Xmx8g \
  -v <stage>:/var/opentripplanner ghcr.io/minsuk330/nextroute-otp@sha256:77c24038... \
  --build --save /var/opentripplanner
test "$(head -c 28 graph.obj | tail -c 7)" = "0000276"
```

## S3 가 할 일 (요약)
- PR-3: compose `otp` 서비스 추가(image=위 digest, graph.obj file-mount, mem_limit/memswap 3000m, `-Xmx1900m`, **healthcheck 쿼리 `{feeds{feedId}}` 로 — 위 정정**), api/worker env 배선(`ROUTING_OTP_BASE_URL=http://otp:8080` + 토글 2개 shadow 기본값), `OtpRoutingProperties.graphVersion` = 위 graphVersion 으로 bump. **shadow 최초 배포 = `docker compose up -d otp` 만**(api/worker 재생성 금지).
- PR-4: deployment-runbook.md + release-manifest.json (위 image digest / base digest / graph SHA·크기 / ser id 276 / 입력 SHA / service window / graphVersion 전부 고정).

## 성능/정확성 게이트
- **P4_FORMAL_PENDING** 유지. shadow dormant(OTP HTTP 0)라 프로덕션 영향 0. 실제 provider 전환 전 README correctness fingerprint(4 OD) 재현 필수 + OTP adapter in-flight semaphore 선행.
- service window 만료 **2026-11-15** — runbook 에 21/7일전(2026-10-25 / 2026-11-08) 재빌드 알림.
