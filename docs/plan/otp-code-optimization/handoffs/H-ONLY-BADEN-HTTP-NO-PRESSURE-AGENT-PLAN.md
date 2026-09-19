# H-only Baden-Württemberg HTTP 지속 부하 — 에이전트 실행 계획

작성: 2026-09-16  
상태: `P6_STOPPED_PARTIAL / P7-P9_DIAGNOSTIC_COMPLETE / ENVIRONMENT_CONTAMINATED`

## 1. 목적

공식 Baden-Württemberg SpeedTest의 `travelSearch.csv`를 HTTP GraphQL 요청으로
변환해, 동일 graph·요청 스케줄·JVM 조건에서 A(원본)와 H(H-only)를 비교한다.

### A/H 역할 고정

- **A = upstream 원본**: `arrivalTimes[]`/`departureTimes[]` 평탄 배열을 요청마다
  생성하고 stop×trip 값을 복사한다.
- **H = 본 기여 최적화**: 평탄 배열을 제거하고 `TripTimes[]`와
  `offsetsByTrip[]`를 참조한다.

이 역할은 소스/바이트코드로 확인한 의미론적 역할이다. `/A`·`/H` worktree명과
`otp-baseline.jar`·`otp-h-only.jar` basename은 과거 생성 과정에서 뒤섞였으므로
역할 판정에 사용하지 않는다.

상세한 SHA·소스 근거는 [H-ONLY-A-H-ROLE-DEFINITION.md](H-ONLY-A-H-ROLE-DEFINITION.md)를 따른다.

이 실험은 다음을 분리해 답한다.

1. H가 HTTP 라우팅 결과를 바꾸지 않는가?
2. heap 상한에 걸리지 않은 상태에서 H 로직 자체가 지연·CPU를 어떻게 바꾸는가?
3. 지속 요청에서 H가 allocation, GC, heap committed/used, RSS를 얼마나 바꾸는가?
4. 발생한 지연 차이가 GC pause 때문인지, GC와 무관한 코드 경로 차이인지 구분할 수 있는가?

주 실험은 **macOS host의 native Java**로 실행한다. Docker Desktop VM,
cgroup, bridge network 변수를 제거해 라우팅 로직 차이를 우선 측정하기 위함이다.
Docker로 바꾸지 말고, 불가피하면 기동 전에 이유·이미지 digest·memory/swap
한도·예상 시간을 사용자에게 보고한다.

## 2. 이 실험이 입증하지 않는 것

- 모든 graph, CPU, OS, JVM, 부하 조건에서 H가 항상 낫다는 보편적 주장
- 메모리 제한에 걸린 운영 서버의 OOM/Full GC 회피 효과
- Docker/Linux/VPS와 동일한 RSS 절대값
- HTTP 외부 응답 시간을 OTP 내부 `Worker` 시간과 동일하다는 주장
- 공식 upstream CI가 이 HTTP benchmark를 수행했다는 주장

## 3. 고정 자산과 사전 사실

| 항목 | 고정값 |
| --- | --- |
| A upstream baseline JAR (content-verified; legacy basename) | `/private/tmp/otp-baden-build-LgbuLS/otp-h-only.jar` |
| A upstream baseline JAR SHA-256 | `2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6` |
| H optimized JAR (content-verified; legacy basename) | `/private/tmp/otp-baden-build-LgbuLS/otp-baseline.jar` |
| H optimized JAR SHA-256 | `3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d` |
| 공유 graph | `/private/tmp/otp-baden-memory-FRsabG/graph/graph.obj` |
| graph SHA-256 | `64ee49f2d24243ab21f88225d47f44aeab17017bb6a9b28a2d8d6e476b412bfc` |
| 공식 corpus | `/private/tmp/otp-baden-memory-FRsabG/official/A-rerun/travelSearch.csv` |
| corpus SHA-256 | `42c6f861a704b1c6a246b2a55506267be1d4c4c49cb6f11ec8e2a962d0bef65c` |
| SpeedTest config | `testDate=2022-07-25`, `feedId=1`, `graph=graph.obj` |
| Java | `/opt/homebrew/opt/openjdk@26/bin/java`, 26.0.2.1 |
| host | Apple M3 Pro, 18 GiB RAM |

주의: 원래 임시 build 디렉터리 이름은 소스 내용과 달리 해석되었고, JAR basename도
역할을 보장하지 않는다. 위 표의 **SHA와 바이트코드 내용**만 실험 명령·분석·문서에
사용한다. `otp-h-only.jar`의 내용이 A 원본이고 `otp-baseline.jar`의 내용이 H
최적화라는 점을 반드시 유지한다.
경로 basename만으로 A/H를 재지정하지 않는다.

corpus는 256건이다. ID는 `1..256` 연속값이 아니라 공식 원본 ID `1..416`
중 선별된 값이므로 ID 연속성을 검증하면 안 된다.

- transit: 240건, `TRANSIT|WALK`
- car: 16건, `CAR`
- departure: 모두 `08:00`
- explicit window: `6h` 8건, `8h` 4건, 빈 값 244건

H는 transit 자료 경로를 바꾸므로 240건 transit을 주 분석으로 하고,
16건 CAR는 H가 직접 개입하지 않는 음성 대조군으로 별도 보고한다.
전체 256건 결과도 누락 없이 공개한다.

`/private/tmp` 경로는 영속 저장소가 아니다. P0에서 존재·hash를 재확인하고,
하나라도 다르거나 없으면 실험을 시작하지 말고 보고한다.

## 4. 전체 실행 규칙

1. A와 H는 위 graph 하나만 읽는다. graph를 재빌드하지 않는다.
2. A/H를 동시에 띄우지 않고 같은 host에서 순차 실행한다.
3. 모든 run은 fresh JVM으로 시작하고 완전 종료 후 다음 run을 시작한다.
4. JVM은 모두 `-Xms512m -Xmx10g -XX:+UseG1GC`를 쓴다.
5. 예열·측정 중 `System.gc()`, `jcmd GC.run`, heap dump를 금지한다.
6. host의 기존 swap *used*가 0이어야 하는 것은 아니다. 각 run 구간의
   swap-in/swap-out counter delta가 0이어야 한다.
7. 로드 제너레이터는 open-loop 고정 도착률을 사용한다. 응답이 느리다고
   다음 요청을 늦추는 closed-loop를 주 결과에 쓰지 않는다.
8. A/H에 동일한 seed, case 순서, 예정 도착 시각, 요청 수를 적용한다.
9. 성능 수치보다 정합성·압박 없음·부하 생성 정상 gate를 먼저 판정한다.
10. 실험 중 swap I/O가 한 번이라도 발생하면 즉시 run ledger와 로그에
    시각·counter delta를 기록하고 해당 run을 `ENVIRONMENT_INVALID`로 분류한다.

## P0. 실행 정체성·host 여유 검증

### 수행

1. 이 문서, 상위 H-only 계획, 해당 저장소의 `AGENTS.md`를 읽는다.
2. A/H JAR·graph·corpus hash를 위 표와 대조한다.
3. `java -version`, macOS version, CPU, 물리 RAM, 현재 `memory_pressure`,
   `vm_stat`, `sysctl vm.swapusage`, 주요 background 프로세스를 기록한다.
4. JAR의 OTP commit/serialization version을 `--version`/`--serializationVersionId`로
   확인한다. A/H의 기반 commit·dependency·serialization version은 같아야 한다.
5. 두 JAR를 `javap`/클래스 hash로 검사해 A 원본은
   `arrivalTimes/departureTimes`, H 최적화는 `TripTimes[]/offsetsByTrip`인지 확인한다.
   두 JAR의 내용 차이는 해당 `TripPatternForDates.class`와 build metadata 외에는 0이어야 한다.
6. graph의 mtime/hash를 실험 시작 전과 전체 종료 후 비교한다.

### 통과 기준

- hash 모두 일치하고 verified role이 표와 일치
- host 물리 RAM 18 GiB 확인
- 백그라운 메모리 압박이 크지 않고, OTP `-Xmx10g`와 host 운영 여유 확보
- 절전/thermal throttling 징후 없음

통과하지 못하면 background app을 임의 종료하지 말고 사용자에게 보고한다.
`-Xmx12g`로 올려 통과시키지 말고 32 GiB 이상 host를 후속 대안으로 제안한다.

## P1. Baden 공식 search → HTTP 변환기 작성·검증

기존 `http-experiment/otp_http_corpus.py`는 Helsinki schema와 연속 ID를 가정하므로
그대로 쓰지 않는다. 그 코드를 참고하되 Baden 전용 변환기와
테스트를 `handoffs/http-experiment/` 아래에 추가한다. OTP production diff에는
포함하지 않는다.

### 필수 매핑

| CSV | HTTP GraphQL effective request |
| --- | --- |
| `fromLat/fromLon` | `from` 좌표 |
| `toLat/toLon` | `to` 좌표 |
| `testDate` | `2022-07-25` |
| `departure` | `time=08:00`, `arriveBy=false` |
| `TRANSIT|WALK` | `transportModes=[TRANSIT,WALK]` |
| `CAR` | `transportModes=[CAR]`; transit mode를 섞지 않음 |
| empty `window` | `searchWindow=null`; OTP dynamic window |
| `6h` | legacy `plan.searchWindow=21600` seconds |
| `8h` | legacy `plan.searchWindow=28800` seconds |
| SpeedTest `-i 3` | `numItineraries=3` |

응답 canonical form에는 itinerary 순서와 각 itinerary의 start/end, duration,
transfers, generalizedCost, leg mode, scheduled time, from/to stop, route ID를 포함한다.
HTTP/debug timing, request ID만 비교에서 제외한다. 중복 itinerary는 제거하지 않는다.

### SpeedTest와 HTTP 의미 동등성 gate

SpeedTest `SpeedTestRequest.toRouteRequest()`와 GTFS GraphQL resolver를 소스에서 대조해
다음을 표로 남긴다.

- profile `md`/RAPTOR profile·optimization·direction
- default route request/preferences
- dynamic/explicit search window
- mode/filter 해석
- itinerary filter와 반환 개수

HTTP API로 표현할 수 없는 내부 SpeedTest 옵션이 하나라도 남으면,
이 실험을 **“공식 Baden search corpus를 사용한 HTTP workload”**로 표현한다.
“공식 SpeedTest와 완전히 동일한 요청”이라고 쓰지 않는다.

### 변환기 필수 기록

각 request JSONL에 다음을 기록한다.

- `seq`, 원본 `testCaseId`, category, modes, window, cycle, seed
- `scheduledAtEpochNs`, `sentAtEpochNs`, `completedAtEpochNs`
- monotonic scheduled/sent/completed ns
- scheduling lag, service latency, schedule-to-completion latency
- status, GraphQL errors, routing errors, timeout, response fingerprint
- in-flight at send/complete, response bytes

정확한 wall/monotonic origin을 metadata에 저장해 GC absolute timestamp와 request 구간을
연결할 수 있게 한다. 스케줄은 seed `20260916`으로 corpus 순서를
cycle별 shuffle해 미리 JSONL로 생성하고, A/H에 같은 파일을 제공한다.
스케줄·GraphQL query·payload set의 SHA-256을 manifest에 남긴다.

### 통과 기준

- 256건 변환, transit 240/CAR 16, window 244/8/4가 정확히 유지됨
- 원본 ID 누락·중복 0
- 같은 input/seed의 schedule hash가 항상 같음
- fixture test가 mode/window/ID/time 변환 오류를 검출함

## P2. native OTP 기동·수집기 smoke

run root를 `/private/tmp/otp-baden-http-native-<timestamp>`로 만들고 run별 디렉터리에
manifest, server log, GC log, request JSONL, process/heap/host CSV를 분리한다.

기동 template:

```bash
/opt/homebrew/opt/openjdk@26/bin/java \
  -Xms512m -Xmx10g -XX:+UseG1GC \
  -XX:NativeMemoryTracking=summary \
  -Xlog:gc*,safepoint:file=<RUN>/gc.log:time,uptime,level,tags \
  -jar <A_OR_H_JAR> --load --port 18080 \
  /private/tmp/otp-baden-memory-FRsabG/graph
```

endpoint는 `http://127.0.0.1:18080/otp/gtfs/v1`로 고정한다. 기동 후 ready
로그와 간단한 GraphQL query 성공을 모두 확인한다. 기동 타임아웃은 10분이다.

수집:

- Java PID RSS/VSZ/CPU: 250 ms
- heap used/committed/generation: `jstat` 1 s
- GC/safepoint: unified log
- NMT summary: 예열 직전과 측정 직후; 측정 중 `jcmd`는 호출하지 않음
- host `vm_stat`, `memory_pressure`, swap counters: run 전/예열 후/측정 후
- load generator PID CPU/RSS도 별도 수집

sampler coverage 99% 이상, 최대 간격 1초 이하여야 한다. PID가 바뀌거나
단위가 불명확하면 run을 무효로 한다.

## P3. 정합성 gate

A1 → H1 → H2 → A2 fresh JVM 순서로 256건을 concurrency 1에서 각 1회
실행한다. 이 구간은 성능 표본으로 사용하지 않는다.

필수 검증:

- HTTP 2xx, transport error 0, GraphQL error 0, timeout 0
- A 자체 반복 fingerprint mismatch 0
- H 자체 반복 fingerprint mismatch 0
- case별 A/H canonical fingerprint mismatch 0
- 무경로/itinerary 개수·routing error 차이 0

이전 SpeedTest의 공식 expected 189/256 mismatch는 A/H 모두에 동일하게 발생한
버전/provenance 문제이다. HTTP A/H 정합성 gate와 합치지 말고 별도 기록한다.
H 고유 차이가 한 건이라도 있으면 부하 실험을 시작하지 않는다.

## P4. 비포화 도착률 pilot

### 예열

각 fresh JVM에서 전체 256건을 사용해 최소 3 cycle과 10분을 모두 충족할
때까지 예열한다. 연속 2개 2분 창의 latency p50 변화가 5% 이하이고,
backlog·heap 사용이 지속 상승하지 않아야 한다. 예열 상한은 20분이다.

### open-loop ramp

A에서 1, 2, 4, 8 requests/s를 각 2분 실행한다. 8 rps에서도 server CPU가
35% 미만이고 모든 gate를 통과하면 16 rps 등으로 2배씩 늘린다.
다음 조건 중 하나에 도달하면 ramp를 즉시 중단한다.

- server CPU 60% 초과
- scheduling lag p99가 arrival interval 초과 또는 backlog 지속 증가
- timeout/error 발생
- heap/host 압박 gate 근접 또는 실패

통과한 가장 높은 rate에서 H pilot을 10분 수행한다. H가 실패하면
둘 다 통과하는 직전 낮은 rate를 formal rate로 선택한다.

### no-pressure 필수 gate

| 계층 | 필수 기준 |
| --- | --- |
| Heap | measured max used < 80% of 10 GiB; GC 후 used p95 < 60%; 지속 상승 없음 |
| GC | 자연 Full GC 0; allocation/evacuation failure, to-space exhausted 0 |
| Host swap | 측정 구간 swap-in/out delta 0 |
| Host memory | memory pressure warning/critical 0; pageout 지속 증가 0 |
| CPU | server 평균 60% 이하, thermal throttling 없음 |
| Generator | timeout/drop 0, scheduling lag p99 < min(100 ms, arrival interval) |
| Queue | in-flight/backlog의 지속 상승 없음 |

GC는 발생해도 된다. **heap 상한 때문에 발생하는 압박성 GC/Full GC**를
배제하는 것이지, H가 줄이려는 자연 Young GC 차이까지 없애는 것은 아니다.

P4 종료 후 formal rate, 예열 시간, 측정 시간, 예상 전체 소요 시간,
pilot A/H 지연·heap·GC·RSS·swap을 사용자에게 보고하고 P5 진행 전 대기한다.

## P5. 교차 screening 실험

사용자가 P4 보고 후 진행을 지시하면 A1 → H1 → H2 → A2 순서로 실행한다.

- 매 run: fresh JVM
- 예열: P4에서 고정한 조건
- 측정: 15분, 또는 10,000 요청 중 더 긴 조건
- 추가 GC 호출 없음
- 같은 formal schedule file/hash 재사용
- run 사이 최소 2분 host settle; pressure·swap counter 재확인

한 run이 환경 gate를 실패하면 그 run만 지우지 말고 pair 전체를 무효로
표시한다. 환경을 안정화한 뒤 pair 전체를 한 번만 재실행한다.
H 고유 error/지연/메모리 회귀를 환경 실패로 재분류하지 않는다.

P5 결과를 먼저 보고한다. screening이므로 n=2 run/variant로 PR 결론을
확정하지 않는다.

## P6. PR 증거용 formal 반복

P5의 정합성·no-pressure·generator gate가 모두 통과하면 6 pair/12 fresh JVM을
수행한다. pair 순서는 `AB, BA, BA, AB, AB, BA`로 고정해 순서 효과를 균형화한다.

- 예열: P4에서 고정한 조건
- 측정: pair당 각 variant 30분, 또는 20,000 요청 중 더 긴 조건
- A/H의 같은 pair는 동일 schedule/hash를 쓸 것
- 전체 소요 시간을 P6 시작 전 다시 보고할 것

장시간 실행을 무리하게 밤새 진행하지 말고, host 사용 가능 시간과
절전 방지를 확인한다. 실험 도중 새 background 부하가 생기면 ledger에 적고
해당 pair를 환경 무효로 판정한다.

## P7. GC-overlap·메모리 세부 분석

GC log의 absolute start/end와 request `sentAtEpochNs/completedAtEpochNs`를 교차해 각 요청을
다음으로 표시한다.

- `GC_FREE`: service 구간과 stop-the-world pause가 겹치지 않음
- `GC_OVERLAP`: service 구간과 pause가 1 ns이라도 겹침

자연 Full GC, Young/Mixed pause, concurrent cycle, safepoint를 구분한다. `G1Old` 같은
이벤트 이름만으로 Full GC 횟수를 세지 않는다. cause와 pause line을 본다.

보고할 메모리:

- heap used/committed p50, p95, max
- GC 직후 used 분포; 이를 정확한 live set으로 과장하지 않음
- Java PID RSS p50, p95, sampled max
- NMT Java heap committed, class, thread, code, GC, other
- Young/Mixed/Full GC 횟수, pause sum/max/p95, GC time / measured time
- CPU seconds/completed request

명시적 GC 후 live/NMT 진단이 필요하면 **측정 종료 timestamp 후** 별도로
`jcmd <pid> GC.run`을 1회 수행할 수 있다. 그 값은 latency/GC 성능 표본에
포함하지 않고 `POST_MEASUREMENT_DIAGNOSTIC`으로 표시한다.

## P8. 지표·통계·판정

### 주 지표

1. transit 240-case `GC_FREE` HTTP service latency p50/p95
2. transit 전체 HTTP service latency p50/p95/p99
3. 예정 도착 → 완료 p95/p99
4. CPU seconds / completed transit request
5. correctness mismatch/error/timeout rate

### 보조 지표

- RSS/heap used/committed p50/p95/max
- allocation/GC 횟수·pause·time
- CAR 16-case latency: 음성 대조군, 주 결론에 섞지 않음
- case/category/window별 latency·결과
- GC_OVERLAP vs GC_FREE latency 차이

HTTP API가 OTP 내부 Worker/TripPatternFiltering timer를 안정적으로 노출하지 않으면
그 수치를 추정하지 않는다. 필요하면 동일 corpus의 SpeedTest timer를 별도 참고로
병기하되 HTTP latency와 합쳐서 산출하지 않는다.

### 통계

- 독립 단위는 개별 request가 아니라 6개 pair이다.
- pair별 `log(H/A)`를 집계하고 paired bootstrap 10,000회,
  seed `20260916`, percentile 95% CI를 계산한다.
- ratio, 95% CI, 절대 ms/MiB, `100 × (1 - H/A)`를 함께 보고한다.
- p99는 request 표본 크기와 스케줄 의존성을 명시하고 단일 run p99로
  일반화하지 않는다.

### 사전 판정 기준

- correctness: mismatch/error/timeout 0이어야 통과
- no-pressure: P4 gate 전부 통과해야 유효
- latency 비열등 탐색 기준: H/A p95 CI 상한 ≤ 1.05
- 메모리 감소 근거: RSS 또는 heap p95의 H/A CI 상한 < 1
- GC 감소만 있고 latency/RSS 차이가 없으면 `ALLOCATION_GC_ONLY`
- latency 회귀가 5%를 초과하고 메모리 이득이 작으면 `TRADE_OFF_OR_REGRESSION`
- CI가 넓거나 pair 방향이 불일치하면 `INCONCLUSIVE`
- swap/heap/CPU/generator gate 실패면 `ENVIRONMENT_UNQUALIFIED`

5%는 이 실험의 사전 탐색 기준이지 OTP upstream의 공식 합격선이 아니다.

## P9. 산출물·인수인계

다음 파일을 `H-ONLY-BADEN-HTTP-NO-PRESSURE-*`로 작성한다.

- `RESULT.md`: 결론 중심 A/H 비교, CI, correctness, GC overlap, 한계
- `MANIFEST.json`: 모든 SHA, 실행 명령/cwd, JDK/JVM, host, query/schedule hash
- `RUN-LEDGER.md`: run 순서, 시간, gate, retry/무효 사유, swap 발생 시각
- 변환기·sampler·GC-overlap parser·bootstrap script와 fixture test
- PR에 넣을 영문 요약 표와 재현 명령

raw에는 server stdout/stderr, request/response JSONL, GC/safepoint, process/heap/host
시계열, NMT, analyzer output을 보존한다. `/private/tmp`만에 있는 상태로 완료라고
하지 않는다. 요약·script·hash는 문서 저장소에 보존하고, 대형 raw의
영속 저장 위치를 manifest에 정확히 기록한다.

PR 표현:

> A separate open-loop HTTP benchmark derived from OTP's official Baden-Württemberg
> SpeedTest search corpus, using the same graph and request schedule for baseline and H-only.

공식 SpeedTest의 expected mismatch 189건은 숨기지 않되 A/H 간 동등성과 분리한다.
Baden 한 graph의 결과를 모든 OTP graph에 일반화하지 않는다.

## 중단·보고 지점

다음에서는 임의로 넘어가지 말고 결과를 보고한다.

1. P0 hash/runtime/host 불일치 또는 JAR 역할 반전
2. P1 SpeedTest↔HTTP 미지원 필드 발견
3. P3 A/H correctness mismatch
4. P4 swap I/O, Full GC, heap 80%, CPU/backlog/generator gate 실패
5. P4 종료: formal rate·예상 시간 보고 후 P5 전 대기
6. P5 screening 결과 보고 후 P6 전 대기
7. Docker 전환, 기존 app 종료, 32 GiB host 이동 필요

## 완료 체크리스트

- [x] P0 자산/hash/runtime/host gate (JAR 역할 반전 확인 후 정정)
- [x] P1 Baden 변환기·fixture·SpeedTest 의미 대조
- [x] P2 native server·sampler smoke
- [x] P3 4-run correctness gate
- [x] P4 no-pressure rate pilot·실행 계약 고정 (1 rps run recorded host Swapins +31)
- [x] P5 ABBA screening (8 rps, 10,000 requests/run; swap/pageout contamination recorded)
- [ ] P6 6-pair formal (사용자 중단: pair06-H 17,602/20,000에서 중단; pair06-A·pair01 교정 미실행)
- [x] P7 GC-overlap·memory 세부 분석 (수집된 10개 완전 run + pair06-H partial; host 압력으로 진단 전용)
- [x] P8 paired CI·사전 판정 (완료된 4개 pair bootstrap만 산출; no-pressure 통과 pair 0)
- [x] P9 영속 raw·PR 증거·NEXT-AGENT 갱신 (partial-stop archive 및 상태 명시; PR 성능 증거로 승격하지 않음)

### 현재 중단 상태 (2026-09-17)

사용자 지시에 따라 추가 Java/HTTP 실행을 중단했다. P6 formal six-pair gate는
미완료이며, 현재 생성된 P7/P8 산출물은 완료된 4개 pair와 `pair06-H` partial을
대상으로 한 진단 결과다. `pair06-A`와 pair01 교정 pair를 재개하려면 사용자의
별도 지시가 필요하다. 현재 상태에서 no-pressure 성능 결론이나 upstream PR
성능 주장을 만들지 않는다.

## 다음 에이전트에게 전달할 실행 프롬프트

> `H-ONLY-BADEN-HTTP-NO-PRESSURE-AGENT-PLAN.md`를 전체 읽고 P0부터
> 순서대로 실행하라. 공식 Baden `travelSearch.csv` 256건을 HTTP GraphQL
> 요청으로 변환하되 transit 240건과 CAR 16건, explicit search window를
> 누락하지 마라. A/H에 같은 graph·스케줄·JVM을 사용하고
> native Java `-Xms512m -Xmx10g`, G1에서 명시적 GC 없이 수행하라.
> swap I/O가 발생하면 즉시 시각과 delta를 ledger에 쓰고 run을 무효로
> 표시하라. P4 pilot이 끝나면 formal rate·시간·heap·GC·RSS·swap을
> 결론 중심으로 보고하고 P5 전에 대기하라. P5 screening 후에도
> P6 장시간 formal 실행 전 보고하라. Docker로 전환하거나 기존
> app을 종료하지 마라. 개선을 전제하지 말고 correctness, GC-free
> latency, CPU/request, heap/RSS, GC-overlap과 CI를 그대로 보고하라.
