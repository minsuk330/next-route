# H-only PR 성과 검증 — 동일 graph A/H 에이전트 실행 계획

작성: 2026-09-15. 상태: **S0–S5 실행 완료 / S6 증거 문서화 완료**.

## 1. 목표와 우선순위

공식 OTP Helsinki SpeedTest를 사용하여 H 변경의 결과 동일성, 메모리 사용량,
라우팅 성능을 동일 graph에서 비교한다. 최종 산출물은 유지보수자가 재현할 수 있는
실행 명령·원자료·통계·PR 본문이다. 개선 여부는 측정 후 결정한다.

이 계획은 `H-ONLY-CONTRIBUTION-PLAN.md`의 Phase 3 및 성과 검증 종료 기준을 대체한다.
특히 **A/H별 graph 재생성은 비교 방법으로 허용하지 않는다.** 이전 Phase 0–2의
소스 격리·테스트와 Phase 5의 초안 작성은 활용하되, 성과 검증 완료로 취급하지 않는다.
새 서울 corpus 검증, 공식 CI 실행, Helsinki 반복 성능·메모리 검증은 아직 완료되지 않았다.

계획에 따라 호스트에서 benchmark를 실행했으며, GitHub에는 게시하지 않았다. 결과는
동일 graph 정확성 PASS, JFR 할당 감소 지지, RSS 절감 미확정, 전체 routing latency 개선
없음으로 판정한다. 원자료 경로와 해시는 S6 산출물에 기록했다.

검증할 주장은 세 가지다.

1. 같은 입력에서 H가 A와 같은 routing 결과와 cost를 반환한다.
2. A/H가 각각 얼마나 많은 RSS·heap·할당량을 사용하는지, 차이가 얼마나 되는지 측정한다.
3. 메모리 절감에 따른 속도 개선 또는 접근 비용에 따른 속도 악화를 함께 보고한다.

## 2. 현재 증거와 정정 사항

- 기준 SHA: 공식 upstream `a0b62f69fc543e84687752e17dae8a66b01c5e28`.
- A worktree: `/private/tmp/otp-a-only-run-7mQx2K`, `codex/otp-a-baseline`.
- H worktree: `/private/tmp/otp-h-only-run-v83UVX`, `codex/otp-h-trip-times`.
- H patch SHA-256: `ca1caaf61758100a821211c716e754a597936d182c7f63c6e6630592f29fa9a1`.
- H diff: `TripPatternForDates.java`, `TripPatternForDatesTest.java` 두 파일.
- 기존 테스트 기록: reactor 총 8,255개, failures/errors 0, skipped 20.
  8,255개가 전부 실행·통과했다는 표현 대신 총수와 skip을 나누어 쓴다.
- 이전 Helsinki는 A/H 각각 다른 graph에서 1 JVM씩 실행했다. 반복 pair, RSS 수집,
  JFR allocation 비교가 없으므로 이번 정식 표본에 포함하지 않는다. 아래 S1–S5는
  A가 만든 동일 serialized graph를 양쪽이 공유한 후 새로 실행한 결과다.
- 이전 A `233/408`, H `227/408`은 공식 expected와 일치한 testcase 수다.
  나머지가 모두 경로 검색 실패였다는 뜻이 아니다. 실제 예외·무경로·expected mismatch를
  구분하여 집계한다. 두 graph의 15-edge 차이가 결과 차이의 원인이라는 주장도 아직 가설이다.
- 이전 원자료를 보존하고 새 run 결과로 덮어쓰지 않는다.

## 3. 실행 계약

- 기본 실행은 호스트 JVM, **`-Xmx8g`**, A/H 순차 실행이다. 동시에 두 JVM을 띄우지 않는다.
- JDK, GC, heap flags, Compact Object Headers, CPU 조건, query/config는 A/H 동일하게 고정한다.
- 체크아웃의 공식 CI는 Temurin 25, Maven 3.9.12를 사용한다. 가능하면 JDK 25로 A/H를
  함께 재빌드·측정한다. 현재 확인된 로컬 빌드는 Java 26.0.2.1/Maven 3.9.16이므로
  26을 유지하면 그 차이를 명시한다. 한 matrix 안에서 JDK를 바꾸지 않는다.
- `JAVA_HOME`·실제 java 경로·Maven JVM·실제 적용된 JVM flags를 manifest에 기록한다.
  `-Xmx8g`는 상한이지 실제 사용 메모리가 아니다. 큰 Xms를 새로 고정하지 않는다.
- 기존 서비스는 유지한다. Docker가 필요해지면 실행 전 컨테이너·명령·메모리 한도·
  서비스 영향·중단 조건을 사용자에게 보고한다.
- 기준 SHA를 실험 중 갱신하지 않는다. PR용 rebase/코드 변경 시 영향을 확인하고
  변경 후 artifact로 필요한 검증을 다시 수행한다.
- 공식 InfluxDB에 업로드하지 않도록 자식 프로세스에서
  `PERFORMANCE_INFLUX_DB_PASSWORD`를 제거한다. 비밀번호 값은 출력하지 않는다.

## S0. 소스·artifact·측정 환경 확정

1. 저장소 지침과 양쪽 HEAD/diff/status를 확인한다. 원래 H+F 브랜치를 보존한다.
2. 두 변형의 운영 클래스 차이가 H뿐인지 검증한다. H 테스트도 A에서 실행하여
   baseline 동작을 설명하는 테스트인지 확인하되 A 운영 코드는 변경하지 않는다.
3. 사용할 JDK로 양쪽 build/test-compile을 준비한다. 같은 reactor dependency가 섞이지
   않도록 변형별 Maven 저장소 또는 완전히 고정한 runtime classpath를 사용한다.
4. 우선 공식 README의 Maven `exec:java` 진입점을 사용한다. 누락 plugin을 정상적으로
   resolve하고 sandbox/network 오류가 있으면 제공된 승인 절차로 재시도한다.
   실제 해결 불가 시, Maven으로 산출한 정확한 dependency 목록과 같은 test main을
   쓰는 direct Java 실행을 문서화할 수 있다. 임의의 `.m2` 전체 JAR wildcard는 금지한다.
5. artifact마다 source SHA, patch hash, shaded JAR hash, test-class/tree hash,
   dependency/classpath hash를 기록한다. linked-worktree git metadata가 잘못 나오면
   원인을 해결하거나 검증한 값을 명시적으로 주입하고 그 명령을 보존한다.
   내부 metadata만으로 코드 정체성을 보증하지 않는다.
6. 짧은 별도 진단에서 class loading 경로를 확인한다. 특히 `TripPatternForDates`와
   `SpeedTest`가 해당 변형에서 로드되는지 확인한다. class-loading 로그는 본 측정에서 끈다.
7. RAM, OS, CPU, JDK/GC, 전원/절전, 디스크, 현재 서비스, swap 카운터를 기록한다.

완료 조건: A/H 실행 파일과 classpath가 구분되며, H 이외 운영 코드 차이가 없다.

## S1. graph 하나 고정 및 양쪽 로드 검증

재사용 우선 후보는 A가 생성한 다음 파일이다. 존재와 SHA를 실제로 재검증한다.

- 파일: `/private/tmp/otp-a-only-helsinki-4wN2sL/graph.obj`
- SHA-256: `178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0`

1. 공식 Helsinki OSM/GTFS, `build-config`, `router-config`, `speed-test-config`,
   `travelSearch.csv`, 공식 expected CSV의 경로·SHA를 고정한다.
   testDate는 `2026-02-19`, 해당 build service 기간 안이어야 한다.
2. 각 run에는 별도 출력 디렉터리를 만들고 같은 graph에 read-only symlink 또는
   검증된 동일 바이트 사본으로 연결한다. 동일 파일 이름만으로 같다고 판정하지 않는다.
3. A와 H가 같은 graph를 읽고 각각 전체 corpus를 실행할 수 있는지 S2에서 확인한다.
   serialization ID뿐 아니라 실제 로드 성공·경로·graph hash를 확인한다.
4. 기존 graph가 선택 JDK/artifact와 호환되지 않으면 **A로 한 번만** 새 graph를 만들고
   양쪽이 그 파일을 공유한다. H로 별도 graph를 만드는 우회는 하지 않는다.
5. run 시작 전과 전체 matrix 종료 후 hash를 검증한다. config/data 변경도 감지한다.

완료 조건: 모든 run이 한 graph SHA를 사용하고 양쪽에서 정상 로드된다.
공유가 불가능하면 원인을 보고하고 비교 측정을 중단한다.

## S2. 결과 동일성 gate

성능 측정 전에 독립 JVM에서 A1, H1, H2, A2를 실행한다. 각 run 전체 408 testcase,
`md`, itinerary 3개, 공식 expected 보존. 정확성용 실행은 `-n 1`을 사용해
각 전체 corpus 실행의 CSV를 확보하고, 반복 성능 표본에는 넣지 않는다.

비교를 세 층으로 나눈다.

1. **실행 정상성:** 전체 testcase 실행 수, 실제 예외, 무경로, itinerary 수를 집계한다.
   프로세스 exit 0만으로 PASS로 처리하지 않는다.
2. **공식 expected 비교:** 공식 옵션 `-0` 조건의 일치·불일치 testcase와 원인을 기록한다.
   공식 expected는 `-R`로 갱신하지 않는다. A도 틀리는 부분은 A 재현 여부를 증거로 남긴다.
3. **A/H 엄격 비교:** CSV parser로 `tcId`별 itinerary를 비교한다. arrival/departure,
   duration, transfers, agencies/modes/routes/stops, details 및 **c1/c2 cost도 포함**한다.
   기본은 원래 순서까지 비교하고, 순서만 다른 경우 중복 개수를 보존한 multiset 비교를
   추가한다. cost나 1초 차이를 임의 허용하지 않는다. 정렬 동률에 따른 순서 차이는
   API 의미를 검토하여 별도 판정하며 자동 무시하지 않는다.

A1/A2, H1/H2 자체 반복 동일성도 검사한다. 다르면 baseline 비결정성인지 조사한다.
A/H 차이가 남으면 case를 축소해 graph/설정/classpath/코드 원인을 확인하고,
**해결 전 정식 성능 matrix로 넘어가지 않는다.**

기존 tool의 CSV는 `-n 4` 중 마지막 sample만 저장한다. 마지막 CSV 하나를
4개 sample 전부의 엄격 동일성 증명이라고 표현하지 않는다. 본 matrix에서는 매 run의
마지막 CSV와 모든 sample의 case 수·상태·예외를 검사한다. sample별 이상이 발견되면
해당 조건을 정확성용 단일 sample run으로 재현한다.

완료 조건: 반복 자체 안정성, A/H 408개 case의 결과·cost 동일성,
H에서 새로 발생한 예외/무경로 없음. 공식 expected 잔여 mismatch는 별도 unresolved 항목이다.
공식 expected가 미통과이면 PR에 'official expected passed'라고 쓰지 않는다.

## S3. 메모리 수집기 검증 및 A/H pilot

사용자가 원하는 '얼마나 먹는가'를 다음처럼 구분한다. A/H 각각 절대값과 차이를 제시한다.

| 지표 | 단위·측정 구간 | 수집 방법과 해석 |
| --- | --- | --- |
| 프로세스 전체 peak RSS | MiB, 시작부터 종료 | macOS `/usr/bin/time -l`의 maximum resident set size. 실행 JVM PID를 추적하고 OS별 단위를 확인한다. Maven exec이면 Maven도 같은 JVM에 포함됨을 표시한다. |
| 라우팅 중 RSS | MiB, sample 측정 구간 | 실제 JVM PID의 RSS를 100ms 주기로 수집. sample peak·p95·median 기록. 순간 peak를 놓칠 수 있어 sampled peak로 명시한다. |
| heap used/committed | MiB, 구간별 | 별도 동일 설정 진단 run의 JFR/GC 로그. committed와 used를 구분한다. |
| GC 후 heap | MiB, 매 sample 직전 및 최종 GC 후 | 공식 runner의 명시적 GC와 대응되는 after-GC 이벤트에서 추출. 이후 warmup이 있으므로 '측정 직전 살아 있는 객체만'이라고 단정하지 않는다. |
| 할당 압력 | sampled MiB/request | JFR `ObjectAllocationSample.weight`를 같은 측정 구간에서 집계하고 실제 요청 수로 나눈다. 전체 추정치와 `TripPatternForDates` stack·배열 class를 별도 제시한다. |
| GC 부담 | count, pause ms, pause 비율 | 같은 구간의 GC 로그/JFR. 공식 강제 GC와 자연 GC를 분리한다. |

공식 소스의 `SpeedTestTimer.globalCount()`는 upload registry가 있을 때만 메모리 값을
기록한다. 따라서 로컬 로그에 메모리 숫자가 없으면 0이 아니라 **미수집**이다.
JFR에서 얻은 allocation은 추정치이며 정확한 allocated bytes, retained heap, RSS와 다르다.
H의 주된 효과가 임시 할당 감소라면 GC 후 heap이 거의 같을 수도 있다.

수집 계약:

- 운영 코드와 공식 routing/예열/GC 알고리즘은 그대로 둔다. runner를 감싸는 수집기·
  parser는 별도 실험 도구로 관리한다.
- JVM PID를 명시적으로 찾고 시작/종료를 추적한다. `time`이 감싼 shell/caffeinate만의
  RSS를 JVM RSS로 잘못 기록하지 않는다. time 결과와 PID sampler를 교차 확인한다.
- raw 로그를 timestamp와 함께 보존한다. `START`/`SUMMARY` 경계 및 JFR 시계를 연결한다.
  이 경계에는 출력 비용도 일부 포함됨을 기록하고, 경계 불확실하면 전체 run 지표로 낮춘다.
- log의 sample·case 수가 4×408인지 검사한다. stdout/stderr, RSS, exit, swap 수집을
  실제로 검증하는 pilot A/H 한 쌍을 실행한다. parser는 정상/누락/예외 fixture로 확인한다.
- sampler 유효 시간 coverage ≥99%, 최대 간격 ≤1s를 초기 수집 기준으로 고정한다.
  host 부하가 크면 간격을 matrix 시작 전에 다시 정하고 모든 run에 동일 적용한다.
- `vm_stat`의 Swapins/Swapouts를 전후 및 1초 간격으로 수집한다. 페이지 크기와 delta를
  기록하고 측정 중 delta가 증가하면 `ENVIRONMENT_CONTAMINATED`로 표시한다.
- collector가 없거나 종료를 놓치면 `INCOMPLETE`로 처리한다. OOM/절전/서비스 영향이면
  중단하고 원인을 기록한다. 기존 swap 사용량만으로 실패로 판정하지 않는다.

완료 조건: A/H 각각 RSS와 구간 식별이 실제로 수집되고, 별도 JFR pilot에서 필요한
heap/allocation/GC 이벤트가 존재한다. 필수 값이 없으면 본 측정 전에 수집기를 수정한다.

## S4. 성능·RSS 정식 측정

공식 README의 로컬 표준 명령을 사용한다. 아래는 변수를 채울 실행 형식이며 실제로
실행한 완전한 argv·환경·cwd를 별도 저장한다.

```bash
MAVEN_OPTS='-Xmx8g -XX:+UseCompactObjectHeaders' \
mvn --projects application exec:java \
  -Dexec.mainClass=org.opentripplanner.transit.speed_test.SpeedTest \
  -Dexec.classpathScope=test \
  -Dexec.args='--dir=<run-dir> -p md -n 4 -i 3 -0'
```

`exec:java`는 Maven JVM 안에서 실행되므로 heap/JVM 설정은 `MAVEN_OPTS`에 적용한다.
실제 GC 및 flags를 확인해 고정한다. 수집 wrapper는 이 JVM에 연결한다.
direct Java가 필요하면 S0에서 검증한 동일 classpath·main·args로 A/H 모두 실행한다.

### 표본 계약

- **6개 독립 pair, 총 12개 fresh JVM**. 순서: AB, BA, AB, BA, AB, BA.
  A=baseline, B=H. 순서는 ledger에 측정 전 고정한다.
- 각 JVM `-n 4`, 각 sample 408 case. 합계 19,584 measured query이며 내부 warmup은
  이 수에서 제외한다. `-n 4`는 네 동시 요청을 뜻하지 않는다.
- official warmup·강제 GC를 유지한다. JFR/class-loading/NMT 등의 진단은 본 run에서 끈다.
  저비용 RSS/host sampler는 양쪽 동일하게 켠다.
- log에 출력되는 Worker/RAPTOR mean과 Routing Total mean을 수집한다.
  case별 Total·Transit 배열에서 p50/p95를 계산한다. Transit은 RAPTOR와 같지 않으며,
  공식 출력에 case별 RAPTOR가 없다면 그 값을 만들어내지 않는다.
  여기의 p95는 고정 corpus의 질의 시간 분포이며 운영 동시 부하에서의 API p95가 아니다.
- graph load/빌드/Maven 시작 시간을 query latency에 포함하지 않는다. 전체 process
  wall time은 부가 지표로만 기록한다.
- 전원 연결·절전 방지와 고정된 run 간 휴지 조건을 유지한다. 큰 빌드/다운로드/다른
  benchmark를 병행하지 않는다. CPU 열/호스트 부하 변화를 가능한 범위에서 기록한다.
- 불량 run은 원자료와 제외 이유를 남긴다. 한쪽만 유리하게 교체하지 않고 해당 pair
  전체를 같은 순서로 재실행한다. 환경 문제 재시도는 전체 최대 2 pair까지로 제한한다.
  유효 6 pair를 못 얻으면 부족한 표본 수와 원인을 보고한다.

### 집계와 판정 기준 — 실행 전에 고정

1. 한 JVM의 4개 sample을 요약하여 run 대표값을 만들고 pair별 A/H 비율을 구한다.
   primary latency는 Routing Total mean, secondary는 RAPTOR mean과 case p50/p95다.
2. `개선율 = 100 × (A − H) / A`로 통일한다. 양수는 감소/개선이다.
   run·pair 원자료, 절대값, 비율을 함께 제시한다.
3. 같은 JVM의 sample이나 408개 OD를 독립 JVM 표본으로 취급하지 않는다.
   pair를 단위로 log-ratio 평균 및 paired bootstrap 95% CI를 구한다.
   seed·재표집 횟수(10,000)·분위수 계산법·집계 코드를 보존한다.
4. 성능 회귀 허용 한계는 이번 검증용으로 **+5%**를 사전 설정한다.
   upstream 공식 합격 기준이라고 표현하지 않는다.
   H/A Total mean 비율의 CI 상한 ≤1.05면 해당 범위에서 비열등 근거,
   CI 상한 <1이면 속도 개선 근거다. 경계를 걸치면 미확정이다.
5. 전체 평균과 함께 공식 query category 및 case별 반복 악화를 확인한다.
   6 pair 중 4 pair 이상에서 >5% 느린 case는 조사 대상으로 공개한다.
   다수 case의 검정은 탐색 분석이며 개별 확정적 유의성으로 과장하지 않는다.
6. primary memory는 measured-window sampled peak RSS다. CI가 감소를 지지하는지 보고하고
   process 전체 peak RSS도 병기한다. CI가 걸치면 RSS 개선 미확정이다.
7. 6 pair는 로컬 제안 근거다. 결과가 애매하다는 이유로 유리해질 때까지 반복하지 않는다.
   추가 표본이 필요하면 독립된 후속 실험으로 사전에 설계한다.

## S5. allocation·heap·GC 원인 검증

S4와 같은 graph·JDK·heap·config에서 별도 **2개 pair(AB, BA), 4 fresh JVM**를
동일 JFR 설정으로 실행한다. profiler가 켜진 지연은 S4 평균에 합치지 않는다.

1. JFR 설정 파일/hash, GC log 옵션, 이벤트 종류를 고정한다. 공식 CI의 Helsinki
   50초 delay는 로컬 warmup 경계를 보장하지 않는다. 시작부터 수집하고 실제 sample
   경계로 구간을 선택한다. 구간을 복구할 수 없으면 전체 run 추정치라고 표시한다.
2. graph load·warmup·measured routing·최종 강제 GC를 분리하여 집계한다.
   measured allocation/request의 분모에는 measured 요청만 넣는다.
3. A/H 각각 whole-JVM allocation sample weight와 `TripPatternForDates` 생성자 관련
   weight, `int[]`/reference array, GC count·pause·after-GC heap을 기록한다.
   constructor 전체에는 다른 할당도 있으므로 제거된 두 배열만의 수치와 구분한다.
4. 두 pair에서 같은 감소 방향인지 확인한다. sample이 없으면 zero-byte라고 쓰지 않는다.
   부족하면 allocation 결과를 `INSUFFICIENT_SAMPLES`로 보고한다.
5. 구조적 payload 모델 `8 × stops × trips` 대 `trips × (referenceBytes + 4)`는
   실제 reference 폭을 확인한 뒤 보조 설명으로 쓴다. 실제 RSS 개선율로 대체하지 않는다.

완료 조건: A/H RSS 절대값 및 비교, heap/GC 정보, 할당 감소의 진단 근거 또는
구체적 미수집 사유가 있다. 특히 allocation 감소와 process memory 감소를 따로 결론낸다.

## S6. PR 증거 패키지와 최종 결론

문서 산출물:

- `H-ONLY-SHARED-GRAPH-RESULT.md`: 결론, 정확성, A/H 수치·CI, 회귀 case, 제약.
- `H-ONLY-SHARED-GRAPH-MANIFEST.json`: source/JAR/classpath/graph/data/config hash,
  환경, 모든 run argv, collector 설정, artifact 경로/hash, 단계별 상태.
- `H-ONLY-SHARED-GRAPH-RUN-LEDGER.md`: 순서, 시작/종료, 종료코드, coverage,
  swap delta, 유효/제외/재시도 이유. 빈 값은 null/NOT_COLLECTED로 표시한다.
- 재현 wrapper·parser·집계 코드 및 정상/실패 fixture. 운영 PR diff에 실험 도구를 섞지 않는다.
- 기존 `H-ONLY-RESULT.md`, Issue/PR 초안, `NEXT-AGENT.md`에 검증된 결론을 반영한다.

raw 산출물은 run마다 stdout/stderr, `/usr/bin/time` 결과, RSS 시계열, swap/환경,
result CSV, GC log/JFR, parsed metrics, correctness diff를 보존한다. 그래프·JFR 등
대형 파일은 Git에 넣지 않는다. `/private/tmp`만으로 장기 보존을 보장하지 않으므로
PR 준비 시 사용 가능한 영속 artifact 위치에 필요한 raw를 복사하고 해시를 기록한다.
공유는 별도 게시 지시 범위에서 진행한다. 공개할 fixture/log의 비밀값을 확인한다.

PR 표의 각 셀은 원자료에 연결할 수 있어야 한다.

| 지표 | A | H | 변화율 및 불확실성 |
| --- | --- | --- | --- |
| A/H 결과·cost 일치 | 408 cases | mismatch 수 | 공식 expected 비교와 분리 |
| 라우팅 중 sampled peak RSS | MiB | MiB | pair CI |
| 프로세스 전체 peak RSS | MiB | MiB | pair CI |
| Total / RAPTOR mean | ms | ms | 각각 pair CI |
| case Total p50 / p95 | ms | ms | 각각 pair CI |
| GC 후 heap / committed heap | MiB | MiB | 진단 run 범위 |
| 할당 추정량 | sampled MiB/request | sampled MiB/request | JFR 추정·2 pair |
| GC pause | ms, count | ms, count | 강제/자연 GC 분리 |

최종 판정:

- `SUPPORTED`: A/H 결과·cost 동일, 필수 수집 정상, 메모리 지표의 개선 근거가 있으며
  latency 비열등 기준 충족. 개선한 지표와 미개선한 지표를 개별 명시한다.
- `ALLOCATION_REDUCTION_ONLY`: allocation 감소는 지지되나 RSS 감소는 미확정.
  RSS 절감이나 전체 memory win을 주장하지 않는다. latency 판정도 별도 표시한다.
- `TRADE_OFF`: 메모리/할당은 감소했지만 반복적인 속도 악화가 확인된다.
  절감량과 악화량을 함께 제시하고 설계 수용 여부를 유지보수자에게 묻는다.
- `INCONCLUSIVE`: 표본·환경·collector 또는 CI 때문에 방향을 확정할 수 없다.
- `CORRECTNESS_FAILED`: H 특유의 결과/cost 차이·예외가 해결되지 않았다.
  성능 성과 PR 근거로 사용하지 않는다.

성과 문장 예시(실측 전 수치 채우지 않기):

> On OTP's Helsinki SpeedTest at commit <base>, A and H used the same serialized graph
> (<sha>) with <JDK> and -Xmx8g. Across six paired fresh-JVM runs, sampled peak RSS
> changed from <A> to <H> MiB (<change>, 95% CI <...>), and mean total routing time
> changed from <A> to <H> ms (<change>, 95% CI <...>). The repeated correctness runs
> matched all 408 cases including costs. <Official expected mismatch status>.
> Separate JFR runs estimated <allocation result>. These are local results from the
> upstream test suite; <differences from the official CI host/JDK/sample count>.

공식 CI 실행과 로컬 재현을 구분한다. 이 checkout의 workflow는 upstream owner의
`dev-2.x` push에서 실행되며 Helsinki는 extended profile이다. 일반 fork PR을 여는 것만으로
해당 러너가 실행된다고 약속하지 않는다. 유지보수자에게 같은 base/H의 공식 러너 비교와
artifact 제공을 요청하는 문안을 준비한다. 공식 러너 자격이나 GitHub 게시 권한을 가정하지 않는다.

## 실행 결과 체크리스트

- [x] S0 소스·빌드·classpath 정체성 고정.
- [x] S1 A/H 공유 graph 로드·hash 검증.
- [x] S2 408 case 결과·cost 및 자체 반복 안정성 검증.
- [x] S3 RSS/heap/allocation/GC 수집 pilot 검증.
- [x] S4 유효 pair 수·순서·성능/RSS 통계 기록.
- [x] S5 별도 JFR pair 및 해석 완료.
- [x] S6 PR 표·재현 명령·raw 경로·한계·최종 판정 작성.

> H-ONLY-SHARED-GRAPH-AGENT-PLAN.md에 따라 S0부터 진행하라. H-only 브랜치와 기존
> 테스트 기록을 검증해 재사용하고, 하나의 graph SHA를 A/H에 공유하라. 호스트 -Xmx8g,
> 동일 JDK/GC/config로 결과와 cost 동일성을 먼저 확인하라. 수집 pilot 통과 후 6 pair의
> 성능/RSS 및 별도 JFR 2 pair를 실행하고 원자료·CI·회귀를 PR 증거로 정리하라.
> 공식 expected mismatch와 실제 routing 오류를 구분하고, 비교 불능이나 미수집을
> 성공으로 표시하지 말라. Docker 실행 전 사용자에게 보고하라. GitHub 게시는 별도 지시다.

### 확인한 공식 소스 (고정 SHA 기준)

- `test/performance/README.md`: 로컬 실행 명령, 데이터, upstream runner 절차.
- `.github/workflows/performance-test.yml`: JDK 25, Maven 3.9.12, Helsinki extended/n=1,
  JFR delay와 heap 설정. 본 계획 n=4는 README 로컬 명령 기준이다.
- `application/src/test/java/org/opentripplanner/transit/speed_test/SpeedTest.java`:
  warmup/GC 순서, 마지막 sample CSV 저장, exit 처리, after-GC counter.
- 같은 패키지의 `model/testcase/TestCaseResults.java`, `ResultPrinter.java`:
  expected 비교와 성공 수의 의미.
- 같은 패키지의 `model/timer/SpeedTestTimer.java`, `MeterRegistrySetup.java`:
  로컬 counter 수집 제한 및 외부 업로드 조건.
