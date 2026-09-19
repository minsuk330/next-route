# OTP 수도권 shadow 배포 런북 (VPS 수동)

대상: VPS (`ssh vps`, /root/apps/nextroute). 배포자=단일 운영자.
전제: `docs/plan/otp-vps-shadow-deploy/PLAN.md` (L), handoffs/S1-out.md·S2-out.md.
상태: **dormant shadow** — OTP 컨테이너 기동만. `otpEnabled=false`/`defaultProvider=ODSAY` 라 앱은 OTP HTTP 호출 0. 기존 ODsay 검색·api/worker·postgres·observability 영향 0.

> **전환(OTP 실제 provider 화)은 이 런북 범위 아님.** 게이트만 배선. 전환 = §6 별건(3값 동시 + OTP adapter in-flight semaphore 선행).

## 0. 배포 아티팩트 (S2 확정, 대조용)

| 항목 | 값 |
|---|---|
| GHCR image | `ghcr.io/minsuk330/nextroute-otp:20260915-8dbad55` |
| **image digest** | `sha256:77c240387c804135700bb466fb61ee7c1b0d969998a6bd2420f58099416eaa5a` |
| base image | `eclipse-temurin:25-jre` amd64 `sha256:65655f70...` |
| fork / JAR / ser id | `8dbad55` / `dcd08ded...` / `276` |
| **graphVersion** | `seoul-bbox-202503-cal20260707_20261108-otp276-g4e098383` |
| **graph.obj SHA-256** | `4e098383e95566805f88a5de77e1cff9fe5838ef970cfa12345236d97e7ed72c` |
| graph.obj 크기 | `229,616,986` bytes (header `0000276`) |
| 로컬 graph 원본 | `tools/transit-routing-benchmark/data/otp-seoul/graph-otp276-4e098383.obj` (Mac) |
| **service window 만료** | **2026-11-15** (OTP transitService `[2026-09-14, 2026-11-16)`) |

## 1. 선행 (착수 전)
- VPS 메모리 여유 확인: `free -m` → available ≥ 3G (OTP peak ~2.0G + 완충). S2 실측 available 5195M.
- ⚠ 호스트 swap 이미 843M 사용중(S2 실측) — soak 중 swap **증가** 여부가 핵심 중단 신호.
- 디렉터리 생성: `mkdir -p /srv/nextroute/otp/seoul-bbox-202503-cal20260707_20261108-otp276-g4e098383`
- host port `127.0.0.1:18080` free 확인(S2: VPS grep 0건).

## 2. 배포 절차

### 2-1. 이미지 pull (digest 고정)
```sh
docker pull ghcr.io/minsuk330/nextroute-otp@sha256:77c240387c804135700bb466fb61ee7c1b0d969998a6bd2420f58099416eaa5a
```
`.env` 에 추가:
```
OTP_IMAGE=ghcr.io/minsuk330/nextroute-otp@sha256:77c240387c804135700bb466fb61ee7c1b0d969998a6bd2420f58099416eaa5a
```

### 2-2. graph.obj 전달 (원자, up **전에** 필수)
> ⚠ compose bind mount 는 소스 파일 없으면 **빈 디렉터리 graph.obj 를 만든다**(create_host_path). 반드시 `up -d otp` **전에** 파일을 배치하고 SHA 검증할 것.
```sh
# Mac 로컬에서
scp tools/transit-routing-benchmark/data/otp-seoul/graph-otp276-4e098383.obj \
    vps:/srv/nextroute/otp/seoul-bbox-202503-cal20260707_20261108-otp276-g4e098383/graph.obj
# VPS 에서 SHA 검증 (불일치 시 중단, 덮어쓰기 금지)
sha256sum /srv/nextroute/otp/seoul-bbox-202503-cal20260707_20261108-otp276-g4e098383/graph.obj
# 기대: 4e098383e95566805f88a5de77e1cff9fe5838ef970cfa12345236d97e7ed72c
```
- 덮어쓰기 금지. 이전 graph/image 는 보존(중단교체, blue/green 불가).

### 2-3. 기동 (**otp 만**)
```sh
docker compose up -d otp     # ⚠ api/worker 재생성 금지 — 전체 up -d 돌리지 말 것
```
- app-environment 의 `ROUTING_OTP_*` 변경분은 여기서 반영 안 됨(다음 앱 재배포 때). 토글 off 라 무해.

### 2-4. readiness + smoke
```sh
# health (compose healthcheck 와 동일 쿼리)
curl -sf -X POST http://127.0.0.1:18080/otp/routers/default/index/graphql \
  -H 'Content-Type: application/json' -d '{"query":"{feeds{feedId}}"}'
# 기대: {"data":{"feeds":[{"feedId":"1"}]}}  (errors 없음)
docker compose ps otp    # STATUS healthy 대기(start_period 180s)
# planConnection smoke (수도권 OD)
curl -sf -X POST http://127.0.0.1:18080/otp/routers/default/index/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{planConnection(origin:{location:{coordinate:{latitude:37.5663,longitude:126.9779}}},destination:{location:{coordinate:{latitude:37.4979,longitude:127.0276}}},first:1){edges{node{duration legs{mode}}}}}"}'
```
> ⚠ `{feeds}`(subselection 없이) 는 2.10 에서 GraphQL 검증에러(feeds=[Feed] object). 반드시 `{feeds{feedId}}`.

### 2-5. 자원 실측 + soak
- 실측: `docker stats otp --no-stream` + `free -m` peak 기록.
- soak: **compose 로 뜬 실 otp 컨테이너 대상** (OTP fork `test/performance/seoul-metro/soak_compose.py` — `run_isolated.py` 재사용 금지: 2번째 JVM 기동됨).
  - c1/c2/c4 짧은 run + 30분 soak(c4, otp 하나만). 전 run 동일 3000m/3000m/-Xmx1900m + service window 안 `--when` 고정.
  - 결과에 image digest / graph SHA / JAR SHA / resolved compose config / cgroup limit 기록.
- **중단 기준**: host `MemAvailable < 1G` / swap 증가 / 이웃 컨테이너 restart / OTP OOM-kill.

### 2-6. rollback (shadow)
```sh
docker compose stop otp      # 앱 무영향(dormant). 검색(ODsay) 정상 확인.
```
- 전환 후 rollback = 이전 image digest + 이전 graph + 이전 manifest **짝**으로 되돌림.

## 3. 검증 (완료 판정)
- [ ] OTP graph load 성공 + `{feeds{feedId}}` errors 없음/feeds 비지 않음.
- [ ] api/worker 기존 ODsay 검색 무영향(otpEnabled=false, 재생성 없음).
- [ ] 3000m/-Xmx1900m 에서 c1/c2/c4 + 30분 soak: OOM·swap증가·이웃 restart 없음.
- [ ] rollback(otp stop) 시 앱 정상.
- [ ] release-manifest ↔ 실제 image digest/graph SHA/serialization id 276 대조 일치.

## 4. service window 만료 (2026-11-15)
- **2026-10-25**(21일전) / **2026-11-08**(7일전) 재빌드 알림.
- 재빌드: S2 절차(동일 이미지로 graph `--build --save`) → SHA·serialization 검증 → scp 교체 → health.
- 실패 시 ODSAY 유지(otpEnabled 이미 false 라 자동).

## 5. 전환 절차 (별건 참조, 이 배포 범위 밖)
"env 2개 토글"은 base URL 이 이미 앱 컨테이너에 반영됐다는 전제에서만 성립. shadow 중 api/worker 는 `ROUTING_OTP_BASE_URL` 이 **런타임에 없음**(compose 엔 있으나 재생성 안 해서 미반영) → default `localhost:18080`(otpEnabled=false 라 무해). 전환 = **api/worker 재생성**하며 3값 동시 검증:
- `ROUTING_OTP_BASE_URL=http://otp:8080`
- `ROUTING_PROVIDER_DEFAULT_PROVIDER=OTP`
- `ROUTING_PROVIDER_OTP_ENABLED=true`
+ **OTP adapter in-flight semaphore 선행**(동시성 제한, 이 배포 범위 밖 별 PR).
+ 전환 전 README correctness fingerprint(4 OD) 재현(P4_FORMAL_PENDING).
