# 측정기 보완 실행 기록

> **재분류 주의(2026-09-09):** 아래에서 `정식 pair`라고 기록했던 c1·c2·c4
> formal-1/2 12개 run은 `--require-warmup-stability` 없이 실행됐다. 따라서 모두
> 예비 표본으로 재분류하며 최종 5쌍 gate의 독립 pair 수에는 산입하지 않는다. 특히
> warmup `stable=false`가 확인된 run은 F c2 pair 2, F c4 pair 1/2, H c1 pair 1,
> H c4 pair 1/2다. 안정 예열 3개 window·5%·600초 제한을 실제로 통과한 A/H
> pair만 최종 근거로 승격한다.

> 과거 재개 기록 (2026-09-09): 아래는 누적 실행 이력이다. 당시의 E2/F 재개
> 순서는 완료 전 지시였으며, 2026-09-11 H+F 최종 gate 완료 후 재실행 대상이 아니다.
> E2 5쌍은 OD당 20건의 선별 측정이며 최종 검증이 아니고, `comparison:null`인
> run은 응답 성공만 확인한 자료다. 최신 F가 B+C+E2+F 조합이었다는 기록도
> 최종 H+F 후보와 분리해 해석한다.

상태: COMPLETE / 최종 H+F 후보 VERIFIED / 이전 B/C/G 포함 조합은 c1 개별 OD 회귀로 NOT_MET

> 최종 판정(2026-09-11): 현재 OpenTripPlanner 작업트리에는 H+F만 남아 있다. H+F는
> c1 10쌍, c2/c4 각 5쌍 paired gate, correctness, c4 30분 soak, 전체 회귀를
> 통과했다. 아래의 이전 `IN_PROGRESS`, `TARGET_MET`, `B/C/F/H` 표기는 당시
> 실험 이력이며 최종 후보 판정을 뒤집지 않는다.

## 2026-09-09 재개 최신 gate

- 원본 A/A 반복 corpus: `/private/tmp/otp-a-a-corpus-new`, 14건(성공 8 + 의도된 오류 6), validation error 0.
- F-only corpus: `/private/tmp/otp-f-only-corpus-new2`, 원본 A raw와 비교 mismatch 0.
- F-only JAR: `/private/tmp/otp-f-only/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`, SHA-256 `926060f030f198d322b4a7328232160a73b3d139d7916a93dc1d82ca2c3ef030`.
- F-only 선별(기존 A 1쌍, OD당 20건): c1은 p50/p95/RSS +17.05%/+48.56%/+19.48%, c2는 +4.86%/+0.69%/+0.36%, c4는 -23.88%/-35.73%/-0.09%. c4 회귀 때문에 최종 후보로 확정하지 않는다.
- c4 BA 재측정(후보→원본)은 F-only `766.145/1,059.246ms, 4,017,811,456B`, A `738.950/1,010.155ms, 4,030,853,120B`로 개선률 `-3.68%/-4.86%/+0.32%`였다. 높은 host swap 상태였고, c4 5% 개선은 재현되지 않았다.
- 안정화 c4 A/A 2회는 p50/p95 `742.627/1,064.225ms`, `814.856/1,221.365ms`로 원본 자체 변동이 컸다. A→F 인접 쌍은 A `764.251/1,691.704ms`, F-only `955.250/1,808.335ms`, RSS `4,062,687,232B/4,059,418,624B`였고, F→A BA는 F warmup 안정화 timeout으로 중단됐다.
- 진단 JFR(`/private/tmp/otp-jfr-a-new`, `/private/tmp/otp-jfr-f-new`)에서 F-only는 `Path` allocation sample을 21.01%에서 0.01%로 제거했지만 `int[]` 35.86%→39.63%, `TransitStopArrival` 18.26%→23.89%가 남았다. F CPU sample에는 `ParetoSet.qualify` 및 scalar comparator가 상위에 나타났다. JFR은 성능 합격 근거가 아니라 다음 가설 선택 근거다.
- `int[]` allocation sample의 대부분은 `TripPatternForDates.<init>`에서 발생했다(A 34,850MB, F 29,992MB 샘플 가중치). E2가 줄인 것은 departure 배열이며, 남은 큰 비용은 `arrivalTimes`다. 다음 작업에서는 날짜 offset·frequency·random access 계약을 확인한 뒤에만 compact/cache 후보를 만든다.
- 결론: 정확성은 통과했지만 최종 성능 계약(각 c1/c2/c4 5쌍, OD당 100건, 안정 예열, c4 30분 soak)은 미완료이며 상태는 `INCONCLUSIVE`다.

## 역할과 범위

- Luna: `compare_graphql.py`, `test_compare_graphql.py` — 응답 검증·raw 보존·정합성 query·예열 gate.
- 메인: `collect_process_memory.py`, `run_isolated.py`, `summarize_run.py` 및 대응 테스트·문서·실제 실행.
- Java 최적화 후보와 기존 사용자 변경은 유지. graph/GTFS/bbox/검색 옵션을 축소하지 않는다.
- 빌드·프로파일을 성능 측정과 동시에 실행하지 않는다. 기존 Postgres/Redis는 건드리지 않는다.

## 이번 실행 순서와 예산

1. 수집기 smoke: 원본 A, 기존 4개 OD 각 1회. 성능 근거 아님.
2. 확장 정합성 smoke: 원본 A, 실제 GTFS GraphQL schema와 응답 필드 검증.
3. A/A: c1 독립 JVM 2회, 각 최소 warmup 100회 + 3개 안정화 window(40개/window),
   예열 최대 600초, measured 20회/OD. 실행당 시작 최대 180초 + 예열 최대 600초 + 측정 구간.
4. A/A 정합성 또는 예열 gate 실패 시 해당 원인을 먼저 조사한다. 실패한 값을 5쌍 성능 비교로 밀어 넣지 않는다.
5. 선행 gate 통과 후 A/D 선별. 최종 5쌍×c1/2/4와 soak는 Phase 4 진입 조건 충족 후 별도 실행한다.

산출물 루트: `/private/tmp/otp-verified-harness-jwkO7u`.

## 수집기 smoke

- `collector-smoke`: 검색 4/4 성공, RSS sample 29개, median 간격 약 105.5ms.
- warmup 0으로 시험하니 첫 sample보다 검색 시작이 먼저여서 idle RSS가 없었다.
  이를 INCOMPLETE로 감지했으며 runner에 첫 sample 수신 대기를 추가했다.
- 메모리: `/proc/1/status` VmRSS를 bytes로 변환. Java PID인지 검증한다.
- cgroup v2 current/peak/anon/file은 별도 필드. lifetime peak를 검색 RSS로 사용하지 않는다.
- GC: JVM UTC timestamp 로그와 measured start/end marker를 대조한다.
  pause 횟수는 GC cycle 횟수와 구분한다. after-pause heap은 JVM 로그의 반올림 값이다.
- 실제 100ms보다 긴 sample 간격과 누락은 숨기지 않는다. 관측 peak이지 정확한 최대 RSS가 아니다.

## 재현 도구

OTP 저장소 `test/performance/seoul-metro/`:

- `run_isolated.py`: 실행별 새 JVM, JAR/graph hash, 환경·GC 로그, 측정 스크립트 사본/hash 보존.
- `collect_process_memory.py`: 단일 지속 Docker exec로 process RSS와 container 메모리 수집.
- `summarize_run.py RUN_DIRECTORY`: measured 구간 메모리·GC 재집계. marker가 없으면 INCOMPLETE.

이 기록은 Phase 1 전체 완료나 최적화 성공을 뜻하지 않는다. 확장 corpus와 A/A 결과는 아래에 추가한다.

## 실제 A/A 결과

- 기존 4개 OD의 확장 정합성: 독립 JVM A1/A2 각 8건, 총 16건 성공. 모든 요청의 전체
  itinerary node(route/trip/stop ID, 시간, geometry 포함) 비교 불일치 0건.
- c1 performance A/A: 독립 JVM, 각각 measured 80건 성공, 단순 fingerprint 불일치 0건.
  A1은 warmup 180건, A2는 260건 후 안정화했다.

| 지표 | A1 | A2 |
| --- | ---: | ---: |
| p50 | 468.992ms | 503.123ms |
| p95 | 750.277ms | 814.358ms |
| 관측 peak process RSS | 3,284,844,544B | 3,312,099,328B |
| RSS sample 수 | 350 | 373 |
| 최대 sample gap | 175.3ms | 151.3ms |

동일 원본의 p50 약 7.3%, p95 약 8.5% 차이: 작은 개선폭은 단일 A/D 비교로 확정할 수 없다.
RSS는 약 0.83% 차이였다. 이는 최종 5쌍 신뢰구간 검증의 대체물이 아니다.

## 협업 검토 반영

- Luna 초안의 Transmodel 필드 혼용과 부분 응답 예외 유실을 메인 검토에서 발견·수정.
- Luna가 수집기 검토에서 지적한 before/after coverage 및 sample gap 누락을 보완.
  최대 gap 500ms는 수집기 품질 판정용 초기값이며 성능 합격 5% 기준을 변경하지 않는다.
- runner가 measured end 이후 실제 RSS sample을 확인하고 cleanup/collector/metrics 상태를
  environment.json에 남기도록 보완. 기존 A/A 실행에는 이 추가 cleanup 기록이 없으므로
  해당 run은 raw coverage를 별도로 재검증했다.
- Luna는 확장 corpus 작성 중 사용량 제한으로 중단. 남긴 코드는 메인이 인수했으며
  정상적인 no-route의 baseline 비교 오류와 manifest tuple/list 불일치를 수정했다.

## 확장 corpus 원본 발견 및 동등성

- `a1-corpus`: 최초 원본 발견 run. 성공 8건 + routing_error 6건이며 기대값 검증 실패를 그대로 보존.
- 원본에서 발견한 실제 의미: walk-short는 165초 WALK itinerary와
  WALKING_BETTER_THAN_TRANSIT가 함께 반환됨; boundary-east는 NO_STOPS_IN_RANGE와 빈 결과;
  outside-graph는 OUTSIDE_BOUNDS와 빈 결과.
- GTFS schema RoutingErrorCode와 원본 코드의 의미를 확인한 뒤, 후보 실행 전에
  expectationVersion=2로 고정. 에러를 모두 허용하는 것이 아니라 해당 case의 정확한 코드·결과만 허용.
- `a1-corpus-v2`, `a2-corpus-v2`, `d-corpus-v2`: 각각 14건 검증 통과.
  A/A 및 A/D의 전체 node·오류 코드 비교 불일치 0건. 세 run 모두 exitCode 0,
  metricsStatus OBSERVED, collectorCoveredEnd true, cleanupErrors 없음.
- 경계 사례는 성공 경로가 아니라 경계의 정류장 접근 불가 동작을 검증한다.
  별도의 '경계에서 성공하는 대중교통' 대표성까지 확보했다고 주장하지 않는다.

## Measurement v2 c1 선별 결과

측정 정의 v2(`elapsedMs`는 HTTP response body read 완료까지, JSON 처리시간은
`clientProcessingMs`)로 새 JVM을 실행했다. A1/A2/D1 모두 80 measured 요청 성공,
RSS/GC 수집 상태 `OBSERVED`, measured end coverage와 cleanup 성공, correctness mismatch 0건이다.

| run | p50 | p95 | observed peak RSS |
| --- | ---: | ---: | ---: |
| A1 | 556.267ms | 842.318ms | 별도 metrics.json |
| A2 | 417.095ms | 513.892ms | 별도 metrics.json |
| D1 | 504.538ms | 675.686ms | 별도 metrics.json |

A2 대 D1 한 쌍의 paired improvement는 p50 -20.96%, p95 -31.48%, RSS -0.43%로
후보가 느렸다. 독립 pair가 1개이므로 통계 상태는 `INCONCLUSIVE`이며 최종 기각이나
채택이 아니다. A1/A2 p50 자체도 33% 이상 차이가 나므로 추가 pair 없이는 작은 개선을
주장할 수 없다. c2/c4, 최소 5쌍, 30분 soak는 아직 실행하지 않았다.

Maven 경계 테스트는 총 11개 통과했다. 첫 두 번의 fixture 컴파일 오류(제네릭 타입,
반복 정류장 시간 배열)를 수정한 뒤 최종 `BUILD SUCCESS`를 확인했다.

## 2026-09-09 우선순위 실험

JFR `ObjectAllocationSample`을 measured 구간으로 시간 필터링한 결과, `int[]` allocation
sample의 약 94%가 `TripPatternForDates.<init>`에서 발생했다. 이 결과에 따라 출발시각을
도착시각 대비 `short` dwell delta로 저장하고 범위를 벗어날 때만 `int[]`로 승격하는 후보
E/E2를 구현했다. 원본·후보 확장 corpus는 14/14 동일했고 mismatch는 0이었다.

현재 측정기 screening(각 동시성 한 쌍)은 다음과 같다.

| concurrency | A p50/p95 | E p50/p95 | A peak RSS | E peak RSS |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 486 / 784ms | 479 / 771ms | 3.61GB | 2.55GB |
| 2 | 723 / 1,133ms | 513 / 634ms | 4.00GB | 3.67GB |
| 4 | 761 / 1,096ms | 804 / 1,302ms | 4.02GB | 4.02GB |

정상 경로의 두 번째 순회를 제거한 E2도 c4에서 947 / 1,873ms로 측정됐다. c4에서 RSS가
줄지 않고 실행 간 변동도 크므로 E/E2는 `INCONCLUSIVE`이며 최종 후보가 아니다. 최소 5개
독립 JVM 쌍과 soak를 생략하지 않는다.

GC는 코드 후보와 분리해 원본 A로 screening했다. G1 기본 대비 문자열 중복 제거 off는
c1 p50/p95 584 / 1,182ms, ZGC는 771 / 1,307ms였고 ZGC pause만 약 5ms로 낮았다.
ZGC peak RSS는 약 3.91GB로 더 높았다. 단일 실행 결과이므로 운영 기본 GC를 바꾸지 않으며,
JVM 튜닝 값은 코드 최적화 합격률에 포함하지 않는다.

runner 옵션 보강 후 같은 조건으로 원본 A와 단일 순회 후보 E2를 다시 screening했다.

| concurrency | A p50/p95 | E2 p50/p95 | A peak RSS | E2 peak RSS |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 541 / 957ms | 436 / 634ms | 3.38GB | 2.72GB |
| 2 | 666 / 983ms | 735 / 1,272ms | 3.99GB | 3.88GB |
| 4 | 1,020 / 2,176ms | 947 / 1,873ms | 4.01GB | 4.01GB |

c2 회귀와 단일 실행 쌍이라는 한계 때문에 E2는 반복 검증 대상으로만 유지한다. c1/c2/c4
5쌍과 soak 전에는 최종 채택하지 않는다.

### 반복 측정 추가 결과

같은 runner로 c1·c2·c4를 각각 한 쌍씩 추가 실행했다. 각 run은 80개 measured 요청 성공,
`metricsStatus=OBSERVED`, correctness mismatch 0건이었다.

| concurrency | A p50/p95 | E2 p50/p95 | A peak RSS | E2 peak RSS | 판정 |
| ---: | ---: | ---: | ---: | ---: | --- |
| 1 (repeat-2) | 593 / 1,121ms | 556 / 1,187ms | 3.18GB | 2.84GB | RSS↓, p95↑ |
| 2 (repeat-1) | 854 / 2,081ms | 629 / 1,507ms | 3.99GB | 3.84GB | 지연·RSS↓ |
| 4 (repeat-2) | 1,155 / 2,144ms | 1,255 / 1,902ms | 3.76GB | 3.73GB | p50↑, RSS≈ |

이로써 c2의 첫 회귀는 재현되지 않았다. 반면 c4에서는 E2의 peak RSS 감소가 거의 없고
p50이 악화됐으므로, “동시성이 높을수록 항상 유리하다”는 결론은 내릴 수 없다. E2는
아직 `INCONCLUSIVE`이며, c1/c2/c4 각각 독립 pair 5개와 soak를 완료하기 전에는 병합
또는 기본값 변경을 하지 않는다.

#### 네 번째 paired run

`compare_runs.py`로 기존 screening과 추가 실행을 합쳐 동시성별 4쌍을 계산했다.
개선률은 `(A - E2) / A`이고, 부트스트랩 구간은 실행 간 변동성을 포함한다.

| concurrency | p50 중앙값 | p95 중앙값 | peak RSS 중앙값 | 상태 |
| ---: | ---: | ---: | ---: | --- |
| 1 | +8.46% | +7.04% | +13.46% | INCONCLUSIVE |
| 2 | +0.32% | -6.53% | +3.45% | INCONCLUSIVE |
| 4 | +4.10% | -8.20% | +0.02% | INCONCLUSIVE |

네 쌍 모두 95% 구간이 0을 포함한다. c1 메모리 절감은 비교적 일관적이지만 c4 RSS는
변하지 않아, E2를 서버 전체의 기본 최적화로 채택하지 않는다. 다섯 번째 pair를 채워
통계 gate를 닫되, 최종 결정에는 correctness·soak·운영 메모리 목표를 함께 적용한다.

#### 다섯 번째 paired run과 결론

다섯 번째 쌍까지 완료했다. 개선률은 `(A - E2) / A`이며, 괄호는 paired bootstrap 95%
구간이다.

| concurrency | p50 중앙값 (95% CI) | p95 중앙값 (95% CI) | peak RSS 중앙값 (95% CI) | 판정 |
| ---: | ---: | ---: | ---: | --- |
| 1 | +6.14% (-25.85, +19.34) | -0.66% (-11.93, +33.73) | **+12.04% (+10.66, +19.59)** | RSS만 목표 충족 |
| 2 | +7.56% (-20.99, +26.39) | -13.77% (-46.84, +27.59) | +2.97% (+0.03, +7.61) | RSS 목표 미달·p95 회귀 |
| 4 | +7.12% (-8.65, +28.57) | +11.27% (-129.03, +27.57) | +0.03% (-0.04, +0.72) | RSS 미달·불확실 |

E2는 corpus 정합성 14/14를 통과했지만, 계획의 전체 조건(모든 동시성에서 RSS와 p50/p95
동시 5% 개선)을 충족하지 못했다. 따라서 E2를 기본 branch에 병합하거나 GC/JVM 기본값을
바꾸지 않는다. 다음 코드 후보는 원본 A를 기준으로 다시 평가한다.

#### 후보 F: qualify용 dummy path 재사용

JFR allocation site의 다음 hotspot인 `Path.dummyPath`(약 22.05%)를 대상으로 후보 F를
추가했다. `DestinationArrivalPaths`가 request scope이고 `ParetoSet.qualify()`가 후보를
보존하지 않는다는 계약을 이용한다. 정상 검색(debug listener 없음)에서는 scalar만 가진
mutable dummy 하나를 재사용하고, debug 검색에서는 기존 immutable 객체를 사용한다.

- Raptor 테스트: 954개 실행, 실패 0, skip 2.
- 확장 corpus: 14/14 검증 통과.
- A→F 단일 paired screening:

| concurrency | p50 개선률 | p95 개선률 | peak RSS 개선률 |
| ---: | ---: | ---: | ---: |
| 1 | +8.28% | +27.86% | +25.84% |
| 2 | +14.61% | +35.23% | +11.82% |
| 4 | +7.50% | +6.86% | +0.68% |

F 실행에는 E2가 함께 포함되어 있고 pair가 1개뿐이므로 채택/기각하지 않는다. 다음은
원본 A 기준 AB/BA를 섞은 5쌍과 c4 soak다. F가 실패하면 `StopArrivalFactoryC1`(allocation
약 19.18%)을 다음 조사 대상으로 삼는다.

F의 두 번째 독립 실행도 완료했다. 기존 A 독립 run과의 비동기 비교에서 c1/c2/c4 p50/p95
개선률은 각각 `+9.53/+22.18%`, `+1.56/+9.22%`, `+10.94/+18.28%`였으며 peak RSS는
각각 `+14.34%`, `+3.87%`, `+0.80%`였다. 실행 시점이 다른 baseline을 사용했으므로
최종 통계에는 포함하지 않고 방향성만 보존한다. 다음 실행부터는 A/F를 같은 paired 순서로
묶어 5쌍 gate를 채운다.

두 번째 정식 A→F paired run도 완료했다(아직 각 동시성 1쌍).

| concurrency | p50 개선률 | p95 개선률 | peak RSS 개선률 |
| ---: | ---: | ---: | ---: |
| 1 | -3.01% | -0.64% | +12.81% |
| 2 | +2.76% | +20.51% | +8.13% |
| 4 | +10.88% | +28.79% | +0.33% |

c1 지연 개선은 변동성이 확인됐고 c2/c4 p95 개선은 두 쌍에서 방향이 유지됐다. F는
고동시성 tail latency 후보로만 유망하다고 기록하며, 5쌍과 soak 전에는 채택하지 않는다.

#### 후보 G: 휴리스틱 선판정으로 stop-arrival 객체 생성 억제

`TransitStopArrival`/`TransferStopArrival`은 목적지 휴리스틱에서 탈락하더라도 기존에는
먼저 생성됐다. G는 일반 검색에서 stop·time·travelDuration·C1/C2를 primitive로 먼저
qualify하고, 통과한 경우에만 객체를 만든다. debug stop listener가 필요한 경우에는 기존
객체 우선 경로를 유지한다.

검증 결과:

- Raptor 954개 테스트: 실패 0, skip 2
- 핵심 휴리스틱 테스트: 3개 통과
- correctness corpus: 14건, 성공 8 + 의도된 routing error 6, validation error 0,
  원본 fingerprint mismatch 0
- JAR: `/private/tmp/otp-stop-arrival-candidate.jar`, SHA-256
  `b52e7a3c9b2aa91d12a561fc6781c2b4eb39da0143a1acab662f19a1ed30f96d`

OD당 20건의 짧은 screening은 다음과 같다. 이전 F 조합 run과 실행 시점이 달라 최종 paired
통계가 아니라 후보 방향성만 보여준다.

| concurrency | G p50/p95 | G peak RSS | 이전 F 조합 p50/p95 | 이전 F 조합 peak RSS |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 437.679 / 625.714ms | 2,302,312,448B | 532.917 / 842.937ms | 2,565,201,920B |
| 2 | 546.399 / 907.618ms | 3,883,143,168B | 535.152 / 658.234ms | 3,535,060,992B |
| 4 | 773.436 / 1,086.290ms | 4,004,261,888B | 669.807 / 866.869ms | 3,996,241,920B |

c1 단발성 개선만으로는 채택하지 않는다. c2/c4에서 목표를 재현하지 못했고 안정 예열·최종
5쌍·soak를 생략했으므로 G는 `INCONCLUSIVE/NOT_VERIFIED` 실험 후보로 남긴다. 다음에는
`TripPatternForDates`의 `arrivalTimes` 배열이 요청마다 생성되는 경로와 날짜 offset,
frequency, random access 계약을 계측한 뒤 compact/cache 가능성을 검토한다.

#### 후보 H: `TripPatternForDates` 시간 배열 제거

H는 위 `arrivalTimes` allocation hotspot을 직접 줄이는 후보다. F-only 분리 소스에서 요청별
`nStops * nTrips` arrival/departure 배열을 만들지 않고, 글로벌 trip index별 immutable
`TripTimes` 참조와 서비스 날짜 offset만 보관한다. 시간 조회는 원본 `TripTimes`의 stop 값을
읽어 offset을 더한다. `TripTimes`는 Scheduled/RealTime 모두 final 상태를 가진 immutable
snapshot이고 실시간 갱신은 새 객체 교체이므로 request-local 참조가 변하지 않는다는 전제를
확인했다. frequency 경로와 기존 랜덤 접근 API는 바꾸지 않았다.

검증 결과:

- 대상 테스트 6개 통과.
- 영향 모듈 전체 Maven test 8,225개, failures/errors 0, skipped 20.
- correctness corpus 14건(성공 8, 의도된 routing error 6), validation error 0,
  원본 fingerprint mismatch 0.
- H JAR `/private/tmp/otp-trip-times-candidate.jar`, SHA-256
  `28eb50f341fcbbe712ec2f10cdc76b7eb977a3b3d966ba825c4396b138849236`.

F-only 기준과 OD당 10건의 동일 workload를 실행했지만, 서로 다른 JVM 순서·host pressure의
비paired screening이므로 방향성만 기록한다.

| concurrency | F p50/p95 | H p50/p95 | F peak RSS | H peak RSS | H 개선률(p50/p95/RSS) |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 496.025 / 761.097ms | 359.262 / 460.824ms | 2,959,753,216B | 1,903,935,488B | +27.57% / +39.45% / +35.67% |
| 2 | 561.290 / 1,064.983ms | 556.842 / 732.968ms | 3,613,499,392B | 1,926,496,256B | +0.79% / +31.18% / +46.69% |
| 4 | 794.772 / 1,085.081ms | 506.979 / 685.017ms | 4,023,721,984B | 1,976,221,696B | +36.21% / +36.87% / +50.89% |

세 동시성 모두 낮은 방향이지만 단일 비paired 결과로는 성능 채택을 결정하지 않는다. H는
`INCONCLUSIVE` 후보이며, 다음 단계는 H/F 동일 graph·JVM·seed의 AB·BA paired 5쌍, OD당
100건·안정 예열, 이후 c4 30분 soak다. 해당 gate 전에는 main branch에 병합하거나 JVM 기본값을
변경하지 않는다.

짧은 paired pilot(OD당 10건)에서는 c1의 H 개선률이 p50/p95/RSS `+19.64% / +24.39% /
+36.04%`, c2가 `-1.19% / +10.98% / +51.66%`였다. c2 p50의 소폭 회귀 때문에 latency가
모든 동시성에서 좋아진다고 해석하지 않는다. 초기 c4 F 기준은 RSS collector의 measured-end
coverage가 끊겼지만, `run_isolated.py`를 보강해 collector 종료코드와 컨테이너 상태를 남긴
뒤 재실행했다. 보강된 c4 F/H는 모두 RSS `OBSERVED`, 40/40 성공, `OOMKilled=false`였고
H 개선률은 p50/p95/RSS `+24.81% / -7.69% / +46.88%`였다. 결과 경로는 각각
`/private/tmp/otp-f-c1-pilot`, `/private/tmp/otp-f-c2-pilot`, `/private/tmp/otp-f-c4-pilot-repair`
이며 H 기준은 `/private/tmp/otp-h-c1-short`, `/private/tmp/otp-h-c2-short`,
`/private/tmp/otp-h-c4-pilot-repair`다.

실행 순서를 뒤집은 c4 BA pilot(`/private/tmp/otp-f-c4-pilot-ba` →
`/private/tmp/otp-h-c4-pilot-ba`)에서는 p50/p95/RSS `+49.34% / +60.45% / +54.88%`가
나왔다. AB의 p95 `-7.69%`와 차이가 크므로 host pressure와 실행 순서가 단기 결과를
지배할 수 있다. 두 결과 모두 최종 5쌍 gate로 승격하지 않고, 정식 측정에서 AB/BA를 균형화한다.

추가로 실행 순서를 뒤집은 c1·c2 BA pilot도 완료했다(각 OD당 10건). c1은 F
`/private/tmp/otp-f-c1-pilot-ba` → H `/private/tmp/otp-h-c1-pilot-ba` 순서로 실행했고,
H 개선률은 p50/p95/RSS `+15.93% / +13.74% / +25.73%`였다. c2는 F
`/private/tmp/otp-f-c2-pilot-ba` → H `/private/tmp/otp-h-c2-pilot-ba` 순서이며,
H 개선률은 `+18.60% / +24.83% / +54.55%`였다. 네 BA/AB pilot 모두 RSS
`OBSERVED`, HTTP 40/40 성공, `OOMKilled=false`를 확인했다. 다만 각 동시성에서
독립 pair가 아직 2개뿐이고 OD당 10건이므로 최종 성능 판정은 계속 `INCONCLUSIVE`다.
이 결과는 H의 메모리 절감 방향이 실행 순서가 바뀌어도 반복되는지 확인하는 보조 근거로만
사용하며, latency 전역 PASS로 승격하지 않는다.

안정 예열 gate 미적용 예비 표본의 첫 c1 pair를 완료했다. F→H 순서로 각각 OD당 warmup 100건·측정 100건
(총 measured 400건)을 실행했으며 두 run 모두 HTTP 400/400 성공, RSS `OBSERVED`,
`OOMKilled=false`였다. F `/private/tmp/otp-f-c1-formal-1`의 p50/p95/peak RSS는
`473.229 / 862.154ms / 3,530,203,136B`, H `/private/tmp/otp-h-c1-formal-1`은
`396.469 / 520.462ms / 1,833,783,296B`로 H 개선률은
`+16.22% / +39.63% / +48.05%`였다. 이는 정식 표본의 첫 pair 결과일 뿐이며,
AB/BA 균형 5쌍과 c2·c4 및 soak가 남아 있어 상태는 `INCONCLUSIVE`다.

c1의 두 번째 예비 pair는 H→F 역순으로 실행했다. H
`/private/tmp/otp-h-c1-formal-2`의 p50/p95/peak RSS는
`390.895 / 526.652ms / 1,992,622,080B`, F `/private/tmp/otp-f-c1-formal-2`는
`522.462 / 785.259ms / 3,123,937,280B`였고, H 개선률은
`+25.18% / +32.93% / +36.21%`였다. 두 번째 pair도 400/400 성공·RSS
`OBSERVED`·`OOMKilled=false`였지만 c1 독립 pair가 2개뿐이므로 최종 gate는 아직
`INCONCLUSIVE`다.

c2 예비 pair 1은 F→H 순서로 실행했다. F `/private/tmp/otp-f-c2-formal-1`의
p50/p95/peak RSS는 `559.893 / 812.585ms / 4,007,616,512B`, H
`/private/tmp/otp-h-c2-formal-1`은 `429.588 / 573.718ms / 1,974,607,872B`였고,
H 개선률은 `+23.27% / +29.40% / +50.73%`였다. 각 run은 measured 400/400
성공·RSS `OBSERVED`·`OOMKilled=false`였으며 c2 역순 pair가 남아 있다.

c2 예비 pair 2는 H→F 역순으로 실행했다. H `/private/tmp/otp-h-c2-formal-2`의
p50/p95/peak RSS는 `419.081 / 567.590ms / 2,167,132,160B`, F
`/private/tmp/otp-f-c2-formal-2`는 `577.785 / 1,122.741ms / 4,016,586,752B`였고,
H 개선률은 `+27.47% / +49.45% / +46.05%`였다. 양쪽 모두 measured 400/400
성공·RSS `OBSERVED`·`OOMKilled=false`였다. c2도 AB/BA 두 쌍이지만 최종 5쌍과
c4·soak가 남아 최종 상태는 `INCONCLUSIVE`다.

c4 예비 pair 1은 F→H 순서로 실행했다. F `/private/tmp/otp-f-c4-formal-1`의
p50/p95/peak RSS는 `779.764 / 1,065.975ms / 4,073,365,504B`, H
`/private/tmp/otp-h-c4-formal-1`은 `450.514 / 711.055ms / 1,961,013,248B`였고,
H 개선률은 `+42.22% / +33.30% / +51.86%`였다. 두 run 모두 measured 400/400
성공·RSS `OBSERVED`·`OOMKilled=false`였다. c4 역순 pair와 30분 soak가 남아 있다.

c4 예비 pair 2는 H→F 역순으로 실행했다. H `/private/tmp/otp-h-c4-formal-2`의
p50/p95/peak RSS는 `451.034 / 611.647ms / 2,046,291,968B`, F
`/private/tmp/otp-f-c4-formal-2`는 `825.571 / 1,286.972ms / 4,044,533,760B`였고,
H 개선률은 `+45.37% / +52.47% / +49.41%`였다. 양쪽 모두 measured 400/400
성공·RSS `OBSERVED`·`OOMKilled=false`였다. 이로써 c1·c2·c4 각각 AB/BA 2쌍을
확보했지만 최종 5쌍·c4 30분 soak가 남아 전체 상태는 `INCONCLUSIVE`다.

F c4 장기 run(`/private/tmp/otp-f-c4-soak`)도 완료했다. OD당 2,500건(총 measured
10,000건), c4 동시성으로 measured 구간은 `2,580.686초`(약 43분)였고
`benchmarkExitCode=0`, HTTP 오류 0건, `OOMKilled=false`였다. peak RSS는
`4,137,394,176B`, GC pause 합계는 약 `465,249ms`였다. 5분 창 RSS는 초기
약 3.82GiB에서 후반 약 3.85GiB로 완만히 안정화됐다. 그러나 장시간 run에서
RSS sampler 최대 간격이 `0.625초`로 계약 상한 `0.5초`를 한 번 넘겨 요약 상태가
`INCOMPLETE`다. 따라서 이 run은 누수·OOM 부재를 보는 보조 자료로 보존하고,
최종 soak PASS에는 산입하지 않는다. H 측정부터 실제 measured 1,800초 이상과
안정 예열 gate를 함께 만족해야 한다.

H c4 장기 run(`/private/tmp/otp-h-c4-soak`)은 `--require-warmup-stability`를
활성화한 상태로 시작했고, warmup 3개 window 이후 measured에 진입했다. measured
구간은 실제 `17,157.567초`(약 4시간 46분)로 1,800초 기준을 충분히 넘겼다.
다만 동일 run에서 20,000건 중 15,737건만 성공했고 4,263건은 컨테이너를
정리할 때 발생한 transport error이며, collector coverage도 종료 시점에 끊겼다.
요약 상태는 `INCOMPLETE`, `benchmarkExitCode=1`, `OOMKilled=false`다. 성공 구간
지연 p50/p95는 `431.563 / 591.565ms`, peak RSS는 `2,086,002,688B`였고,
5분 창 RSS는 약 1.94GiB에서 장시간 안정적이었다. 시간·RSS 추세 확인용 보조
자료로만 보존하며 최종 soak PASS로 산입하지 않는다. 다음 A/H soak는 컨테이너
정리를 중간에 하지 않고 모든 요청·collector coverage가 끝난 `OBSERVED` run으로
다시 확보해야 한다.

원본 A/H c1 gate 재시도 결과도 최종 pair로 산입하지 않는다. A
`/private/tmp/otp-a-c1-gate-2`는 warmup 안정화 후 400/400 성공·RSS `OBSERVED`였지만,
H `/private/tmp/otp-h-c1-gate-2`는 warmup은 통과했어도 RSS sampler 최대 간격이
`1.137초`로 0.5초 계약을 초과해 `INCOMPLETE`였다. 당시 호스트 swap 사용량은
약 6.5~8.3GiB였다. latency raw는 보존하되 최종 A/H pair 수에는 넣지 않는다.

H c1 gate 3(`/private/tmp/otp-h-c1-gate-3`)은 동일한 안정 예열 옵션으로 재시도했으나,
warmup 기본 400건과 window 1~8이 모두 `stable=false`였다. window 9 진행 중 프로세스를
중단해 measured marker·summary가 없으므로 성능 표본으로 사용하지 않는다. 요청 raw와 RSS
수집 자료는 보존했고 컨테이너는 `Exited (143)`으로 정리됐다. 시작 시점 swap 사용량은
약 6.7GiB였고 수집기 샘플은 계속 쌓였지만 queue wait 변동이 커 안정성 gate를 만족하지
못했다. 장시간 측정 중 macOS 자동 절전·재개가 있었을 가능성도 확인됐으므로, sampler 공백과
transport error를 코드 결함으로 해석하지 않는다. 이 gate 3 자체는 A/H pair에 산입하지
않으며, 다음 측정은 전원 연결·화면을 닫지 않은 상태에서 `caffeinate -dimsu`로 전체
프로세스를 감싸고, sampler 간격·컨테이너 종료를 자동 무효화하는 조건으로 재실행한다.

절전 방지 조건을 적용한 첫 A/H c1 쌍을 새로 완료했다. A
`/private/tmp/otp-a-c1-gate-3`는 warmup window 6에서 안정화 후 measured 400/400
성공·RSS `OBSERVED`였고, p50/p95/peak RSS는 `453.220 / 720.635ms /
3,512,758,272B`였다. 같은 조건의 H `/private/tmp/otp-h-c1-gate-4`도 warmup 안정화·
400/400 성공·RSS `OBSERVED`·`OOMKilled=false`였고, p50/p95/peak RSS는
`395.978 / 545.177ms / 1,847,795,712B`였다. H 개선률은 p50/p95/RSS
`+12.63% / +24.35% / +47.40%`다. 두 실행 모두 `caffeinate -dimsu`로 감쌌고
sampler 최대 간격은 각각 `0.164초`와 `0.115초`였다. 이는 안정 예열을 통과한
유효한 A/H c1 pair 1개로 집계하지만, c1 최소 5쌍·c2/c4·soak가 남아 전체 판정은
계속 `INCONCLUSIVE`다.

H c1 gate 3(`/private/tmp/otp-h-c1-gate-3`)은 동일한 안정 예열 옵션으로 재시도했으나,
warmup 기본 400건과 window 1~8이 모두 `stable=false`였다. window 9 진행 중 프로세스를
중단해 measured marker·summary가 없으므로 성능 표본으로 사용하지 않는다. 요청 raw와 RSS
수집 자료는 보존했고 컨테이너는 `Exited (143)`으로 정리됐다. 시작 시점 swap 사용량은
약 6.7GiB였고 수집기 샘플은 계속 쌓였지만 queue wait 변동이 커 안정성 gate를 만족하지
못했다. 이 gate 3 자체는 A/H pair에 산입하지 않는다. 장시간 측정 중 macOS 자동
절전·재개 가능성도 확인됐으므로 sampler 공백과 transport error를 코드 결함으로
해석하지 않는다. 다음 측정은 전원 연결·화면을 닫지 않은 상태에서 `caffeinate -dimsu`로
전체 프로세스를 감싸고, sampler 간격·컨테이너 종료를 자동 무효화하는 조건으로 재실행한다.

절전 방지 조건의 c1 역순(H→A) 두 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c1-gate-5`는 warmup window 7에서 안정화 후 measured 400/400
성공·RSS `OBSERVED`, p50/p95/peak RSS `406.878 / 562.170ms /
2,156,351,488B`였다. A `/private/tmp/otp-a-c1-gate-4`도 안정화·400/400 성공·RSS
`OBSERVED`·`OOMKilled=false`였고 p50/p95/peak RSS `445.380 / 744.694ms /
3,364,507,648B`였다. H 개선률은 p50/p95/RSS `+8.64% / +24.51% / +35.91%`다.
두 run 모두 `caffeinate -dimsu`로 실행했고 sampler 최대 간격은 `0.146초`와
`0.128초`였다. c1 유효 A/H pair는 AB·BA 2개가 됐지만 최소 5쌍·c2/c4·soak가
남아 전체 판정은 계속 `INCONCLUSIVE`다.

c2 동시성 2의 AB 순서(A→H) 첫 유효 pair도 완료했다. A
`/private/tmp/otp-a-c2-gate-1`은 warmup 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `541.045 / 872.934ms / 4,014,080,000B`였다. H
`/private/tmp/otp-h-c2-gate-1`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `413.787 / 576.057ms / 2,100,056,064B`였다. H 개선률은
p50/p95/RSS `+23.52% / +34.01% / +47.68%`다. 두 run의 sampler 최대 간격은
`0.132초`와 `0.122초`였고 모두 `caffeinate -dimsu`로 실행했다. c2 유효 pair
1개를 집계하며, 역순 pair 1개와 c1·c4·5쌍·soak가 남아 전체 판정은
`INCONCLUSIVE`다.

c2 동시성 2의 역순(H→A) 두 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c2-gate-2`는 warmup window 2 안정화·400/400 성공·RSS
`OBSERVED`, p50/p95/peak RSS `426.421 / 607.929ms / 2,309,447,680B`였다. A
`/private/tmp/otp-a-c2-gate-2`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `551.725 / 828.825ms / 4,023,226,368B`였다. H 개선률은
p50/p95/RSS `+22.71% / +26.65% / +42.60%`다. sampler 최대 간격은 `0.149초`와
`0.129초`였고 모두 `caffeinate -dimsu`로 실행했다. c2 유효 pair 2개(AB·BA)를
확보했지만 c1·c4 최소 5쌍과 soak가 남아 전체 판정은 `INCONCLUSIVE`다.

c1 동시성 1의 AB 순서(A→H) 세 번째 유효 pair도 완료했다. A
`/private/tmp/otp-a-c1-gate-5`는 warmup 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `420.319 / 644.354ms / 3,256,889,344B`였다. H
`/private/tmp/otp-h-c1-gate-6`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `429.945 / 659.360ms / 2,131,439,616B`였다. H 개선률은
p50/p95/RSS `-2.29% / -2.33% / +34.56%`로 latency가 소폭 회귀했지만, 단일 pair로
기각하지 않고 변동성 자료로 보존한다. sampler 최대 간격은 `0.130초`와 `0.147초`였고
모두 `caffeinate -dimsu`로 실행했다. c1 유효 pair는 3개가 됐지만 최소 5쌍·c2/c4·
soak가 남아 전체 판정은 `INCONCLUSIVE`다.

c1 동시성 1의 역순(H→A) 네 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c1-gate-7`은 warmup window 3 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `418.447 / 642.950ms / 2,006,863,872B`였다. A
`/private/tmp/otp-a-c1-gate-6`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `444.055 / 756.130ms / 3,356,606,464B`였다. H 개선률은
p50/p95/RSS `+5.77% / +14.97% / +40.21%`다. sampler 최대 간격은 `0.130초`와
`0.170초`였고 모두 `caffeinate -dimsu`로 실행했다. c1 유효 pair는 4개가 됐지만
다섯 번째 pair와 c2/c4·soak가 남아 전체 판정은 `INCONCLUSIVE`다.

c1 동시성 1의 AB 순서(A→H) 다섯 번째 유효 pair도 완료했다. A
`/private/tmp/otp-a-c1-gate-7`은 warmup window 1 안정화·400/400 성공·RSS `OBSERVED`,
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
`/private/tmp/otp-a-c4-gate-1`은 warmup window 8 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `804.092 / 1,450.965ms / 4,081,348,608B`였다. H
`/private/tmp/otp-h-c4-gate-1`도 warmup window 2 안정화·400/400 성공·RSS `OBSERVED`·
`OOMKilled=false`, p50/p95/peak RSS `477.665 / 668.544ms / 1,918,050,304B`였다.
H 개선률은 p50/p95/RSS `+40.60% / +53.92% / +53.00%`다. sampler 최대 간격은
`0.214초`와 `0.117초`였고 모두 `caffeinate -dimsu`로 실행했다. c4 유효 pair
1개를 집계하며 역순 pair와 c1·c2 최소 5쌍 및 soak가 남아 전체 판정은
`INCONCLUSIVE`다.

c4 동시성 4의 역순(H→A) 두 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c4-gate-2`는 warmup 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `444.564 / 580.961ms / 2,328,334,336B`였다. A
`/private/tmp/otp-a-c4-gate-2`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `750.191 / 1,288.260ms / 4,060,102,656B`였다. H 개선률은
p50/p95/RSS `+40.74% / +54.90% / +42.65%`다. sampler 최대 간격은 `0.110초`와
`0.174초`였고 모두 `caffeinate -dimsu`로 실행했다. c4 유효 pair 2개(AB·BA)를
확보했지만 각 동시성 최소 5쌍과 soak가 남아 전체 판정은 `INCONCLUSIVE`다.
c2 동시성 2의 AB 순서(A→H) 세 번째 유효 pair도 완료했다. A
`/private/tmp/otp-a-c2-gate-3`은 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `522.006 / 875.770ms / 4,010,885,120B`였다. H
`/private/tmp/otp-h-c2-gate-3`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `432.255 / 638.005ms / 2,123,206,656B`였다. H 개선률은
p50/p95/RSS `+17.19% / +27.15% / +47.06%`다. sampler 최대 간격은 `0.164초`와
`0.144초`였고 모두 `caffeinate -dimsu`로 실행했다. c2 유효 pair는 3개(AB 2,
BA 1)가 됐지만 최소 5쌍과 c4·soak가 남아 전체 판정은 `INCONCLUSIVE`다.
c2 동시성 2의 역순(H→A) 네 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c2-gate-4`는 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `426.981 / 586.513ms / 1,993,764,864B`였다. A
`/private/tmp/otp-a-c2-gate-4`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `615.408 / 1,229.857ms / 4,010,840,064B`였다. H 개선률은
p50/p95/RSS `+30.62% / +52.31% / +50.29%`다. sampler 최대 간격은 `0.120초`와
`0.309초`였고 모두 `caffeinate -dimsu`로 실행했다. c2 유효 pair는 4개(AB 2,
BA 2)가 됐지만 마지막 5번째 pair와 c4·soak가 남아 전체 판정은 `INCONCLUSIVE`다.
c2 동시성 2의 다섯 번째 예정 A 실행(`/private/tmp/otp-a-c2-gate-5`)은 warmup
안정화 게이트가 600초 안에 통과하지 못해 `compare_graphql.py`가 종료 코드 2로
실패했다(`warmup did not stabilize before timeout`). measured marker와 summary가
없으므로 유효 pair에 산입하지 않으며, raw/G C/RSS 산출물은 보존한다. 동일 조건의
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
BA 1)가 됐지만 2쌍과 soak가 남아 전체 판정은 `INCONCLUSIVE`다.
c4 동시성 4의 역순(H→A) 네 번째 유효 pair도 완료했다. H
`/private/tmp/otp-h-c4-gate-4`는 안정화·400/400 성공·RSS `OBSERVED`,
p50/p95/peak RSS `508.180 / 701.019ms / 2,236,317,696B`였다. A
`/private/tmp/otp-a-c4-gate-4`도 안정화·400/400 성공·RSS `OBSERVED`·`OOMKilled=false`,
p50/p95/peak RSS `926.763 / 1,590.143ms / 4,063,039,488B`였다. H 개선률은
p50/p95/RSS `+45.17% / +55.91% / +44.96%`다. sampler 최대 간격은 `0.182초`와
`0.156초`였고 모두 `caffeinate -dimsu`로 실행했다. c4 유효 pair는 4개(AB 2,
BA 2)가 됐지만 마지막 5번째 pair와 soak가 남아 전체 판정은 `INCONCLUSIVE`다.
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
`feb94ba087dd8e870fb75e6d138f5ed25b89de73a218c0afe329b4b1fd481487`)를 추가로
시험했다. J는 H의 `TripTimes[]` 참조 경로를 유지하되, 작은 패턴(정류장 수 기준
`<=1024`)만 직접 인덱싱 가능한 compact 배열로 두고 큰 패턴은 H 경로를 쓰는
하이브리드다. 빌드와 `TripPatternForDatesTest` 4/4, 고정 corpus 14건(성공 8,
의도된 routing_error 6, validationErrors 0)을 통과했다.

J 동시성 1 파일럿은 `/private/tmp/otp-j-c1-gate-1`에서 400/400 성공,
p50/p95 `391.563/581.232ms`, peak RSS `1,850,470,400B`,
`OBSERVED`(최대 샘플 공백 0.163초)였고, `/private/tmp/otp-j-c1-gate-2`는
`424.787/651.926ms`, `1,926,348,800B`, `OBSERVED`(0.150초)였다. 기존 유효
A `/private/tmp/otp-a-c1-gate-6` 및 순서 반대 A
`/private/tmp/otp-a-j-c1-gate-2`와 2쌍을 임시 비교한 결과는
`/private/tmp/otp-a-j-c1-2pairs.json`에 저장했으며 p50 중앙 `+10.74%`, p95
`+23.55%`, RSS `+43.38%`다. 쌍 수가 2개이므로 모두 `INCONCLUSIVE`이고, c1
최종 gate를 대체하지 않는다. A `/private/tmp/otp-a-j-c1-gate-1`은 샘플 공백
`0.692초`로 `INCOMPLETE`라 유효 쌍에서 제외했다.

J 동시성 2 파일럿 `/private/tmp/otp-j-c2-pilot-1`은 400/400 성공,
p50/p95 `452.390/689.402ms`, peak RSS `1,946,296,320B`, `OBSERVED`였다.
동시성 4 파일럿 `/private/tmp/otp-j-c4-pilot-1`도 400/400 성공,
p50/p95 `561.281/804.711ms`, peak RSS `1,971,945,472B`, `OBSERVED`였다.
따라서 J는 H의 c2/c4 개선을 회귀시키지 않는 초기 신호가 있으나, 아직 각 동시성
5쌍 paired bootstrap과 J 기준 soak가 없어 최종 후보로 승격하지 않는다.

다음 실행 순서: (1) J/A를 c1·c2·c4 각각 최소 5쌍(AB/BA 균형)으로 다시 수집,
(2) correctness corpus 재비교, (3) J/A 동일 조건 30분 soak를 수행한다. 세 조건과
기존 SLO를 모두 충족하기 전까지 상태는 `INCONCLUSIVE`다. J 소스는 아직 OTP 본
저장소에 반영하지 않았고 `/private/tmp/otp-hybrid-candidate`에만 보존했다.

추가 검증에서 J 실행본이 현재 작업트리의 E2 변경을 포함하지 않았다는 점을 확인했다.
따라서 현재 작업트리(E2 포함)에서 J의 하이브리드 `TripPatternForDates`만 적용한 K
후보(`/private/tmp/otp-k-candidate.jar`, SHA-256
`8f6f77e40b90065e528964081f7c2a9f870a2dd91cf97c450957cd53645e5776`)를 새로 만들었다.
K는 빌드 성공, `TripPatternForDatesTest` 7/7, 고정 corpus 14건(성공 8,
routing_error 6, validationErrors 0)을 통과했다.

K 파일럿은 동시성 1(`/private/tmp/otp-k-c1-pilot-2`) p50/p95
`375.901/512.522ms`, peak RSS `1,966,731,264B`; 동시성 2
(`/private/tmp/otp-k-c2-pilot-1`) `395.254/545.543ms`, `2,147,840,000B`;
동시성 4(`/private/tmp/otp-k-c4-pilot-1`) `427.137/556.023ms`,
`2,118,139,904B`였다. 세 실행 모두 400/400 성공, `OBSERVED`, 샘플 공백 0.16초
이하였다. J soak와 동시에 수행되어 안정화에 실패한 K c1 파일럿 1회는 무효로
기록하며 통계에 포함하지 않는다.

J의 8,000건 c4 보조 soak(`/private/tmp/otp-j-c4-soak-final`)는 8,000/8,000
성공, p50/p95 `570.072/996.913ms`, peak RSS `2,205,544,448B`, GC pause
`33.04s`, 최대 샘플 공백 `0.373s`로 `OBSERVED`였다. 이는 J(H/F 기반) 자료이며
K 최종 soak를 대체하지 않는다.

현재 최종 후보는 K다. 다음은 K/A를 c1·c2·c4 각각 최소 5쌍(AB/BA 균형)으로
측정하고, K correctness 재비교와 동일 조건 30분 soak를 수행하는 것이다. 그 전까지
기존 H/J 결과를 K의 최종 근거로 합산하지 말고 전체 상태를 `INCONCLUSIVE`로 유지한다.

### 2026-09-10 재개 결과

K c2 `/private/tmp/otp-k-c2-gate-1`은 안정화·400/400·RSS `OBSERVED`를 통과했지만,
직후 실행한 원본 A `/private/tmp/otp-a-k-c2-ba-1`은 호스트 스왑 압박으로 안정화
timeout되어 paired run으로 사용할 수 없다. K c1과 A c1은 각각
`/private/tmp/otp-k-c1-ab-1`, `/private/tmp/otp-a-k-c1-ab-1`에서 400/400·RSS
`OBSERVED`를 통과했다. c1 paired 결과는 p50 `-12.88%`, p95 `-22.34%`, RSS
`+38.27%`로, K는 메모리 절감과 응답시간 회귀를 교환하는 양상이 첫 fresh pair에서
재현됐다. 이 단일 pair를 최종 판정으로 승격하지 않되, K 추가 5쌍보다 H c1 지연
불확실성 해소를 우선한다.

K c1은 유효 실행 5개(`/private/tmp/otp-k-c1-pilot-2`, `k-c1-gate-2`~`5`)를
확보했다. A 기존 유효 실행 5개와의 임시 집계(`/private/tmp/otp-a-k-c1-5pairs.json`)
는 p50 중앙 `+11.87%`, p95 `+28.36%`, peak RSS `+44.01%`였지만, 마지막
쌍의 변동 때문에 p50 bootstrap `[−11.28%, +18.03%]`, p95 `[−12.24%, +35.16%]`로
지연시간은 `INCONCLUSIVE`, RSS만 `TARGET_MET`이다. per-case로는 부평→서울/강남의
p50·p95가 통과했으나 도심 case 하한이 0 미만이다. K를 먼저 실행하고 A를 뒤에 둔
순서 반대 기준선은 warmup 안정화 timeout으로 무효(`/private/tmp/otp-k-c1-a-after-1`)였다.
따라서 K도 현재 전체 `VERIFIED`가 아니며, c2/c4는 아직 파일럿만 있다.

H c1을 원본 A와 새로 세 쌍 추가해 9쌍으로 재집계했다
(`/private/tmp/otp-a-h-c1-9pairs.json`). p50 중앙 `+6.94%`, bootstrap
`[-0.07%, +13.02%]`; p95 중앙 `+20.07%`, `[-2.33%, +24.51%]`; RSS 중앙
`+40.21%`, `[+35.91%, +47.61%]`다. 모든 새 run은 안정화·400/400·RSS
`OBSERVED`였지만, p50/p95 하한이 0을 넘지 않아 H 전체 gate는 여전히
`INCONCLUSIVE`다. 추가 pair는 계속 AB/BA를 교차한다.

## 2026-09-11 H c1·soak 완료

H c1 10쌍 집계(`/private/tmp/otp-a-h-c1-10pairs.json`)에서 p50 개선 중앙값
`+7.79%`(bootstrap 95% `[+1.26%, +16.54%]`), p95 `+20.54%`
(`[+6.32%, +25.75%]`), peak RSS `+41.27%`(`[+36.87%, +47.40%]`)로 세 지표 모두
하한 0을 통과했다. c2·c4 5쌍 gate 및 고정 correctness corpus 14건 비교도 기존과
동일하게 통과 상태다.

H c4 30분 soak(`/private/tmp/otp-h-c4-soak-30m`)은 16,000/16,000 성공,
`1889.039초`, p50/p95 `499.042/685.075ms`, peak RSS `1,982,930,944B`, GC
`6,560회·43,105.427ms`, 최대 GC pause `155.725ms`, sampler 최대 공백 `0.291초`,
`OBSERVED`/exit 0/cleanup 오류 없음이다. 5분 창 분석에서도 p50 `494~504ms`, p95
`661~705ms`, RSS 중앙값 `1.943~1.959GB`로 상승 추세나 tail latency 악화가 없다.
원본 A 30분 soak와의 차이는 p50 `41.23%`, p95 `57.82%`, peak RSS `52.06%`, 누적
GC pause `93.20%` 감소지만 실행 시간이 달라 정식 paired gate가 아닌 보조 자료다.

위 기록 이후 본 작업트리 H `TripTimes[]` 저장 방식의 타깃/영향 범위 테스트, shaded
JAR 재빌드, correctness corpus 재비교까지 완료했다. 다만 아래 paired gate/soak는
H+F 후보에 대한 것이며 현재 B/C/F/H 조합과는 실행본이 다르다.

본 작업트리 H 소스는 타깃 테스트 18개와 checkstyle/컴파일을 통과했다. shaded JAR는
정상 Maven package로 재빌드했고 `/private/tmp/otp-h-final.jar` SHA-256은
`a88393546ab3bf1dd1a940cf10147909435e69ee48d0e9cb4d277b7d32edc158`이다. 재빌드 JAR
correctness corpus(`/private/tmp/otp-h-final-corpus`)는 14건(성공 8, 의도된
routing_error 6), validation error 0, 원본 A `/private/tmp/otp-a-a-corpus-new`
대비 fingerprint mismatch 0으로 확인했다. 현재 B/C/F/H JAR의 c1/c2/c4 smoke도
각 1,600/1,600 성공·`OBSERVED`였지만, 최종 paired gate를 대체하지 않는다. 다음
에이전트는 현재 조합으로 c1·c2·c4 5쌍과 c4 30분 soak를 다시 수행해야 한다.
커밋·PR·배포는 별도 요청 전에는 진행하지 않는다.

## 2026-09-11 현재 B/C/F/H c1 조기 판정

현재 B/C/F/H shaded JAR(`/private/tmp/otp-h-final.jar`)을 원본 A와 안정화 예열
100회/OD, 측정 100회/OD 조건으로 세 쌍 교차 측정했다. 각 실행은 400/400 성공,
RSS `OBSERVED`, 컨테이너 OOM 없음이었다. 전체 개선률(p50/p95/peak RSS)은
쌍별 `(+6.96%, −4.40%, +44.35%)`, `(+8.36%, +6.45%, +40.63%)`,
`(+7.24%, −0.10%, +40.69%)`였다. 3쌍 중앙은 `(+7.24%, −0.10%, +40.69%)`다.

`cityhall-gangnam` p50은 세 쌍 모두 5% 초과 악화(`−7.76%`, `−5.71%`,
`−6.39%`)했고, 이는 개별 OD 회귀 금지 기준을 위반한다. 따라서 B/C/F/H 조합은
메모리 절감과 무관하게 c1 gate `NOT_MET`으로 조기 판정하며 c2/c4 추가 paired
측정과 soak는 진행하지 않는다. 다음 실행은 B/C를 제외한 기존 H+F 후보 산출물의
재현성 확인이다. H+F의 과거 paired/soak 자료를 B/C/F/H 결과에 합산하지 않는다.

## 2026-09-11 현재 작업트리 전체 회귀 확인

현재 B/C/F/H 작업트리에서 다음 명령을 재실행했다.

```bash
mvn -pl application -am -PprettierSkip \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Utils 340, Domain Core 63, Raptor 954, A* 32, Street 850, Application 5,986으로
총 8,225개 테스트가 실행됐고 failures/errors 0, skipped 20, `BUILD SUCCESS`였다.
현재 조합의 shaded JAR 빌드·14건 correctness corpus 비교·기능 회귀는 통과한 상태다.
다만 H+F 실행본으로 수집된 기존 c1/c2/c4 paired gate와 soak를 현재 B/C/F/H
조합에 그대로 승계할 수 없다. 따라서 현재 B/C/F/H 조합의 상태는 c1 개별 OD
회귀 기준 위반으로 `NOT_MET`이며, H+F는 별도 후보로만 `TARGET_MET` 근거를
보유한다.

H+F JAR(`/private/tmp/otp-trip-times-candidate.jar`, SHA-256
`28eb50f341fcbbe712ec2f10cdc76b7eb977a3b3d966ba825c4396b138849236`)를 별도로
correctness 재실행했다. `/private/tmp/otp-hf-final-corpus-2`는 14건(성공 8,
의도된 routing error 6), validation error 0이며 원본 raw
`/private/tmp/otp-a-a-corpus-new/requests.jsonl`와 mismatch 0이다. benchmark exit 0,
RSS `OBSERVED`, cleanup 오류 없음도 확인했다. 이 자료는 B/C/F/H 결과와 섞지 않고
H+F 최종 후보의 정합성 재현 근거로만 사용한다.

## 2026-09-11 최종 후보 H+F 확정

B/C와 G 주 코드 변경을 제거한 현재 작업트리는 H(`TripPatternForDates`)와
F(`DestinationArrivalPaths`, `PathConfig`, `McRangeRaptorConfig`)만 포함한다.
현재 재빌드 JAR의 SHA-256은
`54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f`이며, 기존 H+F
정식 측정 JAR와 `otp-project-info.properties`를 제외한 모든 class entry가 동일하다.

H+F는 c1 10쌍, c2/c4 각 5쌍 paired bootstrap, correctness, c4 30분 soak를
계획 기준으로 통과했다. 현재 JAR로도 correctness 14건(mismatch 0), 대상 테스트
18개, 영향 모듈 전체 8,225개(failures/errors 0, skipped 20), 측정기 테스트 33개,
checkstyle을 재확인했다. 따라서 H+F 기준 Phase 4와 전체 최적화 목표는
`VERIFIED`다. B/C/G의 c1 회귀 결과는 재도입하지 않는 실패 가설 기록으로만 남긴다.
