# OTP 최적화 — 다음 에이전트 실행 인수인계

> **H 후속 설계 방향 (2026-09-16, 구현 미실행):** Helsinki 408-case에서 H의 지연
> 회귀가 관측되어 현재 H를 그대로 범용 성능 개선 PR로 제안하지 않는다. 큰 stop×trip
> 배열 제거는 유지하면서 실제 검색 stop의 시간 row만 lazy materialization하여
> RAPTOR hot loop를 primitive 배열 조회로 되돌리는 H2 방향을
> [H-ONLY-LAZY-STOP-COLUMN-DESIGN.md](H-ONLY-LAZY-STOP-COLUMN-DESIGN.md)에 기록했다.
> accessor 비용·unique stop row를 먼저 계측하고, correctness 및 Helsinki/서울 동일 버전
> ABBA 검증을 통과한 뒤에만 구현을 PR 후보로 승격한다.

> **최우선 후속 계획 (2026-09-16, 신규 실험 미실행):**
> [H-ONLY-UPSTREAM-VALIDATION-AGENT-PLAN.md](H-ONLY-UPSTREAM-VALIDATION-AGENT-PLAN.md)를 따른다.
> 운영 H+F를 보존하고 upstream 후보는 H-only로 검증한다. 로컬 Docker에서 공식 CPU 회귀,
> HTTP Xms 2×2 영향, 서울 제한 조건 및 Helsinki VPS-like pressure 재현을 확인한 뒤 조건부 구현 개선과 VPS 최종 확인으로
> 진행한다. Xms가 RSS 효과를 가렸다는 설명과 accessor 회귀 원인은 아직 가설이다.
> 기존 HTTP P4/서울 HF matrix를 자동 재개하지 않는다. 다음 작업은 새 계획 P0 상태 확인이며,
> Docker 기동 전 자원·조건·시간·중단 기준을 보고한다. 현재는 계획 작성만 완료했다.

> **H+F 공유 graph 재실험 완료 (2026-09-16):** H와 F를 함께 적용한 별도 checkout에서
> 동일 Helsinki graph·공식 408-case·`-Xmx8g` 조건으로 correctness, SpeedTest,
> HTTP no-pressure, JFR 진단을 수행했다. H+F는 SpeedTest 결과 fingerprint와
> 233/408 expected-match를 A와 모두 동일하게 유지했지만, 6개 신규 paired SpeedTest
> 평균에서 Worker +22.1%, Routing Total +9.9%로 느렸고 sampled RSS peak는 -1.3%,
> p95는 -1.3%로 사실상 동일했다. 16 RPS 10분 HTTP pair에서는 H+F p95 239.23ms
> 대 A 180.13ms, RSS p95 5,836.4 대 6,263.6MiB, Young GC 316 대 434였다.
> 동일 graph JFR 2 pair에서는 whole-recording allocation sample weight 평균
> 170.23GB→123.22GB, `int[]` 23.75GB→4.20GB,
> `TripPatternForDates.<init>` 21.79GB→2.23GB로 감소했지만,
> 이를 RSS 절감이나 속도 향상으로 해석하지 않는다. 현재 H+F 결론은
> **정합성 유지 + allocation/GC 압력 감소, 보편적 속도/RSS 개선 미입증**이다.
> 상세 결과와 실행 원장은 [HF-SHARED-GRAPH-RESULT.md](HF-SHARED-GRAPH-RESULT.md)와
> [HF-SHARED-GRAPH-RUN-LEDGER.md](HF-SHARED-GRAPH-RUN-LEDGER.md)를 따른다. Docker OTP
> 서비스는 시작하지 않았고, 모든 유효 run의 swap event는 0이다.

> **최신 후속 계획 (2026-09-16, P0–P2 완료·P3 관측 부분 완료):**
> [H-ONLY-HTTP-NO-PRESSURE-AGENT-PLAN.md](H-ONLY-HTTP-NO-PRESSURE-AGENT-PLAN.md)를 우선한다.
> 목적은 공식 Helsinki 공유 graph·408-case HTTP 부하에서 메모리 압박 없이 H-only의
> RAM 절감과 응답 성능을 검증하는 것이다. 기존 SpeedTest 전체 평균 유사성을 보편적
> 성능 동등성으로 확대하지 않는다. Docker 기동 전 보고, 요청 매핑·정합성·압력 pilot,
> 동일 도착률의 반복 pair, 별도 JFR·soak, 영속 raw와 PR 근거 순서로 진행한다.
> 아래 완료 기록은 이전 실험의 기록이며 새 계획의 gate를 대신하지 않는다.

> **HTTP 실행 인수인계 (2026-09-16):** P0–P2는 macOS host에서 완료했고, P3는 10분
> open-loop 관측까지 부분 완료했다. 공식 Helsinki
> 408-case HTTP A/H gate는 실제 순서 H1→A1→H2→A2로 4회 실행했고 fingerprint
> mismatch 0, HTTP 200/오류 0이었다. P3에서는 4/8/12/16/24/32 RPS 짧은 탐색과
> 16 RPS 10분(각 9,600건) A/H pilot을 수행했다. 모든 run의 swap-in/out delta는
> 0/0, `vm.swapusage used=0.00M`이었다. 24/32 RPS는 포화 경향으로 주 표본에서
> 제외하고, 16 RPS 10분 쌍은 latency p95 H 280.36 ms vs A 311.86 ms, RSS p95
> H 5,574 vs A 5,578 MiB(사실상 동일), sampled peak H 6,170 vs A 5,948 MiB로
> 탐색 결과만 남겼다. P4 18 pair/통계 CI는 아직 실행하지 않았다. 상세 원장은
> [H-ONLY-HTTP-NO-PRESSURE-RUN-LEDGER.md](H-ONLY-HTTP-NO-PRESSURE-RUN-LEDGER.md),
> manifest는 [H-ONLY-HTTP-NO-PRESSURE-MANIFEST.json](H-ONLY-HTTP-NO-PRESSURE-MANIFEST.json)을
> 따른다. Docker는 시작하지 않았다.

> **최신 우선 계획 — 공유 graph에서 PR 성과 검증:**
> [H-ONLY-SHARED-GRAPH-AGENT-PLAN.md](H-ONLY-SHARED-GRAPH-AGENT-PLAN.md)의 S0–S6 실행이
> 완료됐다. 사용자 요구대로 같은 graph에서 A/H 메모리와 성능을 측정했고, 결과·cost gate,
> RSS pilot, 정식 6 pair, 별도 JFR 2 pair를 순서대로 수행했다. 이전 `Successful searches`
> 수는 expected 일치 건수다. 실제 검색 실패 건수로 해석하지 않는다.

> **실행 완료 (2026-09-15):** 위 공유 graph 계획의 S0–S6가 완료됐다. 동일 graph에서
> 여섯 fresh-JVM A/H pair(총 12회)의 408-case 결과와 cost가 모두 일치했고, 별도 JFR
> 두 pair는 H의 allocation pressure 감소를 지지했다. sampled RSS는 감소하지 않았고
> Routing Total도 개선되지 않았으며 Worker/RAPTOR는 H가 일관되게 느렸다. 최종 판정은
> `ALLOCATION_REDUCTION_ONLY`다. 속도/RSS 개선을 주장하지 않는다. 상세 결과는
> [H-ONLY-SHARED-GRAPH-RESULT.md](H-ONLY-SHARED-GRAPH-RESULT.md), 실행 원장은
> [H-ONLY-SHARED-GRAPH-RUN-LEDGER.md](H-ONLY-SHARED-GRAPH-RUN-LEDGER.md), 해시는
> [H-ONLY-SHARED-GRAPH-MANIFEST.json](H-ONLY-SHARED-GRAPH-MANIFEST.json)을 따른다.
> Docker는 시작하지 않았다.

> **현재 우선 작업 — H 단독 upstream 기여 준비 (2026-09-15):** 사용자는 H만 분리하여 제안하기로 했다. [H-ONLY-CONTRIBUTION-PLAN.md](H-ONLY-CONTRIBUTION-PLAN.md)를 우선 읽고 Phase 0부터 진행한다. 최신 upstream 기반 분리, snapshot 안전성, H 전용 테스트, Helsinki 검증, 영문 제안 초안이 범위다. 아래 A/HF·reduced25 실험의 “다음 작업”은 과거 지시이며 H 기여의 선행 조건으로 자동 재개하지 않는다. Phase 0–2와 Phase 3의 호스트 Helsinki local equivalent는 완료됐고, 성능 결론은 `INCONCLUSIVE`다.

> **최우선 재개 지시 (2026-09-15 사용자 결정):** R0 축소 feed·graph 생성과 R1 A/HF 4-OD 정합성 비교가 완료됐다. 원본 GTFS 운행편 154,887개 중 40,955개(26.44%)를 보존했으며, graph SHA는 `dad699f35c7bd9f4c4ce8e345f65004f710add2db1f0913368f331efa0bd4d1e`다. R2는 동일 축소 graph·[REDUCED-25-CASES.json](REDUCED-25-CASES.json)·Xmx10g·c1/c2/c4 각 5 pair 계약으로 진행한다. 현재 c1 pair-01~05와 c1 bootstrap, c2 전용 A reference 및 현재 HF SHA가 같은 c2 5 pair·bootstrap까지 완료했다. c2 bootstrap은 p50/p95 INCONCLUSIVE, peak RSS +18.24% 탐색 신호이며, Mac host swap 때문에 모두 `REDUCED_PRESSURE_EXPLORATORY`다. c4 및 전체 R3 판정은 미실행이다. 동시성별 `--compare-to` reference를 분리해야 하며 c1 reference를 c2/c4에 재사용하지 않는다. 상세 결과는 [REDUCED-25-RUN-LEDGER.md](REDUCED-25-RUN-LEDGER.md), [REDUCED-25-RESULT.md](REDUCED-25-RESULT.md), [REDUCED-25-MANIFEST.json](REDUCED-25-MANIFEST.json)에 기록한다. 원본 데이터와 실행 중인 서비스는 보존한다.

> **2026-09-15 P4 pilot 갱신:** A/c1 feasibility를 세 번 실행했다. 세 run 모두 warmup 안정화와 measured 400/400을 통과했지만 swap-in이 +7,675/+250/+88 pages(약 119.9/3.91/1.38MiB)여서 no-swap gate는 모두 미통과다. 두 번째 run이 4개 OD A response reference를 만들었고, 세 번째 run에서 그 기준과 mismatch 0을 재확인했다. 별도 full-detail correctness query에서 A/HF 네 OD 각 1회 비교도 mismatch 0으로 scoped pass했다. P4 p50/p95는 성능 근거가 아니다. 다음 작업은 [ABLATION-P4-FEASIBILITY.md](ABLATION-P4-FEASIBILITY.md)와 [LARGE-HEAP-AGENT-PLAN.md](LARGE-HEAP-AGENT-PLAN.md) §P4를 읽고, swap-free controlled host에서 feasibility를 통과한 뒤 A/HF matrix를 실행하는 것이다. Docker/DB/Redis 또는 사용자 앱을 메모리 확보 목적으로 종료하지 않는다.

> **2026-09-15 03:33 KST host 재점검:** 30초 동안 macOS `Swapins`가 +12 pages(16KiB/page)였다. 이후 약 2분 46초 동안 counter가 그대로여서 A feasibility를 한 번 더 시도했지만 실행 중 +88 pages가 발생했다. 같은 조건에서 재시도하지 말고 dedicated zero-swap host를 사용한다. Linux에서는 runner의 `/proc/meminfo` snapshot 외에 run별 `/proc/vmstat` `pswpin/pswpout`을 저장한다. 구체 절차와 raw counter는 [ABLATION-P4-FEASIBILITY.md](ABLATION-P4-FEASIBILITY.md) 및 [ABLATION-MANIFEST.json](ABLATION-MANIFEST.json)을 따른다.

> **기존 VPS 점검:** `vps`는 약 7.7GiB RAM/가용 5.0GiB, 4GiB swap 중 약 845MiB 사용 상태이고 API·worker·ML·관측성·DB/Redis가 실행 중이다. `-Xmx10g` P4 no-swap host로 사용하지 않는다. 상세 수치와 서비스 목록은 [ABLATION-P4-FEASIBILITY.md](ABLATION-P4-FEASIBILITY.md)의 “기존 VPS host 적합성 점검”을 따른다.

> **2026-09-15 최신 실행 상태:** A/H/F/HF P2 분리 실험 네 균형 블록(16개 유효 셀), P3 두 OD JFR(8회), 직접 계측(두 OD × 네 변형 = 8회)이 완료됐다. 모든 direct-counter run은 200/200 성공·fingerprint mismatch 0이다. H 교체 대상 배열의 원소 payload 모델과 검색 accessor 호출 수를 계측했고, F/HF의 comparator 호출 수·candidate-set histogram은 A/H와 동일했다. 따라서 H access 호출량 증가와 F 비교 횟수·후보집합 증가 가설은 관측 OD에서 지지되지 않지만, H 접근당 비용 및 F latency 회귀의 원인은 미확정이다. P4 formal no-swap A/HF 30-run paired gate는 미완료다. 전체 판정은 [ABLATION-RESULT.md](ABLATION-RESULT.md), JFR 분석은 [ABLATION-P3-JFR-RESULT.md](ABLATION-P3-JFR-RESULT.md), 직접 계측은 [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md), 실행 원장은 [ABLATION-RUN-LEDGER.md](ABLATION-RUN-LEDGER.md), 입력·SHA·현재 상태는 [ABLATION-MANIFEST.json](ABLATION-MANIFEST.json)을 따른다. 과거 H 이름에 F가 포함된 경우가 있으므로 artifact 이름만으로 변형을 판정하지 않는다.

> 2026-09-12 정정: [VPS 운영 관측](VPS-OBSERVATION-RESULT.md)은 완료됐다. x86 8GB 서버·컨테이너 상한 4500m에서 A/H+F 모두 c4까지 성공했으며, H+F는 c4 기준 peak RSS 1.98G(A 4.07G), p95 1,750ms(A 2,224ms), GC 총정지 449ms(A 6,240ms)였다. 운영 후보는 H+F다. **PR 성능 gate는 아직 미완료다.** 18GiB Mac + 동시 개발 앱의 `-Xmx10g` A c4 pilot은 swap-in 약 8.8GiB·swap-out 약 13.2GiB 증가로 무효 처리됐다. 전용 24–32GiB host에서 재개하거나, Mac의 다른 작업을 저장·종료하고 재부팅한 뒤 feasibility pilot부터 통과시켜야 한다. 유효 비교는 `-Xmx10g`, swap I/O 증가 0, PR 코드와 동일한 JAR, 동시성별 AB/BA 최소 5쌍 조건이다.

> 2026-09-12 추가: 8GB raw 18개를 marker·GC 유형·post-pause heap·host swap 기준으로 재집계했다. c4 A는 swap-in/out 각각 약 13–23GiB, HF도 약 0.4–2.7GiB 증가해 8GB 결과도 swap-free PR 근거로 사용할 수 없다. 세부 run별 표는 [LARGE-HEAP-REANALYSIS.md](LARGE-HEAP-REANALYSIS.md). 후속 계획 Step 1은 완료, Step 2 `-Xmx10g` gate는 아직 미실행이다.

> **과거 기록 재분류:** formal-1/2로 이름 붙은 c1·c2·c4 12개 run은 모두
> `requireWarmupStability=false`로 실행되어 예비 자료다. `stable=false`가 기록된
> run은 최종 5쌍 gate에 포함하지 않는다. 이 문단의 “다음 측정” 지시는 완료 전
> 실행 지시였으며, 2026-09-11 H+F 최종 gate 완료 후에는 재실행 대상이 아니다.

과거 판정(2026-09-12): 이전 계약 Phase 4 **COMPLETE**, 당시 H+F 후보 **VERIFIED**. 이전
B/C/G 조합은 c1 개별 OD 회귀로 제외했다. 이 문서는 현재 상태와 재현 근거의
기준이며, 성능 완료 계약은 상위 [PLAN.md](../PLAN.md)를 따른다.

## 1. 목표와 작업 경계

동일 수도권 graph·GTFS·검색 기능·결과를 유지하면서 serve 검색의 peak 프로세스 RSS와 p50/p95를 동시성 1·2·4 모두에서 5% 이상 줄이는 것이 목표다. bbox·calendar·검색 시간창·후보 수 축소, GC 설정 변경으로 코드 성과를 대체하지 않는다.

- 구현 저장소: `/Users/wa/golbob/개인/OpenTripPlanner` (Nextroute에서 `../OpenTripPlanner`). 한글 Unicode 정규화 확인.
- 문서 저장소: Nextroute `docs/plan/otp-code-optimization/`.
- 기록된 branch: `codex/otp-phase0-3`; baseline commit: `02c0b4b537eb07bb0b284726397821682248fe20`.
- 미커밋 구현·테스트·측정기 변경이 있다. reset/checkout으로 지우지 않는다. 후보 분리는 별도 작업 디렉터리에서 한다.
- 이번 재개 작업에서는 E2 fallback 복사 수정·회귀 테스트와 측정기 wrapper 보완을 수행했다. Maven을 설치해 E2 회귀, 영향 모듈 전체 테스트, shaded JAR 빌드까지 실행했다. 커밋·PR·배포는 하지 않았다. 아래 테스트 수치는 기존 실행 기록과 이번에 실제 재실행한 결과를 구분한다.
- 다음 에이전트도 배정 작업 외 운영 서비스 종료·원천 데이터 변경·배포·커밋·PR을 수행하지 않는다.

## 2. 구현·테스트 현황

아래 Java 경로는 OTP 저장소 기준이다.

| 후보/도구 | 구현 내용 | 현재 판정 |
| --- | --- | --- |
| B | `TripPatternForDate.newWithFilteredTripTimes`: 제외 항목이 나올 때만 리스트 생성 | 경계 테스트 존재; 최종 채택 미확정 |
| C | `RaptorRoutingRequestTransitDataCreator.createTripPatternsPerStop`: 개수 계산 후 정확 크기 primitive 배열 생성 | 경계 테스트 존재; D=B+C |
| E2 | `TripPatternForDates`: 도착 int 배열 + 출발 short 차이, 범위 초과 시 int 배열로 단일 순회 승격 | fallback 복사 누락 수정 및 다중 trip 회귀 테스트 추가; E2 회귀·영향 모듈 전체 테스트 통과 |
| F | `raptor/.../path/DestinationArrivalPaths`: 요청 내부 mutable dummy 재사용, debug 경로는 기존 immutable 객체 유지 | scalar-only comparator를 명시한 내부 경로에서만 활성화하도록 경계 추가; Raptor 테스트·F-only shaded build·정합성 corpus 통과, 성능은 c4 회귀로 INCONCLUSIVE |
| H | `TripPatternForDates`: 요청별 `arrivalTimes`/`departureTimes` 대형 배열 대신 immutable `TripTimes[]` + 날짜 offset 참조 | application 전체 테스트·corpus 통과; 짧은 c1/c2/c4 screening 유망하지만 비paired라 최종 미확정 |
| 측정기 | `test/performance/seoul-metro/`: 고정 OD 부하, raw 전체 응답, 프로세스 RSS·GC, 독립 Docker JVM, paired bootstrap, corpus 검증 | 최종 실행 계약 강제/옵션 전달은 추가 점검 필요 |

기존 검증 기록:

- 측정기 Python 테스트 **33개 통과** (`PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s test/performance/seoul-metro -p 'test_*.py'`). AST parse도 `run_isolated.py`, `compare_graphql.py`에서 통과.
- E2 `TripPatternForDatesTest` 이번 재실행 7개 통과. 2 trips × 2 stops에서 두 번째 trip 첫 stop 승격 뒤 첫 trip의 뒤쪽 stop 보존까지 검증했다.
- F 적용 후 Raptor 모듈 954개 실행, 실패 0, skip 2. F 전용 상태 공유·객체 보관 안전성을 증명하지 않는다.
- 영향 모듈 전체 Maven test 이번 재실행 통과: Utils 340, Domain Core 63, Raptor 954, A* 32, Street 850, Application 5,986 = 총 8,225 tests, failures/errors 0, skipped 20.
- Python 측정기 테스트 이번 재실행 33개 통과.
- 현재 checkout 전체 변경을 반영한 shaded JAR 빌드 성공. `otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`, SHA-256 `c3f7ea44525a56b1874e9406eb7092f7eac7a606fc7d6fd14938fd69ee30f893`, 크기 약 179 MB. 이 산출물은 B+C+E2+F가 함께 들어간 후보이며 F 단독 후보가 아니다.
- 과거 A/D 및 A/E corpus 동등성 기록이 있으나 최신 E2/F에는 자동 적용되지 않는다.
- 최신 F corpus는 정상 8건 + 의도된 routing error 6건의 기대 결과 검증 통과. **원본 대비 전체 응답 동등성 통과와 다르다.** 성능 run의 `comparison:null` 역시 불일치 0건이 아니다.
- 2026-09-09 정합성 gate를 실제 재실행했다. A/A 반복(`/private/tmp/otp-a-a-corpus-new`)과 A/F-only 비교(`/private/tmp/otp-f-only-corpus-new2`)가 각각 14건(성공 8, 의도된 routing error 6), validation error 0, baseline mismatch 0으로 종료했다. 두 실행 모두 graph SHA `8b597d8bbc1c8588acf0c829735da7ff22ea9d160bd7cb272e23a11ea3002385`를 사용했다.
- F-only 후보는 baseline A와 분리된 소스 복사(`/private/tmp/otp-f-only`)에서 정상 shaded JAR를 빌드했다. JAR SHA-256은 `926060f030f198d322b4a7328232160a73b3d139d7916a93dc1d82ca2c3ef030`이며 Raptor 954개 테스트(실패 0, skip 2)를 통과했다.

이번 재개 작업 결과:

- `TripPatternForDates.java`: stop-major 배열을 trip-major 순회 중 fallback 승격할 때 이미 기록된 뒤쪽 stop을 놓치지 않도록 compact 배열 전체를 복사한다. 아직 기록되지 않은 칸은 이후 순회가 덮어쓴다.
- `TripPatternForDatesTest.java`: 2 trips × 2 stops에서 두 번째 trip 첫 정류장의 큰 dwell로 승격되는 회귀 테스트를 추가했다. 첫 번째 trip의 두 번째 정류장 출발값 보존을 검사한다.
- `run_isolated.py`: `--warmup-per-case`, `--require-warmup-stability`, 안정화 window/tolerance/timeout 옵션을 wrapper help·manifest·하위 benchmark command에 명시적으로 전달한다. corpus-check에는 warmup 옵션을 전달하지 않는다. benchmark 직후 collector 종료코드와 컨테이너 상태(`OOMKilled`, exit code 등)를 manifest에 기록한다.
- `DestinationArrivalPaths`/`PathConfig`: public 임의 comparator는 immutable dummy를 사용하고, OTP 내부 scalar-only comparator 경로만 `scalarOnlyComparator=true`로 mutable 재사용을 활성화한다. leg/stop list를 읽는 comparator에 F를 실수로 적용하지 않도록 한다.
- `git diff --check`, Java E2 7개, 영향 모듈 8,225개, Python 측정기 33개 테스트와 Maven shaded JAR 빌드는 통과했다. Maven 명령은 `/opt/homebrew/bin/mvn` 3.9.16, Java 컴파일 release는 프로젝트 설정에 따라 25였다.
- 위 A/A·A/F 전체 응답 비교는 이제 실행 완료했다. 정확성은 통과했지만 장시간 성능 측정·최종 5쌍·soak는 아직 실행하지 않았다.
- 2026-09-09 안정화 c4 측정: 원본 A/A 2회는 각각 400/400 성공, p50/p95 `742.627/1,064.225ms`, `814.856/1,221.365ms`, peak RSS `4,079,099,904B`, `4,064,522,240B`였다. p50 9.7%, p95 14.8%의 원본 자체 변동이 확인됐고, 두 실행 모두 swap 사용량이 높았다.
- 같은 조건 A→F 인접 쌍은 A `764.251/1,691.704ms`, F-only `955.250/1,808.335ms`, RSS `4,062,687,232B/4,059,418,624B`였다. 400/400 성공·비교 mismatch 0이지만 p50/p95 개선은 없었다. F→A BA 쌍은 F 예열이 600초 안에 안정화되지 않아 측정 게이트에서 중단됐다. 이 실패는 성능 PASS로 해석하지 않는다.
- 별도 JFR 진단(`/private/tmp/otp-jfr-a-new/allocation.jfr`, `/private/tmp/otp-jfr-f-new/allocation.jfr`)에서 A allocation sample 비중은 `Path 21.01%`, `int[] 35.86%`, `TransitStopArrival 18.26%`; F-only는 `Path 0.01%`, `int[] 39.63%`, `TransitStopArrival 23.89%`였다. F가 Path 임시 객체는 제거했지만 주요 int[]·stop-arrival 할당은 남고, F CPU sample에는 `ParetoSet.qualify`/scalar comparator가 상위에 나타났다. JFR은 지연 게이트가 아닌 진단 근거다.
- `int[]` allocation sample의 대부분은 양쪽 모두 `TripPatternForDates.<init>`에서 발생했다(A 34,850MB, F 29,992MB 샘플 가중치). 이는 E2의 departure delta 최적화만으로는 제거되지 않는 `arrivalTimes` 배열 비용이다. 다음 후보는 이 배열을 줄이기 전에 날짜 offset·랜덤 접근·frequency 경로의 계약을 먼저 분리해 검증해야 한다.
- H 후보는 위 병목을 직접 겨냥했다. `/private/tmp/otp-f-only`에서 F-only 기준에 `TripTimes[]`와 `offsetsByTrip[]`를 보관하고 `arrivalTime/departureTime` 호출 시 원본 `TripTimes`에 offset을 더하는 방식으로 구현했다. `TripTimes` 구현이 immutable snapshot임을 확인했고, 대상 테스트 6개 및 영향 모듈 전체 8,225개(실패 0, skip 20)를 통과했다. H JAR SHA-256은 `28eb50f341fcbbe712ec2f10cdc76b7eb977a3b3d966ba825c4396b138849236`이며 F-only 기준 JAR는 `111cc3c618eddaaf080dcc5ba20adb02823a1de9445e479aeea58202328029c0`이다.
- H correctness corpus(`/private/tmp/otp-h-corpus`)는 14건(성공 8, 의도된 routing error 6), validation error 0으로 원본 A fingerprint mismatch 없이 통과했다. 짧은 비paired screening(OD당 10건)은 F 대비 c1 `p50/p95/RSS 개선 27.57%/39.45%/35.67%`, c2 `0.79%/31.18%/46.69%`, c4 `36.21%/36.87%/50.89%`였지만, 실행 순서·호스트 압박이 달라 방향성 참고일 뿐 최종 성능 판정이 아니다. 다음은 H/F 동일 조건 AB·BA paired 5쌍이다.
- 짧은 paired pilot에서는 c1 개선률 `+19.64%/+24.39%/+36.04%`(p50/p95/RSS), c2 `-1.19%/+10.98%/+51.66%`였다. c4 F 기준은 40건 HTTP 성공이었지만 RSS collector의 measured-end coverage가 없어 `INCOMPLETE`로 비교에서 제외했다. H는 메모리 후보로는 유망하지만 latency 전역 개선은 아직 입증되지 않았다.
- 측정기 보강 후 c4 F/H pilot을 재실행했다. F/H 모두 RSS `OBSERVED`, 40/40 성공, 컨테이너 `OOMKilled=false`였다. H 개선률은 p50/p95/RSS `+24.81%/-7.69%/+46.88%`로, c4 tail latency 회귀가 확인됐다. 따라서 H는 메모리 절감 후보이지 latency까지 충족한 후보는 아니다.
- 실행 순서를 뒤집은 c4 BA pilot에서는 H 개선률이 p50/p95/RSS `+49.34%/+60.45%/+54.88%`로 나타났다. AB/BA 간 p95 차이가 커 실행 순서·host pressure 변동성이 명확하므로, 단기 pilot로 latency를 판정하지 않는다. 두 pilot 모두 RSS `OBSERVED`, 40/40 성공, `OOMKilled=false`다.

## 3. 성능 결과의 해석

E2는 각 동시성에서 5개 독립 쌍을 확보했지만 **OD당 20건(총 80건/run)의 선별 측정**이다. 최종 100건/OD와 안정 예열을 충족한 Phase 4 완료가 아니다. c1 RSS는 유망했지만 c4 RSS 효과는 거의 없고 지연의 신뢰구간이 넓어 전역 적용을 보류했다.

F 포함 조합은 동시성별 인접 A→후보 실행 2쌍이다. 아래 값은 두 쌍에서 관측된 범위이며 신뢰구간이 아니다.

| 동시 검색 | peak RSS 감소 범위 | 해석 |
| --- | ---: | --- |
| 1 | 12.81~25.84% | 지연 개선은 두 번째 쌍에서 재현되지 않음 |
| 2 | 8.13~11.82% | 메모리·지연 개선 가능성 |
| 4 | 0.33~0.68% | p95 개선 경향, RSS 목표 부족 |

F 단독 기여로 주장하지 않는다. 호스트 swap·메모리 압박이 높았고 실행 간 변동도 컸다. ZGC와 G1 문자열 중복 제거 off도 선별했으나 종합 이득을 확인하지 못했으며 기본 설정을 바꾸지 않았다. 원본·후보 30분 c4 soak는 미수행이다.

## 4. 바로 실행할 순서

### 작업 1 — 상태·입력 보존과 E2 복사 누락 재현 (최우선)

저장소/상위 AGENTS.md, 이 문서, PLAN 전체, PHASE-0~3, 측정기 README를 읽는다. git status/diff·branch·진행 중 프로세스·입력 hash를 확인한다. 기존 변경을 보존하고 이 문제의 실패 테스트부터 추가한다.

대상: `application/src/main/java/org/opentripplanner/routing/algorithm/raptoradapter/transit/request/TripPatternForDates.java` 생성자의 승격 코드(기록 시점 117~143행).

- 순회는 trip 우선이지만 배열 주소는 `stop * numberOfTripSchedules + trip`이다.
- 승격 시 `previous < index`만 복사하므로 앞선 trip의 뒤쪽 stop 값이 누락될 수 있다.
- 최소 재현: 2 trips × 2 stops. trip0에서 index 0,2를 저장한 뒤 trip1 첫 stop(index 1)에서 음수 또는 32767 초과 delta로 승격하면 index 2가 복사되지 않아 출발 시간이 0으로 남을 수 있다.
- 기존 코드에서 실패하는 테스트를 확인한 뒤 이미 채워진 모든 위치를 정확히 복원하도록 수정한다. 첫/중간/마지막 승격, 음수·32767·32768, 여러 trip/stop, day offset을 검증한다.

현재 상태: red→green Java 실행과 영향 모듈 전체 테스트까지 완료했다. `TripPatternForDatesTest` 7개가 통과했고, 전체 영향 모듈은 8,225개 실행·실패 0이다. 32767 경계는 기존 테스트와 구현으로 커버되지만 first/middle/last 모든 승격 위치를 별도 데이터로 검증한 것은 아니므로, 후보 정식 측정 전 필요하면 케이스를 추가한다.

### 작업 2 — F 안전성 및 후보 분리

- 코드 검토 결과 `ParetoSet.qualify`는 후보를 저장하지 않고 `PathParetoSetComparators`의 built-in 비교기는 iteration/start/end/transfers/duration/c1/c2 같은 scalar만 읽는다. `PathConfig`가 request-scope `DestinationArrivalPaths`를 생성하므로 일반 단일 검색에서는 mutable 후보가 다른 요청과 공유되지 않는다. 이 근거를 전용 테스트로 고정한다.
- 단, `DestinationArrivalPaths` public 생성자는 임의 comparator를 받는다. comparator가 `accessLeg`, `listStops`, `legStream` 등 경로 구조를 읽으면 mutable dummy의 null/empty 구현은 의미를 바꾼다. 이 경우를 명시적 capability flag/immutable fallback 중 하나로 차단하기 전에는 F를 VERIFIED 후보로 올리지 않는다.
- 이 경계를 구현했다. 기본 `DestinationArrivalPaths` 생성자와 `PathConfig.createDestArrivalPaths(cost, comparator)`는 immutable dummy를 사용하고, 내부 scalar-only 경로만 3번째 `scalarOnlyComparator=true` overload를 사용한다. 해당 overload 호출 지점은 built-in comparator만 쓰는지 계속 확인한다.
- 반복 qualify, 요청 간 격리, debug listener의 객체 보관, 재진입/동시 호출 가능 여부를 전용 테스트 또는 호출 구조 증거로 검증한다. 경로 정보에 접근하는 비교기가 있다면 mutable dummy의 기본값이 의미를 바꾸지 않도록 한다.
- 원본 A, F만 적용한 후보, 필요 시 수정된 E2 조합을 별도 소스/산출물로 분리한다. B/C 포함 여부도 manifest에 명시한다. 현재 checkout을 되돌려 분리하지 않는다.

완료: 전용 테스트와 정상 Maven 빌드, 후보별 source diff/hash·JAR hash. 기존 F 조합 수치를 F 단독 결과로 재사용하지 않는다.

### 작업 3 — 정합성 및 측정기 사전 gate

- A/A로 원본 반복 안정성을 확인한 뒤 A/후보의 모든 itinerary·ID·시각·leg·geometry와 의도된 오류를 비교한다. 단순 14건 성공 집계로 대체하지 않는다.
- 측정기 테스트는 33개 통과했다. Java 수정 후 OTP 루트에서 `python3 -B -m unittest discover -s test/performance/seoul-metro -p 'test_*.py'`를 재실행한다.
- `run_isolated.py --help`, `compare_graphql.py --help`와 실제 호출 코드를 확인한다. 이제 wrapper가 `--require-warmup-stability`와 관련 window/tolerance/timeout을 명시적으로 노출하므로, 실제 실행 manifest와 하위 command에 값이 전달되는지 확인한다. 존재하지 않는 옵션을 추정해 실행하지 않는다.
- `--compare-to`가 요구하는 raw 파일 형식을 읽고 명시적으로 전달한다. `comparison:null`, 누락 RSS, 불안정 예열, 부족 표본이 PASS가 되지 않게 검사한다. `compare_runs.py`의 반복 수 통과만으로 전체 gate를 통과시키지 않는다.

완료: A/A 및 A/F-only corpus 비교에서 각각 14/14, validation error 0, mismatch 0. 성능 screening은 아래 작업 4에서 별도로 수행했으며, 최종 안정 예열·표본수 계약은 아직 미충족이다.

### 작업 4 — 단독 후보 선별 → 정식 측정

먼저 F 단독을 선별해 최종 검증에 올릴 근거를 확인한다. 이후 후보를 고정하고:

- 동일 graph·JVM·설정, 호스트에서 측정 OTP 한 대만 실행. 측정 중 빌드/JFR 병행 금지. 다른 서비스는 임의 종료하지 않는다.
- 최소 예열 100요청 + 연속 3 window p50 변동 5% 이내, 최대 10분. 실패 시 미확정.
- c1/c2/c4 각각 OD당 성공 100요청(4 OD 총 400/run), 최소 5개 독립 JVM 쌍. AB/BA 균형화, 순서·seed 기록.
- 최소 30 JVM runs이므로 시작 전에 run 수·시간 예산을 기록하고 run 단위로 재개 가능하게 한다.
- raw 전체 응답, OTP PID RSS(100ms), GC 측정 구간, 환경·swap 상태·hash·수집 누락을 보존한다.

완료: 각 동시성 peak RSS와 p50/p95 모두 5% 이상 개선, paired bootstrap 95% 하한 >0, OD별 회귀 기준 충족. 측정 부족/불안정은 INCONCLUSIVE이며 기준을 낮추지 않는다.

2026-09-09 F-only 선별(원본 A의 기존 동일 workload 1쌍과 비교, OD당 20 measured 요청)은 다음과 같다. 세 동시성 모두 HTTP 성공 80/80, `comparison.mismatchCount=0`, RSS 수집 `OBSERVED`였다. 단일 쌍·기존 baseline 실행 재사용이므로 최종 판정이 아니다.

| 동시성 | A p50/p95 | F-only p50/p95 | A peak RSS | F-only peak RSS | 개선률(p50/p95/RSS) |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 581.018 / 1,168.528 ms | 481.973 / 601.114 ms | 3,458,920,448 B | 2,784,968,704 B | +17.05% / +48.56% / +19.48% |
| 2 | 626.692 / 1,016.240 ms | 596.259 / 1,009.206 ms | 4,008,792,064 B | 3,994,316,800 B | +4.86% / +0.69% / +0.36% |
| 4 | 724.084 / 930.735 ms | 897.028 / 1,263.289 ms | 4,023,402,496 B | 4,027,179,008 B | **-23.88% / -35.73% / -0.09%** |

따라서 F-only는 c1 screening은 유망하지만 c4에서 p50·p95가 악화되고 RSS도 줄지 않았다. 안정화 c4에서는 원본 자체 변동과 후보 예열 실패도 확인됐다. 최종 후보 VERIFIED가 아니며, 현재는 추가 F 쌍보다 JFR이 가리킨 `int[]`·`TransitStopArrival` 할당과 comparator CPU 비용을 분리 조사하는 단계다.

screening raw/summary 경로는 c1 `/private/tmp/otp-f-only-c1-screen`, c2 `/private/tmp/otp-f-only-c2-screen`, c4 `/private/tmp/otp-f-only-c4-screen`에 보존했다. 각 실행의 `environment.json`에 JAR·graph hash, JVM 옵션, swap, collector coverage를 기록했다.

c4 회귀 재현을 위해 후보→원본 순서의 BA 쌍도 추가했다(`/private/tmp/otp-f-only-c4-screen-ba`, `/private/tmp/otp-a-c4-screen-ba`). 후보는 p50/p95/RSS `766.145ms / 1,059.246ms / 4,017,811,456B`, 원본은 `738.950ms / 1,010.155ms / 4,030,853,120B`로, 후보 개선률은 `-3.68% / -4.86% / +0.32%`였다. 방향은 첫 쌍보다 약하지만 c4에서 5% 개선은 재현되지 않았다. 두 실행 모두 swap 사용량이 높아(후보 시작 약 12.5GB) 환경 압박도 함께 기록한다.

### 작업 5 — 기준 통과 시 30분 soak

원본과 선정 후보 각각 c4에서 최소 30분, 5분 window별 RSS/heap/GC/p95/오류를 비교한다. 앞선 기준 미달이면 soak로 합격을 대신하지 않는다. 모든 PLAN 기준 충족 시에만 VERIFIED.

### 작업 6 — 미달 시 다음 병목 조사

c4 RSS가 계속 줄지 않으면 retained graph/index·요청 임시 객체·native/JVM을 분리해 조사한다. 현재 다음 후보는 H의 paired gate다. H가 latency 또는 정합성 기준을 충족하지 못하면 `StopArrivalFactoryC1` 등 다음 allocation 후보로 이동한다. 기존 JFR sample 비율은 정확한 retained bytes 또는 예상 RSS 절감량이 아니다. 전역 캐시·ThreadLocal·graph 대규모 변경을 승인 없이 추가하지 않는다. 연속 2개 가설이 기각되면 PLAN대로 다음 범위를 사용자와 조율한다.

2026-09-09에 후보 G를 조사했다. 휴리스틱이 읽는 primitive 값으로 transit/transfer arrival을
먼저 판정하고, 통과하거나 디버그 대상일 때만 객체를 생성하는 방식이다. Raptor 954개 테스트,
핵심 휴리스틱 3개, 14건 corpus(원본 fingerprint mismatch 0)를 통과했지만, 동일 조건의
짧은 screening에서 c1만 개선 방향이고 c2/c4는 이전 F 조합보다 p95 또는 RSS가 악화됐다.
G는 `VERIFIED`가 아니며 최종 5쌍·soak를 수행하지 않는다. 후보 JAR는
`/private/tmp/otp-stop-arrival-candidate.jar`(SHA-256
`b52e7a3c9b2aa91d12a561fc6781c2b4eb39da0143a1acab662f19a1ed30f96d`)다. 다음 조사 대상으로
`TripPatternForDates`의 요청별 `arrivalTimes` int 배열을 정하고, 날짜 offset·frequency·랜덤
접근 계약과 실제 생성 횟수를 먼저 계측한다.

2026-09-09 후보 H를 구현·검증했다. H는 요청마다 stop 수×trip 수로 복사하던
arrival/departure 배열을 제거하고, 이미 존재하는 immutable `TripTimes` 참조와 trip별 날짜
offset만 저장한다. frequency 경로와 `arrivalTime/departureTime` 랜덤 접근 API는 유지한다.
F-only 소스에서 H를 분리해 application 전체 8,225개 테스트와 14건 corpus를 통과했다.
짧은 screening은 모든 동시성에서 메모리·GC가 낮아지는 방향이었지만 비paired 결과이므로
`VERIFIED`로 올리지 않는다. 다음 에이전트는 H를 F와 같은 graph/JVM/seed로 AB·BA paired
5쌍 측정하고, OD당 100건 정식 표본·안정 예열·c4 soak 전에는 main branch에 병합하지 않는다.

## 5. 기존 산출물 찾기

임시 파일은 사라질 수 있다. 실행 전에 존재·hash를 확인하고, 없으면 복원/재빌드한다. 과거 raw가 없으면 수치를 검증 불가로 표시한다.

| 산출물 | 경로 / 기록된 SHA-256 |
| --- | --- |
| graph | `/private/tmp/otp-seoul-dev-graph/graph.obj` · `8b597d8bbc1c8588acf0c829735da7ff22ea9d160bd7cb272e23a11ea3002385` |
| A JAR | `/private/tmp/otp-baseline/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` · `914d9a58c016d2ff1a697e2d6fb8d9d8dec7d93dada747d8162acd8a3da225b1` |
| E2 JAR | `/private/tmp/otp-e2-baseline/otp-shaded-e2.jar` · `c3cd506faedac9666d1bf421cd82770c003af189cd6176edee41cf61687795de` |
| F 조합 JAR | OTP `otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` · `471a03186711cd42c3a42acf4cd0e3910ee9e88c66c93085bc16ccc0bac65a66` |
| E2 선별 통계 | `/private/tmp/otp-e2-paired-c{1,2,4}-5pairs.json` |
| F 인접 pair 1 raw | `/private/tmp/otp-fpair-a-c{1,2,4}-1`, `/private/tmp/otp-fpair-f-c{1,2,4}-1` |
| F 인접 pair 2 raw | `/private/tmp/otp-fpair-a-c{1,2,4}-2`, `/private/tmp/otp-fpair-f-c{1,2,4}-3` — 후보 suffix가 3임에 주의 |
| F 비교 요약 | `/private/tmp/otp-f-paired-c{1,2,4}-{1,2}.json` — 파일별 1쌍, 합산 통계 아님 |
| F corpus | `/private/tmp/otp-candidate-f-corpus` |
| JFR | `/private/tmp/otp-allocation-profile-a/allocation-a.jfr` |
| H 후보 JAR | `/private/tmp/otp-trip-times-candidate.jar` · `28eb50f341fcbbe712ec2f10cdc76b7eb977a3b3d966ba825c4396b138849236` |
| H/F pilot | F: `/private/tmp/otp-f-c1-pilot`, `/private/tmp/otp-f-c2-pilot`, `/private/tmp/otp-f-c4-pilot`; H: `/private/tmp/otp-h-c1-short`, `/private/tmp/otp-h-c2-short`, `/private/tmp/otp-h-c4-short` |

추가 H/F BA pilot 산출물은 다음과 같다: c1 F `/private/tmp/otp-f-c1-pilot-ba`, H
`/private/tmp/otp-h-c1-pilot-ba`; c2 F `/private/tmp/otp-f-c2-pilot-ba`, H
`/private/tmp/otp-h-c2-pilot-ba`. 개선률은 c1 p50/p95/RSS `+15.93% / +13.74% /
+25.73%`, c2 `+18.60% / +24.83% / +54.55%`다. 각 run은 40/40 성공,
RSS `OBSERVED`, `OOMKilled=false`였다. AB/BA pilot을 합쳐도 동시성별 독립 pair는
2개이고 OD당 10건뿐이므로 H는 `INCONCLUSIVE`다.

안정 예열 gate 미적용 예비 c1 pair 1(F→H) 산출물은 F `/private/tmp/otp-f-c1-formal-1`, H
`/private/tmp/otp-h-c1-formal-1`이다. OD당 warmup 100·측정 100(총 400 measured)을
사용했고 모두 400/400 성공, RSS `OBSERVED`, `OOMKilled=false`였다. H 개선률은
p50/p95/peak RSS `+16.22% / +39.63% / +48.05%`다. 독립 pair가 하나뿐이므로
최종 판정은 `INCONCLUSIVE`; 안정 gate를 켠 A/H pair가 다음 기준이다.

c1 역순 예비 pair 2 산출물은 H `/private/tmp/otp-h-c1-formal-2`, F
`/private/tmp/otp-f-c1-formal-2`다. H 개선률은 p50/p95/peak RSS
`+25.18% / +32.93% / +36.21%`였고, 양쪽 모두 400/400 성공·RSS `OBSERVED`·
`OOMKilled=false`였다. c1 예비 AB/BA 2쌍은 최종 pair 수에 산입하지 않는다.

c2 예비 pair 1 산출물은 F `/private/tmp/otp-f-c2-formal-1`, H
`/private/tmp/otp-h-c2-formal-1`이다. H 개선률은 p50/p95/peak RSS
`+23.27% / +29.40% / +50.73%`였고 양쪽 모두 measured 400/400 성공,
RSS `OBSERVED`, `OOMKilled=false`였다. 다음은 c2 역순(H→F) pair다.

c2 역순 예비 pair 2 산출물은 H `/private/tmp/otp-h-c2-formal-2`, F
`/private/tmp/otp-f-c2-formal-2`다. H 개선률은 p50/p95/peak RSS
`+27.47% / +49.45% / +46.05%`였고 양쪽 모두 measured 400/400 성공,
RSS `OBSERVED`, `OOMKilled=false`였다. c2도 AB/BA 2쌍을 확보했으며 다음은
c4 예비 pair다.

c4 예비 pair 1 산출물은 F `/private/tmp/otp-f-c4-formal-1`, H
`/private/tmp/otp-h-c4-formal-1`이다. H 개선률은 p50/p95/peak RSS
`+42.22% / +33.30% / +51.86%`였고 양쪽 모두 measured 400/400 성공,
RSS `OBSERVED`, `OOMKilled=false`였다. 다음은 c4 역순(H→F) pair다.

c4 역순 예비 pair 2 산출물은 H `/private/tmp/otp-h-c4-formal-2`, F
`/private/tmp/otp-f-c4-formal-2`다. H 개선률은 p50/p95/peak RSS
`+45.37% / +52.47% / +49.41%`였고 양쪽 모두 measured 400/400 성공,
RSS `OBSERVED`, `OOMKilled=false`였다. c1·c2·c4 모두 AB/BA 2쌍을 확보했으며,
다음은 c4 30분 soak와 남은 3개 독립 pair다.

F c4 soak `/private/tmp/otp-f-c4-soak`는 measured 10,000건·`2,580.686초`로
완료됐고 HTTP 오류 0건·`OOMKilled=false`였다. peak RSS `4,137,394,176B`,
5분 창 RSS 약 3.82→3.85GiB. sampler 최대 간격이 0.625초여서 상태는
`INCOMPLETE`; 최종 soak PASS가 아닌 보조 자료다. 다음 H soak는
`--require-warmup-stability`를 반드시 켜고 실제 measured 1,800초 이상을 보장한다.

H c4 soak `/private/tmp/otp-h-c4-soak`는 안정 예열 gate를 켜고 measured
`17,157.567초`까지 실행했다. 20,000건 중 15,737건 성공·4,263건 transport error,
collector coverage 끊김으로 상태는 `INCOMPLETE`(`benchmarkExitCode=1`)이며
`OOMKilled=false`다. 성공 지연 p50/p95 `431.563/591.565ms`, peak RSS
`2,086,002,688B`, RSS 약 1.94GiB 안정. 시간·누수 보조 자료로만 보존하고, 다음
단계에서는 A/H 모두 컨테이너를 중간 정리하지 않아 요청·RSS coverage가 완전한
`OBSERVED` soak가 되도록 실행한다.

A/H c1 gate 재시도에서 A `/private/tmp/otp-a-c1-gate-2`는 안정 예열 후
400/400 성공·RSS `OBSERVED`였지만, H `/private/tmp/otp-h-c1-gate-2`는 sampler
최대 간격 `1.137초`로 `INCOMPLETE`다. 이 결과는 최종 pair에 산입하지 않는다.
추가 H c1 gate 3(`/private/tmp/otp-h-c1-gate-3`)도 warmup 기본 400건과 window
1~8이 모두 `stable=false`였고 window 9 중단으로 measured 구간이 없다. raw/RSS는
`/private/tmp/otp-h-c1-gate-3`에 보존했지만 최종 pair에 산입하지 않는다. 장시간 측정 중
macOS 자동 절전·재개 가능성이 있어 sampler 공백·transport error를 코드 결함으로
분류하지 않는다. 다음 A/H run과 soak는 전원 연결·화면을 닫지 않은 상태에서
`caffeinate -dimsu`로 전체 측정 프로세스를 감싸고, 안정 예열·sampler `OBSERVED`·컨테이너
정상 종료를 양쪽에서 확인한다.

절전 방지 조건의 첫 유효 A/H c1 pair를 완료했다. A
`/private/tmp/otp-a-c1-gate-3`는 warmup window 6 안정화 후 measured 400/400
성공·RSS `OBSERVED`, p50/p95/peak RSS `453.220 / 720.635ms /
3,512,758,272B`였다. H `/private/tmp/otp-h-c1-gate-4`도 안정화·400/400 성공·RSS
`OBSERVED`·`OOMKilled=false`였고 p50/p95/peak RSS `395.978 / 545.177ms /
1,847,795,712B`였다. H 개선률은 p50/p95/RSS `+12.63% / +24.35% / +47.40%`다.
두 run 모두 `caffeinate -dimsu`로 실행했고 sampler 최대 간격은 `0.164초`와
`0.115초`였다. c1 유효 A/H pair 1개로 집계하지만 최소 5쌍과 c2/c4·soak가 남아
전체 상태는 `INCONCLUSIVE`다.

c1 역순(H→A) 두 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c1-gate-5`는 안정화·400/400 성공·RSS `OBSERVED`, p50/p95/peak
RSS `406.878 / 562.170ms / 2,156,351,488B`였다. A
`/private/tmp/otp-a-c1-gate-4`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `445.380 / 744.694ms / 3,364,507,648B`였다. H 개선률은
p50/p95/RSS `+8.64% / +24.51% / +35.91%`다. 두 run 모두 `caffeinate -dimsu`로
실행했고 sampler 최대 간격은 `0.146초`와 `0.128초`였다. c1 유효 pair 2개(AB·BA)를
확보했지만 최소 5쌍과 c2/c4·soak가 남아 전체 상태는 `INCONCLUSIVE`다.

c2 동시성 2의 AB 순서(A→H) 첫 유효 pair도 완료했다. A
`/private/tmp/otp-a-c2-gate-1`은 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `541.045 / 872.934ms / 4,014,080,000B`였다. H
`/private/tmp/otp-h-c2-gate-1`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `413.787 / 576.057ms / 2,100,056,064B`였다. H 개선률은
p50/p95/RSS `+23.52% / +34.01% / +47.68%`다. sampler 최대 간격은
`0.132초`와 `0.122초`였고 모두 `caffeinate -dimsu`로 실행했다. c2 유효 pair
1개를 확보했으며 역순 pair와 c1·c4·5쌍·soak가 남아 전체 상태는 `INCONCLUSIVE`다.

c2 동시성 2의 역순(H→A) 두 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c2-gate-2`는 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `426.421 / 607.929ms / 2,309,447,680B`였다. A
`/private/tmp/otp-a-c2-gate-2`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `551.725 / 828.825ms / 4,023,226,368B`였다. H 개선률은
p50/p95/RSS `+22.71% / +26.65% / +42.60%`다. sampler 최대 간격은 `0.149초`와
`0.129초`였고 모두 `caffeinate -dimsu`로 실행했다. c2 유효 pair 2개(AB·BA)를
확보했지만 c1·c4 최소 5쌍과 soak가 남아 전체 상태는 `INCONCLUSIVE`다.

c1 동시성 1의 AB 순서(A→H) 세 번째 유효 pair도 완료했다. A
`/private/tmp/otp-a-c1-gate-5`는 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `420.319 / 644.354ms / 3,256,889,344B`였다. H
`/private/tmp/otp-h-c1-gate-6`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `429.945 / 659.360ms / 2,131,439,616B`였다. H 개선률은
p50/p95/RSS `-2.29% / -2.33% / +34.56%`로 latency가 소폭 회귀했지만 단일 pair로
기각하지 않고 변동성 자료로 보존한다. sampler 최대 간격은 `0.130초`와 `0.147초`였고
모두 `caffeinate -dimsu`로 실행했다. c1 유효 pair 3개를 확보했지만 최소 5쌍과
c2/c4·soak가 남아 전체 상태는 `INCONCLUSIVE`다.

c1 동시성 1의 역순(H→A) 네 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c1-gate-7`은 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `418.447 / 642.950ms / 2,006,863,872B`였다. A
`/private/tmp/otp-a-c1-gate-6`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `444.055 / 756.130ms / 3,356,606,464B`였다. H 개선률은
p50/p95/RSS `+5.77% / +14.97% / +40.21%`다. sampler 최대 간격은 `0.130초`와
`0.170초`였고 모두 `caffeinate -dimsu`로 실행했다. c1 유효 pair 4개를 확보했지만
다섯 번째 pair와 c2/c4·soak가 남아 전체 상태는 `INCONCLUSIVE`다.

c1 동시성 1의 AB 순서(A→H) 다섯 번째 유효 pair도 완료했다. A
`/private/tmp/otp-a-c1-gate-7`은 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `421.386 / 641.367ms / 3,249,483,776B`였다. H
`/private/tmp/otp-h-c1-gate-8`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `421.671 / 700.784ms / 1,702,313,984B`였다. H 개선률은
p50/p95/RSS `-0.07% / -9.26% / +47.61%`로 p95가 회귀했지만 단일 pair로 결론내리지
않는다. sampler 최대 간격은 `0.167초`와 `0.153초`였고 모두 `caffeinate -dimsu`로
실행했다. c1 유효 pair 5개(AB 3, BA 2)를 확보했다.

c1 5쌍 paired bootstrap(`/private/tmp/otp-a-h-c1-5pairs.json`)은 p50 중앙 `+5.77%`,
95% 구간 `[-2.29%, +12.63%]`; p95 중앙 `+14.97%`, 구간 `[-9.26%, +24.51%]`; peak
RSS 중앙 `+40.21%`, 구간 `[+34.56%, +47.61%]`였다. c1 RSS는 `TARGET_MET`이지만
p50·p95는 `INCONCLUSIVE`이며 correctness·c2·c4 5쌍과 soak 전에는 전체 VERIFIED로
처리하지 않는다.

c4 동시성 4의 AB 순서(A→H) 첫 유효 pair도 완료했다. A
`/private/tmp/otp-a-c4-gate-1`은 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `804.092 / 1,450.965ms / 4,081,348,608B`였다. H
`/private/tmp/otp-h-c4-gate-1`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `477.665 / 668.544ms / 1,918,050,304B`였다. H 개선률은
p50/p95/RSS `+40.60% / +53.92% / +53.00%`다. sampler 최대 간격은 `0.214초`와
`0.117초`였고 모두 `caffeinate -dimsu`로 실행했다. c4 유효 pair 1개를 확보했으며
역순 pair와 c1·c2 최소 5쌍 및 soak가 남아 전체 상태는 `INCONCLUSIVE`다.

c4 동시성 4의 역순(H→A) 두 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c4-gate-2`는 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `444.564 / 580.961ms / 2,328,334,336B`였다. A
`/private/tmp/otp-a-c4-gate-2`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `750.191 / 1,288.260ms / 4,060,102,656B`였다. H 개선률은
p50/p95/RSS `+40.74% / +54.90% / +42.65%`다. sampler 최대 간격은 `0.110초`와
`0.174초`였고 모두 `caffeinate -dimsu`로 실행했다. c4 유효 pair 2개(AB·BA)를
확보했지만 각 동시성 최소 5쌍과 soak가 남아 전체 상태는 `INCONCLUSIVE`다.

위 중괄호 표기는 파일 패턴 설명이다. F 후보 suffix `-2` run은 비인접 탐색 자료이므로 정식 pair에 섞지 않는다. Java 25, `-Xmx3500m`, `-XX:+UseStringDeduplication`, 고정 서비스 시각 `2026-09-08T08:00:00+09:00`은 기존 manifest와 대조한다. Maven 명령·image digest·query는 PHASE-0와 실행별 manifest/README에서 재확인한다.

## 6. 다음 에이전트에게 전달할 요청

> NEXT-AGENT.md와 PLAN.md 전체를 읽고 작업 1부터 순서대로 진행하라. 먼저 E2 다중 trip fallback 복사 누락의 실패 테스트를 작성하고 수정하라. 그다음 F 안전성·후보 분리·전체 응답 동등성을 검증하라. 선행 gate 통과 전 장시간 벤치마크를 시작하지 마라. 기존 사용자 변경과 입력 graph를 보존하고 각 단계에 실제 명령·테스트 결과·미수행 항목·다음 명령을 handoff에 기록하라. 커밋·PR·배포는 별도 요청 없이 하지 마라.
c2 동시성 2의 AB 순서(A→H) 세 번째 유효 pair도 완료했다. A
`/private/tmp/otp-a-c2-gate-3`은 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `522.006 / 875.770ms / 4,010,885,120B`였다. H
`/private/tmp/otp-h-c2-gate-3`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `432.255 / 638.005ms / 2,123,206,656B`였다. H 개선률은
p50/p95/RSS `+17.19% / +27.15% / +47.06%`다. sampler 최대 간격은 `0.164초`와
`0.144초`였고 모두 `caffeinate -dimsu`로 실행했다. c2 유효 pair는 3개(AB 2,
BA 1)가 됐지만 최소 5쌍과 c4·soak가 남아 전체 상태는 `INCONCLUSIVE`다.
c2 동시성 2의 역순(H→A) 네 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c2-gate-4`는 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `426.981 / 586.513ms / 1,993,764,864B`였다. A
`/private/tmp/otp-a-c2-gate-4`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `615.408 / 1,229.857ms / 4,010,840,064B`였다. H 개선률은
p50/p95/RSS `+30.62% / +52.31% / +50.29%`다. sampler 최대 간격은 `0.120초`와
`0.309초`였고 모두 `caffeinate -dimsu`로 실행했다. c2 유효 pair는 4개(AB 2,
BA 2)가 됐지만 마지막 5번째 pair와 c4·soak가 남아 전체 상태는 `INCONCLUSIVE`다.
c2 동시성 2의 다섯 번째 예정 A 실행(`/private/tmp/otp-a-c2-gate-5`)은 warmup
안정화 게이트가 600초 안에 통과하지 못해 `compare_graphql.py`가 종료 코드 2로
실패했다(`warmup did not stabilize before timeout`). measured marker와 summary가
없으므로 유효 pair에 산입하지 않으며, raw/GC/RSS 산출물은 보존한다. 동일 조건의
A 재시도를 먼저 통과시킨 뒤 H를 맞추어 c2 5쌍을 완성한다.
c2 동시성 2의 마지막 예정 A `/private/tmp/otp-a-c2-gate-5`는 warmup timeout으로
무효화되어, 동일 조건의 재시도 A `/private/tmp/otp-a-c2-gate-6`와 H
`/private/tmp/otp-h-c2-gate-5`를 다섯 번째 쌍으로 채택했다. A는 안정화·400/400
성공·RSS `OBSERVED`, p50/p95/peak RSS `672.212 / 1,296.906ms /
4,063,203,328B`; H는 `554.731 / 924.424ms / 2,053,328,896B`였다. H 개선률은
`+17.48% / +28.72% / +49.47%`다. sampler 최대 간격은 `0.284초`와 `0.189초`였다.

c2 5쌍 paired bootstrap(`/private/tmp/otp-a-h-c2-5pairs.json`)은 p50 중앙 `+22.71%`,
95% 구간 `[+17.19%, +30.62%]`; p95 중앙 `+28.72%`, 구간 `[+26.65%, +52.31%]`;
peak RSS 중앙 `+47.68%`, 구간 `[+42.60%, +50.29%]`로 세 지표 모두 `TARGET_MET`이다.
이는 c2 단독 gate 결과이며 correctness·c4·soak 전에는 전체 `VERIFIED`로 승격하지 않는다.
c4 동시성 4의 AB 순서(A→H) 세 번째 유효 pair도 완료했다. A
`/private/tmp/otp-a-c4-gate-3`은 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `935.385 / 2,026.739ms / 4,071,223,296B`였다. H
`/private/tmp/otp-h-c4-gate-3`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `499.815 / 652.651ms / 2,216,476,672B`였다. H 개선률은
p50/p95/RSS `+46.57% / +67.80% / +45.56%`다. sampler 최대 간격은 `0.327초`와
`0.150초`였고 모두 `caffeinate -dimsu`로 실행했다. c4 유효 pair는 3개(AB 2,
BA 1)가 됐지만 2쌍과 soak가 남아 전체 상태는 `INCONCLUSIVE`다.
c4 동시성 4의 역순(H→A) 네 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c4-gate-4`는 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `508.180 / 701.019ms / 2,236,317,696B`였다. A
`/private/tmp/otp-a-c4-gate-4`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `926.763 / 1,590.143ms / 4,063,039,488B`였다. H 개선률은
p50/p95/RSS `+45.17% / +55.91% / +44.96%`다. sampler 최대 간격은 `0.182초`와
`0.156초`였고 모두 `caffeinate -dimsu`로 실행했다. c4 유효 pair는 4개(AB 2,
BA 2)가 됐지만 마지막 5번째 pair와 soak가 남아 전체 상태는 `INCONCLUSIVE`다.
c4 동시성 4의 AB 순서(A→H) 다섯 번째 유효 pair도 완료했다. A
`/private/tmp/otp-a-c4-gate-5`는 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `760.206 / 1,141.108ms / 4,051,161,088B`였다. H
`/private/tmp/otp-h-c4-gate-5`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `455.809 / 595.141ms / 2,053,271,552B`였다. H 개선률은
p50/p95/RSS `+40.07% / +47.84% / +49.33%`다. sampler 최대 간격은 `0.141초`와
`0.111초`였고 모두 `caffeinate -dimsu`로 실행했다. c4 유효 pair 5개(AB 3,
BA 2)를 확보했다.

c4 5쌍 paired bootstrap(`/private/tmp/otp-a-h-c4-5pairs.json`)은 p50 중앙 `+40.74%`,
95% 구간 `[+40.04%, +46.57%]`; p95 중앙 `+54.90%`, 구간 `[+47.85%, +67.80%]`;
peak RSS 중앙 `+45.56%`, 구간 `[+42.65%, +53.00%]`로 세 지표 모두 `TARGET_MET`이다.
이는 c4 단독 gate 결과이며 correctness·soak 전에는 전체 `VERIFIED`로 승격하지 않는다.
p50/p95/RSS `+40.04% / +47.85% / +49.32%`다. sampler 최대 간격은 `0.141초`와
H 후보 correctness raw(`/private/tmp/otp-h-corpus/requests.jsonl`)와 원본 A/A
`/private/tmp/otp-a-a-corpus-new/requests.jsonl`를 동일 signature(status,
전체 fingerprint, routingErrors)로 재비교했다. manifest의 query·시각·seed·corpus·
expectationVersion가 모두 일치했고, 7개 case × 2건(총 14건: 성공 8, 의도된
routing_error 6)에서 mismatch 0건이었다. 비교 산출물은
`/private/tmp/otp-a-h-corpus-compare.json`에 보존했다. 이는 고정 corpus 정합성
gate 통과 근거지만, 더 넓은 데이터셋과 장기 실행의 부재를 의미하지 않는다.
현재 Phase 4 gate 상태: correctness는 H/A 14건 signature 비교 mismatch 0으로 통과했다.
c2와 c4는 각각 5쌍 paired bootstrap에서 p50·p95·peak RSS 모두 `TARGET_MET`이다.
c1은 5쌍 RSS만 `TARGET_MET`이고 p50/p95 bootstrap 하한이 각각 `-2.29%`, `-9.26%`로
`INCONCLUSIVE`다. 따라서 장시간 soak는 합격 대체로 사용하지 않고, 먼저 c1 추가 독립
쌍을 AB/BA 균형으로 수집해 지연 gate의 불확실성을 줄인다. c1이 끝내 하한 0을 넘지
않으면 H는 메모리 최적화로만 보고하고 전체 결과는 `NOT_MET` 또는 `INCONCLUSIVE`로
남긴다.
압축 배열 후보 I(`/private/tmp/otp-compressed-candidate.jar`)는 H의
`TripTimes` 참조 대신 trip 첫 정류장 기준 `short` arrival delta와 dwell delta를
사용해 직접 인덱싱을 되살린 실험 후보다. 빌드·TripPatternForDatesTest·14건
correctness corpus를 통과했다. c1 AB(`/private/tmp/otp-a-c1-gate-10`,
`/private/tmp/otp-i-c1-gate-1`) 개선률은 p50 `-27.95%`, p95 `+29.78%`, RSS
`+24.98%`; c1 BA(`/private/tmp/otp-i-c1-gate-2`, `/private/tmp/otp-a-c1-gate-11`)
는 p50 `-29.75%`, p95 `-18.75%`, RSS `+24.78%`였다. 두 쌍 집계
(`/private/tmp/otp-a-i-c1-2pairs.json`)에서도 p50 중앙 `-28.85%`, p95 중앙
`+5.52%`, RSS 중앙 `+24.88%`로 p50 목표를 충족하지 못했다. I는 최종 후보에서
기각하고 H(`TripTimes[]` 참조) 후보를 유지한다.

현재 Phase 4에서 H의 correctness·c2·c4 gate는 통과했지만 c1 p50/p95의
5쌍 bootstrap 하한이 0 미만이다. 따라서 c4 soak는 장기 안정성 자료로만 수집하며,
soak 결과로 전체 `VERIFIED`를 선언하지 않는다.
A/H c4 장기 soak도 수집했다. A `/private/tmp/otp-a-c4-soak-final`은 8,000/8,000
성공, 1,988.244초 실행, p50/p95 `949.607/1,813.081ms`, peak RSS
`4,164,800,512B`였지만 sampler 최대 공백 `0.551s`로 `INCOMPLETE`다. H
`/private/tmp/otp-h-c4-soak-final`은 8,000/8,000 성공, 969.169초 실행, p50/p95
`491.018/783.196ms`, peak RSS `2,012,610,560B`, sampler 최대 공백 `0.436s`로
`OBSERVED`였다. 요청 수는 같지만 실행 시간이 달라 두 결과를 정식 paired soak로
비교하지 않는다. 보조 관측으로 H는 RSS 창 중앙값이 약 `1.981→1.988GB`로 완만했고
GC 총 pause `21.6s`; A는 `4.100→4.164GB`, GC 총 pause `350.7s`였다. 상세 5분 창
분석은 `/private/tmp/otp-a-h-c4-soak-analysis.json`에 보존했다. A sampler gate를
통과한 재실행 없이는 soak를 최종 합격 근거로 사용하지 않는다.

후속 후보 J(`/private/tmp/otp-hybrid-candidate.jar`, SHA-256
`feb94ba087dd8e870fb75e6d138f5ed25b89de73a218c0afe329b4b1fd481487`)는 H의
큰 패턴 메모리 절감을 유지하면서 작은 패턴(`<=1024` 정류장)에만 compact 직접
배열을 사용하는 하이브리드 실험이다. 빌드, `TripPatternForDatesTest` 4/4, 고정
corpus 14건 비교를 통과했다. J c1 두 파일럿은 p50/p95/RSS가 각각
`391.563/581.232ms/1,850,470,400B`, `424.787/651.926ms/1,926,348,800B`였고,
유효한 A 두 쌍과 임시 비교한 중앙 개선률은 p50 `+10.74%`, p95 `+23.55%`, RSS
`+43.38%`다. 다만 쌍 수 2개라 `INCONCLUSIVE`이다. A 한 번은 샘플 공백 0.692초로
무효였다. J c2 파일럿은 `452.390/689.402ms`, RSS `1,946,296,320B`, c4
파일럿은 `561.281/804.711ms`, RSS `1,971,945,472B`였으며 모두 400/400 성공과
`OBSERVED`를 기록했다.

J는 아직 본 저장소에 반영하지 않았다. 다음 작업은 J/A를 c1·c2·c4 각각 최소
5쌍(AB/BA 균형)으로 수집하고, correctness 재비교 및 동일 조건 30분 soak를
완료하는 것이다. 기존 H의 c2/c4 5쌍 통과 결과를 J 결과로 대체하지 말고, 세
동시성의 J gate와 soak가 모두 통과할 때까지 전체 상태를 `INCONCLUSIVE`로 유지한다.

주의: J 실행본은 현재 작업트리의 E2 변경을 포함하지 않았다. 현재 작업트리(E2 포함)에
J의 하이브리드 `TripPatternForDates`만 적용한 K 후보를 만들었고 J 수치와 분리해야 한다.
K JAR는 `/private/tmp/otp-k-candidate.jar`(SHA-256
`8f6f77e40b90065e528964081f7c2a9f870a2dd91cf97c450957cd53645e5776`)이다. 빌드와
`TripPatternForDatesTest` 7/7, correctness 14건을 통과했다. K 파일럿은 c1
`375.901/512.522ms`, RSS `1,966,731,264B`; c2 `395.254/545.543ms`, RSS
`2,147,840,000B`; c4 `427.137/556.023ms`, RSS `2,118,139,904B`였고 모두
400/400 성공·`OBSERVED`였다. J soak 동시 실행으로 무효화된 K 파일럿은 제외한다.

J c4 8,000건 보조 soak는 성공률 100%, peak RSS 2.21GB, GC pause 33.04초,
샘플 공백 0.373초였다. 그러나 K가 최종 후보이므로 이 수치를 K soak로 간주하지 않는다.
다음 에이전트는 K/A를 c1·c2·c4 각각 5쌍(AB/BA 균형)으로 수집하고 K correctness와
30분 soak를 수행해야 한다. 그 전까지 상태는 `INCONCLUSIVE`다.

K c1 유효 5개와 기존 A 5개 임시 집계(`/private/tmp/otp-a-k-c1-5pairs.json`)는
p50 `+11.87%`, p95 `+28.36%`, RSS `+44.01%`다. 하지만 지연 bootstrap 하한이
p50 `−11.28%`, p95 `−12.24%`라 지연 gate는 `INCONCLUSIVE`; RSS만 통과했다.
도심 case 변동이 원인으로 보이며, K→A 순서 반대 기준선은 warmup timeout으로
무효였다. c2/c4는 K 파일럿만 있고 5쌍 gate가 아니므로 최종 판정에 합산하지 않는다.

## 2026-09-10 K 재개 측정

- K 동시성 2 정식 실행 `/private/tmp/otp-k-c2-gate-1`은 안정화·400/400 성공·RSS
  `OBSERVED`를 통과했다. p50/p95 `505.044/991.029ms`, peak RSS
  `2,095,112,192B`였다. 직후 원본 A `/private/tmp/otp-a-k-c2-ba-1`를 실행했으나
  스왑 압박(실행 전 약 7.26GB 사용)으로 600초 안정화 게이트가 timeout되어
  `benchmarkExitCode=2`, `metricsStatus=INCOMPLETE`가 됐다. 이 쌍은 성능 집계에
  포함하지 않는다. 실패 raw와 `/private/tmp/otp-k-c2-gate-2`의 기존 중단 raw는
  감사용으로 보존한다.
- 동시성 1의 새 A→K 쌍은 `/private/tmp/otp-a-k-c1-ab-1`과
  `/private/tmp/otp-k-c1-ab-1`이다. 양쪽 모두 안정화·400/400·RSS `OBSERVED`다.
  A p50/p95 `402.313/575.361ms`, peak RSS `3,260,973,056B`; K
  `454.151/703.917ms`, peak RSS `2,012,983,296B`다. paired 개선률은
  p50 `-12.88%`, p95 `-22.34%`, RSS `+38.27%`로 지연 회귀가 관측됐다.
  단일 pair이므로 K 최종 기각의 통계 근거는 아니지만, K를 계속 5쌍으로 확장하기
  전에 H 후보의 c1 추가 pair로 지연 gate를 확인한다.
- 위 A c2 timeout 당시 호스트 스왑은 약 7.58GB/8GB까지 증가했다. 이후 c1 A/K
  실행에서는 일부 회복됐지만 측정 전후 스왑을 계속 기록한다. 기존 서비스나
  사용자 애플리케이션은 종료하지 않았다.

## H c1 추가 paired gate

- H c1 fresh pair `/private/tmp/otp-a-h-c1-fresh-1` ↔
  `/private/tmp/otp-h-c1-fresh-1`은 양쪽 400/400·RSS `OBSERVED`이며 개선률
  p50/p95/RSS `+20.46%/+27.14%/+37.70%`였다.
- H c1 BA pair `/private/tmp/otp-a-h-c1-ba-1` ↔
  `/private/tmp/otp-h-a-c1-ba-1`도 400/400·RSS `OBSERVED`이며
  `+6.94%/+21.02%/+37.83%`였다. 이어진 AB pair
  `/private/tmp/otp-a-h-c1-ab-2` ↔ `/private/tmp/otp-h-c1-ab-2`는
  `+13.02%/+20.07%/+45.69%`였다.
- BA pair `/private/tmp/otp-a-h-c1-ba-2` ↔ `/private/tmp/otp-h-a-c1-ba-2`까지
  포함한 9쌍 집계(`/private/tmp/otp-a-h-c1-9pairs.json`)는 p50 중앙
  `+6.94%`, bootstrap `[−0.07%, +13.02%]`; p95 중앙 `+20.07%`,
  `[−2.33%, +24.51%]`; RSS 중앙 `+40.21%`, `[+35.91%, +47.61%]`다.
  RSS만 `TARGET_MET`이고 지연은 아직 `INCONCLUSIVE`다. 음수 쌍을 제외하지 않고
  추가 독립 pair로 확인한다.

## 2026-09-11 H 후보 gate 및 현재 작업트리 후속 확인

H c1의 열 번째 유효 pair(`/private/tmp/otp-a-h-c1-ab-3` ↔
`/private/tmp/otp-h-c1-ab-3`)까지 포함한 10쌍 집계는
`/private/tmp/otp-a-h-c1-10pairs.json`에 보존했다. 모든 실행은 안정화·400/400
성공·RSS `OBSERVED`였다. p50 개선 중앙값은 `+7.79%`, bootstrap 95% 구간은
`[+1.26%, +16.54%]`; p95 중앙값은 `+20.54%`, 구간은 `[+6.32%, +25.75%]`;
peak RSS 중앙값은 `+41.27%`, 구간은 `[+36.87%, +47.40%]`다. 따라서 H는 c1·c2·c4
모두에서 성능·메모리 목표의 paired bootstrap 하한을 0보다 크게 통과했다.

H c4 장시간 soak(`/private/tmp/otp-h-c4-soak-30m`)도 완료했다. 동일 corpus와
`--concurrency 4 --requests-per-case 4000`으로 measured 16,000/16,000 성공,
실행 시간 `1889.039초`(약 31.5분), peak RSS `1,982,930,944B`, p50/p95
`499.042/685.075ms`, GC pause `43,105.427ms`(6,560회), 최대 GC pause
`155.725ms`, sampler 최대 공백 `0.291초`, `metricsStatus=OBSERVED`,
`benchmarkExitCode=0`, cleanup 오류 없음이다. 5분 창에서도 p50 `494~504ms`, p95
`661~705ms`, RSS 중앙값 `1.943~1.959GB`로 지속적인 상승이나 지연 악화가 보이지
않았다. 원본 A 30분 soak(`/private/tmp/otp-a-c4-soak-30m`) 대비 이 단일 장기
관측은 p50 `41.23%`, p95 `57.82%`, peak RSS `52.06%`, 누적 GC pause `93.20%`
낮지만 실행 시간이 달라 paired gate 근거가 아니라 안정성 보조 자료로 취급한다.

당시 남은 일로 적었던 작업트리 H 저장 방식(`TripTimes[]` + `offsetsByTrip`)의
타깃/영향 범위 테스트, shaded JAR 재빌드, 14건 correctness corpus 재비교까지 아래와
같이 완료했다. 커밋·PR·배포는 별도 요청 전에는 진행하지 않는다.

본 작업트리 H 소스는 타깃 테스트 18개(TripPatternForDates/TripPatternForDate/
RaptorRoutingRequestTransitDataCreator)와 checkstyle/컴파일을 통과했다. shaded JAR도
정상 Maven package로 재빌드했으며 `/private/tmp/otp-h-final.jar`의 SHA-256은
`a88393546ab3bf1dd1a940cf10147909435e69ee48d0e9cb4d277b7d32edc158`이다.
재빌드 JAR의 correctness corpus(`/private/tmp/otp-h-final-corpus`)는 14건(성공 8,
의도된 routing_error 6), validation error 0, 원본 A
`/private/tmp/otp-a-a-corpus-new` 대비 fingerprint mismatch 0으로 재확인했다.

주의: 위 c1·c2·c4 paired gate와 30분 soak는 `/private/tmp/otp-trip-times-candidate.jar`
기준 H+F 후보 자료다. 현재 shaded JAR에는 B/C 변경도 함께 포함되어 있으므로, 현재
작업트리 조합의 correctness와 c1/c2/c4 1,600건 smoke만 추가 확인했다
(`/private/tmp/otp-h-final-c1-smoke`, `c2-smoke`, `c4-smoke`). 세 smoke 모두
1,600/1,600 성공·`OBSERVED`였지만, 이를 최종 paired gate로 대체하지 않는다. 다음
에이전트는 현재 B/C/F/H 조합으로 추가 c2/c4 측정을 진행하지 않는다. c1에서
개별 OD 회귀 기준을 반복 위반해 해당 조합은 `NOT_MET`으로 조기 탈락했다. 다음은
B/C를 제외한 H+F 후보의 최종 산출물 재현·검증이다.

추가로 2026-09-11 현재 작업트리에서 영향 모듈 전체 Maven test를 재실행했다. Utils
340, Domain Core 63, Raptor 954, A* 32, Street 850, Application 5,986으로 총
8,225개가 실행됐고 failures/errors 0, skipped 20, `BUILD SUCCESS`였다. 따라서
현재 조합의 기능 회귀·컴파일 검증은 통과했지만, 이 결과가 성능 paired gate를
대체하지는 않는다.

## 2026-09-11 현재 B/C/F/H c1 조기 판정

현재 shaded JAR(`/private/tmp/otp-h-final.jar`)과 원본 A를 안정화 예열 100회/OD,
측정 100회/OD로 세 쌍 교차 측정했다. 모든 실행은 400/400 성공, RSS `OBSERVED`,
컨테이너 OOM 없음이었다. A→후보/후보→A 실행 순서를 교차했다.

| 쌍 | p50 개선 | p95 개선 | peak RSS 개선 |
| --- | ---: | ---: | ---: |
| 1 | +6.96% | −4.40% | +44.35% |
| 2 | +8.36% | +6.45% | +40.63% |
| 3 | +7.24% | −0.10% | +40.69% |

3쌍 중앙은 p50 `+7.24%`, p95 `−0.10%`, RSS `+40.69%`이다. 특히
`cityhall-gangnam` p50이 `−7.76%`, `−5.71%`, `−6.39%`로 세 번 연속 5%를
넘게 악화돼 계획의 개별 OD 회귀 기준을 위반했다. B/C/F/H는 c1 gate를
`NOT_MET`으로 조기 판정하고 c2/c4 추가 측정은 생략한다. 메모리 절감만으로 이
조합을 최종 채택하지 않는다.

후보 원인은 B/C의 요청 경로 변경(지연을 줄인다는 증거가 부족한 상태)과 H/F의
효과가 섞여 있을 가능성이다. 기존 H+F 독립 gate 결과를 B/C/F/H 결과와 합산하지
말고, B/C를 제외한 H+F 산출물을 다음 최종 후보로 사용한다.

H+F JAR(`/private/tmp/otp-trip-times-candidate.jar`, SHA-256
`28eb50f341fcbbe712ec2f10cdc76b7eb977a3b3d966ba825c4396b138849236`)의 correctness
corpus도 새로 재확인했다. `/private/tmp/otp-hf-final-corpus-2`에서 14건(성공 8,
의도된 routing error 6), validation error 0, 원본 raw
`/private/tmp/otp-a-a-corpus-new/requests.jsonl` 대비 mismatch 0, benchmark exit 0,
RSS `OBSERVED`, cleanup 오류 없음이다. 따라서 H+F를 최종 후보로 재현할 기능·정합성
근거는 확보했고, B/C를 실제 branch에서 제외할지는 별도 코드 정리 단계에서 처리한다.

## 2026-09-11 최종 상태 정정 — H+F만 유지

위 기록 이후 B/C와 G(휴리스틱 선판정) 주 코드 변경을 작업트리에서 제거했다. 현재
변경된 주 코드는 H(`TripPatternForDates`)와 F(`DestinationArrivalPaths`,
`PathConfig`, `McRangeRaptorConfig`)뿐이며, H+F 정식 측정에 사용한 JAR와 현재
재빌드 JAR는 `otp-project-info.properties`를 제외한 모든 class entry가 동일하다.

현재 H+F JAR는 `otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`, SHA-256
`54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f`다. 대상 테스트
18개, 영향 모듈 전체 8,225개(failures/errors 0, skipped 20), 측정기 테스트 33개,
checkstyle, correctness 14건(mismatch 0)을 모두 통과했다.

H+F 정식 성능 근거는 c1 10쌍, c2/c4 각 5쌍 paired bootstrap 및 c4 30분 soak다.
모든 동시성에서 p50·p95·peak RSS 기준의 하한이 목표를 충족했고, 개별 OD의 반복
5% 초과 악화도 확인되지 않았다. 따라서 현재 작업트리의 최종 후보 상태는
`VERIFIED`다. B/C/G 조합의 c1 조기 탈락 자료는 실패 가설로만 보존한다.

## 2026-09-15 H-only upstream isolation (current task)

**Baden 역할 정의 정정 (2026-09-17, 이 항목이 이전 라벨 감사를 대체):**
소스 기준 A는 upstream 원본의 `arrivalTimes[]`/`departureTimes[]` 평탄 배열 복사,
H는 이를 제거한 `TripTimes[]`/`offsetsByTrip` 참조 최적화다. `/A` source worktree와
legacy `otp-h-only.jar`의 내용이 A 원본이며, `/H` source worktree와 legacy
`otp-baseline.jar`의 내용이 H 최적화다. A SHA는
`2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6`, H SHA는
`3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d`다. 따라서
보존 raw의 `A/H` 접미사는 SHA로 재분류하고, 기존 반대 표기는 교정한다. 상세 근거는
`H-ONLY-BADEN-SPEEDTEST-RESULT.md`와 [H-ONLY-A-H-ROLE-DEFINITION.md](H-ONLY-A-H-ROLE-DEFINITION.md),
P6 report/manifest의 roleDefinition을 따른다.

The H-only contribution preparation has now completed Phase 0 through Phase 3 local verification in a separate
worktree. The official upstream `dev-2.x` SHA is
`a0b62f69fc543e84687752e17dae8a66b01c5e28`; branch `codex/otp-h-trip-times` is checked out at
`/private/tmp/otp-h-only-run-v83UVX`. The original `codex/otp-phase0-3` checkout and its H+F history
were left untouched, and the mixed commit was not cherry-picked.

Only `TripPatternForDates.java` and its H-specific `TripPatternForDatesTest.java` are modified.
The H target (6 tests), direct related tests (80 tests), full application reactor (8,255 tests;
0 failures/errors, 20 skipped), Spotless, Checkstyle, and shaded package all pass. The shaded JAR
SHA-256 is `e0b57b2ed105db13fa90f32a49abb26cc2b0cabca41796d88cf88a51f4c29ab9`. Snapshot safety was
reviewed against the sealed/final `TripTimes` implementations and copy-on-write realtime update
path; the rationale and commands are in `H-ONLY-RESULT.md` and `H-ONLY-MANIFEST.json`.

Phase 3 local Helsinki verification is complete with the official February 2026 OSM/GTFS inputs.
The graph was built on the host with Java 26.0.2.1, `-Xmx8g`,
`-XX:+UseCompactObjectHeaders`, and `caffeinate -dimsu`; no Docker container was started and no
service was stopped. H graph SHA is
`e1cce9681eb20861a0f7e53a24d19028a124dd86e204f5be26a5b7217aeb3871`; A reference graph SHA is
`178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0` (15-edge difference despite
identical input/config). The direct-Java SpeedTest equivalent used `-p md -n 4 -i 3 -0`: H
`227/408` successful, `181` unexpected; A `233/408` successful, `175` unexpected. H worker/total
means were `56.0/98.8 ms`; A `53.8/97.3 ms`; therefore performance is `INCONCLUSIVE`, and the
non-zero expected-result mismatches are explicitly recorded. Full hashes, logs, and commands are
in `H-ONLY-MANIFEST.json` and `H-ONLY-RESULT.md`.

Do not start Docker without first reporting the exact container/command, expected memory, service
impact, and stop conditions to the user. Do not reuse the historical H+F performance matrix as
H-only evidence. Phase 4 is limited to historical Seoul evidence review; Phase 5 drafts are ready
at `H-ONLY-ISSUE-DRAFT.md` and `H-ONLY-PR-DRAFT.md`. No commit, issue, or PR has been created.

**Baden H-only HTTP P6 중단 시점 인수인계 (2026-09-17):** 사용자의 중단 요청으로
native open-loop 실험을 종료했다. 고정 graph/corpus와 위의 A/H 소스 역할을 사용했으며,
실제 legacy JAR는 A=`otp-h-only.jar`, H=`otp-baseline.jar`였다. raw `pair06-H`는
semantic A 원본이며, pair02–05의 4개 pair(각 20,000건)는 완주했지만
host pageout/swap-in이 모두 발생해 no-pressure gate는 0/4이다. `pair06-H`는
17,602/20,000건에서 중단됐고 `pair06-A` 및 pair01 교정 pair는 실행하지 않았다.
중단 직전 partial에도 swap 사용과 pageout 이벤트가 기록됐다. 따라서 결과는
`ENVIRONMENT_CONTAMINATED / DIAGNOSTIC_ONLY`이며 H의 PR 성능 우위로 주장하지 않는다.

상세 결과는 [H-ONLY-BADEN-HTTP-NO-PRESSURE-P6-FORMAL-RESULT.md](H-ONLY-BADEN-HTTP-NO-PRESSURE-P6-FORMAL-RESULT.md),
실행 원장은 [H-ONLY-BADEN-HTTP-NO-PRESSURE-RUN-LEDGER.md](H-ONLY-BADEN-HTTP-NO-PRESSURE-RUN-LEDGER.md),
재현 매니페스트는 [H-ONLY-BADEN-HTTP-NO-PRESSURE-MANIFEST.json](H-ONLY-BADEN-HTTP-NO-PRESSURE-MANIFEST.json)이다.
raw는 `.artifacts/otp-baden-p6/`에 `archiveMode=partial-stop`으로 보존했고
`SHA256SUMS.txt` 검증을 완료했다. P6의 미완료 항목은 6-pair 무압력 재실행이지만,
현재 지시대로 추가 측정은 중단된 상태다.
