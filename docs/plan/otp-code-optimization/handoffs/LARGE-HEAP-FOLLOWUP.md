# 10GB heap PR 검증 — 메모리 여유 조건의 성능 비교

> 2026-09-14 이후 에이전트 실행 순서·변형 계약·측정량·완료 기준은 [LARGE-HEAP-AGENT-PLAN.md](LARGE-HEAP-AGENT-PLAN.md)를 따른다. 이 문서는 기존 측정 결과와 배경 근거를 보존한다.

작성일: 2026-09-13. 갱신: 2026-09-15. 상태: **기존 10GB 탐색·이전 5-pair matrix·P3 JFR/direct counter는 완료했고, 4-OD full-detail A/HF correctness query도 scoped pass했다. 최신 A/H/F/HF ablation의 P4 formal no-swap performance gate는 미완료다. 세 A feasibility run에서 swap-in이 각각 약 119.9MiB, 3.91MiB, 1.38MiB 증가했다.** 두 번째 실행으로 전체 4-OD A reference/A-A 재현성을 확보했고, 세 번째 run은 이 기준과 mismatch 0이었다. full-detail query에서 네 OD A/HF mismatch 0도 확인했다. 성능 matrix만 남았으며 swap-free host가 필요하다. survivor/promotion 분석과 15-OD 확장은 추가 원인 연구 후보로 남긴다. 최신 판정은 [LARGE-HEAP-AGENT-PLAN.md](LARGE-HEAP-AGENT-PLAN.md), [ABLATION-RESULT.md](ABLATION-RESULT.md), [P4 feasibility report](ABLATION-P4-FEASIBILITY.md)를 따른다. VPS 운영 관측은 완료됐다.

## 목표

PR을 올리기 전에 **heap 용량이 넉넉한 `-Xmx10g` 조건에서 기존 A와 PR 후보의 속도·메모리를 실제 비교**한다. 개선을 전제하지 않고 c1 회귀와 동시성 부하 이득을 함께 드러낸다. 호스트 swap 영향을 통제하고 PR에 실제 포함할 코드와 측정 JAR의 범위를 일치시킨다.

선행 자료:

- [8GB 결과](LARGE-HEAP-RESULT.md)
- [기존 원인 분석](LARGE-HEAP-ANALYSIS.md)
- [VPS 운영 관측 결과](VPS-OBSERVATION-RESULT.md)
- [3.5GB 최종 검증](PHASE-4.md)
- [최초 큰 heap 계획](../LARGE-HEAP.md)

기존 3.5GB·8GB 결과와 VPS 관측은 보조 근거로 보존한다. 최종 PR의 “충분한 heap에서도 동시성 성능이 개선됐다”는 주장은 아래 `-Xmx10g` 통제 결과만으로 판정한다.

## 0. 완료된 VPS 관측과 현재 결론

VPS 관측은 이미 완료됐다. 8GB x86 서버, OTP 컨테이너 상한 4500m, OD 4개 × 20건 조건에서 두 변형 모두 c4까지 80/80 성공했고 OOM은 없었다.

| 동시성 | A / HF peak RSS | A / HF p95 | A / HF GC 총정지 | 해석 |
| ---: | ---: | ---: | ---: | --- |
| 1 | 3.48G / 2.04G | 1,175 / 1,303ms | 766 / 266ms | HF는 메모리·GC 우세, 지연은 단일 관측상 열세 |
| 2 | 4.08G / 2.09G | 1,596 / 1,380ms | 4,461 / 282ms | HF가 RSS 약 49%, p95 약 14%, GC 정지 약 94% 개선 |
| 4 | 4.07G / 1.98G | 2,224 / 1,750ms | 6,240 / 449ms | HF가 RSS 약 51%, p95 약 21%, GC 정지 약 93% 개선 |

따라서 운영 후보로 H+F를 선택할 근거는 확보됐다. 다만 이는 각 조건 1회 관측이며 graph SHA도 로컬 실험과 다르다. 원인 규명과 일반화에는 반복 측정이 더 필요하다. 특히 원본이 운영 서버에서 기동하지 못하거나 OOM 난다는 이전 예측은 폐기한다.

## 0.1 `-Xmx10g` feasibility pilot — 무효·환경 차단

2026-09-12 로컬 Mac에서 문서의 swap 검증 pilot을 시작했다. 비교 표본으로 쓰려는 run이 아니라, A c4가 이 호스트에서 swap 없이 실행될 수 있는지 확인하는 짧은 사전 시험이었다.

| 항목 | 값 |
| --- | --- |
| Host RAM | 18GiB (`hw.memsize=19,327,352,832` bytes) |
| Docker VM | 14,592MiB, 11 vCPU |
| Baseline JAR A | SHA-256 `914d9a58c016d2ff1a697e2d6fb8d9d8dec7d93dada747d8162acd8a3da225b1` |
| Graph | SHA-256 `24d5331b29ce88fa26750187056dd9ccc3acb43eb242e7d8ecad5ea037658a0a` |
| JVM / container | `-Xmx10g`, `--memory=12g --memory-swap=12g` |
| Workload | A, c4, 예열 25 + 측정 20 / OD, 4 OD, `2026-09-15T08:00+09:00` |
| Peak observed before stop | Java RSS 10.43GiB; container cgroup peak 10.74GiB of 12GiB |
| Result | swap 증가 확인 후 중단. `metricsStatus=INCOMPLETE`, `exitCode=2`; 성능 결과로 사용 불가 |

Runner 시작 시 host `vm_stat`은 Swapins `60,108,219`, Swapouts `63,818,368` pages였다. 중단 직후에는 각각 `60,685,031`, `64,682,180` pages로 증가했다. macOS page size 16KiB 기준 약 **8.8GiB swap-in, 13.2GiB swap-out** 증가다. 전역 OS counter라 각 페이지를 OTP 단독 원인으로 단정하지 않지만, 같은 시간대에 memory pressure가 68%에서 32%로 떨어졌고 swap 사용량이 1.20GiB에서 5.69GiB로 증가해 이 run은 명백히 swap 통제 조건을 만족하지 않는다.

OTP harness는 컨테이너를 정리했고 Docker Desktop VM을 종료했다. Docker Desktop 기동 때 자동 재시작된 `nextroute-postgres-1`·`nextroute-redis-1`은 원래 상태대로 다시 정지했다. VM 종료 뒤 memory pressure는 75%로 회복했다. pilot raw는 `/private/tmp/otp-xmx10g-viability-A-c4-20260912-run2/`에 보존했다. 앞선 sandbox 차단 시도는 `/private/tmp/otp-xmx10g-viability-A-c4-20260912/`에 남아 있으며 Docker 컨테이너를 시작하지 못했다.

**판정:** 현재 18GiB Mac + 14.25GiB Docker VM + 동시 개발 앱이 남은 구성에서는 `-Xmx10g` c4 pilot이 swap-free가 아니었다. macOS 전역 swap counter이므로 동시 Claude/cmux 작업의 기여가 섞였고, 이번 run만으로 18GiB Mac 자체가 불가능하다고 증명하지는 않는다. 다음은 둘 중 하나다: 전용 24–32GiB 이상 host에서 PR gate를 시작하거나, 현재 Mac에서 다른 진행 작업을 저장·종료하고 재부팅한 뒤 **짧은 feasibility pilot만** 다시 실행한다. 동일 구성에서 swap이 재발하면 full gate는 더 큰 host로 옮긴다. 현재까지 유효한 `-Xmx10g` 성능 pair는 0개다.

### 0.2 2026-09-12 재개 점검

- A baseline JAR 존재: `/private/tmp/otp-baseline/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`, SHA-256은 위 baseline hash와 일치.
- H+F 후보 JAR 존재: `/Users/wa/golbob/개인/OpenTripPlanner/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`, SHA-256 `54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f`.
- 수도권 graph 존재: `/private/tmp/otp-graph-rebuild/graph.obj`, SHA-256 `24d5331b29ce88fa26750187056dd9ccc3acb43eb242e7d8ecad5ea037658a0a`.
- `run_isolated.py --help`에서 `--xmx`, `--container-memory`, Java 25 고정 이미지 옵션을 확인했다. JAR·graph 모두 pilot 입력과 SHA가 일치한다.
- 지금 Docker daemon은 꺼져 있고 `/private/tmp` 여유 공간은 18GiB다. Docker Desktop 재기동 시 기존 `nextroute-postgres-1`·`nextroute-redis-1`이 자동 시작됐던 이력이 있으므로, 실행 시 먼저 원래 상태를 확인하고 측정 종료 후 이전 상태로 돌린다.
- 23:39 KST에 Docker를 켜지 않은 상태로 18초 간격 host baseline을 두 번 확인했다. memory pressure는 75–76%, swap-in은 4 page만 증가(약 64KiB), swap-out은 변화가 없었다. 이 짧은 idle 구간은 현재 host background I/O를 가늠할 뿐, 14.25GiB Docker VM + 10GB heap c4의 안전성을 입증하지 않는다.
- Docker 유휴 preflight를 위해 `/Applications/Docker.app`과 내장 `Docker Desktop.app` 실행을 시도했지만 Launch Services가 `kLSNoExecutableErr`를 반환했다. `codesign --verify --deep --strict`도 Docker.app 서명을 invalid로 판정했고, Docker daemon은 시작되지 않았다. Docker 외 대체 런타임(podman/colima/lima/nerdctl)과 원격 Docker context도 현재 확인되지 않았다. 설치 복구/재설치는 Docker 사용자 데이터에 영향을 줄 수 있어 별도 승인 없이 수행하지 않는다.
- OTP 저장소는 `codex/otp-phase0-3` 브랜치이며 구현·테스트·측정기 변경이 미커밋 상태다. 재측정 과정에서 reset/checkout/clean/build overwrite를 하지 않는다.
- 원격 점검에서 24GiB 이상 접근 가능한 실행 host를 확보하지 못했다. 따라서 Docker 설치 복구 승인 또는 적합한 host, 그리고 필요 시 다른 앱 세션 종료·재부팅 승인 전에는 10GB pilot을 다시 실행하지 않는다.

### 0.3 2026-09-12 23:57 KST Docker 재기동 후 점검

사용자가 Docker Desktop을 다시 켠 뒤 읽기 전용으로 상태를 확인했다. 이는 실행 전 preflight일 뿐, 10GB feasibility pilot이나 성능 run은 시작하지 않았다.

| 항목 | 현재 관측 |
| --- | --- |
| Docker daemon | Server `27.4.0`, Linux 응답 정상 |
| Docker VM 메모리 | Docker Desktop 설정 `MemoryMiB=14592` (14.25GiB). `docker info MemTotal=14,902,792,192` bytes (약 13.88GiB)는 VM에서 Docker daemon이 보고하는 유효 메모리이며, 설정 상한 14GiB 미달로 해석하면 안 됨 |
| 실행 중 컨테이너 | 없음 |
| Java 이미지 | `maven:3.9-eclipse-temurin-25`, digest `sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753` — 계획과 일치 |
| Mac 물리 메모리 | `19,327,352,832` bytes (`18GiB`) |
| Mac 부팅 시각 | `2026-08-31 21:18:58`; 기존 pilot은 `2026-09-12 23:06–23:09`에 생성됨 — pilot 이후 재부팅하지 않음 |
| Swap 사용량 | `1,733.25MiB` 사용, `1,338.75MiB` 여유 |
| 30초 idle 표본 | memory pressure `71% → 70%`; swap-in `+16` pages (`~256KiB`), swap-out `+0` pages; swap 사용량 변화 없음 |
| 작업 입력 | A JAR, HF JAR, graph SHA-256 모두 계획과 일치 |
| `/private/tmp` 여유 공간 | `18GiB` |

**판정(당시):** Docker daemon과 이미지 준비는 해결됐지만 그 시점엔 10GB workload를 실행하지 않았다. 이후 host가 재부팅된 뒤 사용자가 현재 Docker·앱 구성을 자신의 최선 RAM 조건으로 지정하고, Docker Desktop을 종료하지 말고 계속 진행하라고 요청했다. 그 후속 실행 결과는 §0.5에 기록한다. JAR·graph 해시와 미커밋 변경은 그대로 보존했다.

### 0.4 2026-09-13 00:02 KST 재확인

새 날짜 기준으로 환경을 다시 읽었다. host boot time은 여전히 `2026-08-31 21:18:58`이고, Docker Server는 `27.4.0`, VM `MemTotal`은 `13.88GiB`, 실행 컨테이너는 0개다. Mac 물리 메모리는 여전히 `18GiB`다.

30초 idle 동안 memory pressure는 `71% → 70%`, swap-in은 `2,746` pages 증가(약 `42.9MiB`), swap-out은 변화가 없었다. swap 사용량은 약 `1,677MiB → 1,637MiB`로 줄어, 기존 page 재활성화 등 background 변동이 있음을 확인했다. 따라서 이 표본은 swap-free workload를 보증하지 않는다. `/private/tmp` 여유 공간은 `21GiB`로 충분하고 Docker·이미지·입력 파일은 준비됐지만, host 재부팅 및 앱 종료 조건은 여전히 확인되지 않았다. **10GB workload와 전체 matrix는 실행하지 않았다.** 안전 조건이 충족되거나 더 큰 host가 제공될 때까지 대기한다.

### 0.5 2026-09-13 `-Xmx10g` 전체 비교 결과 — 탐색 결과, 정식 gate 미통과

사용자는 Docker Desktop을 끄지 말고 현재 구성을 최선의 RAM 조건으로 간주해 계속 진행하라고 명시했다. 이에 Docker Desktop/VM은 전체 측정 동안 유지하고, 러너가 각 run마다 만든 임시 OTP 컨테이너/JVM만 종료·정리했다. Docker Desktop 재시작으로 VM page cache를 비우는 절차는 실행하지 않았다. 측정 종료 후 Docker daemon에는 연결 가능했고 `docker ps`에는 실행 중 컨테이너가 없었다.

| 조건 | 실제 값 |
| --- | --- |
| Mac physical RAM | 18GiB |
| Docker Desktop 설정 | `MemoryMiB=14592` (14.25GiB 설정값); `docker info MemTotal=14,902,792,192` bytes (13.88GiB 유효 보고값) |
| OTP container / JVM | `--memory=12g --memory-swap=12g`; `-Xmx10g`; G1 |
| Java / image | Java `25.0.4`; image digest `sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753` |
| graph | SHA-256 `24d5331b29ce88fa26750187056dd9ccc3acb43eb242e7d8ecad5ea037658a0a` |
| A baseline JAR | `/private/tmp/otp-baseline-src-20260913/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`; SHA-256 `f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52` |
| HF candidate JAR | `/Users/wa/golbob/개인/OpenTripPlanner/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`; SHA-256 `54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f` |
| workload | OD 4개, `--when 2026-09-15T08:00:00+09:00`, seed `20260908`, warmup 100/OD + measured 100/OD, warmup stability gate, 5 AB/BA pair per c1/c2/c4 |

Baseline JAR는 OTP 저장소의 source commit `02c0b4b537eb07bb0b284726397821682248fe20`를 archive로 추출해 다시 빌드했고, 빌드에서 `maven.gitcommitid.skip=true`를 사용했다. 따라서 이전 문서에 적힌 baseline JAR hash `914d9a58...`와 같은 바이너리가 아니며, 새 JAR 내부 build metadata의 commit/branch 값도 `UNKNOWN`이다. 소스 revision은 고정했지만 기존 artifact와의 binary identity는 입증하지 못했다. 위의 새 hash를 이번 실험 입력으로 사용한다. HF JAR는 OTP 저장소의 H+F 작업 tree에서 만들어졌고, 당시 미커밋 변경은 보존했다.

유효 run은 3개 동시성 × 5쌍 × 2변형 = **30개**다. 각 run은 측정 요청 400/400 성공, routing fingerprint 비교 mismatch 0이었다. c2의 pair 1 A는 stability gate 때문에 warmup이 400에서 700요청으로 연장됐고, 그 외 유효 run은 warmup 400이었다. 별도 correctness query도 A/HF 각 4/4 OD 성공, full itinerary 비교 mismatch 0이었다. 각 비교는 10,000회 paired bootstrap으로 계산했다. 표의 개선율은 양수일 때 HF가 더 빠르거나 RSS가 더 낮다는 뜻이다.

| 동시성 | A median run p50 / p95 / peak RSS | HF median run p50 / p95 / peak RSS | paired p50 개선율 (95% CI) | paired p95 개선율 (95% CI) | paired peak RSS 감소율 (95% CI) |
| ---: | --- | --- | --- | --- | --- |
| 1 | 349.654 / 422.798 ms / 3.14GiB | 357.322 / 440.268 ms / 2.53GiB | −1.44% [−2.96, +1.29] — 우위 미확정 | −4.13% [−12.34, −0.58] — HF가 느린 방향 | +21.92% [+16.51, +33.46] |
| 2 | 418.629 / 494.070 ms / 4.38GiB | 383.760 / 491.113 ms / 2.44GiB | +8.33% [+6.50, +9.70] | +1.34% [−1.75, +3.47] — 우위 미확정 | +45.06% [+41.98, +49.38] |
| 4 | 533.538 / 640.201 ms / 6.68GiB | 432.332 / 550.688 ms / 2.65GiB | +20.16% [+11.30, +22.51] | +16.60% [−5.02, +19.55] — 우위 미확정 | +60.37% [+41.56, +60.76] |

GC 관측의 run별 median도 모든 동시성에서 H+F가 낮았다. 아래 pause count는 GC cycle 수가 아니라 완료된 pause event 수다.

| 동시성 | 완료 pause count A / HF | GC pause 총시간 A / HF | 최대 pause A / HF |
| ---: | ---: | ---: | ---: |
| 1 | 327 / 129 | 1,424 / 291ms | 10.17 / 4.56ms |
| 2 | 369 / 135 | 2,482 / 380ms | 15.16 / 4.99ms |
| 4 | 263 / 85 | 2,734 / 369ms | 28.47 / 7.87ms |

OD별 p50에서는 c1의 `cityhall-gangnam`이 HF에서 약 11.06% 느렸고(CI −12.86%~−9.94%), `cheonggye-sadang`도 약 3.12% 느렸다(CI −4.74%~−1.24%). 반면 `bupyeong-seoul`은 약 14.26% 개선, `bupyeong-gangnam`은 약 0.51%로 뚜렷한 차이가 없었다. c2에서는 `cityhall-gangnam`이 약 3.46% 악화된 반면 나머지 OD는 개선됐고, c4는 네 OD 모두 p50 개선 구간이 0보다 컸다.

**환경 한계와 판정:** 전체 유효 run에서 macOS host의 `Swapouts`는 증가하지 않았지만, host `Swapins`는 다수 run에서 증가했고 `Pageouts`도 늘었다. 이는 OTP만의 I/O인지 분리되지 않는 OS 전역 counter다. 특히 c4 pair1 A에서는 host Swapins가 24,227 pages 증가했고, 당시 swap 사용량도 2.50GiB에서 2.02GiB로 변했다. Docker VM 내부 swap counter는 수집하지 못했다. 따라서 §2의 엄격한 “예열·측정 중 swap-in/out 증가 run 제외” 기준으로는 이 matrix를 **swap-free 통제 표본이라고 부를 수 없다**. Docker Desktop을 유지한 채 독립 JVM/컨테이너만 새로 띄운 절차 또한 Desktop 재시작을 요구한 원래 절차와 다르다. 사용자 지시에 따라 진행한 최선의 현재 host 측정이지만, 결과는 환경 영향을 배제한 확정 성능 증거가 아니라 탐색 근거다.

**결론:** 넉넉한 10GB heap에서도 H+F의 관측 peak RSS는 c1/c2/c4 모두 감소했다. p50은 c2·c4에서 개선됐지만 c1은 우위가 확인되지 않았고, p95는 c1에서 HF 열세 방향이며 c2·c4는 우위 미확정이다. 그러므로 “A보다 항상 빠르다” 또는 “c4 p95가 통계적으로 개선됐다”고 주장할 수 없다. PR에서는 반복 측정에서 확인된 메모리 이득, c2/c4 p50 이득, c1 OD 회귀, 그리고 host swap으로 인한 한계를 함께 공개해야 한다.

실패·제외 run도 숨기지 않고 보존한다.

- `/private/tmp/otp-xmx10g-pair1-A-c4-20260913/` — 잘못된 기본 시각 `2026-09-08`로 실행해 `OUTSIDE_SERVICE_PERIOD`; 성능 표본에서 제외.
- `/private/tmp/otp-xmx10g-pair4-A-c1-20260913-sep15-retry1/` — `--when` 전달 누락으로 동일한 서비스 기간 오류; 제외. 올바른 재실행은 `...retry2/`이며 표본에 포함.
- `/private/tmp/otp-xmx10g-viability-A-c4-20260913-postreboot/` — feasibility workload에서 host swap 변화가 커 정식 비교로 사용하지 않음.

유효 raw 위치는 `/private/tmp/otp-xmx10g-pair{1..5}-{A|HF}-c{1|2|4}-20260913-sep15/`이며, c1 pair4 A만 `...sep15-retry2/`를 사용한다. c1 pair4의 빈 최초 출력 디렉터리와 retry1 오류 raw도 그대로 두었다.

## 0.6 2026-09-15 P4 feasibility 누적 재시도 — no-swap 조건 미통과

현재 Mac에서 A/c1 feasibility pilot을 세 번 수행했다. 세 run 모두 예열 안정화와 measured 400/400을 통과했지만 swap-in이 각각 7,675/250/88 pages(약 119.9/3.91/1.38MiB) 증가해 formal P4 performance 표본으로 쓸 수 없다. 첫 실행의 비교 경로가 두 OD만 포함한 문제는 두 번째 run에서 바로잡았다. 두 번째 run은 네 OD A reference를 만들었고 세 번째 run은 OD별 100개 fingerprint 비교에서 mismatch 0이었다. 별도 full-detail correctness query에서 HF와의 네 OD 대조도 mismatch 0이었다. 03:33–03:36 KST에 counter가 일시 정체하고 memory pressure free 78%를 보였지만, 뒤이은 feasibility run에서 swap-in이 재발했다. 남은 것은 swap-free controlled host에서의 A/HF 성능 matrix다. 상세·hash는 [ABLATION-P4-FEASIBILITY.md](ABLATION-P4-FEASIBILITY.md)와 manifest에 있다.

현재 남은 P4 선행조건은 측정 전 구간에서 swap-in/out이 0으로 유지되는 통제 host에서 feasibility gate를 통과하는 것이다. 4-OD performance A reference는 `/private/tmp/otp-p4-feasibility-retry-20260915/c1-A-run2/requests.jsonl`, full-detail correctness A/HF reference는 `/private/tmp/otp-p4-correctness-20260915/{A,HF}/requests.jsonl`에 있다. 새 host/architecture에서는 correctness를 재확인하고, 사용자 앱이나 Docker/DB/Redis를 종료하지 않으며 clean-host pilot 통과 전에는 정식 matrix를 시작하지 않는다.

## 1. 먼저 바로잡을 해석

| 기존 표현 | 후속 보고에서 사용할 해석 |
| --- | --- |
| GC 후 heap = 살아있는 객체량 | GC 후 heap 사용량. 아직 회수하지 않은 쓰레기를 포함할 수 있음 |
| H+F 메모리는 동시성과 무관한 상수 | 측정한 동시성 1·2·4에서 GC 후 사용량이 거의 일정했음. 요청별 검색 상태는 남음 |
| 지연 개선 원인은 전부 Mixed GC | GC 정지 감소와 지연 개선이 함께 관측됨. CPU·메모리 접근·호스트 스왑의 기여는 미분리 |
| 3.5GB c1 개선은 코드 효과가 아님 | 같은 heap 조건에서 할당 감소로 얻은 코드 효과이며, 크기는 heap 여유에 의존함 |
| Full GC가 없으니 메모리 압박이 없음 | 8GB에서는 Full GC가 없었음. 호스트 메모리 압박·스왑까지 배제하지는 못함 |
| 요청당 약 400MB가 모두 시각표 복사분 | H+F 전체 차이에서 얻은 추정치. F의 기여·예열·할당 추정법 영향을 분리해야 함 |
| CPU 11개이므로 경합 없음 | CPU 사용률·GC 스레드·검색 내부 병렬 실행·스케줄링을 확인해야 함 |
| heap을 늘려도 소용없음 | 8GB에서 H+F 우위가 관측됐으나, heap 크기에 따른 일반 법칙은 미확정 |
| 조회 비용 때문에 c1이 느림 | 유력 가설. 조회 횟수·CPU 프로파일로 검증 필요. JIT 인라인 가능성도 고려 |

확인된 정정 근거:

- `/private/tmp/otp-lh8-A-c4-p1/environment.json`: 실행 전후 호스트 Swapins +852,434페이지, Swapouts +970,952페이지. 호스트 전체 카운터이므로 OTP 단독 책임은 미확정이지만 스왑 없는 환경으로 볼 수 없다.
- 같은 run의 `gc.log` 전체 구간: Mixed 223회·29.656초, Prepare Mixed 90회·11.086초. 기존 약 41초는 두 종류를 합친 값이다. 별도 항목으로 보고한다.
- `/private/tmp/otp-a-c4-gate-5/gc.log`: 이전 답변의 Full GC 144회는 시작·종료 중복 집계. 완료 로그 기준 72회다. 이 값은 시작·예열 포함 전체 구간이며 측정 구간 횟수와 구별한다.

## 2. 실행 순서

### Step 1 — 기존 raw 재집계와 문서 정정

- [x] 실제 실행된 JAR·graph hash, JVM 옵션, 실행 시간 순서, 예열 조건을 run별로 대조한다. 파일명의 p1/p2/p3만 보고 AB/BA 순서를 가정하지 않는다.
- [x] 최초 계획의 10g→실제 8g, c4 일부 예열 timeout 600→900초 변경을 기록한다.
- [x] 측정 구간 marker에 맞춰 GC를 다시 집계한다. Young Normal, Concurrent Start, Prepare Mixed, Mixed, Full, Remark/Cleanup을 구별하고 완료된 GC ID 기준으로 중복을 제거한다.
- [x] GC 정지 총시간/측정 경과시간, 요청 수, GC 후 heap 분포, RSS peak를 보고한다. 예열 포함 누적 통계는 별도 표에 둔다.
- [x] 스왑 카운터의 전후 증가량과 수집 시점을 기록한다. 기존 스왑 사용량이 0이 아니라는 이유만으로 run을 무효화하지 않는다. 실제 증가가 있는 run은 영향 미분리로 표시한다.
- [x] 기존 분석의 “증명됨” 중 위 가설·일반화 표현을 수정하고 3.5GB·8GB 결과는 각각 보존한다.

산출물 완료: [LARGE-HEAP-REANALYSIS.md](LARGE-HEAP-REANALYSIS.md) — run별 재집계 표, 실제 입력 목록, marker 기준 GC/heap 재산출, host swap 전후 delta, 해석 수정 내역. 원본 raw는 그대로 보존했다.

### Step 2 — `-Xmx10g` 재측정(PR 성능 주장용; 실행 완료, 환경 gate 미통과)

이 단계는 운영 후보 선택에는 필수였던 작업이 아니지만, **큰 heap에서도 기존 로직이 더 느리다는 PR 성능 주장을 위해서는 필수**다.

- [x] 전용 24–32GiB host는 확보되지 않았다. 사용자 지시에 따라 18GiB Mac과 현재 Docker 구성에서 matrix를 끝까지 실행했고, §0.5에 탐색 결과 및 환경 한계를 분리해 적었다.
- [x] Host 재부팅 이후 실행했다. 다만 다른 앱/개발 작업을 별도로 종료하지 않았고 Docker Desktop도 유지했다. 이를 host 자원 격리 완료로 간주하지 않는다.
- [x] Docker Desktop 메모리 설정은 14,592MiB(14.25GiB), OTP container는 12GiB, JVM은 `-Xmx10g`로 A/HF 동일하게 적용했다.
- [x] 10GB 조건이나 graph/workload를 낮추지 않고 full matrix를 실행했다. host swap-in 변화가 있어 공식 swap-free gate는 미통과다.
- [x] host `vm_stat`, `memory_pressure`, `sysctl vm.swapusage`, Docker container RSS·CPU를 run 전후에 수집했다. Docker VM 내부 swap counter는 수집하지 못했다.
- [x] 각 run은 새 OTP 컨테이너/JVM으로 실행했다. 사용자 지시에 따라 Docker Desktop/VM은 완전히 재시작하지 않았으므로 VM cache reset 절차는 미충족으로 기록한다.
- [x] graph·Java·GC·heap·query·시각·예열·컨테이너 설정을 고정했고, 지연 run과 별도 JFR/build를 겹치지 않았다.
- [x] A/HF 실행 JAR hash와 source identity를 기록했다. 기존 문서 예상 A JAR hash와 실제 baseline hash가 달라 §0.5에 한계를 명시했다.
- [x] c1·c2·c4 각각 독립 JVM 5쌍, AB/BA 교차 순서, OD당 측정 100건, 안정 예열을 완료했다. 모든 유효 run은 400/400 성공했다.
- [x] 지정 순서(pair1 `A→HF`, pair2 `HF→A`, pair3 `A→HF`, pair4 `HF→A`, pair5 `A→HF`)로 실행했으며 동시 matrix run은 하지 않았다.
- [x] 전체·OD별 p50/p95, 10,000회 paired bootstrap 구간을 계산하고 c1 회귀 및 불확실한 지표를 §0.5에 남겼다.
- [x] 기존 측정은 새 결과에 합치지 않았다. 사용한 통계 결론은 이번 10GB matrix 내부의 paired 비교이며 전 서비스 부하로 일반화하지 않는다.

기존 도구는 OTP 저장소의 `test/performance/seoul-metro/`에 있다. 이번 측정에서는 `run_isolated.py --help`와 옵션 전달을 확인했고, `--xmx 10g`가 실제 JVM 명령·manifest·GC log에 반영된 것을 검증했다. 재현 시에도 실패 run을 숨기지 말고 종료 조건과 예산을 먼저 기록한다.

2026-09-13 전체 matrix에서 사용한 실행 입력:

| 입력 | 경로 | SHA-256 |
| --- | --- | --- |
| A baseline JAR | `/private/tmp/otp-baseline-src-20260913/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` | `f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52` |
| HF candidate JAR | `/Users/wa/golbob/개인/OpenTripPlanner/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` | `54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f` |
| graph | `/private/tmp/otp-graph-rebuild/graph.obj` | `24d5331b29ce88fa26750187056dd9ccc3acb43eb242e7d8ecad5ea037658a0a` |

`run_isolated.py --help`에서 `--xmx`, `--container-memory`, 이미지 고정 옵션을 확인했다. 기준 workload는 OD 4개 × warmup 100 + measured 100, `--when 2026-09-15T08:00:00+09:00`, `--seed 20260908`, `-Xmx10g`, G1, Java 25, image `sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753`, `--memory=12g --memory-swap=12g`로 고정했다. Sep 8은 graph의 서비스 기간 밖이어서 쓸 수 없었다. warmup stability window는 100, stability timeout은 **모든 run에서 900초로 고정**했다.

PR 성능 주장 통과 조건:

- [ ] c4 p50·p95 paired 개선율의 95% 구간 하한이 모두 0%보다 크다. p50은 충족했지만 p95 CI에 음수 구간이 있다.
- [x] c2에서 0을 포함한 p95는 개선 주장하지 않고, p50 개선만 구분해 보고했다.
- [x] c1 p50/p95 및 OD별 회귀를 숨기지 않고 §0.5에 공개했다.
- [x] 유효 matrix run routing 성공률 100%, 성능 fingerprint mismatch 0, full correctness 비교 mismatch 0, OOM 없음.
- [ ] swap-in 증가가 있는 run을 formal PR 표본에서 제외한다. 이번 §0.5 표는 exploratory 결과로만 유지하며 swap-free PR gate를 통과한 표본은 0개다.

산출물: §0.5에 측정 결과·실패 run·해석·raw 경로/hash를 기록했다. 다만 formal swap-free gate는 미통과이므로 PR에서 확정적 지연 개선을 주장하기 전에 swap을 통제할 수 있는 전용 host에서 matrix를 재실행해야 한다.

### Step 3 — 복사·조회·승격 비용 직접 확인

- [x] 성능 run과 분리해 c1의 시청→강남·부평→서울에서 A/H/F/HF JFR CPU·allocation profile을 수집했다. 진단 workload는 OD별 예열 100·측정 200이며 JFR 결과로 지연 gate를 판정하지 않는다. 결과는 [ABLATION-P3-JFR-RESULT.md](ABLATION-P3-JFR-RESULT.md)에 기록했다. 초기 A/HF profile은 [ALLOCATION-LOOKUP-PROFILE.md](ALLOCATION-LOOKUP-PROFILE.md)에 보존한다.
- [x] `TripPatternForDates` 생성 개수와 stop/trip 차원 합을 계측해 바뀐 두 배열의 원소 payload 모델을 계산했다. graph 전체 기준이며 retained heap/전체 할당량은 아니다. 날짜·필터 조건은 고정 검색 workload에 포함했고 H+F 전체 할당 차이를 전부 복사분으로 간주하지 않았다. 산식은 [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md)에 있다.
- [x] A/H-only/F-only/H+F JFR을 같은 두 OD·고정 workload로 나눠 allocation/CPU sample을 비교했다. 샘플 한계와 변형별 JAR hash는 P3 결과 및 manifest에 기록했다. 이 결과는 exact counter 또는 latency gate가 아니다.
- [x] 두 OD에서 A/H/F/HF의 time accessor, wrapper, Pareto comparator 호출 수와 candidate set histogram을 direct counter로 비교했다. variant별 호출량/histogram은 동일했고 receiver pair만 바뀌었다. F inlining JFR에서 관측한 `MutableDummyPath` getter는 인라인된 사례가 있다. 계측값은 latency 증거가 아니다.
- [ ] 승격 가설은 age/old 영역 변화 및 가능한 객체 생존 계측으로 보강한다. 일반 GC 로그만으로 어떤 배열이 승격됐는지 확정하지 않는다.
- [ ] OD 15개(도심 버스·지하철 중심·혼합 각 5개)로 확장한다. 반환 경로 유형과 실제 탐색량을 구별한다.

산출물: [ABLATION-P3-JFR-RESULT.md](ABLATION-P3-JFR-RESULT.md) 및 [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md) — A/H/F/HF sampled JFR, JIT 진단, 두 OD 직접 계측 완료. Survivor/promotion 분석과 OD 15개 확장은 미실행.

### Step 4 — 근거가 확보되면 정류장 행 lazy 생성 실험

- [ ] 조회 비용이 병목으로 확인됐을 때만 별도 후보를 만든다. 첫 조회 시 해당 정류장의 연속 시간 배열을 생성하고 이후 재사용한다.
- [ ] 요청별 객체라는 이유만으로 thread-safe하다고 가정하지 않는다. 내부 병렬 검색 사이에서 객체가 공유되는지 먼저 확인한다.
- [ ] 날짜 offset, frequency, 역방향 조회, 실시간 snapshot, 필터 조건, 결과 동등성을 검증한다.
- [ ] H+F 대비 c1 손해가 줄고 c2/c4의 메모리·지연 이득이 유지되는지 검증한다. 실패하면 후보를 채택하지 않는다.

산출물: 별도 후보 계획과 비교 결과. 날짜별 공유 시각표 캐시는 실시간 갱신·서비스일·필터 설계가 필요한 후순위 작업이다.

## 3. 운영 확인과 완료 기준

- VPS 용량 관측은 완료됐다. 결과는 [VPS-OBSERVATION-RESULT.md](VPS-OBSERVATION-RESULT.md)를 기준으로 하며 로컬 RSS 절대값을 운영값으로 사용하지 않는다.
- 대표 부하의 통제된 soak와 운영 RSS/p95 모니터링은 서로 보완한다. 평상시 모니터링만으로 부하 soak를 대체하지 않는다.
- ZGC는 H+F 적용 후 별도 가설로 검증할 수 있다. 과거 원본의 단기 ZGC screening만으로 H+F에서의 효과를 배제하지 않는다.
- 완료: 집계 오류 정정, 스왑 통제 유효성 판정, 환경별 성능 결론, 원인별 증거 수준, 코드 후보 채택/보류 이유가 문서에 남아 있음. 미확정 결과도 정직한 완료 산출물이며 반드시 개선을 만들어야 하는 것은 아니다.
- 운영 작업의 다음 순서는 H+F JAR 배포와 `provider=OTP` 전환, Prometheus를 통한 RSS·p95·GC 추세 관측이다. 배포·provider 변경·PR 제출은 별도 승인 없이 이 문서만으로 자동 실행하지 않는다.

## 다음 작업자에게 전달할 요청

> [LARGE-HEAP-AGENT-PLAN.md](LARGE-HEAP-AGENT-PLAN.md), [ABLATION-RESULT.md](ABLATION-RESULT.md), [ABLATION-P4-FEASIBILITY.md](ABLATION-P4-FEASIBILITY.md), [PR-EVIDENCE.md](PR-EVIDENCE.md), [ABLATION-P3-JFR-RESULT.md](ABLATION-P3-JFR-RESULT.md), [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md)를 먼저 읽어라. VPS 관측은 완료됐으므로 반복하지 않는다. P2 16-cell screening과 P3 JFR·direct counter는 완료됐다. H의 원소 payload 모델은 크게 감소하고 accessor 호출량은 변형 간 같았다. F comparator 호출 수와 candidate-set histogram도 같아 호출량 폭증은 관측 OD에서 지지되지 않는다. H 접근당 비용 및 F latency 회귀 원인은 여전히 미확정이다. 4-OD A reference와 A/A 재현성(OD별 100/100 mismatch 0)은 확보됐다. 다음 필수 작업은 swap-in/out 0인 controlled host에서 feasibility를 통과한 뒤 P4 formal A/HF matrix를 수행하는 것이다. Survivor/promotion 분석 및 OD 15개 확장은 선택적인 추가 원인 연구로 남긴다. 동일 host의 JFR/계측 p50/p95를 성능 gate에 쓰지 말고, 사용자의 명시 없이 Docker Desktop/VM을 종료·재시작하지 않는다. 구현 저장소는 `/Users/wa/golbob/개인/OpenTripPlanner`, 문서 저장소는 Nextroute다. 양쪽 변경과 raw를 보존하고 각 계측 JAR/script hash 및 관측/추정 구분을 남겨라.
