# H-only upstream 기여 검증 — 에이전트 실행 계획

작성: 2026-09-16. 상태: **계획 작성 완료 / 신규 실험 미실행**.

## 1. 목적과 우선순위

1. 제한된 VPS에서 이미 확보한 H+F 운영 효과를 보존한다.
2. H-only가 upstream에 채택할 가치가 있는지 allocation·CPU·RSS·GC의 이득과 비용을 재현 가능하게 검증한다.

이 문서가 이후 실험의 우선 계획이다. 기존 HTTP P4 18-pair matrix나 과거 서울/HF 계획을 자동 재개하지 않는다. H+F 운영 증거를 H-only 기여 증거로 승계하지 않는다. merge 여부는 maintainer 판단이며, 자체 기준 통과를 upstream 승인으로 표현하지 않는다.

실행 장소는 로컬 Docker/Linux를 기본으로 한다. CPU 회귀, Xms 영향, 데이터셋 효과를 로컬에서 분리한 후 VPS는 최종 운영 확인에 사용한다. Docker 기동 전에 이미지/JVM/자원/시간/중단 기준을 사용자에게 보고한다. 계획 작성만으로 실험·운영 배포·GitHub 게시를 수행하지 않는다. 후속 실행 지시가 오면 로컬 단계는 자율 진행하며, 운영 서비스 교체·중단 및 외부 게시에는 해당 작업의 명시적 권한이 필요하다.

## 2. 재개 시 읽을 자료

1. 이 문서 및 두 저장소와 상위 디렉터리의 AGENTS.md.
2. H-ONLY-CONTRIBUTION-PLAN.md → NEXT-AGENT.md → PR-EVIDENCE.md.
3. H-ONLY-SHARED-GRAPH-RESULT.md 및 MANIFEST/RUN-LEDGER.
4. H-ONLY-HTTP-NO-PRESSURE-AGENT-PLAN.md 및 MANIFEST/RUN-LEDGER.
5. HF-SHARED-GRAPH-RESULT.md, ABLATION-RESULT.md, ABLATION-P3-JFR-RESULT.md,
   ABLATION-P3-COUNTER-RESULT.md, VPS-OBSERVATION-RESULT.md.
6. 고정 OTP checkout의 CONTRIBUTING.md, ARCHITECTURE.md, PR template,
   test/performance/README.md, performance-test.yml, SpeedTest.java,
   TestCases.java, SpeedTestRequest.java, 기존 HTTP 부하·RSS·GC 수집기.

## 3. 현재 증거와 미확정 가설

| 자료 | 관측 | 제한 |
| --- | --- | --- |
| H-only Helsinki SpeedTest 6 pair | allocation sample weight −11.37%, int[] −83.84%; Total 약 +1.35%, Worker 약 +7.63%, case p95 약 +12.04% | Java 26/macOS 로컬 결과; Total CI가 0을 포함해도 동등성 증명은 아님; RSS 개선 없음 |
| H-only Helsinki HTTP | 16 RPS 10분, Xms=Xmx=8g; RSS p95 거의 동일, Young GC −10.9% | 한 pair pilot; 정식 반복 비교 미완료 |
| 과거 서울 H-only Docker | RSS peak 중앙값 약 3.31→2.63GB, GC pause 약 −76% | 구 baseline, 두 고정 OD, 4-block screening, 일부 host swap-in; 보편적 20% 절감 근거 아님 |
| VPS H+F c4 | RSS 약 4.07→1.98GB, measured GC pause 6.24→0.45초 | H+F, 각 조건 1회, 운영 이웃 부하; H-only에 귀속 불가 |
| Helsinki H+F | Total 약 +9.9%, Worker 약 +22.1% | 이번 upstream 후보에서 F 제외 |

- Xms8g가 RSS 차이를 가렸다는 설명은 **검증할 가설**이다. committed heap과 RSS는 같지 않다.
- H accessor/메모리 locality가 Worker 회귀 원인이라는 설명 역시 가설이다.
- VPS 로그의 heap 확장/Full GC 차이는 allocation-pressure 해석을 지지하지만 객체별 residency 기여율을 증명하지 않는다. 19회 Full GC는 startup/warmup 포함 전체 run 수치이며 measured 수치와 섞지 않는다.
- GC 후 used heap은 정확한 retained live set이 아니다. JFR weight는 sampled 누적 추정량이다.
- 과거 JFR 서울 measured-window와 Helsinki whole-recording 비율을 동일 분모로 직접 비교하지 않는다.
- 저장된 SpeedTest 결과 CSV는 마지막 sample 결과다. 전체 sample 동일성을 주장하려면 sample별 검증 출력도 확보한다.
- A/H 공통 공식 expected mismatch 175/408은 H 회귀와 구별한다. 기준 데이터·날짜·설정 불일치 원인을 조사하고 unresolved면 그대로 공개한다.

## 4. 공통 실험 계약

### 소스·데이터

- 코드: /Users/wa/golbob/개인/OpenTripPlanner
- 문서: /Users/wa/golbob/개인/nextroute/docs/plan/otp-code-optimization/handoffs
- 기존 base 후보: a0b62f69fc543e84687752e17dae8a66b01c5e28.
- 기존 Helsinki graph 후보: /private/tmp/otp-a-only-helsinki-4wN2sL/graph.obj
  (SHA256 178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0).
- 경로/브랜치/JAR는 manifest와 실제 파일을 대조한다. 임시 파일 존재를 가정하지 않는다.
- A=고정 upstream, H=A+H production patch와 관련 테스트만. 혼합 커밋 cherry-pick 금지.
- P0에서 upstream 최신 상태와 기존 base 차이를 확인해 한 base를 고정한다. baseline 변경은 새 series이며 기존 결과와 합산하지 않는다.
- 데이터셋마다 A로 graph를 한 번만 빌드하고 A/H에 동일 파일을 read-only mount한다. 새 JDK/base와 호환되지 않으면 양쪽 공통 graph를 재생성한다.
- 서울과 Helsinki가 서로 같은 graph라는 의미가 아니다. 각 데이터셋 내부에서 A/H graph hash가 같아야 한다.
- JDK는 고정 checkout의 upstream workflow와 맞춘다(과거 기준 JDK 25). image digest·architecture·JDK patch version·Maven·JVM flags를 기록한다. x86 에뮬레이션으로 로컬 성능을 측정하지 않는다.

### 실행·측정

- 한 번에 OTP JVM 하나만 실행한다. 빌드/JFR/성능 run을 겹치지 않는다. 실행 순서를 균형화한다.
- HTTP에는 System.gc, jcmd GC.run, heap dump 등 강제 수거를 호출하지 않는다. 공식 SpeedTest의 GC 정책은 수정하지 않는다.
- macOS host와 Docker Linux VM의 swap/여유, cgroup memory/PSI/events를 모두 기록한다. 미수집은 0이 아니라 MISSING이다.
- swap 발견 즉시 UTC/KST 시각, run/variant, 계층, 이전/현재 counter, delta, RSS/heap/부하를 SWAP_EVENT 로그에 남긴다.
- no-pressure series는 swap delta>0이면 해당 pair를 부적격으로 보존하고 중단한다. 원인 해결 후 최대 2회 재시도; 같은 상태에서 반복하지 않는다.
- 제한 메모리 series의 자연 Full GC·컨테이너 한도 도달은 결과다. 반면 host/VM swap과 이웃 서비스 영향은 중단 사유다. A의 OOM/timeout/오류를 환경 잡음으로 삭제하지 않는다.
- 기존 서비스·앱·Docker Desktop을 자원 확보 목적으로 종료하지 않는다. 로컬 RAM 부족이면 필요 자원을 보고하고 별도 테스트 머신으로 해당 단계를 옮긴다. 운영 VPS를 넉넉한 heap 검증용으로 쓰지 않는다.
- no-pressure 기준은 기존 HTTP 계획 P3를 사용한다: host/VM available≥20%, cgroup current≤limit의 80%, swap delta 0, PSI full 증가 0, 자연 Full GC/evacuation failure 0, 지속 backlog/throttling 없음. 미지원이면 관측 등급을 낮춘다.
- JVM 실제 PID RSS를 100ms 목표로 수집하고 startup/warmup/measured/drain 구간을 명시한다. measured coverage≥99%, 최대 gap≤1초가 기준이다.
- latency는 HTTP 전송부터 완료와 예정 도착부터 완료를 둘 다 수집한다. scheduling lag·실제 in-flight·완료 RPS·timeout·오류도 기록한다.
- throughput·RSS 비교에는 동일 입력 도착률을 사용한다. closed-loop c4와 16 RPS를 같은 부하로 간주하지 않는다. 평균 in-flight 추정에는 p50이 아닌 평균 체류시간을 사용하며 실제 관측 분포를 우선한다.
- CPU quota·thread pool·GC flags·object-header flags·cache·realtime 설정은 A/H 동일. 외부 realtime 입력은 고정/차단하고 snapshot 격리 테스트는 별도로 유지한다.

### 통계·보고

- 변동률은 100×(H/A−1)로 통일한다. latency/RSS 양수는 악화다.
- 독립 단위는 요청이 아니라 fresh-JVM run/pair/block이다. 각 조건 6 pair, AB/BA 3회씩, paired bootstrap 10,000회·95% CI를 사용한다.
- 한 run의 수천 요청을 독립 반복으로 취급하지 않는다. pilot과 JFR run은 성능 표본에서 제외한다.
- 주 지표: SpeedTest Total/Worker, HTTP p95·CPU seconds/완료 요청, measured RSS p95. peak·p50/p99·committed·GC는 보조 지표로 모두 공개한다.
- 자체 실용 기준: latency/CPU 악화의 CI 상한≤+5%이면 해당 지표의 5% 비열등성 근거, CI 하한>+5%면 실질 회귀, 나머지는 미확정. 이는 upstream 기준이 아니다. Worker/case tail 회귀를 Total 평균으로 감추지 않는다.
- RSS 개선은 CI 상한<0이어야 하며 5% 이상 개선을 주장하려면 CI 상한≤−5%여야 한다. allocation 효과만 있으면 그 범위로 결론을 제한한다.
- 성공값이 나올 때까지 표본을 추가하지 않는다. 6 pair 후 불확실하면 그대로 보고하고 후속 연구 계약을 별도로 정한다.

## P0. 상태 확인·실험 계약 동결

1. 저장소 status/HEAD/remote, worktree, 기존 raw/JAR/graph 및 동작 중 OTP/Docker를 read-only 확인한다.
2. upstream base/JDK/image를 고정하고 별도 codex/ 브랜치 또는 worktree에서 A/H를 빌드한다.
3. H diff와 snapshot 안전성, 관련 단위/영향 테스트 및 formatter/checkstyle을 확인한다. base/JDK 변경에 필요한 검증만 다시 수행한다.
4. 기존 HTTP corpus 매핑을 읽고 SpeedTestRequest와 API effective options를 검증한다. 408건 A1/H1/H2/A2 정합성 확인; 노출되지 않는 cost는 별도 routing 검증으로 구분한다.
5. collector의 PID/단위/구간/clock/swap/Full GC 분류와 부하 생성기 backlog·timeout 처리를 fixture 또는 짧은 smoke로 검증한다.
6. 자원 여유를 측정한다. Xmx8g 외 native·graph·VM·host 여유도 필요하며 단순히 available 8GB로 통과시키지 않는다.
7. 아래 단계별 run 수, 예상 시간, warmup, duration, rates, seed, 임계값을 MANIFEST에 동결한다. Docker 실행 전에 이를 사용자에게 보고한다.

산출물: 소스/이미지/graph/corpus hash, 정합성 결과, 수집기 검증, 자원 판정. 완료 기준: 정체성·측정·정합성 gate 통과.

## P1. 공식 조건에서 H-only CPU 회귀 재확인

- 로컬 Docker, Helsinki shared graph, upstream JDK/flags, Xmx8g. 공식 명령/옵션을 manifest에 저장한다.
- A/H 6 pair(12 fresh JVM), 4 samples/run, 공식 GC/warmup 변경 없음.
- sample별 Total/Worker와 testcase timings/오류/expected 일치 수를 보관한다. 최종 CSV에만 의존하지 않는다.
- 느린 testcase/category가 pair마다 반복되는지 검사한다. testcase p95는 다양한 case 간 분포이며 HTTP 요청 tail과 다른 지표로 표시한다.
- 재현되면 별도 A/H AB/BA JFR 2 pair에서 동일 구간 CPU·allocation stack을 비교한다. profile 비중 증가는 절대 CPU 시간 증가와 구별한다.
- accessor/constructor 비용이 의심되면 해당 부분의 JIT/CPU 진단을 한정 수행한다. microbenchmark는 결과 소비·warmup·fork·현실적 stops/trips 차원을 갖추고 전체 서버 증거를 대체하지 않는다.

판정: 회귀 재현/미재현/미확정. 회귀가 있어도 P2의 제한된 원인 검증은 수행할 수 있지만 P3 대규모 matrix는 P4 구현 판단 후 진행한다.

## P2. HTTP Xms 영향 분리 — Helsinki 2×2 실험

| 셀 | 코드 | heap |
| --- | --- | --- |
| A-fixed | A | Xms8g Xmx8g |
| H-fixed | H | Xms8g Xmx8g |
| A-elastic | A | Xms512m Xmx8g |
| H-elastic | H | Xms512m Xmx8g |

1. 4셀 공통 G1/JDK/image/graph/CPU/컨테이너 한도 사용. AlwaysPreTouch와 추가 GC 튜닝을 넣지 않는다. 변수는 Xms와 H 여부뿐이다.
2. 이전 16 RPS를 바로 채택하지 않는다. 네 셀이 모두 no-pressure·비포화인 공통 도착률을 짧은 pilot에서 결정한다. pilot값으로 본 측정의 입력 seed·순서·시간표를 고정한다.
3. warmup은 최소 5분과 전체 corpus 3회 모두 충족한다. pilot에서 p50 두 연속 2분 창 변화≤5%, backlog 증가 없음 확인. 네 셀 중 가장 긴 필요 warmup을 공통 고정 시간으로 선택(상한 20분). 본 실행이 안정화되지 않으면 실패로 기록하고 유리한 셀만 임의 연장하지 않는다.
4. warmup부터 RSS·heap used/committed·GC를 수집한다. 본 구간은 최소 10분과 10,000 예정 요청 모두 충족하도록 duration=max(600,ceil(10000/RPS))초로 사전 고정한다.
5. 네 셀을 6 block, 총 24 fresh JVM 실행한다. block 내 fixed/elastic pair 순서를 교차하고 각 pair의 A/H도 AB/BA 3회씩 맞춘다. 각 block의 동일 workload로 interaction을 비교한다.
6. 별도 진단 2 block(8 JVM)에서 measured-window JFR allocation/완료 요청 및 GC heap timeline을 수집한다. 필요시 NMT 진단은 별도 run으로 제한한다.
7. elastic에서의 H/A 비율과 fixed에서의 H/A 비율을 block별 비교하고 bootstrap CI를 산출한다. baseline A의 Xms 효과, H의 Xms 효과도 보고한다.

판정: Xms가 H의 RSS 효과를 변화시킴/입증 안 됨/불확실. committed 차이를 RSS 절감으로 치환하지 않는다. 개선이 없더라도 실패 은폐 없이 종료한다.

## P3. 서울 재현·조건별 효과 검증

P1 실질 CPU 회귀가 있으면 P4에서 개선하거나 tradeoff 연구로 계속할 이유를 먼저 기록한다.

### P3a. 충분한 메모리의 서울 HTTP

- 최신 고정 A/H, 서울 shared graph, Xms512m/Xmx8g 및 P2와 동일 실행 도구 사용.
- 과거 2 OD는 역사 재현용 pilot으로 분리한다. 주 corpus는 원본 4 OD를 포함하고 도보/대중교통/환승·거리·방향·검색시간창을 고려한 최소 20개 OD를 candidate 성능을 보기 전에 동결한다. 날짜는 graph service validity 안으로 명시한다.
- 전체 corpus의 A/H correctness를 먼저 확인한다. 각 데이터셋 내부 요청 옵션은 고정하고 서로 다른 도시에서 완전히 동일 검색이라고 주장하지 않는다.
- c1/c2/c4 pilot으로 포화·메모리 여유를 확인하고, 공통 비포화 최대 도착률의 약 30%와 70%에서 정식 6 pair씩 수행한다. 조건별 12 JVM, 총 24 JVM.
- P2의 Helsinki elastic rate가 두 수준 중 하나와 정확히 같고 나머지 계약도 같으면 기존 6 pair를 재사용한다. 다르면 Helsinki 대응 조건만 추가하며 합산하지 않는다.
- 동일 warmup/측정 계약과 measured-window JFR 2 pair/선정 조건을 사용한다. stops/trips 차원 계측은 별도 진단으로만 실행한다.
- 최소 목표 산출: 요청당 constructor allocation, 전체 allocation, RSS p95, committed timeline, CPU/request, HTTP tail, GC pause/request를 두 도시에서 비교.

### P3b. 로컬 제한 메모리 재현

- 서울 graph, Xms512m/Xmx3500m, container memory=4500m, memory-swap=4500m을 출발점으로 명시적으로 고정한다. VPS의 Xms 생략과는 완전 동일하지 않다고 기록한다.
- host/VM에는 충분한 여유를 남겨 컨테이너 내부 제약만 만든다. no-pressure 기준의 자연 Full GC 금지는 이 series에 적용하지 않는다.
- closed-loop c1/c2/c4 pilot은 과거 비교용 보조 자료다. 선택한 부담 수준에서 동일 open-loop 도착률로 A/H 6 pair를 비교해 같은 offered load의 성공률·지연·GC·RSS를 제시한다.
- A가 감당하지 못하면 그 자체를 결과로 남긴다. timeout·backlog cap·OOM이 발생한 run의 성공 요청만으로 빠르다고 계산하지 않는다. 동일 요청 총량/시간과 실패율을 함께 보고한다.
- 목적은 최소 메모리 보장이나 최대 처리량 경계 전체 탐색이 아니다. 추가 한도 탐색은 이 결과에 따라 별도 결정한다.

### P3c. VPS RSS 차이 원인 브리지 실험 — 명확한 원인 판정에 필요

P3a/P3b가 끝나도 과거 VPS의 절반 RSS가 Xms, heap 상한, 서울 workload 중 무엇에서
왔는지를 단정하지 못하면 이 단계를 수행한다. P5 VPS 관측만으로는 CPU·이웃 컨테이너·JDK·
graph/JAR 차이가 섞이므로 인과 분리에 사용하지 않는다.

1. 최신 H-only A/H를 같은 Linux Docker image/JDK 25에서 빌드한다. 동일 Seoul graph와
   과거 VPS의 고정 OD corpus를 사용하되, graph를 새 source로 빌드했는지와 SHA를 기록한다.
   과거 `24d533…` graph/JAR를 그대로 현재 H 결과로 부르지 않는다.
2. 컨테이너 설정은 `--memory=4500m --memory-swap=4500m`, JVM은 `-Xmx3500m`, G1로
   고정한다. Xms만 다음 두 값으로 나눈다.
   - elastic: `-Xms512m -Xmx3500m`
   - fixed: `-Xms3500m -Xmx3500m`
   `AlwaysPreTouch`와 명시적 GC는 사용하지 않는다.
3. 과거 실행과 같은 closed-loop 부하를 재현한다: OD 4개, OD당 warmup 25 + measured
   20, c1과 c4를 별도 cell로 실행한다. c2는 c1/c4 방향이 다를 때만 추가한다. 각 cell은
   A/H AB/BA 최소 3 pair로 실행하고 동일 요청·동일 순서·동일 timeout을 사용한다.
4. 각 run에서 다음 시계열을 함께 보존한다.
   - JVM PID RSS와 cgroup current/peak
   - G1 GC log의 committed capacity와 `before->after` heap
   - Full GC 횟수·pause 합·최대 pause
   - warmup 종료 직전 idle RSS, measured peak RSS, measured 구간 RSS delta
   - Docker/host available·swap·PSI·`memory.events`
5. 사전 판정 규칙:
   - `fixed`에서 A/H RSS 차이가 사라지고 `elastic`에서 재현되면 Xms/heap 초기 정책의
     기여가 지지된다.
   - 두 Xms에서 A만 3500MiB committed에 도달하고 H는 1.5~1.7GiB에 머물면
     request allocation이 G1 heap 확장 임계점을 넘긴다는 설명이 지지된다.
   - A/H committed heap이 비슷한데 RSS만 다르면 graph/native/cgroup 측정 또는
     artifact 차이를 재조사한다.
   - 현재 H-only가 옛 VPS 비율을 재현하지 못하면 “VPS 수치는 옛 JAR·graph·JDK·
     workload가 결합된 결과”로 판정하며 현재 H의 보편적 RSS 절감으로 승계하지 않는다.
6. local Linux bridge가 과거 방향을 재현한 뒤에만 P5에서 VPS 최소 c1/c4 pair를 수행한다.
   VPS pair는 운영 재현성 확인이고, bridge의 heap/RSS timeline이 원인 증거다. VPS의
   기존 서비스 상태·host counter·실제 x86 결과는 별도로 기록한다.

산출물: `H-ONLY-UPSTREAM-VALIDATION-BRIDGE-MANIFEST.json`, cell별 raw/
`bridge-run-ledger.md`, committed/RSS/GC 시계열 plot 또는 표, Xms·heap 확장·workload
가설별 `SUPPORTED`/`NOT_SUPPORTED`/`INCONCLUSIVE` 판정. 이 단계가 없으면 VPS RSS 차이의
원인은 “allocation pressure로 A의 heap이 확장된 설명과 일치” 수준으로만 보고한다.

### P3d. Helsinki VPS-like pressure series — 서울과 별도

현재 Helsinki no-pressure 결과와 과거 서울 VPS 결과 사이의 빈칸을 메우기 위한 단계다.
Helsinki에서 RSS 차이가 **발생하지 않는 결과도 유효한 결과**로 취급한다. 서울 P3c의
결과와 합산하지 않고, “같은 H 변경이 graph와 부하에 따라 pressure cliff를 만드는가”를
확인하는 별도 series로 보존한다.

1. P2/P1에서 고정한 Helsinki graph·408-case corpus·최신 A/H-only JAR를 사용한다. A/H는
   같은 graph·JDK·image·query·seed를 사용한다. 과거 H+F JAR나 서울 graph를 이 단계에
   사용하지 않는다.
2. P3c와 동일한 Linux Docker/JDK 25 조건을 적용한다.
   - JVM: `-Xmx3500m`, G1
   - container: `--memory=4500m --memory-swap=4500m`
   - Xms cell: `-Xms512m`(elastic), `-Xms3500m`(fixed)
   - `AlwaysPreTouch`, `System.gc`, `jcmd GC.run`, 추가 GC tuning 금지
3. 두 부하를 별도 측정한다.
   - **Helsinki-full:** 408개 전체 testcase를 고정 seed로 순환하는 open-loop 부하. c1과
     c4를 별도 cell로 실행해 graph 전체의 pressure 반응을 본다.
   - **Helsinki-fixed:** 사전에 hash로 고정한 Helsinki testcase 4개를 과거 VPS의
     OD당 warmup 25 + measured 20 계약으로 반복한다. 사후에 A에 유리하거나 H에
     유리하도록 testcase를 고르지 않는다. 선정 근거와 제외 목록을 manifest에 남긴다.
   `Helsinki-fixed`는 VPS workload 구조와의 비교용이고, `Helsinki-full`은 공식 corpus
   대표성 확인용이다. 두 결과를 한 평균으로 만들지 않는다.
4. 각 Xms·부하 cell은 A/H AB/BA 최소 3 pair로 실행한다. warmup과 measured 모두
   명시적 GC 없이 실행한다. A/H의 입력 도착시간·동시성·timeout·요청 수를 맞춘다.
   measured 구간은 고정 OD에서는 20회/OD, full corpus에서는 최소 10분 또는 10,000
   예정 요청 중 큰 값으로 사전 고정한다.
5. P3c와 같은 지표를 수집하고 특히 다음 시계열을 함께 본다.
   - G1 committed capacity가 `3500M`에 도달하는 시점
   - warmup 종료 직전 idle RSS와 measured peak RSS
   - GC `before->after` heap, Full GC 횟수·pause, 자연 Young GC
   - JVM PID RSS, cgroup current/peak, host/VM pressure·swap·OOM
   - 실제 in-flight 수, backlog, 완료율, HTTP p50/p95/p99
6. 사전 판정:
   - Helsinki에서도 A만 `3500M` committed 및 Full GC에 도달하고 H가 낮은 capacity에
     머물면, allocation pressure→heap expansion→RSS 차이 경로가 graph를 넘어 재현된
     것으로 판정한다.
   - Helsinki-full에서는 차이가 없고 Helsinki-fixed에서만 차이가 나면, 효과를 특정
     request mix로 제한해 보고한다.
   - 두 부하 모두 A/H capacity·RSS가 비슷하면, 과거 VPS 절반 RSS는 서울 graph·OD·
     VPS 조건의 결합 효과로 분류한다.
   - `Xms512m`에서만 차이가 나고 fixed에서 사라지면 Xms/heap 초기 정책 기여를 지지한다.
   - RSS 차이가 committed capacity 차이 없이 발생하면 graph/native/cgroup 측정 정의를
     재검증하고 원인 판정을 `INCONCLUSIVE`로 남긴다.
7. pressure cell에서 host/VM swap이 발생하면 해당 run을 즉시 `SWAP_EVENT`로 기록한다.
   이 series의 목적은 pressure 반응을 관측하는 것이지만, swap의 양을 H의 코드 효과로
   직접 귀속하지 않는다. swap-free 결과와 swap-affected 결과를 별도 표로 유지한다.

산출물: `H-ONLY-UPSTREAM-VALIDATION-HELSINKI-PRESSURE-MANIFEST.json`,
`helsinki-pressure-run-ledger.md`, Xms·부하별 heap/RSS/GC 그래프, 서울 P3c와 분리된
판정표. 이 단계가 끝나야 “Helsinki에서도 VPS와 같은 RSS 확대가 재현되는가”에 답한다.

## P4. CPU 회귀 개선 — 조건부

1. 재현된 profile/코드 근거로 한 가설을 선택한다. accessor, 간접 참조, locality, JIT 문제를 확정 전 단정하지 않는다.
2. H-original을 보존하고 H2를 별도 patch로 만든다. 요청 snapshot·정렬·offset·overnight·frequency·boarding 의미를 유지한다. 전역 mutable cache나 새로운 복잡한 설정을 근거 없이 도입하지 않는다.
3. 관련 회귀 테스트와 좁은 microbenchmark/SpeedTest screening을 수행한다. 본 6-pair 결과를 반복 tuning에 사용하지 않는다.
4. 후보가 안정되면 A/H2 fresh confirmation 6 pair와 핵심 HTTP 조건을 새 series로 확인한다. 기존 H의 유리한 표본을 H2에 합치지 않는다.
5. 최대 두 설계 후보까지만 이 계획에서 시도한다. 해결되지 않으면 tradeoff와 가설을 문서화하고 maintainer 논의 단계로 이동한다.

통과는 allocation 이득 유지·정합성 유지·CPU/tail 손실의 충분한 축소다. 임의 2~3%를 upstream 합격선이라고 부르지 않는다.

## P5. VPS 최종 확인 — 운영 보호 우선

- P0에서 확인한 운영 서비스 상태와 자원을 다시 확인한다. 원자료 읽기는 가능하나 신규 부하가 기존 운영 서비스에 영향을 줄 수 있으면 시간대·자원 예산·중단 기준에 대해 명시적 실행 권한을 확보한다.
- live OTP 옆에 두 번째 JVM을 무조건 띄우거나 기존 OTP를 중단하지 않는다. 자원이 부족하면 별도 x86 테스트 머신에서 먼저 재현하고 VPS 측정은 보류한다.
- 운영 검증 대상 H+F와 upstream 후보 H를 별도 variant로 표시한다. 로컬 JAR의 JDK/classfile 호환성을 확인하고 필요한 경우 같은 source로 native x86 빌드한다.
- 같은 graph/이미지/heap/요청을 쓰는 A/H 최소 3 AB/BA pair를 안전한 부하에서 수행할 수 있으면 진행한다. 3 pair는 외부 재현·운영 관측 등급이며 광범위한 통계적 우월성 주장에 쓰지 않는다.
- 선택 후보의 30분 soak에서 5분 창 RSS·GC·latency·오류 추세를 기록한다. 30분 결과로 장기 누수 부재를 보장하지 않는다.
- host available<20%, 새 host swap I/O, OOM, 기존 서비스 health 실패 또는 사전 기준을 넘는 지연이 지속되면 실험 부하를 즉시 중단한다. 중단·정리 대상은 해당 실험 소유 PID/container만이다.
- 배포 전환은 별도 권한과 rollback 준비가 필요한 작업이다. 운영 H+F를 기여 후보라는 이유로 자동 교체하지 않는다.

## P6. 기여 자료와 maintainer 판단

P1 결과와 재현 방법이 준비되면 Issue/Draft PR 초안을 만들 수 있다. 남은 실험 전에 의견을 받아 방향을 조정할 수 있지만 GitHub 게시 권한은 따로 확인한다.

| 판정 | 제안 |
| --- | --- |
| 결과 동일, allocation 감소, CPU/tail 회귀 허용 범위 내, 운영 이득 재현 | H-only PR 제출 준비 |
| 특정 데이터에서 메모리/GC 이득, 다른 조건에서 CPU 회귀 | tradeoff Issue/Draft PR, 회귀까지 공개하고 설계 의견 요청 |
| allocation만 감소하고 실질 CPU 회귀 지속 | 일반 성능 개선 PR 보류; 재현 benchmark/문제 분석 또는 개선 설계 기여 |
| H 고유 결과 mismatch | 성능 판정 중단, 기능 원인 해결 후 관련 gate 재실행 |

- PR title은 request-local timetable allocation 감소를 구체적으로 설명한다.
- H production diff와 관련 테스트만 포함하고 도구/대용량 raw/운영 config는 분리한다.
- correctness 범위, snapshot 근거, JDK/flags/base/graph, 실행수, 지표별 CI, Worker/tail 회귀, 공식 expected mismatch 상태를 공개한다.
- 현재 PR 초안의 전체 테스트 수에는 skipped가 포함됐는지 원보고서를 확인하고 passed/executed/skipped를 정확히 구분한다.
- raw·실행 도구는 /private/tmp 외 영속 위치로 보존하고 hash manifest를 작성한다. private feed/응답의 공개 권한을 확인하고 비공개 데이터라면 공개 fixture 재현을 함께 제공한다.
- maintainer 요청으로 base/JDK/설계가 바뀌면 영향을 받는 gate만 재검증한다.

## 5. 실행 산출물·예산·인수인계

이 문서 옆에 아래 파일을 단계별로 생성/갱신한다. 아직 실행하지 않은 단계를 PASS로 쓰지 않는다.

- H-ONLY-UPSTREAM-VALIDATION-MANIFEST.json: base/patch/artifacts, host/image/JVM, graphs/config/corpus, matrix/seed/rates/duration, thresholds, status, raw hashes.
- H-ONLY-UPSTREAM-VALIDATION-RUN-LEDGER.md: run-id, phase/block/cell, PID/container, times, correctness, metrics, swap/pressure, exit, exclusion/retry 사유.
- H-ONLY-UPSTREAM-VALIDATION-RESULT.md: 사전 질문별 결론, 반복 통계, 회귀, 공개 가능한 주장/불가 주장.
- 실행별 stdout/stderr, requests/fingerprints, JVM RSS/cgroup/host/VM series, GC log, options, summary, 별도 JFR 및 parser 버전.
- swap-events.jsonl: 발견 즉시 append, 계층/단위/시각/누적 delta 포함. 원본 로그 수정 금지.

예상 규모: P1 12 timing JVM + 필요시 4 JFR JVM, P2 24 timing JVM + 8 JFR JVM, P3 서울 24 timing JVM + 제한 조건 12 JVM, Helsinki pressure 24~48 timing JVM 및 필요시 8 JFR JVM. pilot·기능 검증·조건부 H2·VPS는 별도다.

HTTP 본 run은 최소 warmup 5분+측정 10분이므로 P2 timing만 최소 6시간이며 낮은 RPS면 더 길어진다. 단계별 ETA는 startup+고정 warmup+max(600,10000/RPS)+drain으로 계산한다. 전체를 한 번에 시작하지 말고 각 단계가 다음 질문을 해결할 가치가 있는지 gate에서 판정한다. 사용자에게 단계 시작/중요 발견/실패/완료를 보고한다.

다음 에이전트 시작 작업: **P0의 read-only 상태 확인과 로컬 실행 계약 작성**. 이 계획의 완료는 merge 보장이 아니라, 운영 효과와 upstream tradeoff에 대해 재현 가능한 결론·제출 가능한 자료가 준비된 상태다.
