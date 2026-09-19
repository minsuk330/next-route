# H-only cross-host × graph matrix — agent execution plan

작성: 2026-09-16. 상태: **PLAN_READY / 미실행**.

## 1. 질문과 현재 판정

목표는 H-only 효과가 서울 graph/workload에서 발생하는지, VPS 플랫폼에서 발생하는지,
또는 두 조건과 작은 heap이 함께 있을 때만 발생하는지 분리하는 것이다.

기존 Helsinki Xms pilot은 fixed와 elastic의 **절대 RSS**를 크게 바꿨지만, A/H 간 RSS
차이는 두 조건 모두 거의 없었다. 따라서 “elastic heap이 어느 환경에서도 결과를 바꾸지
않는다”고 일반화하지 않는다. 현재 확인된 범위는 **Helsinki·macOS ARM64·Xmx8g·8 RPS의
한 pilot에서 Xms가 A/H 상대 RSS 결과를 바꾸지 않았다**는 것이다.

추가 가설은 **Docker 또는 Docker가 적용한 cgroup 제한이 H의 A 대비 RSS 차이를
드러낸다**는 것이다. 현재 패턴은 이 가설과 일치하지만 아직 증명하지 않는다. 차이가 없던
로컬 HTTP는 macOS host JVM·Xmx8g였고, 차이가 난 VPS는 Linux Docker·Xmx1900m·Seoul
workload였으므로 containerization 외에 OS/JDK, heap regime, graph+corpus가 동시에 바뀌었다.

VPS의 최근 A/H 관측에서는 양쪽 모두 `-Xms256m -Xmx1900m`, container memory 3000m,
동일한 swap allowance를 사용했다. 따라서 A 2.14GiB 대 H 1.63GiB 차이를 H+F container의
`memory-swap=3000m` 설정 차이로 설명할 수 없다. H+F와 A/H의 정식 재비교에서는 모두
`memory-swap=3000m`으로 통일한다. `swap current=0`은 실제 cgroup counter 원자료로
확인해야 하며, swap은 반드시 memory limit를 넘은 뒤에만 사용된다고 가정하지 않는다.

## 2. 필요한 2×2 교차 행렬

| host/platform | Helsinki graph + corpus | Seoul graph + corpus |
| --- | --- | --- |
| Local | 기존 pilot은 rate 선정 참고만; 공통 envelope로 재실행 | 신규 실행 |
| VPS | 신규 실행 | 기존 1회 관측은 참고만; 공통 envelope로 재실행 |

각 칸에서 A와 H-only를 비교한다. 사용자가 제안한 “로컬 Seoul”과 “VPS Helsinki”는
필요한 두 신규 방향이 맞다. 그러나 기존 두 칸도 heap·JDK·부하를 새 계약과 동일하게
맞춰 다시 실행해야 interaction을 계산할 수 있다.

이 행렬이 분리하는 효과는 다음과 같다.

- 같은 host에서 Seoul/Helsinki의 H/A 비율 차이: **graph+corpus 효과**
- 같은 graph에서 VPS/local의 H/A 비율 차이: **platform 효과**
- VPS+Seoul에서만 나타나는 차이: **platform × graph × 제한 heap 상호작용**

도시가 다르면 동일 OD를 보낼 수 없으므로 graph와 request mix를 완전히 분리할 수 없다.
결론은 “graph 단독”이 아니라 “graph+corpus” 효과로 표현한다.

## 3. 공통 실행 envelope

### Source and artifacts

- A와 H는 같은 고정 upstream base에서 빌드하고 H에는 `TripPatternForDates` 변경과 관련
  테스트만 포함한다. H+F JAR/커밋은 사용하지 않는다.
- Linux container와 JDK 25/G1을 양 host에서 사용한다. 같은 multi-arch image tag가 아니라
  실제 architecture별 digest를 기록한다. 로컬에서 amd64 emulation으로 timing하지 않는다.
- CPU는 양쪽 모두 4 vCPU quota로 제한하고 JVM `ActiveProcessorCount=4`를 고정한다.
  throttling과 실제 CPU 시간을 기록한다.
- 데이터셋마다 A로 graph를 한 번 빌드하고 A/H 및 local/VPS가 같은 파일을 사용한다.
  graph SHA-256과 serialization header를 네 run 계열에서 대조한다.
- Helsinki는 공식 408-case HTTP mapping을 사용한다. Seoul은 VPS에서 사용한 5개 경로의
  좌표·날짜·GraphQL query·응답 필드·순서를 raw request로 고정한다. 경로 이름만으로
  재작성하지 않는다.

### Heap and container

- JVM: `-Xms256m -Xmx1900m -XX:+UseG1GC -XX:ActiveProcessorCount=4`
- Container: `--memory=3000m --memory-swap=3000m --cpus=4`
- `AlwaysPreTouch`, `System.gc`, `jcmd GC.run`, heap dump, 추가 GC tuning 금지
- 한 host에서 OTP JVM 하나만 실행한다. 모든 cell은 fresh JVM이다.

이 envelope는 현재 VPS 배포 조건에 맞춘다. 기존 local Helsinki Xmx8g 결과와 기존 VPS
Seoul 60-request 결과는 설명 자료로 보존하되 새 통계에 합치지 않는다.

## 4. Workload contract

1. 모든 8개 조합(host 2 × graph 2 × variant 2)에서 2 RPS와 4 RPS 짧은 pilot을 수행한다.
2. A/H 모두에서 scheduling lag 증가, timeout, CPU throttling, Full GC, cgroup high/max,
   swap이 없는 가장 높은 공통 rate를 선택한다. 예상 시작값은 4 RPS이며 gate 실패 시
   2 RPS로 내린다.
3. warmup은 최소 5분과 corpus 3회전을 모두 충족한다. Helsinki 408건 기준 warmup은
   `max(300, ceil(3×408/RPS))`초다. Seoul도 동일 시간을 사용한다.
4. measured duration은 600초로 고정한다. 같은 seed와 corpus 순환 규칙, 예정 도착 시각,
   timeout, worker 상한을 사용한다.
5. HTTP 200만으로 통과시키지 않는다. A/H response fingerprint, GraphQL/routing error,
   no-plan, itinerary 수를 비교한다.

## 5. Phases

### P0 — Identity and compatibility gate

1. A/H diff, JAR SHA, JDK/image digest, graph SHA/header, config/query/corpus hash를 고정한다.
2. 두 graph를 양 architecture에서 load하고 readiness와 correctness smoke를 통과한다.
3. 기존 Helsinki 및 Seoul raw request가 없으면 정식 실행을 시작하지 않는다.
4. collector가 Java PID RSS와 cgroup current/anon/file, heap committed/used, GC, process CPU,
   scheduling lag, swap/PSI를 동일한 시간축으로 수집하는지 검증한다.

### P1 — Missing-cell feasibility

1. Local Seoul A/H 1 pair를 실행한다.
2. VPS Helsinki A/H 1 pair를 실행한다.
3. 공통 rate와 안전 gate를 확정한다. 이 두 pair는 pilot이며 정식 CI에 포함하지 않는다.

### P2 — Full crossed pilot

공통 envelope로 local-Helsinki, local-Seoul, VPS-Helsinki, VPS-Seoul의 A/H 한 pair씩
실행한다. 기존 값과 방향이 달라도 삭제하지 않는다. 아래 decision table로 formal matrix의
가치를 판정한다.

### P2.5 — Runtime bridge: Docker 가설 분리

교차 행렬은 모든 cell을 Docker로 통일하므로 Docker 자체의 원인 여부는 검정하지 못한다.
가장 강한 신호가 나온 VPS·Seoul에서 다음 A/H pair를 같은 JDK build·graph·config·corpus·
Xms/Xmx·rate로 수행한다.

1. Linux native JVM, 별도 cgroup memory 제한 없음
2. 가능하면 `systemd-run` 등으로 memory 3000m·swap 0만 적용한 native JVM(cgroup-only)
3. Docker, memory 3000m·memory-swap 3000m(Docker+cgroup)

각 조건은 최소 AB/BA 한 pair로 screening한다. `cgroup-only`와 Docker의 JVM container
인식(`UseContainerSupport`, 감지된 CPU/memory)을 대조한다. native와 Docker가 같은 JDK
vendor/version만 같고 build가 다르면 Docker 효과와 JDK build 효과가 섞이므로 통과시키지
않는다. native 실행이 운영 서비스를 침범하거나 동등 JDK/cgroup을 만들 수 없으면 이 단계는
`NOT_RUN`으로 남기며 억지 비교하지 않는다.

- native와 cgroup-only의 H/A가 갈리면 cgroup memory regime 효과를 지지한다.
- cgroup-only와 Docker의 H/A가 갈리면 container namespace/image/runtime 효과를 지지한다.
- 세 조건에서 H/A가 같으면 Docker 원인 가설을 기각하고 graph/workload 또는 heap regime를
  우선한다.
- macOS native 대 Docker Desktop 비교는 OS와 Linux VM까지 바뀌므로 보조 자료로만 쓴다.

### P3 — Formal matrix

- host×graph 각 칸에서 A/H 6 pair, AB/BA 각 3회: 4칸 × 12 JVM = **48 fresh JVM**.
- 요청이 아니라 pair/run을 독립 단위로 사용한다.
- pair별 H/A log ratio를 10,000회 paired bootstrap하여 95% CI를 산출한다.
- 성공값이 나올 때까지 표본을 추가하지 않는다. 6 pair 이후 불확실하면 그대로 종료한다.

### P4 — Cause diagnosis

대표 한 AB/BA pair씩 별도 JFR을 수행한다. timing 표본과 합치지 않는다. measured window의
allocation weight, `TripPatternForDates` constructor, `int[]`, CPU stack, GC/heap timeline을
graph별로 비교한다. direct counter로 patterns, trips, stops×trips 차원을 함께 기록한다.

## 6. Metrics and analysis

Primary metrics:

- Memory: measured JVM RSS p95, cgroup anon/current p95
- Heap: committed p95/max/last, after-GC used p95
- CPU: process CPU seconds / completed request
- Latency: scheduled-arrival→complete p95, send→complete p50/p95
- Reliability: success/timeout/error, completed RPS, lag, actual in-flight

`docker stats`의 memory usage를 Java PID RSS라고 부르지 않는다. Java RSS는
`/proc/<pid>/smaps_rollup`/`VmRSS`, container working set은 cgroup `memory.current`와
`memory.stat`의 anon/file로 따로 기록한다. `RSS > Xmx`는 정상이며 Xmx는 Java heap만
제한한다. 그러나 `RSS-Xmx`를 그대로 native memory로 계산하거나 A가 Xmx까지 heap을
사용했다고 추론하지 않는다. 별도 진단 run에서 `jstat`/`GC.heap_info`, after-GC used,
NMT summary, smaps를 함께 수집해야 heap residency와 native/cgroup 차이를 나눌 수 있다.

Secondary metrics: RSS peak, Young/Full GC count, pause/request, maximum pause, allocation JFR,
container throttling, host/VPS PSI and swap.

효과는 각 host×graph 안에서 먼저 `H/A`로 계산한다. 절대 local/VPS latency를 직접
비교하지 않는다.

- Graph interaction: `(H/A)_Seoul ÷ (H/A)_Helsinki`, host별 계산
- Platform interaction: `(H/A)_VPS ÷ (H/A)_Local`, graph별 계산
- Memory와 CPU/latency 각각 별도로 계산하고 한 지표로 합치지 않는다.

## 7. Decision table

| 관측 | 판정 |
| --- | --- |
| 양 host에서 Seoul만 H RSS 감소 | Seoul graph+corpus 효과 지지 |
| 양 graph에서 VPS만 H RSS 감소 | VPS platform/cgroup/JVM 효과 지지 |
| local Seoul도 감소하고 VPS Helsinki도 감소 | graph/host 단독보다 작은-heap 공통 효과 가능 |
| VPS Seoul에서만 감소 | platform×graph×heap interaction; 단일 원인으로 축약 금지 |
| 네 칸 모두 유사 감소 | 공통 envelope에서 보편적 방향 지지 |
| 네 칸 모두 RSS 유사, GC만 감소 | allocation/GC-only로 결론 제한 |

CPU도 같은 표를 적용한다. RSS 결과와 CPU 결과의 원인이 같다고 가정하지 않는다.

## 8. VPS safety gate

- 현재 운영 OTP와 실험 OTP를 동시에 띄우지 않는다. 운영 OTP가 실제 트래픽을 받으면
  별도 VPS/host를 사용한다. dormant OTP 중단도 사용자 승인 없이 수행하지 않는다.
- 실험 전 host MemAvailable ≥4GiB를 요구한다. 실행 중 <1GiB, swap 증가, memory PSI full,
  이웃 container restart, OTP OOM/timeout 지속 시 즉시 해당 실험 container만 중단한다.
- 포트는 운영 18080과 분리한 18081을 사용하고 기존 api/worker/postgres/redis를 변경하지 않는다.
- Helsinki graph 전송 전 SHA와 디스크 여유를 확인한다. 종료 후 artifact 삭제는 별도 승인
  없이는 수행하지 않는다.

## 9. Time and outputs

- Pilot: 8 fresh JVM + startup, 약 2–3시간
- Formal: 48 fresh JVM × (startup + warmup + 10분 measured), 직렬 약 14–18시간
- JFR/counter와 재실행은 별도

Outputs:

- `H-ONLY-CROSS-HOST-GRAPH-MATRIX-MANIFEST.json`
- `H-ONLY-CROSS-HOST-GRAPH-MATRIX-RUN-LEDGER.md`
- `H-ONLY-CROSS-HOST-GRAPH-MATRIX-RESULT.md`
- raw request/fingerprint, RSS/cgroup/heap/GC/CPU/pressure time series, interaction 계산 코드

이 실험은 H가 왜 특정 조건에서 유리한지 범위를 좁힐 수 있지만, 두 도시 밖의 모든 graph와
CPU에서 같은 효과를 보장하거나 maintainer의 merge 결정을 증명하지는 않는다.
