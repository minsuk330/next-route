# Phase 3 handoff

> **증거 상태 재분류(2026-09-09):** 이 문서의 c1·c2·c4 formal-1/2 결과는
> `--require-warmup-stability`를 켜지 않은 예비 표본이다. 이전 문장에서 `정식
> pair`라고 부른 표현은 workload 규모를 뜻할 뿐 최종 gate 통과를 뜻하지 않는다.
> 안정 예열 3개 window·5%·600초 제한을 켠 A/H pair를 새로 측정하기 전까지
> Phase 3과 전체 목표 상태는 `INCONCLUSIVE`다.

- Phase 상태: PARTIAL (E2 선별 5쌍, F 포함 후보 선별 2쌍; 최종 검증 미완료)
- 전체 목표 상태: INCONCLUSIVE
- 작업 위치: `/Users/wa/golbob/개인/OpenTripPlanner`

> 2026-09-09 재개 지시: [NEXT-AGENT.md](NEXT-AGENT.md)를 우선 적용한다. E2 fallback 복사 누락을 먼저 재현·수정하고 F를 분리한다. 아래는 누적 실험 기록이며, 최신 후보 전체 응답 동등성·안정 예열·최종 표본수·soak 완료의 증거가 아니다.

2026-09-09 재개 작업에서 E2 fallback 승격의 stop-major/trip-major 인덱스 복사 누락을 수정하고 2-trip 회귀 테스트를 추가했다. `/opt/homebrew/bin/mvn` 3.9.16으로 E2 7개, 영향 모듈 전체 8,225개(실패 0, skip 20), Python 측정기 33개와 diff check를 통과했고 shaded JAR 빌드도 성공했다. 측정기 `run_isolated.py`에는 warmup 안정화 옵션을 명시적으로 노출했다.

F 안전성 경계도 추가했다. `DestinationArrivalPaths`의 기본 생성자와 일반 `PathConfig` overload는 immutable dummy를 사용하고, built-in scalar-only comparator 경로만 `scalarOnlyComparator=true`로 mutable dummy를 재사용한다. F-only 분리 JAR SHA는 `926060f030f198d322b4a7328232160a73b3d139d7916a93dc1d82ca2c3ef030`이고 Raptor 954개 테스트(실패 0, skip 2)를 통과했다.

정합성 gate를 새로 실행한 결과, A/A 반복과 A/F-only 비교가 각각 14건(성공 8, 의도된 routing error 6), validation error 0, mismatch 0으로 통과했다. 단, F-only 선별 성능은 c1에서 p50/p95/RSS가 각각 17.05%/48.56%/19.48% 개선된 반면 c4에서는 -23.88%/-35.73%/-0.09%로 악화·정체됐다. 이는 각 동시성 1쌍, OD당 20건인 screening이므로 최종 판정이 아니다.

c4 후보→원본 BA 재측정도 후보 p50/p95/RSS `766.145ms / 1,059.246ms / 4,017,811,456B`, 원본 `738.950ms / 1,010.155ms / 4,030,853,120B`로 후보 개선률 `-3.68% / -4.86% / +0.32%`였다. 첫 AB 쌍의 회귀보다 작지만 5% 개선은 재현되지 않았다. 두 실행 모두 높은 host swap 상태에서 수행됐으므로 최종 원인·판정으로 확대하지 않는다.

안정화 게이트를 적용한 추가 c4 실행 결과도 기록한다. 원본 A/A 2회는 각각 400/400 성공, p50/p95 `742.627/1,064.225ms`, `814.856/1,221.365ms`였고 peak RSS는 `4,079,099,904B`, `4,064,522,240B`였다. 같은 A→F 인접 쌍은 A `764.251/1,691.704ms`, F-only `955.250/1,808.335ms`, peak RSS `4,062,687,232B/4,059,418,624B`였다. F→A BA 실행은 600초 안정화 timeout으로 measured 단계에 진입하지 못했다. 이 결과는 F 회귀를 확정하지 않지만, c4 성능 PASS 조건을 충족하지 못한다.

동일 조건의 진단 JFR에서는 F-only의 `Path` allocation sample이 21.01%에서 0.01%로 줄었지만 `int[]`는 35.86%에서 39.63%, `TransitStopArrival`은 18.26%에서 23.89%로 남았다. F-only CPU sample에는 `ParetoSet.qualify`와 scalar comparator가 상위에 관찰됐다. 다음 가설은 StopArrivalFactoryC1 계열의 생성·탈락 경로와 comparator 비용을 각각 측정하는 것이다.

추가로 `int[]` sample의 대부분은 A `34,850MB`, F `29,992MB`가 모두 `TripPatternForDates.<init>`에서 발생했다. 현재 E2는 departure만 compact하게 만들고 arrivalTimes는 랜덤 접근을 위해 int 배열로 유지한다. 따라서 다음 후보는 이 배열을 바로 바꾸지 말고, 요청마다 생성되는지·날짜 offset과 frequency 검색에서 어떤 접근 계약을 요구하는지부터 계측해야 한다.

## 프로파일 근거

후보 D와 원본 A를 동일한 고정 graph·JVM 옵션으로 c4 workload에 기동하고 JFR `settings=profile`
을 수집했다. workload는 OD별 warmup 25회, measured 100회였다. JFR에는 프로파일링 오버헤드가
있으므로 지연값은 최종 성능값으로 사용하지 않고 hotspot 비교에만 사용한다.

- 후보 JFR: `/private/tmp/otp-candidate-phase3-c4.jfr`, duration 213초
- 원본 JFR: `/private/tmp/otp-baseline-phase3-c4.jfr`, duration 240초
- 후보 profile raw: `/private/tmp/otp-candidate-profile-c4.jsonl`
- 후보/원본 JFR 모두 `jdk.ExecutionSample`, `jdk.ObjectAllocationSample`, GC 이벤트를 포함한다.

관찰:

- 양쪽 CPU 상위는 Raptor의 `MultiCriteriaRoutingStrategy.alightOnlyRegularTransferExist`와
  Pareto/boarding 탐색이다. 변경 메서드가 주요 hotspot이라는 근거는 없다.
- 후보에서 `TripPatternForDate.newWithFilteredTripTimes`는 CPU sample 약 1.66%였고,
  `TripPatternForDates.<init>`가 약 4.34%였다.
- 양쪽 allocation sample에서 `int[]`가 약 38.4%로 가장 컸다. 후보 C가 만든 배열이 실제
  RSS를 줄였다고 볼 증거는 없다.
- 후보 JFR summary의 GarbageCollection 이벤트 수는 894, 원본은 665였다. 기록 길이와
  JVM 예열 상태·워크로드 포함 구간이 달라 직접 비교할 수 없다. 기존 메모리 CSV는
  `docker stats` 컨테이너 메모리이며 프로세스 RSS가 아니다. GC/RSS 악화 근거로 사용하지 않는다.

## 판단

측정 구간의 `int[]` 할당을 JFR JSON으로 시간 필터링한 결과, 전체 allocation sample의
약 22.6%가 `int[]`였고 그 중 약 94%가 `TripPatternForDates.<init>`에서 발생했다.
따라서 후보 E를 추가했다.

- E: 도착 시각은 `int[]`로 유지하고, 출발 시각은 도착 대비 dwell delta를 `short[]`로 저장한다.
- 음수 또는 `Short.MAX_VALUE` 초과 delta가 실제로 발견되면 해당 pattern만 `int[]`로 승격한다.
- 정상 경로는 단일 순회로 기록하며, 경계·fallback 테스트를 추가했다.

정합성 corpus는 원본 A와 E에서 14/14 요청이 동일했고, 오류 6건도 동일한 의도된
`NO_STOPS_IN_RANGE`, `OUTSIDE_BOUNDS`, `WALKING_BETTER_THAN_TRANSIT`였다.
현재 측정기의 새 JVM screening 결과는 다음과 같다. 각 행은 독립 실행 한 쌍뿐이므로 최종
판정이 아니다.

| concurrency | A p50/p95 | E p50/p95 | A peak RSS | E peak RSS |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 486 / 784 ms | 479 / 771 ms | 3.61 GB | 2.55 GB |
| 2 | 723 / 1,133 ms | 513 / 634 ms | 4.00 GB | 3.67 GB |
| 4 | 761 / 1,096 ms | 804 / 1,302 ms | 4.02 GB | 4.02 GB |

이 결과는 c1/c2 개선과 c4 지연 회귀가 함께 나타났고, RSS는 c4에서 줄지 않았다.
후보 E의 두 번 순회를 단일 순회로 고친 E2도 c4에서 947 / 1,873 ms가 측정되어 아직
채택 근거가 없다. 따라서 E/E2는 `INCONCLUSIVE`로 보류하며, 원본 대비 5개 독립 실행 쌍과
soak 전에는 최종 후보로 선언하지 않는다.

이후 runner 옵션 보강 후 동일 조건으로 A/E2 screening을 다시 맞췄다.

| concurrency | A p50/p95 | E2 p50/p95 | A peak RSS | E2 peak RSS |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 541 / 957 ms | 436 / 634 ms | 3.38 GB | 2.72 GB |
| 2 | 666 / 983 ms | 735 / 1,272 ms | 3.99 GB | 3.88 GB |
| 4 | 1,020 / 2,176 ms | 947 / 1,873 ms | 4.01 GB | 4.01 GB |

c1/c4는 개선됐지만 c2가 악화됐고 모두 단일 실행 쌍이다. 따라서 E2를 반복 검증 대상으로
유지하되, 현재 상태를 VERIFIED 또는 NOT_MET으로 판정하지 않는다.

추가로 같은 runner와 동일 graph/JAR 조건에서 c1·c2·c4를 각각 한 쌍씩 재측정했다.
모든 실행은 80개 measured 요청 성공, `metricsStatus=OBSERVED`다. 원본 비교가 없는 run의 `comparison:null`은 correctness mismatch 0건을 의미하지 않는다.

| concurrency | A p50/p95 | E2 p50/p95 | A peak RSS | E2 peak RSS | 해석 |
| ---: | ---: | ---: | ---: | ---: | --- |
| 1 (repeat-2) | 593 / 1,121ms | 556 / 1,187ms | 3.18GB | 2.84GB | 메모리 개선, p95·GC 악화 |
| 2 (repeat-1) | 854 / 2,081ms | 629 / 1,507ms | 3.99GB | 3.84GB | 지연·메모리 개선 |
| 4 (repeat-2) | 1,155 / 2,144ms | 1,255 / 1,902ms | 3.76GB | 3.73GB | p50 악화, p95·GC 소폭 개선, RSS 유사 |

따라서 이전 c2 악화는 같은 조건에서 재현되지 않았고, c4도 “메모리 감소 후보”라고
부르기 어렵다. E2는 c1/c2에서 peak RSS를 낮추는 경향이 있지만 c4에서는 효과가 거의
없고 tail latency trade-off가 있다. 이 결과 역시 동시성별 두 쌍 수준이므로 채택 판정이
아니며, 다음은 동일 순서·동일 workload로 pair 수를 5개까지 늘리는 것이다.

네 번째 paired run까지 추가한 `compare_runs.py` 결과도 기록한다. 원본 A 대비 E2의 개선률은
`(A - E2) / A`이며, 음수는 E2가 느리거나 더 큰 RSS라는 뜻이다.

| concurrency | p50 개선률 중앙값 | p95 개선률 중앙값 | peak RSS 개선률 중앙값 | 상태 |
| ---: | ---: | ---: | ---: | --- |
| 1 | +8.46% | +7.04% | +13.46% | INCONCLUSIVE (4쌍) |
| 2 | +0.32% | -6.53% | +3.45% | INCONCLUSIVE (4쌍) |
| 4 | +4.10% | -8.20% | +0.02% | INCONCLUSIVE (4쌍) |

부트스트랩 95% 구간이 모두 0을 포함하고, c4 peak RSS 중앙값은 사실상 0이다. 따라서
E2를 전역 최적화로 채택할 근거는 아직 없다. c1의 RSS 절감은 가장 일관되지만, 이는
고동시성 서버 메모리 목표를 해결하지 못한다.

다섯 번째 paired run으로 최소 반복 수만 충족했다. OD당 20건 선별 측정이며 최종 OD당 100건·안정 예열 계약은 충족하지 않았다. 선별 통계는 다음과 같다.

| concurrency | p50 개선률 중앙값 (95% CI) | p95 개선률 중앙값 (95% CI) | peak RSS 개선률 중앙값 (95% CI) | 판정 |
| ---: | ---: | ---: | ---: | --- |
| 1 | +6.14% (-25.85, +19.34) | -0.66% (-11.93, +33.73) | **+12.04% (+10.66, +19.59)** | RSS만 목표 충족 |
| 2 | +7.56% (-20.99, +26.39) | -13.77% (-46.84, +27.59) | +2.97% (+0.03, +7.61) | RSS 목표 미달, p95 회귀 |
| 4 | +7.12% (-8.65, +28.57) | +11.27% (-129.03, +27.57) | +0.03% (-0.04, +0.72) | RSS 미달·불확실 |

E2의 정합성은 14/14 corpus에서 통과했지만, 전체 계약은 c1·c2·c4 모두에서 peak RSS와
p50/p95를 동시에 5% 이상 줄여야 한다. 따라서 E2는 **전역 기본값으로 채택하지 않는다**.
현재 working tree의 E2 코드는 후보 실험용으로 보존하며, 제품 branch에 병합할 때는 이
판정과 함께 되돌리거나 별도 c1 전용 실험으로 분리해야 한다.

## 후속 후보 F — `qualify()` dummy path 재사용

JFR의 다음 큰 요청 할당은 `Path.dummyPath` 약 22.05%였다. `DestinationArrivalPaths`는
request scope이고 `ParetoSet.qualify()`는 후보를 저장하지 않으므로, debug listener가 없는
정상 검색에서만 scalar 기준을 담은 mutable dummy를 재사용하는 후보 F를 추가했다. debug
모드에서는 기존 immutable `Path.dummyPath`를 유지해 listener가 객체를 보관해도 안전하다.

- Raptor 모듈 테스트: 954개 실행, 실패 0, skip 2.
- F 확장 corpus: 성공 8건 + 의도된 routing error 6건, validation error 0.
- A→F 정식 screening(각 80 measured 요청, 각 1쌍):

| concurrency | p50 개선률 | p95 개선률 | peak RSS 개선률 | 해석 |
| ---: | ---: | ---: | ---: | --- |
| 1 | +8.28% | +27.86% | +25.84% | 유망하지만 1쌍 |
| 2 | +14.61% | +35.23% | +11.82% | 유망하지만 1쌍 |
| 4 | +7.50% | +6.86% | +0.68% | 지연만 개선, RSS≈ |

이 값은 F가 E2 위에 적용된 후보와 원본 A의 단일 paired screening이다. 5쌍 gate를 충족하지
않았고, F 실행에는 E2도 포함되어 있으므로 F 단독의 최종 기여로 해석하지 않는다.

F 두 번째 독립 실행도 확보했다. 기존 A 독립 run을 맞춘 비동기 screening의 개선률은 다음과
같다.

| concurrency | p50 | p95 | peak RSS |
| ---: | ---: | ---: | ---: |
| 1 | +9.53% | +22.18% | +14.34% |
| 2 | +1.56% | +9.22% | +3.87% |
| 4 | +10.94% | +18.28% | +0.80% |

실행 시점이 다른 baseline을 사용했으므로 방향성 확인용으로만 기록하며, 최종 paired gate에는
A와 F를 같은 순서로 새로 묶은 실행만 사용한다.

두 번째 정식 A→F paired run 결과는 다음과 같다. 단일 쌍이므로 여전히 `INCONCLUSIVE`다.

| concurrency | p50 개선률 | p95 개선률 | peak RSS 개선률 |
| ---: | ---: | ---: | ---: |
| 1 | -3.01% | -0.64% | +12.81% |
| 2 | +2.76% | +20.51% | +8.13% |
| 4 | +10.88% | +28.79% | +0.33% |

c1 지연은 두 번째 쌍에서 재현되지 않았고, c2/c4 tail latency 개선은 유지됐다. 따라서 F는
고동시성 tail latency 후보로는 유망하지만, 모든 동시성의 p50/p95와 RSS를 함께 만족하는지는
아직 알 수 없다.

## 후보 G — 휴리스틱 선판정으로 StopArrival 생성 지연

F의 JFR에서 여전히 `TransitStopArrival` 할당이 크게 관찰됐기 때문에, 목적지 휴리스틱이
탈락시킬 후보를 객체로 만든 뒤 버리는 경로를 조사했다. 후보 G는 일반 검색에서 정류장·도착
시각·누적 소요시간·C1/C2를 primitive 값으로 먼저 판정하고, 통과한 경우에만
`TransitStopArrival` 또는 `TransferStopArrival`을 생성한다. 디버그 대상 정류장은 기존
객체 우선 경로를 유지해 거부된 arrival을 listener가 받을 수 있게 했다.

Raptor 전체 테스트 954개(실패 0, skip 2), 핵심 휴리스틱 테스트 3개, 14건 정합성 corpus
(성공 8 + 의도된 routing error 6, validation error 0, 원본 fingerprint mismatch 0)를
통과했다. 후보 JAR는 `/private/tmp/otp-stop-arrival-candidate.jar`, SHA-256은
`b52e7a3c9b2aa91d12a561fc6781c2b4eb39da0143a1acab662f19a1ed30f96d`다.

선별 측정은 동일 graph·JVM·OD에서 OD당 20건만 수행했으며, F 조합의 기존 screening과
실행 시점이 달라 방향성 참고로만 기록한다.

| concurrency | G p50/p95 | G peak RSS | 이전 F 조합 p50/p95 | 이전 F 조합 peak RSS | 해석 |
| ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 437.679 / 625.714ms | 2,302,312,448B | 532.917 / 842.937ms | 2,565,201,920B | 개선 방향 |
| 2 | 546.399 / 907.618ms | 3,883,143,168B | 535.152 / 658.234ms | 3,535,060,992B | p95·RSS 악화 |
| 4 | 773.436 / 1,086.290ms | 4,004,261,888B | 669.807 / 866.869ms | 3,996,241,920B | p50·p95 악화, RSS≈ |

따라서 G는 c1의 단발성 개선만으로 채택하지 않는다. c2/c4에서 지연 또는 RSS 목표를
충족하지 못했고 안정 예열·최종 5쌍 gate도 수행하지 않았다. 코드는 실험 후보로 보존하되
현재 `VERIFIED` 후보로 올리지 않는다. 다음 후보는 요청마다 생성되는 `TripPatternForDates`
의 `arrivalTimes` 배열 비용을 날짜 offset·frequency·random access 계약부터 계측하는
방향으로 전환한다. G 결과 경로는 `/private/tmp/otp-stop-arrival-c{1,2,4}-screen*`,
corpus는 `/private/tmp/otp-stop-arrival-corpus-2`다.

측정 호스트는 이 구간에 swap 사용량과 메모리 압박이 높았으므로, 추가 pair의 환경 manifest와
`vm_stat`/swap 값을 함께 보존한다. 압박이 해소되지 않은 run은 방향성 참고로만 사용한다.

GC 별도 screening도 수행했다. G1 기본(문자열 중복 제거 on)을 기준으로 G1 중복 제거 off와
ZGC를 비교했으며, ZGC는 c1에서 GC pause는 약 5 ms로 낮췄지만 p50/p95 771/1,307 ms,
peak RSS 3.91 GB로 G1 기본보다 불리했다. G1 중복 제거 off도 p50/p95 584/1,182 ms로
기준보다 악화했다. 이 결과는 JVM 운영 튜닝 참고값이며 코드 최적화 성과에 포함하지 않는다.

## 다음 단계

아래는 당시의 실험 방향 기록이다. 실제 재개 순서는 [NEXT-AGENT.md](NEXT-AGENT.md)의 작업 1~6이 우선이며, 정확성 보완 전 추가 장시간 성능 측정을 하지 않는다.

1. E2의 전역 적용은 중단한다. c1 RSS 절감만 별도 feature flag/실험으로 유지할 필요가
   있는지는 운영 메모리 목표가 실제로 c1에 한정되는지 확인한 뒤 결정한다.
2. F를 원본 A와 c1/c2/c4에서 AB/BA 순서를 교차해 최소 5개 독립 JVM 쌍으로 반복한다.
   각 쌍은 동일한 14건 정합성 gate를 통과해야 한다. 현재 F 결과는 screening일 뿐이다.
3. F가 최종 gate를 통과하면 30분 c4 soak로 넘어가고, 하나라도 미달하면 NOT_MET으로
   기록한 뒤 `StopArrivalFactoryC1` 할당(약 19.18%)을 다음 후보로 조사한다.
4. 전역 캐시·ThreadLocal·검색 범위 축소는 무효화·동시성 증명 없이 추가하지 않는다. 새
   후보도 원본 A를 기준으로 평가하며 E2/F 결과를 baseline으로 재사용하지 않는다.

## 후보 H — `TripPatternForDates` 요청별 시간 배열 제거

JFR에서 양쪽 `int[]` allocation sample의 대부분이 `TripPatternForDates.<init>`에 집중됐고,
E2는 departure만 compact하게 만들었기 때문에 arrival 배열 비용이 남아 있었다. H는 F-only
분리 소스(`/private/tmp/otp-f-only`)에서 요청별 `nStops * nTrips` arrival/departure 배열을
없애고, 글로벌 정렬 trip index마다 immutable `TripTimes` 참조와 서비스 날짜 offset을 저장한다.
`arrivalTime(stop, trip)`과 `departureTime(stop, trip)`은 참조된 `TripTimes` 값에 offset을
더해 반환한다. `TripTimes`의 Scheduled/RealTime 구현은 final 상태를 가진 immutable snapshot이며
실시간 갱신은 새 객체를 만들어 timetable에 교체하므로, 요청 중 참조가 변하지 않는 계약을
확인했다. frequency 경로와 랜덤 접근 API는 그대로 유지한다.

검증:

- H 대상 테스트(TripPatternForDates/TripPatternForDate): 6개 통과.
- 영향 모듈 전체 Maven test: 8,225개 실행, 실패·오류 0, skip 20.
- H shaded JAR: `/private/tmp/otp-trip-times-candidate.jar`, SHA-256
  `28eb50f341fcbbe712ec2f10cdc76b7eb977a3b3d966ba825c4396b138849236`.
- correctness corpus(`/private/tmp/otp-h-corpus`): 14건(성공 8, 의도된 routing error 6),
  validation error 0, 원본 A fingerprint mismatch 0.

F-only 기준과 OD당 10건의 짧은 비paired screening을 수행했다. 실행 순서와 host pressure가
다르므로 방향성만 기록하며 최종 gate에는 사용하지 않는다.

| concurrency | F p50/p95 | H p50/p95 | F peak RSS | H peak RSS | H 개선률(p50/p95/RSS) |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 496.025 / 761.097ms | 359.262 / 460.824ms | 2,959,753,216B | 1,903,935,488B | +27.57% / +39.45% / +35.67% |
| 2 | 561.290 / 1,064.983ms | 556.842 / 732.968ms | 3,613,499,392B | 1,926,496,256B | +0.79% / +31.18% / +46.69% |
| 4 | 794.772 / 1,085.081ms | 506.979 / 685.017ms | 4,023,721,984B | 1,976,221,696B | +36.21% / +36.87% / +50.89% |

모든 동시성에서 메모리·GC·지연이 낮아지는 방향이지만, 단일 비paired screening은 원본
변동성을 제거하지 못한다. H는 `VERIFIED`가 아닌 `INCONCLUSIVE` 후보로 유지한다. 다음은
동일 graph/JVM/seed의 H 대 F AB·BA paired 5쌍이며, OD당 100건·안정 예열·c4 soak 전에는
main branch에 병합하지 않는다.

짧은 paired pilot도 추가했다(OD당 10건, 동일 workload manifest). c1에서는 H의 p50/p95/RSS
개선률이 각각 `+19.64% / +24.39% / +36.04%`였다. c2에서는 `-1.19% / +10.98% /
+51.66%`로 p50이 소폭 악화됐다. 초기 c4 F 기준은 요청 40건은 성공했지만 RSS collector
종료 후 coverage가 끊겼다. 측정기를 보강해 collector 종료코드와 컨테이너 상태를 기록한 뒤
c4를 재실행했고, F/H 모두 `metricsStatus=OBSERVED`, 40/40 성공, `OOMKilled=false`였다.
보강된 c4 개선률은 p50/p95/RSS `+24.81% / -7.69% / +46.88%`로 p95 회귀가 확인됐다.
실행 순서를 뒤집은 BA pilot에서는 `+49.34% / +60.45% / +54.88%`로 나타났다. AB/BA 간
p95 차이가 크므로 host pressure와 실행 순서 변동성이 지배적일 수 있다. 두 pilot 모두
RSS `OBSERVED`, 40/40 성공, `OOMKilled=false`였다. 따라서 H의 핵심 근거는 현재
메모리·GC 절감 방향이며, latency 전역 개선은 아직 입증되지 않았다.

같은 방식으로 c1·c2 BA pilot도 추가했다(각 OD당 10건). c1(F→H) 개선률은
p50/p95/RSS `+15.93% / +13.74% / +25.73%`, c2(F→H)는
`+18.60% / +24.83% / +54.55%`였다. c1·c2·c4의 AB/BA pilot은 모두 RSS
`OBSERVED`, HTTP 40/40 성공, `OOMKilled=false`였지만 동시성별 독립 pair가 2개뿐이다.
따라서 실행 순서를 바꿔도 H의 RSS 절감 방향은 유지되는 보조 근거로 기록하되, 정식
5쌍·OD당 100건·안정 예열·c4 soak 전까지 H 상태는 `INCONCLUSIVE`로 유지한다.

안정 예열 gate 미적용 예비 표본의 첫 c1 pair(F→H)를 실행했다. 각 OD당 warmup 100건·측정 100건으로 총
400건을 측정했고, 두 run 모두 HTTP 400/400 성공·RSS `OBSERVED`·`OOMKilled=false`였다.
F의 p50/p95/peak RSS는 `473.229 / 862.154ms / 3,530,203,136B`, H는
`396.469 / 520.462ms / 1,833,783,296B`였으며 개선률은
`+16.22% / +39.63% / +48.05%`다. 정식 pair 하나의 결과이므로 H는 여전히
`INCONCLUSIVE`이며, 안정 gate를 켠 A/H 재측정 전에는 최종 근거가 아니다.

c1 두 번째 예비 pair(H→F)도 완료했다. H의 p50/p95/peak RSS는
`390.895 / 526.652ms / 1,992,622,080B`, F는 `522.462 / 785.259ms /
3,123,937,280B`였으며 H 개선률은 `+25.18% / +32.93% / +36.21%`다. c1에서
AB/BA 두 독립 pair 모두 지연과 RSS가 개선됐지만 표본은 아직 동시성별 5쌍 계약에
미달하므로 H 상태는 `INCONCLUSIVE`로 유지한다.

c2 예비 pair 1(F→H)도 완료했다. F의 p50/p95/peak RSS는
`559.893 / 812.585ms / 4,007,616,512B`, H는 `429.588 / 573.718ms /
1,974,607,872B`였고 개선률은 `+23.27% / +29.40% / +50.73%`다. measured
400/400 성공·RSS `OBSERVED`·`OOMKilled=false`이며, 역순 pair 전까지 c2도
`INCONCLUSIVE`다.

c2 예비 pair 2(H→F)도 완료했다. H의 p50/p95/peak RSS는
`419.081 / 567.590ms / 2,167,132,160B`, F는 `577.785 / 1,122.741ms /
4,016,586,752B`였고 H 개선률은 `+27.47% / +49.45% / +46.05%`다. c2 AB/BA
두 쌍 모두 지연·RSS가 개선됐지만, 동시성별 최종 5쌍·c4·soak가 남았으므로
H 상태는 계속 `INCONCLUSIVE`다.

c4 예비 pair 1(F→H)도 완료했다. F의 p50/p95/peak RSS는
`779.764 / 1,065.975ms / 4,073,365,504B`, H는 `450.514 / 711.055ms /
1,961,013,248B`였고 개선률은 `+42.22% / +33.30% / +51.86%`다. measured
400/400 성공·RSS `OBSERVED`·`OOMKilled=false`였으며, 역순 c4 pair와 soak 전까지
최종 상태는 `INCONCLUSIVE`다.

c4 예비 pair 2(H→F)도 완료했다. H의 p50/p95/peak RSS는
`451.034 / 611.647ms / 2,046,291,968B`, F는 `825.571 / 1,286.972ms /
4,044,533,760B`였고 H 개선률은 `+45.37% / +52.47% / +49.41%`다. c4 AB/BA
두 쌍 모두 지연·RSS가 개선됐고 measured 400/400 성공·RSS `OBSERVED`·
`OOMKilled=false`였다. 세 동시성 모두 AB/BA 2쌍을 확보했지만 최종 5쌍과
30분 soak 전에는 `INCONCLUSIVE`를 유지한다.

F c4 장기 run(`/private/tmp/otp-f-c4-soak`)은 measured 10,000건, 실제
`2,580.686초`(약 43분), HTTP 오류 0건·`OOMKilled=false`였다. peak RSS는
`4,137,394,176B`였고 5분 창에서 약 3.82→3.85GiB로 안정화됐다. 다만 RSS
sampler 최대 간격 `0.625초`가 0.5초 계약을 초과해 요약 상태는 `INCOMPLETE`다.
장기 보조 자료로만 기록하며 최종 soak PASS로 취급하지 않는다. 다음 H soak는
안정 예열 gate와 실제 measured 1,800초 이상을 함께 활성화한다.

H c4 soak(`/private/tmp/otp-h-c4-soak`)는 안정 예열 gate를 켠 채 measured
`17,157.567초`(약 4시간 46분)까지 실행되어 시간 기준은 충족했다. 그러나
20,000건 중 15,737건만 성공하고 4,263건이 컨테이너 정리 시점에 transport error로
끝났으며 collector coverage가 끊겨 상태는 `INCOMPLETE`다. `OOMKilled=false`,
peak RSS `2,086,002,688B`, 성공 지연 p50/p95 `431.563/591.565ms`로 장기 RSS는
약 1.94GiB에 안정적이었다. 누수·OOM 부재를 보는 보조 자료일 뿐 최종 soak PASS가
아니다. 완전한 A/H `OBSERVED` soak를 새로 실행해야 한다.

안정 예열을 켠 A/H c1 gate 재시도에서 A `/private/tmp/otp-a-c1-gate-2`는
warmup 안정화·400/400 성공·RSS `OBSERVED`였지만, H
`/private/tmp/otp-h-c1-gate-2`는 sampler 최대 간격 `1.137초`로
`INCOMPLETE`다. 따라서 latency 결과가 있더라도 최종 A/H pair로 세지 않는다.
호스트 swap 압박을 낮추고 두 run 모두 sampler 계약을 만족하는 재측정이 필요하다.
