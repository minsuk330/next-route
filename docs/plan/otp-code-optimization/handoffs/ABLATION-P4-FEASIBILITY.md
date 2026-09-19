# P4 feasibility pilot — 2026-09-15

상태: **A feasibility pilot 3회 완료, formal P4 gate 미통과.** 두 번째 실행으로 4-OD A 기준 응답을 만들었고, 세 번째 실행은 그 4-OD 기준과 mismatch 0이었다. 세 run 모두 swap-in이 발생해 이 문서는 성능 비교 결과가 아니다.

## 무엇을 확인했나

P4 전체 비교 전에 현재 18GiB Apple Silicon Mac에서 측정 조건이 성립하는지 확인했다. 원본 A JAR로 c1 feasibility pilot을 세 번 실행했다. 두 번째 run은 첫 run의 불완전한 `--compare-to` corpus를 제거해 전체 4개 OD A 기준 응답을 만들었고, 세 번째 run은 이를 비교 기준으로 삼아 mismatch 0을 재확인했다. A/HF paired run·동시성 2/4·정식 반복 표본은 실행하지 않았다.

| 조건 | 값 |
| --- | --- |
| Run ID / 변형 | `p4-feasibility-c1-A-20260915` / A |
| JAR SHA-256 | `f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52` |
| Graph SHA-256 | `24d5331b29ce88fa26750187056dd9ccc3acb43eb242e7d8ecad5ea037658a0a` |
| JVM / image | Temurin 25.0.4+7 / `sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753` |
| Heap / container memory | `-Xmx10g` / 12g |
| Workload | 4 OD, c1, OD별 warmup 100·측정 100, seed `20260908`, `2026-09-15T08:00:00+09:00` |
| 예열 안정화 | window 100, tolerance 5%, timeout 900초; **통과** |

감사·재현용 실제 실행 명령이다. `--compare-to` 대상이 2-OD P2 baseline이라 이 명령을 formal P4용으로 그대로 재사용하면 안 된다.

```sh
python3 /private/tmp/otp-ablation-runner-20260914/run_isolated.py \
  --jar /private/tmp/otp-ablation-artifacts-20260914/A.jar \
  --graph /private/tmp/otp-graph-rebuild/graph.obj \
  --output-dir /private/tmp/otp-p4-feasibility-20260915/c1-A \
  --run-id p4-feasibility-c1-A-20260915 --variant A --concurrency 1 \
  --warmup-per-case 100 --requests-per-case 100 \
  --require-warmup-stability --warmup-stability-window 100 \
  --warmup-stability-tolerance 0.05 --warmup-stability-timeout-seconds 900 \
  --compare-to /private/tmp/otp-ablation-p2-20260914/block1-A/requests.jsonl \
  --source-identity 'A baseline jar f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52; P4 feasibility' \
  --seed 20260908 --when 2026-09-15T08:00:00+09:00 \
  --xmx 10g --container-memory 12g \
  --image sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753
```

## 결과와 유효 범위

- 예열 400/400, 측정 400/400 성공. RSS/GC 수집 상태 `OBSERVED`, OOM 없음, cleanup 오류 없음.
- warmup p50/p95 `384.659/469.545ms`, measured 전체 p50/p95 `379.885/437.679ms`다. 단일 feasibility run의 기술값일 뿐이며, swap gate가 실패했으므로 **성능 증거로 인용하지 않는다.**
- A 비교 파일은 기존 P2 block-1 A로 2개 OD만 포함한다. 이번 파일은 4개 OD여서 runner가 `mismatchCount=200`으로 종료했다. 그 200건은 baseline이 없던 `bupyeong-gangnam`, `cheonggye-sadang` 각 100건이며, 실제 응답 불일치를 뜻하지 않는다. 공통 OD인 `cityhall-gangnam`, `bupyeong-seoul`은 baseline 비교 불일치가 없었다. 그러므로 전체 4-OD correctness 대조도 완성된 것이 아니다.
- `vm.swapusage`의 사용량은 `1926.38MiB → 1798.38MiB`로 약 128MiB 감소했지만, 누적 OS counter는 swap-in 3,533,254→3,540,929 pages, 즉 **7,675 pages ≈119.9MiB 증가**를 기록했다. swap-out은 증가하지 않았다. 사용 중 swap의 스냅샷 감소는 실행 중 swap I/O가 없었다는 뜻이 아니다.
- OS page size 16KiB 기준 page-in은 4,074 pages(약 63.6MiB), page-out은 917 pages(약 14.3MiB) 증가했다. free pages는 10,818→6,013이었다.

P4의 사전 조건은 공식 측정 구간 중 host swap-in/out delta가 모두 0인 것이다. swap-in이 증가했으므로 현재 Mac run은 이 gate를 통과하지 못했다. warmup 안정화나 400/400 성공은 환경 gate를 대신하지 않는다.

## 두 번째 A pilot — 4-OD 기준 corpus 확보

첫 run의 `--compare-to` 경로가 두 OD만 담고 있었으므로, 새 output 경로에서 비교 옵션 없이 A를 다시 실행했다. 실행은 정상 종료했고, 이 응답 파일은 이후 HF run의 전체 4-OD fingerprint baseline으로 사용할 수 있다.

```sh
python3 /private/tmp/otp-ablation-runner-20260914/run_isolated.py \
  --jar /private/tmp/otp-ablation-artifacts-20260914/A.jar \
  --graph /private/tmp/otp-graph-rebuild/graph.obj \
  --output-dir /private/tmp/otp-p4-feasibility-retry-20260915/c1-A-run2 \
  --run-id p4-feasibility-retry-c1-A-run2-20260915 --variant A --concurrency 1 \
  --warmup-per-case 100 --requests-per-case 100 \
  --require-warmup-stability --warmup-stability-window 100 \
  --warmup-stability-tolerance 0.05 --warmup-stability-timeout-seconds 900 \
  --source-identity 'A baseline jar f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52; P4 feasibility retry without external compare' \
  --seed 20260908 --when 2026-09-15T08:00:00+09:00 \
  --xmx 10g --container-memory 12g \
  --image sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753
```

| 조건 | 값 |
| --- | --- |
| Run ID / output | `p4-feasibility-retry-c1-A-run2-20260915` / `/private/tmp/otp-p4-feasibility-retry-20260915/c1-A-run2/` |
| 결과 | warmup stable=true, warmup 400/400, measured 400/400, RSS/GC `OBSERVED`, OOM=false, cleanup 오류 없음 |
| diagnostic p50/p95 | `343.677/407.787ms` — swap gate 실패로 성능 비교에 사용하지 않음 |
| swap counter | swap-in 3,545,147→3,545,397: +250 pages ≈3.91MiB; swap-out delta 0 |
| swap usage snapshot | `1734.38MiB → 1734.38MiB` (변화 없음) |
| page counter | page-in +3,507 pages ≈54.8MiB; page-out +159 pages ≈2.48MiB |

기존 pilot과 새 pilot의 measured fingerprints를 OD별 100건씩 오프라인 대조했다. `cityhall-gangnam`, `cheonggye-sadang`, `bupyeong-seoul`, `bupyeong-gangnam` 각각 **100/100 mismatch 0**이었다. 이는 같은 A artifact에서 4-OD reference가 반복 재현됨을 보인다. HF 전체 응답 대조는 다음 full correctness query로 별도 검사했다. 4-OD baseline 미비는 해소됐지만 두 번째 run의 swap-in으로 formal no-swap gate는 여전히 실패다.

Retry raw SHA-256: `summary.json` `7d6acd83d7df13bdc497c4448698b3516e2236be998c6f067bca25b5f1179b5e`, `environment.json` `0d74c43188e688e2dd59f86fab9e5b3815452776dca34cb0f6677af1f0d9d845`, `requests.jsonl` `431865cd9357b10589cebfd49951c001418542048f21874b5d6165a790817d3f`.

## 세 번째 A feasibility recheck — 기준 응답 일치, swap gate 실패

03:33:46–03:36:32 KST 사이 별도 `vm_stat` 스냅샷의 swap counter는 그대로였고 `memory_pressure`는 system-wide free 78%를 표시했다. 이를 계기로 같은 Mac에서 feasibility를 한 번 더 시험했다. 기존 Docker 서비스는 건드리지 않았다.

```sh
python3 /private/tmp/otp-ablation-runner-20260914/run_isolated.py \
  --jar /private/tmp/otp-ablation-artifacts-20260914/A.jar \
  --graph /private/tmp/otp-graph-rebuild/graph.obj \
  --output-dir /private/tmp/otp-p4-feasibility-recheck-20260915/c1-A-run3 \
  --run-id p4-feasibility-recheck-c1-A-run3-20260915 --variant A --concurrency 1 \
  --warmup-per-case 100 --requests-per-case 100 \
  --require-warmup-stability --warmup-stability-window 100 \
  --warmup-stability-tolerance 0.05 --warmup-stability-timeout-seconds 900 \
  --compare-to /private/tmp/otp-p4-feasibility-retry-20260915/c1-A-run2/requests.jsonl \
  --source-identity 'A baseline jar f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52; third P4 feasibility after stable swap-counter window' \
  --seed 20260908 --when 2026-09-15T08:00:00+09:00 \
  --xmx 10g --container-memory 12g \
  --image sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753
```

| 조건 | 값 |
| --- | --- |
| Run ID / output | `p4-feasibility-recheck-c1-A-run3-20260915` / `/private/tmp/otp-p4-feasibility-recheck-20260915/c1-A-run3/` |
| 실행 구간 | 03:37:46–03:42:08 KST, c1, A, 고정 4 OD |
| 정합성 / 요청 | warmup 400/400, measured 400/400, A reference 비교 `mismatchCount=0`, `manifestMismatch={}` |
| 측정기 / 안정성 | warmup stability 요구됨, RSS/GC `OBSERVED`, memory samples 1,156, coverage 양끝 정상, 최대 sample gap 0.114초 |
| 자원 | peak RSS 3,424,190,464B, peak container 3,681,214,464B, GC 총정지 1,185.147ms, 최대 9.901ms, OOM=false |
| 진단용 지연 | p50/p95 `341.836/384.291ms`; swap gate 실패이므로 성능 비교에 사용 금지 |
| host swap counter | `Swapins` 3,546,190→3,546,278: **+88 pages ≈1.38MiB**; `Swapouts` 4,851,609→4,851,609: delta 0 |
| 다른 host counter | page-in +1,955 pages, page-out +426 pages; swap usage `1718.38MiB → 1718.38MiB` |
| 종료 / cleanup | runner exit 0, OTP harness OOM=false, cleanup errors 없음; 기존 Postgres/Redis는 healthy 유지 |

세 번째 run은 warmup·요청·정합성·수집 게이트를 통과했지만 swap-in delta가 0이 아니어서 **P4 formal sample은 아니다**. 원자료 SHA-256: `summary.json` `abd5f142b83485f1ab79636a67a044c38279d3a897f2f3ef154a669a5f97431a`, `environment.json` `f505ae0a58d2470a4038514913be0815ba79bd8d7bebfd466b159098ee1752da`, `requests.jsonl` `ade36422201a4feb3bb5b4f0ddaf51f468dcf6331c50dff99bcf56e679c32c53`, `metrics.json` `002d74a05a2c6ff1b67d617940bc7249ce2ea26385ca60025d4549a6229d68ad`.

## Full 4-OD A/HF correctness comparison

After establishing the A reference, one full-detail correctness request per OD was run on A and HF using the same graph, date/time, seed, query hash, concurrency, JDK, image, heap, and container cap. The correctness query compares complete itineraries, scheduled/estimated times, stop and route/trip IDs, coordinates, and encoded leg geometry. Both runs returned 4/4 successful requests; the HF comparison had **0/4 fingerprint mismatches**, no manifest mismatch, no OOM, and no cleanup errors. This passes the scoped A/HF correctness gate for these four cases; it is not broad geographic or repeated-sample correctness coverage.

| Input/result | A reference | HF comparison |
| --- | --- | --- |
| Run ID | `p4-correctness-A-20260915` | `p4-correctness-HF-20260915` |
| JAR SHA-256 | `f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52` | `54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f` |
| Requests | 4/4 success (1 per OD) | 4/4 success (1 per OD) |
| Full response comparison | baseline | mismatch 0, `manifestMismatch={}` |
| Raw output | `/private/tmp/otp-p4-correctness-20260915/A/` | `/private/tmp/otp-p4-correctness-20260915/HF/` |

The runner's exact commands are retained in each run's `environment.json`. Raw SHA-256:

- A: `summary.json` `b0b08b842b7c70c194a5b6594842ac827a0469621e0510e36d830270f71e22a2`, `environment.json` `af9ee9d06dab877522ca17204942dbd2d57872a1b611ad329c4ec0434d4f63d9`, `requests.jsonl` `9a2fdc880ae4e1a77a0749acc017c7cf9fed3e4925914e6c595228d3d2dc8623`.
- HF: `summary.json` `8ba765e54b56b98783870d2393c5033bb7ce7e188574db1b58268911ed8a3824`, `environment.json` `b6a2f8484b30233de78e5fcca84f278a0de4eaa7d922951f761c6a9744fbb63e`, `requests.jsonl` `65bb3a6f8e6af576edc9c5ba23155b3e18205583a947109345932cf370290e60`.

## 다음 재개 조건

2026-09-15 03:33 KST 재점검에서 benchmark가 없던 30초 동안 `Swapins`가 +12 pages(약 0.19MiB), `Swapouts`는 0이었다. 뒤이어 카운터가 약 2분 46초간 그대로이고 `memory_pressure`가 free 78%를 표시해 세 번째 A feasibility를 실행했지만, 그 run에서 swap-in +88 pages가 다시 발생했다. 따라서 현재 Mac은 세 번 모두 strict zero-swap gate를 통과하지 못했다. 짧은 안정 구간이나 swap usage snapshot 불변은 gate 통과를 보장하지 않는다. 이번 측정의 입력 전 counter는 03:36:32 스냅샷과 일치했고, run 후 counter는 03:42:08 KST에 저장됐다.

1. P4 workload에서도 swap-in/out이 0으로 유지될 수 있는 격리된 충분 메모리 host를 준비한다. 현재 증거만으로 어떤 host 용량이면 충분하다고 단정하지 않는다. Linux host에서는 현재 runner의 `hostPressureBefore/After`가 `/proc/meminfo`뿐이라 누적 swap I/O를 판정할 수 없다. feasibility run 전후 `/proc/vmstat`의 `pswpin`/`pswpout`을 별도로 저장하고, 각 run의 delta가 0인지 확인한다.
2. 그 host에서 A feasibility pilot을 실행해 swap gate와 측정기 수집 완전성을 확인한다. 현재 Mac에서는 새로 관측된 조건 변화가 없는 한 같은 feasibility를 반복하지 않는다. 새 host/architecture를 쓰면 동일한 4-OD A/HF full correctness query를 다시 수행한다.
3. pilot이 모두 통과한 뒤에만 A/HF c1/c2/c4, 동시성별 최소 5 AB/BA pair(총 30 run), run-pair 단위 10,000회 bootstrap을 실행한다. 다른 host의 표본을 기존 Mac 표본과 합치지 않는다.
4. Docker Desktop·PostgreSQL·Redis 또는 사용자 앱을 메모리 확보 목적으로 종료하지 않는다. 필요한 환경 변경은 사용자가 직접 선택할 때까지 하지 않는다.

## 기존 VPS host 적합성 점검

현재 연결된 `vps`도 P4 host 후보인지 읽기 전용으로 확인했다. `hostname=watoo`, `x86_64`, Linux 5.15 환경이며 다음 상태였다.

| 항목 | 관측값 | P4 판정 |
| --- | --- | --- |
| 물리 메모리 | `MemTotal 8,091,472 kB` (약 7.7GiB) | `-Xmx10g`를 swap 없이 수용할 수 없음 |
| 가용 메모리 | 약 5.0GiB | 부적합 |
| swap | 총 4.0GiB, 사용 약 845MiB, free 약 3.2GiB | 이미 swap 사용 중; strict zero-swap 불가 |
| 누적 counter | `pswpin 2,230,912`, `pswpout 2,930,050` | baseline counter는 있으나 0 delta 보장 안 됨 |
| 실행 중 서비스 | `nextroute-api`, `nextroute-worker`, ML serve, Prometheus/Tempo/Grafana/Alloy/Loki/node-exporter, Redis, PostgreSQL | 측정 격리 안 됨 |

따라서 이 VPS에서 `-Xmx10g` P4를 실행하면 host swap 또는 OOM 위험이 있으며, 기존 서비스 중지 없이 formal evidence를 만들 수 없다. 이 점검은 서비스 상태를 변경하지 않았다. VPS 운영 관측의 4.5GiB container-cap 결과는 별도 운영 용량 자료이지, 이번 `-Xmx10g` no-swap A/HF 비교를 대체하지 않는다. 필요한 host는 최소 메모리 용량을 추정해 임의로 정하지 말고, 실제 workload를 실행하는 동안 `pswpin/pswpout` delta 0을 먼저 확인해야 한다.

원본 raw: `/private/tmp/otp-p4-feasibility-20260915/c1-A/` (`summary.json`, `environment.json`, `requests.jsonl`). SHA-256은 각각 `a91e8b49f19cb6129547992654b4a197340a9cd0530c837b8247c86cbae2a727`, `c41780756e845a6bfd9fab6657848d3fb815d6686dd3717a1ffb2678702b80e7`, `70d379b985f42b33767408e7fd98d30619038c765db0bc87493de82226a84d20`다. 집계와 입력 hash는 [ABLATION-MANIFEST.json](ABLATION-MANIFEST.json), 실행 이력은 [ABLATION-RUN-LEDGER.md](ABLATION-RUN-LEDGER.md)에 기록했다.
