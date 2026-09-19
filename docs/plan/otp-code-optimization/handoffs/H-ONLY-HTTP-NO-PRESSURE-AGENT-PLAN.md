# H-only HTTP 서버 검증 — 메모리 압박 없는 환경의 에이전트 실행 계획

작성: 2026-09-15. 상태: P0–P2 완료, P3 관측 부분 완료 / P4–P7 미실행.

## 1. 목적과 주장 범위

공식 Helsinki 데이터의 동일 graph와 요청 corpus로 OTP HTTP 서버를 실행한다.
메모리가 충분한 조건에서 H가 A보다 실제 RAM을 얼마나 덜 쓰고, HTTP 응답 시간이
얼마나 변하는지 측정하여 upstream PR의 근거를 만든다.

세 질문에 각각 답한다.

1. HTTP 실행에서도 결과와 cost가 동일한가?
2. 같은 요청 유입량에서 프로세스 RSS와 heap 사용량이 감소하는가?
3. 평균 및 tail 응답 시간과 요청당 CPU 비용은 어떻게 변하는가?

사용자의 목적은 오픈소스 기여다. 서울 서비스에서의 절감을 다시 확인하는 작업보다
공식 데이터에서 재현할 수 있는 서버 측 증거를 우선한다. Helsinki 한 데이터셋의
결과를 모든 OTP 배포의 보편적 개선으로 표현하지 않는다. 다른 공식 데이터셋은
Helsinki 결과 이후 별도 계약으로 검증한다.

실행 시 Docker 기동 전에 이미지 digest, JVM/컨테이너 한도, host 여유, 예상 시간,
중단 기준을 보고한다. 현재 실행은 Docker 없이 macOS host JVM에서 진행했다.
GitHub 게시·서비스 배포·기존 서비스 종료는 이 계획의 범위에 포함하지 않는다.

## 2. 먼저 읽을 자료와 기존 결과 해석

1. 이 문서와 저장소별 AGENTS.md.
2. H-ONLY-CONTRIBUTION-PLAN.md — H 격리·snapshot 안전성·테스트.
3. H-ONLY-SHARED-GRAPH-AGENT-PLAN.md, RESULT/ MANIFEST/ RUN-LEDGER — 과거 계약과 raw 위치.
4. PR-EVIDENCE.md, ABLATION-RESULT.md — 과거 서울 H/HF 증거의 한계.
5. 고정 checkout의 공식 performance README, SpeedTest.java, SpeedTestRequest.java,
   HTTP schema/resolver, 서버 설정, 기존 seoul-metro 측정기.

기존 증거는 실행 기록을 대조하여 활용한다. 이전 문서의 완료 체크만으로 통과하지 않는다.

- 같은 graph의 저장된 408-case 결과·cost가 일치했다. -n 4의 CSV는 마지막 sample만
  저장되므로 전체 네 sample의 엄격 동등성을 모두 증명한 것으로 확대하지 않는다.
- 전체 라우팅 평균은 유사했지만 RAPTOR 내부 시간에는 약 7.6% 악화 신호가 있었다.
  유의한 차이를 못 찾았다는 사실은 성능 동등성의 증명이 아니다.
- 기존 5% 기준은 자체 탐색 기준이며 upstream 합격 기준이 아니다.
- 기존 공식 expected는 A/H 모두 175/408 mismatch였다. A/H 동등성과 분리한다.
- SpeedTest의 명시적 GC는 각 sample 전이다. sample 안의 자연 GC는 가능하다.
- JFR G1Old 이벤트 수를 Full GC 횟수로 해석하지 않는다. name/cause를 함께 분석한다.
- 이전 RSS는 전체 실행 구간, 기존 Docker RSS는 measured 구간이었다. Docker 수집기의
  rssBytes는 Java PID VmRSS이고 cgroup 메모리는 별도 필드다.
- 이전 JFR 누적 allocation은 전체 기록의 추정치다. measured 요청 수로 나누어
  요청당 할당량으로 바꾸지 않는다. GC 후 used heap도 정확한 retained live set과 다르다.

## 3. 고정 대상

- 코드 저장소: /Users/wa/golbob/개인/OpenTripPlanner
- 문서 저장소: /Users/wa/golbob/개인/nextroute
- upstream base: a0b62f69fc543e84687752e17dae8a66b01c5e28
- A worktree: /private/tmp/otp-a-only-run-7mQx2K
- H worktree: /private/tmp/otp-h-only-run-v83UVX
- H patch SHA-256: ca1caaf61758100a821211c716e754a597936d182c7f63c6e6630592f29fa9a1
- 공유 graph 후보: /private/tmp/otp-a-only-helsinki-4wN2sL/graph.obj
- graph SHA-256: 178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0
- fixture: Helsinki February 2026, testDate 2026-02-19, 408 cases.

임시 경로는 존재와 hash를 재확인한다. graph가 유실되거나 호환되지 않으면 A에서
한 번만 재생성하고 A/H가 공유한다. 읽기 전용 mount와 시작/종료 hash 검증을 적용한다.
실험 중 upstream SHA를 바꾸지 않는다. 기존 H+F commit을 재사용하지 않는다.

## P0. 소스·서버·수집 도구 준비

1. A/H HEAD, dirty diff, JAR hash, JDK, 의존성, 빌드 flags를 기록한다.
   production diff는 H만 허용한다. 필요한 빌드와 관련 테스트를 수행한다.
2. 동일 Linux 이미지·JDK를 사용한다. 우선 공식 checkout의 JDK 25 조건을 검토한다.
   JDK를 바꾸면 양쪽 재빌드하고 기존 Java 26 결과와 별도 series로 기록한다.
3. heap은 A/H 모두 -Xmx8g, 같은 G1·Xms·object-header flags로 시작한다.
   Xms=Xmx, AlwaysPreTouch, 별도 강제 GC/GC 튜닝을 효과 증명 수단으로 추가하지 않는다.
4. A/H는 같은 host에서 순차로 실행한다. CPU quota/pinning, 서버 thread pool,
   로그 레벨, realtime updater 사용 여부, cache 설정을 같게 고정한다.
   고정 snapshot 실험에서는 외부 realtime/network 입력을 차단하고 한계를 명시한다.
5. Linux Java PID RSS, cgroup current/peak/anon/file, GC log, heap used/committed,
   CPU, host/container 압력, HTTP 측정기를 준비한다. 기존 도구는 읽고 재사용한다.
6. 수집기/parser는 정상·누락·잘못된 PID·시간 경계·timeout·압력 발생 fixture로 검증한다.
   source 도구와 analysis script는 운영 H PR diff 밖에 둔다.

통과: 소스/실행 정체성과 수집 단위가 명확하며 A/H 서버 기동 가능.

## P1. 공식 corpus를 HTTP 요청으로 매핑

1. 고정 schema에서 실제 사용 가능한 HTTP endpoint와 query를 선택한다.
2. 408개 case의 좌표, 날짜/시간/timezone, arriveBy, 모드, 검색창, itinerary 제한,
   접근/환승/비용 옵션을 SpeedTestRequest와 HTTP resolver 기준으로 매핑한다.
3. 매핑표, 완전한 요청 body, 예상 응답 필드, case/category, seed, hash를 저장한다.
   schema default에 맡기는 필드는 실제 effective 값이 같은지 확인한다.
4. HTTP API에 표현할 수 없는 옵션이나 cost 필드는 미지원으로 표시한다.
   c1/c2가 노출되지 않으면 같은 effective 요청의 별도 routing 진단으로 cost를 비교한다.
   그 경우 HTTP 응답에서 직접 cost를 검증했다고 쓰지 않는다. 진단은 본 성능 실행과 분리한다.
5. 매핑 차이가 남으면 동일 workload라는 주장을 중단하고 해결한다. 임의로 case를
   제외하거나 옵션을 단순화해 408개 전체 검증이라고 표시하지 않는다.

통과: 408개 case의 의미 보존 또는 정확한 미지원 사유가 기록됨.

## P2. 결과 동일성 gate

독립 서버 A1 → H1 → H2 → A2에서 전체 corpus를 실행한다.
HTTP status 외에도 GraphQL errors, routing 오류, 무경로, itinerary 수를 검사한다.
동일 case의 경로·시각·duration·환승·노선·정류장 및 노출되는 cost를 비교한다.
동적 debug timing/request ID 등 제외 필드만 사전 whitelist로 정한다.
순서 차이는 별도 기록하고, 중복을 보존하는 multiset 비교를 추가한다.
시간/cost 허용 오차를 임의로 만들지 않는다.

공식 expected mismatch와 A/H 차이를 구분한다. 자체 반복 불안정성 또는 H 고유의
차이가 해결되지 않으면 정식 측정을 시작하지 않는다.
본 부하에서도 case별 응답 fingerprint와 오류를 보관하고 동시 실행의 차이를 검출한다.

## P3. 부하와 메모리 여유 pilot

### 같은 부하의 정의

주 실험은 open-loop 고정 도착률 방식이다. 요청 완료와 무관하게 사전 시간표대로
요청을 보낸다. A/H는 동일 seed·case 순서·예정 시각·총 요청 수를 사용한다.
408개를 한 cycle로 균형 순환하고 cycle마다 고정 seed로 shuffle한다.
단순 sleep-after-response 방식은 금지한다.

동시성 1/2/4의 짧은 closed-loop pilot으로 처리량·overlap·포화 경향을 확인한다.
이 결과로 양쪽 모두 안정 처리 가능한 공통 도착률을 정하고, 30%/50%/70%의 세
도착률을 후보로 한다. CPU/대기열/메모리 조건을 통과하는 값만 본 실험 전에 고정한다.
세 도착률을 c1/c2/c4라고 부르지 않는다. 실제 in-flight 분포를 기록한다.
고정 동시성 비교가 추가로 필요하면 별도 보조 series로 실행하며 주 표본에 합치지 않는다.

load generator의 scheduling lag, connection pool, 최대 in-flight, timeout을 기록한다.
한도 도달 시 요청을 조용히 지연/삭제하지 않고 실패로 기록한다.
HTTP latency와 예정 도착 시각부터 완료까지의 latency를 함께 기록하여
부하 생성기가 느려져 tail이 축소되는 현상을 감지한다.

### 메모리 압박 없음 — 운영상 사전 기준

압력이 수학적으로 0임을 증명하는 대신 아래 관측 기준을 모두 적용한다.
정상 Young/Concurrent GC는 허용한다. GC 감소 자체는 측정할 효과다.

| 계층 | pilot 및 본 측정 기준 |
| --- | --- |
| Host/VM | swap-in/out delta 0, Linux MemAvailable 최저가 RAM 20% 이상 |
| Linux pressure | memory PSI full stall 증가 0, some stall 비율 0.1% 이하 |
| cgroup | memory.events의 high/max/oom/oom_kill 증가 0, memory.swap.current 증가 0 |
| 컨테이너 한도 | current 최고가 limit 80% 이하; 무제한이면 host 여유로 평가 |
| JVM | 자연 Full GC 0, allocation/evacuation failure·to-space exhausted 0 |
| Heap | 관측된 GC 후 used p95가 Xmx 60% 이하, 5분 창의 지속 상승 없음 |
| 실행 조건 | CPU 평균 70% 이하의 할당 용량, 지속 throttling·대기열 증가 없음 |

기준은 이 연구의 사전 규칙이며 OTP 공식 기준이 아니다. 일부 카운터 미지원이면
0으로 대체하지 않는다. Docker Desktop은 macOS host와 Linux VM 양쪽을 검사한다.
VM 지표를 확보할 수 없다면 정식 no-pressure 주장은 보류하고 계측 가능한 환경을 쓴다.

8g가 부족하면 데이터를 줄여 통과시키지 않는다. 양쪽 heap/host 용량을 같은 조건으로
재설정하고 pilot부터 새 series를 시작한다. 기존 실행 서비스는 메모리 확보 목적으로
종료하지 않는다. 환경 확보가 필요하면 필요한 RAM과 근거를 보고한다.

예열은 전체 corpus를 사용해 최소 5분과 3 cycle을 모두 만족한다. 두 연속 비교에서
2분 창의 HTTP p50 변화가 5% 이하이고 backlog가 증가하지 않아야 한다.
예열 상한 20분, 안정화 실패는 본 표본에 포함하지 않는다. 명시적 GC는 호출하지 않는다.
pilot에서는 양쪽 최고 도착률을 최소 10분 측정하여 collector와 압력 기준을 검증한다.

통과: 구체적인 세 도착률·heap·한도·예열/측정 시간·전체 예상 소요량을 manifest에 동결.

## P4. 정식 반복 측정

- 각 도착률 6 pair, 총 18 pair/36 fresh JVM. pair 내 순서 AB/BA를 3회씩 균형화한다.
- 세 부하 수준의 실행 순서도 block별 순환하여 시간대/열 영향을 분산한다.
- 각 JVM은 예열 후 최소 10분, 최소 10,000개 예정 요청을 모두 만족하도록 실행한다.
  최종 duration은 pilot 도착률로 사전 계산하고 같은 pair에 똑같이 적용한다.
  긴 실행 시간을 숨기지 말고 본 실행 전 총 소요 시간을 보고한다.
- burst 없는 균등 도착 시간표를 기본으로 한다. burst/실제 트래픽 재생은 후속 series다.
- 종료 시 미완료 요청의 timeout까지 drain하되, 메모리 주 구간과 drain 구간은 분리한다.
- 양쪽 모두 동일한 GC log·저비용 sampler를 켠다. JFR/NMT/class-loading 진단은 별도다.
- 압력/수집 실패는 해당 pair 전체를 재실행한다. 최대 2 pair 재시도 후 표본 부족으로 종료.
  H 고유 오류·지연·메모리 회귀를 환경 실패로 바꿔 유리한 표본만 선택하지 않는다.
- OOM/호스트 영향/절전/지속 backlog 발생 시 해당 실험 프로세스를 중단하고 기록한다.

## P5. 측정 지표와 별도 진단

| 구분 | 지표·단위 |
| --- | --- |
| Primary memory | measured-window JVM RSS p95, MiB |
| 보조 memory | RSS median/peak/idle, cgroup anon/file/current; lifetime peak 별도 |
| Heap | used/committed 추이 및 GC 전후 used, MiB; committed를 live 객체량으로 해석하지 않음 |
| Primary latency | 예정 도착 시각 → 응답 완료 p95, ms |
| 보조 latency | 송신 → 응답 완료 p50/p95/p99, 내부 routing 시간(노출될 때), scheduling lag |
| Capacity | offered/completed RPS, 실제 overlap, timeout/오류/대기열 |
| CPU/GC | CPU seconds/완료 요청, pause 합/횟수/최대 및 시간 비중, cause별 GC |
| Allocation | 별도 JFR measured-window weight/실제 요청 수, class/site별 추정치 |

RSS는 실제 Java PID를 100ms 목표로 수집한다. timestamp·PID·단위·시작/종료를 보존한다.
coverage 99% 이상, 최대 간격 1초 이하를 요구한다. sampled peak를 정확한 순간 최대로
표현하지 않는다. HTTP/수집기/JFR 시간 경계는 공통 monotonic 또는 검증된 clock으로 연결한다.

JFR은 대표 중간/높은 도착률에서 각 2 pair(AB, BA)로 실행한다. 같은 설정/hash와
실제 measured marker로 범위를 자른다. 시작·예열 allocation을 분모에 섞지 않는다.
공식 JFR event 종류를 확인하고 Old/concurrent cycle과 Full GC/pause를 구분한다.
heap 정보가 부족하면 동일한 별도 진단에 필요한 이벤트를 켠다. heap dump/강제 GC를
본 성능 실행에 넣지 않는다. 진단 지연을 기본 latency 평균에 합치지 않는다.

## P6. 안정성 및 통계 판정

대표 높은 비포화 도착률에서 별도 AB/BA 2 pair, 각 예열 후 30분 soak를 수행한다.
5분 창 RSS/heap/GC/지연 추이를 보고한다. soak는 반복 우월성 검정을 대체하지 않는다.

정식 결과는 요청 개수가 아닌 독립 pair를 통계 단위로 한다.
각 run의 주 지표를 구하고 pair별 H/A log-ratio를 집계한다.
paired bootstrap 10,000회, seed 20260915, percentile 95% CI와 계산 코드를 보존한다.
개선율 100 × (1 − H/A), 절대 MiB/ms, 기하평균 비율과 CI를 함께 보고한다.

- 메모리: H/A RSS p95 CI 상한 <1이면 감소 근거. 5% 이상 절감의 보수적 목표는
  상한 ≤0.95. peak 한 번의 변화만으로 일반화하지 않는다.
- Latency: H/A 주 latency CI 상한 ≤1.05면 해당 부하의 자체 비열등 기준 충족.
  CI 전체가 [0.95, 1.05] 안이면 그 범위의 근사 동등성 근거로 표현한다.
- 세 부하 모두 보고한다. 하나만 유리하면 그 조건으로 주장 범위를 제한한다.
  전체 부하의 동시 우월성 주장은 다중 비교 보정 없이 하지 않는다.
- case/category별 반복 회귀를 공개한다. 6 pair 중 4개 이상에서 >5% 느린 case를
  탐색 대상으로 표시한다. case별 표본이 적으면 p99나 유의성을 확정하지 않는다.
- error/timeout은 지연 통계에서 조용히 버리지 않고 별도 분모와 결과를 제시한다.

최종 분류: SUPPORTED_SCOPED / ALLOCATION_ONLY / TRADE_OFF /
INCONCLUSIVE / ENVIRONMENT_UNQUALIFIED / CORRECTNESS_FAILED.
메모리 절감·HTTP 지연·CPU 비용·정합성을 각각 결론내리고 미수집 항목을 명시한다.

## P7. PR 증거 및 인수인계

산출물 prefix: H-ONLY-HTTP-NO-PRESSURE.

- RESULT.md: 결론, A/H 값·CI, 부하별 회귀, 기존 SpeedTest와의 관계, 한계.
- MANIFEST.json: SHA/graph/image/JDK/flags, 요청 매핑/hash, 도착률·seed·시간표,
  collector·통계 설정, 정확한 명령/cwd, 단계 상태.
- RUN-LEDGER.md: pair 순서/시간/오류/압력/coverage/재시도/제외 이유/raw 링크.
- 작은 재현 wrapper, corpus 변환기, parser 및 검증 fixture.
- PR 표와 영문 문장, 기존 H-only 초안 및 NEXT-AGENT 업데이트.

raw는 stdout/stderr, request/response fingerprint, 예정·송신·완료 시각, RSS/cgroup/host
시계열, GC log/JFR, heap/CPU, 결과 diff를 보존한다. 장기 보존 경로와 SHA 목록을 만든다.
/private/tmp만으로 제출 준비 완료라 하지 않는다. graph/JFR 등 대형 파일은 Git 밖의
사용자 소유 영속 경로에 보관하며 공개 업로드는 별도 게시 지시 범위에서 진행한다.

PR 표현은 “공식 Helsinki fixture를 사용한 별도 HTTP 서버 benchmark”로 한다.
공식 SpeedTest 자체나 upstream CI가 이 부하 실험을 실행한 것으로 표현하지 않는다.
성공해도 모든 데이터셋/부하에서 이득이라고 일반화하지 않는다.

## 완료 체크리스트

- [x] P0 source/runtime/collector 검증
- [x] P1 공식 408 case HTTP 매핑 검증
- [x] P2 반복 결과·cost gate
- [ ] P3 양쪽 no-pressure pilot 및 계약 동결 (10분 open-loop 관측은 완료; closed-loop 및 Linux PSI/cgroup 미수집)
- [ ] P4 18 pair 정식 실행 또는 구체적 미완료 판정
- [ ] P5 지표/JFR 진단
- [ ] P6 soak·통계·회귀 분석
- [ ] P7 영속 raw·재현 도구·PR 증거 문서

## 다음 에이전트 실행 프롬프트

> 이 계획을 읽고 P0부터 실제 상태를 확인하라. 목적은 H-only의 upstream 기여 근거다.
> 공식 Helsinki의 동일 graph·408-case 요청으로 A/H HTTP 서버를 비교하라.
> -Xmx8g부터 양쪽 충분한 메모리 여유를 검증하고, 명시적 GC 없이 동일 도착률을 적용하라.
> Docker 시작 전에 실행 조건과 영향을 보고하라. 매핑·정합성·압력·수집 gate 이후에만
> 정식 pair를 실행하라. 개선을 전제하지 말고 절대 메모리·응답 지연·CPU·GC·CI와
> 회귀를 함께 기록하라. 과거 완료 문구나 H+F 결과를 이번 H-only 증명으로 대체하지 말라.
