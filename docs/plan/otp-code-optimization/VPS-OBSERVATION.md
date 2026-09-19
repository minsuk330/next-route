# VPS 운영 관측 — 원본 A 와 H+F 를 실제 서버에서 한 번씩 띄워 본다

작성일: 2026-09-11. 상태: **COMPLETE / HISTORICAL OPERATIONAL OBSERVATION**. 실서버 관측은 [handoffs/VPS-OBSERVATION-RESULT.md](handoffs/VPS-OBSERVATION-RESULT.md)에 기록됐다. 이 문서는 운영 용량 관측 절차를 보존하며, 현재 `-Xmx10g` formal gate나 25% 축소 성능 실험의 근거로 사용하지 않는다. 상위 계획: [PLAN.md](PLAN.md), 최종 판정: [handoffs/PHASE-4.md](handoffs/PHASE-4.md), 큰 heap 결과: [handoffs/LARGE-HEAP-RESULT.md](handoffs/LARGE-HEAP-RESULT.md).

> **큰 heap 실험이 예측을 강화함(2026-09-11).** 로컬 8g 에서 원본 A 는 동시 4건일 때 peak RSS 9.0~9.1G, GC 총정지 14~35초였다. VPS 는 여유 5.1G·헤드룸 1.2G 뿐이라 **원본은 동시성이 조금만 올라도 컨테이너 상한(4500m)에서 죽거나 스래싱할 것이 거의 확실**하다. H+F 는 동시성 무관 peak RSS 3.0~3.2G 로 헤드룸 안에 든다. 이 관측의 목적은 그 대비(원본 X / 후보 O)를 실서버에서 1회 확인하는 것이다.

## 0. 한 줄 결론

**로컬 paired gate 는 재실행하지 않는다.** VPS 에서는 두 가지만 답한다 — ① 원본 A 가 이 서버에 들어가는가, ② H+F 는 들어가고 얼마나 쓰는가. 결과는 "운영 관측 1회" 로 기록하고, 로컬 개선율과 섞어 새 개선율을 만들지 않는다.

## 1. 목적과 비목적

| 목적 | 비목적 |
| --- | --- |
| x86 + 8GiB 공유 호스트에서 H+F 가 수도권 graph 를 서빙하는지 확인 | 원본 대비 개선율 재산정 (로컬 20쌍이 이미 답함) |
| 같은 조건에서 원본 A 의 idle/peak RSS·응답을 1회 기록 | 30분 soak, 예열 안정 게이트, 5쌍 반복 |
| 포폴 문장 "VPS 8G 에서 원본 X / 후보 O, 운영 RSS n G" 의 근거 확보 | nextroute provider 를 OTP 로 전환하는 운영 배포 (별건, PHASE-6 runbook) |

## 2. 전제 (실행 전 전부 체크)

- [ ] **graph 는 로컬 dev graph 그대로.** `/private/tmp/otp-seoul-dev-graph/graph.obj` (229MB, SHA-256 `8b597d8b…3002385`). 2.9.0 으로 만든 운영 graph 는 2.10.0-SNAPSHOT JAR 가 못 읽는다. A/H+F 둘 다 같은 커밋 계열이라 이 graph 를 공유한다.
- [ ] **JAR 2개 존재·해시 일치.**
  - A: `/private/tmp/otp-baseline/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` = `914d9a58…225b1`
  - H+F: `/Users/wa/golbob/개인/OpenTripPlanner/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` = `54bbdbcf…db7f`
- [ ] **VPS 에 OTP 가 안 떠 있다.** `compose.yaml` 에 otp 서비스 없음. `docker ps` 로 재확인. 포트는 18081 사용(18080 은 향후 운영 OTP 몫).
- [ ] **VPS python3 있음** (측정기 4개 스크립트는 표준 라이브러리만 씀). `python3 -c "import statistics, urllib.request"` 로 확인.
- [ ] **여유 메모리.** `free -m` 에서 available ≥ 4,800MB 면 A 실행 가능. 그 미만이면 A 는 c1 만, 또는 A 생략하고 사실만 기록.
- [ ] **시간대.** KST 01:30~04:00. 지하철 수집기 off-hours(01:00~04:30) 이고 라벨 배치(04:50)·아카이브 cron(05:20) 전. 버스 수집기는 매분 도니 완전 정지는 아님 — 기록에 남긴다.
- [x] **측정기 옵션 추가 완료 (OTP 저장소 `test/performance/seoul-metro/run_isolated.py`).** `--image`(기본=ARM64 digest, VPS 는 x86 digest 전달), `--container-memory`(기본 없음, VPS 는 4500m), `--xmx`(기본 3500m). manifest 에 image·containerMemory·jvmOptions 기록됨. syntax·help 검증 완료. 커밋은 큰 heap 결과와 함께 `codex/otp-phase0-3`.
  - x86 digest 얻는 법(VPS): `docker pull maven:3.9-eclipse-temurin-25 && docker inspect --format '{{index .RepoDigests 0}}' maven:3.9-eclipse-temurin-25`. Java 25 만 맞으면 된다.

## 3. 절차

### 3-0. 전송

```bash
# 로컬
scp /private/tmp/otp-seoul-dev-graph/graph.obj vps:/root/otp-obs/graph.obj
scp /private/tmp/otp-baseline/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar vps:/root/otp-obs/otp-A.jar
scp /Users/wa/golbob/개인/OpenTripPlanner/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar vps:/root/otp-obs/otp-HF.jar
scp -r /Users/wa/golbob/개인/OpenTripPlanner/test/performance/seoul-metro vps:/root/otp-obs/harness
# VPS
sha256sum /root/otp-obs/graph.obj /root/otp-obs/otp-A.jar /root/otp-obs/otp-HF.jar   # 위 해시와 대조
```

총 ~600MB. 디스크 17G 제약이라 끝나면 JAR 2개는 지운다(§6).

### 3-1. 원본 A (들어가는지 + 1회 관측)

```bash
cd /root/otp-obs/harness
free -m > /root/otp-obs/free-before-A.txt
python3 run_isolated.py --jar /root/otp-obs/otp-A.jar --graph /root/otp-obs/graph.obj \
  --output-dir /root/otp-obs/run-A-c1 --run-id vps-A-c1 --variant A \
  --concurrency 1 --warmup-per-case 25 --requests-per-case 20 --port 18081 \
  --image <x86 digest> --container-memory 4500m
```

- 기록: 기동 성공 여부, graph load 시간, idle RSS(부하 직전), peak RSS, p50/p95, 400/400 성공 여부, `OOMKilled`, 실행 중 `free -m` 최소값, swap 사용량.
- **c2 는 c1 이 끝난 뒤 available ≥ 1,000MB 남을 때만.** c4 는 A 에서 하지 않는다(로컬 peak 4.0G, 헤드룸 없음). "안 해봤다" 를 그대로 적는다.
- A 가 기동 실패/OOM 이면 그 사실이 곧 결과다. 재시도는 `--container-memory` 를 5000m 로 한 번만.

### 3-2. H+F (같은 조건 + 동시성 4까지)

```bash
for c in 1 2 4; do
  python3 run_isolated.py --jar /root/otp-obs/otp-HF.jar --graph /root/otp-obs/graph.obj \
    --output-dir /root/otp-obs/run-HF-c$c --run-id vps-HF-c$c --variant HF \
    --concurrency $c --warmup-per-case 25 --requests-per-case 20 --port 18081 \
    --image <x86 digest> --container-memory 4500m
done
```

- A 와 같은 항목 기록. c4 도 한다(로컬 peak 1.98G).
- 정합성 1회: `--corpus-check --query-mode correctness` 로 14건 corpus 를 돌리고 로컬 baseline raw(`/private/tmp/otp-a-a-corpus-new/requests.jsonl`, 미리 scp) 와 `--compare-to` 비교. mismatch 0 이어야 한다. **x86 에서 tie 순서가 달라질 수 있으니** mismatch 가 나오면 성능 기록과 분리해 원인만 적는다.

### 3-3. 요약

```bash
python3 summarize_run.py /root/otp-obs/run-A-c1 ...   # run 마다
```

결과는 `handoffs/VPS-OBSERVATION-RESULT.md` 에 아래 표 형식으로.

| 변형 | 동시성 | 기동 | load 초 | idle RSS | peak RSS | p50 / p95 ms | 성공 | OOMKilled | 호스트 available 최소 | swap |
| --- | ---: | --- | ---: | ---: | ---: | --- | --- | --- | ---: | ---: |
| A | 1 | | | | | | /20×4 | | | |
| A | 2 | (조건부) | | | | | | | | |
| HF | 1 | | | | | | | | | |
| HF | 2 | | | | | | | | | |
| HF | 4 | | | | | | | | | |

환경 한 줄: VPS CPU/arch(`uname -m`, `nproc`), RAM 8G, 동시 실행 중이던 컨테이너 목록(`docker ps`), 이미지 digest, JVM 옵션(`-Xmx3500m -XX:+UseStringDeduplication` 로컬과 동일).

## 4. 중단 기준

- 호스트 available < 500MB 또는 swap 급증 → 즉시 `docker stop otp-harness-*`. postgres/redis/api/worker 헬스 확인 후 재개 여부 결정.
- api/worker 컨테이너 재시작 감지 → 중단. 원인이 관측 때문인지 로그로 확인.
- 측정기가 `stable=false`/`INCOMPLETE` 를 내도 이번엔 실패가 아니다. 그대로 기록한다(예열 게이트를 안 걸었으므로).

## 5. 해석 규칙 (포폴에 쓸 때)

- 쓸 수 있는 문장: "8GiB VPS 에서 원본은 [기동 실패 / c1 만 서빙 / idle n G]. H+F 는 동시 4건까지 서빙, peak RSS n G." 절대값·1회·운영 조건 명시.
- 쓰면 안 되는 문장: VPS 수치로 계산한 개선율. 개선율은 로컬 paired gate 표만.
- OD 4개·20건/OD 는 관측이지 통계가 아니다. 표본 수를 옆에 적는다.
- capacity-report 의 4G/Full GC 41회는 **전국** graph 수치. 수도권 원본 근거는 이번 3-1 결과로 대체한다.

## 6. 정리

- `docker rm` 은 측정기가 한다. 남은 것: `/root/otp-obs/otp-A.jar` 삭제(188MB). H+F JAR·graph 는 운영 전환(PHASE-6) 때 쓰므로 유지.
- `--image` / `--container-memory` 옵션 추가는 OTP 저장소 커밋 대상. 이 관측 결과와 함께 `codex/otp-phase0-3` 에 남긴다.

## 7. 미결

- x86 digest 값: VPS 에서 pull 후 확정.
- A 의 c2 실행 여부: c1 뒤 available 로 결정.
- 운영 전환 시점: 별건. 이 문서 범위 밖.
