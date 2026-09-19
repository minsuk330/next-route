# P3 JFR 결과 — A/H/F/HF 원인 가설 좁히기

작성: 2026-09-14. 갱신: 2026-09-15. 상태: **JFR allocation·CPU 분석과 직접 호출·차원 계측까지 완료했다. 이 문서는 JFR 결과이며 직접 카운터는 [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md)에 있다. 둘 다 원인 진단 자료이지 성능 gate 결과가 아니다.**

실행 원장과 파일 hash는 [ABLATION-RUN-LEDGER.md](ABLATION-RUN-LEDGER.md), [ABLATION-MANIFEST.json](ABLATION-MANIFEST.json)을 따른다. 직접 계측 수치·설계 payload 모델은 [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md)를, P2 및 현재 전체 판정은 [ABLATION-RESULT.md](ABLATION-RESULT.md)를 참조한다.

## 실행 범위와 해석 규칙

표준 `profile` JFR은 cityhall→gangnam, bupyeong→seoul 각각 A/H/F/HF로 총 8회 실행했다. 매 실행은 새 Java 25.0.4/G1 JVM, `-Xmx10g`, 12GiB OTP 컨테이너, 예열 100회·측정 200회, concurrency 1이다. allocation/CPU 이벤트는 `requests.jsonl`의 **측정 시작·종료 marker 안에서만** 집계했다. 각 candidate는 같은 OD의 A 결과와 routing fingerprint mismatch 0이다.

기본 `profile` 설정에는 `jdk.CompilerInlining` 이벤트가 없었다. 따라서 같은 cityhall workload에서 A/H/F/HF를 한 번씩 추가 진단 실행했다. JFR 설정은 Java 23 `profile.jfc`에 `jdk.CompilerInlining#enabled=true`를 더한 파일이다. JIT 컴파일은 예열 중 일어나므로 이 이벤트는 **예열 시작부터 측정 종료까지** 집계했다. 이 네 실행도 각각 200/200 성공, mismatch 0이다. F 진단 중 host swap 활동이 크게 증가하는 등 환경은 깨끗하지 않았고, 이 실행의 p50/p95를 서로 비교하지 않는다.

`jdk.ObjectAllocationSample.weight`는 표본 기반 누적 할당량 **추정치**다. 아래 MB/request는 sampled weight를 200으로 나눈 값일 뿐 실제 할당 byte, retained heap, RSS가 아니다. JFR `ExecutionSample`은 stack 표본이지 정확한 CPU 시간이나 메서드 호출 횟수가 아니다. 인라이닝 이벤트 수도 컴파일 시도·호출 지점 사건 수이지 런타임 호출 수가 아니다.

## 측정 marker 구간의 allocation 표본

단위는 sampled weight MB/request 추정치다. `TripPatternForDates.<init>`과 `Path.dummyPath`는 allocation stack site, `[I`와 `Path`/`TransitStopArrival`는 할당된 class 기준이다.

| OD | 변형 | 전체 weight | `TripPatternForDates.<init>` | `int[]` | `Path.dummyPath` | `StopArrivalFactoryC1` |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 시청→강남 | A | 649.3 | 160.2 | 175.1 | 191.0 | 133.3 |
| 시청→강남 | H | 453.2 (A 대비 −30.2%) | 0.4 | 12.9 | 173.3 | 137.4 |
| 시청→강남 | F | 481.7 (−25.8%) | 181.5 | 193.3 | 0.0 | 171.2 |
| 시청→강남 | HF | 283.7 (−56.3%) | 4.8 | 16.0 | 0.1 | 137.8 |
| 부평→서울 | A | 361.7 | 157.5 | 172.3 | 68.8 | 48.8 |
| 부평→서울 | H | 164.9 (A 대비 −54.4%) | 3.6 | 10.9 | 51.2 | 41.8 |
| 부평→서울 | F | 311.8 (−13.8%) | 165.5 | 175.0 | 0.0 | 45.6 |
| 부평→서울 | HF | 109.4 (−69.8%) | 4.5 | 12.0 | 0.0 | 45.7 |

### H — 정류장×운행편 배열 가설

H-only에서 A 대비 `TripPatternForDates.<init>` allocation-site weight는 시청→강남 `160.2→0.4`, 부평→서울 `157.5→3.6` MB/request로 크게 줄었다. `int[]` class weight도 각각 `175.1→12.9` 및 `172.3→10.9`로 약 93–94% 감소했다. HF에서도 같은 방향이다. H 코드가 정류장별 도착·출발 primitive array 대신 정렬된 `TripTimes` 참조와 day offset으로 시간을 조회하는 구조와 일치한다.

**판정: H의 큰 배열 allocation 제거는 JFR 표본 및 별도 직접 계측으로 지지됨(SUPPORTED).** measured 200개 요청 배치에서 `TripPatternForDates` 787,200개, `Σ(stops×trips)=5,259,269,400`, `Σtrips=82,364,600`을 확인했다. CompressedOops가 켜진 같은 Java 25 환경에서 바뀌는 두 배열의 원소 payload 모델은 A 약 42.07GB, H 약 0.659GB다(배치 총량; 요청당 평균 A 약 210.37MB, H 약 3.29MB; 약 98.43% 감소). 배열 header/alignment 및 전체 heap은 포함하지 않는다. 정확한 산식과 accessor 계측은 [직접 계측 결과](ABLATION-P3-COUNTER-RESULT.md)를 참조한다.

### F — dummy `Path` 재사용 가설

F-only에서 `Path.dummyPath` site의 표본 weight는 시청→강남 `191.0→0.0`, 부평→서울 `68.8→0.0` MB/request였다. F 변경이 해당 임시 immutable dummy `Path` 할당을 제거한다는 코드 구조와 일치한다. HF도 표본에서 해당 site가 거의 보이지 않는다.

**판정: F가 해당 dummy `Path` allocation을 줄인다는 가설은 JFR 표본과 직접 receiver 계측으로 지지됨(SUPPORTED).** 전체 allocation weight 감소 폭은 OD별 `13.8–25.8%`이고, P2에서 F-only latency는 반복적으로 느린 방향이었다. 직접 계측에서 A/H/F/HF의 comparator 방향별 호출 수와 candidate-set histogram은 각 workload 안에서 동일했다. 따라서 F의 지연 신호를 비교 횟수 증가나 candidate set 증가로 설명할 근거는 없다. 시청→강남 F JFR CPU 표본 중 `RaptorPath.compareIterationDepartureTime`이 첫 OTP frame으로 나타난 비중은 8.1%, 부평→서울은 3.2%였으나, 이는 exclusive CPU 비율이나 호출 수가 아니다.

## F의 receiver type / 인라이닝 진단

추가 JFR에서 JIT 이벤트가 실제로 기록되는 것을 확인했다. A/H/F/HF 모두 `PathParetoSetComparators`의 timetable+C1 comparator가 `RaptorPath.compare*` scalar 비교로 인라인된 사례가 있었다. A/H에서는 `Path` scalar accessor의 성공 사례가 있었고, F/HF에서는 저장된 `Path`와 임시 `MutableDummyPath` 양쪽의 accessor가 성공적으로 인라인된 사례가 있었다. JIT event의 compiler thread도 C1/C2였다. 선택한 getter call-site에서 `MutableDummyPath`가 실패해서 inlining이 깨졌다는 이벤트는 관측되지 않았다.

| 가설 | P3 현재 판정 | 근거와 제한 |
| --- | --- | --- |
| H가 정류장×운행편 배열 allocation을 줄임 | **SUPPORTED** | H-only의 constructor-site 및 `[I` JFR weight가 두 OD에서 크게 감소. 별도 direct counter가 고정 200-request 배치 차원과 원소 payload 모델을 보강했다. |
| F가 temporary `Path.dummyPath` allocation을 줄임 | **SUPPORTED** | F-only 두 OD에서 해당 allocation site 표본 weight가 0.0 MB/request. 표본 미관측을 정확한 zero-byte 증거로 과장하지 않음. |
| `MutableDummyPath` receiver type 때문에 비교 getter inlining이 실패함 | **NOT SUPPORTED (관측한 call-site 범위)** | F/HF에서 `MutableDummyPath` getter가 C1/C2 인라이닝된 사례. 다른 call-site·환경까지 보편적으로 기각하는 결과는 아님. |
| F comparator 호출량/후보 집합이 늘어 latency가 느려짐 | **NOT SUPPORTED (두 고정 OD)** | 직접 계측에서 호출 수와 후보 집합 histogram이 A/H/F/HF별로 정확히 일치했다. 이 두 OD 밖으로 일반화하지 않는다. |
| F의 지연 신호가 comparator 처리에서 발생함 | **INCONCLUSIVE** | 비교 수 증가는 배제됐지만 1회 비교의 비용·다른 CPU/cache 상호작용은 측정하지 않았다. JFR 지연은 gate가 아니고 host swap 영향도 있음. |
| H accessor 조회가 호출 수를 늘려 새 latency 병목이 됨 | **NOT SUPPORTED (호출량 기준)** | wrapper·arrival·departure 호출 수가 A/H/F/HF에서 동일. 접근당 self-time/캐시 비용은 미측정이므로 비용 영향 자체는 INCONCLUSIVE. |

인라이닝 선택 event는 warmup 시작부터 측정 종료까지 분석했다. 관측한 RaptorPath/Pareto 관련 decision 집계는 A 2,856, H 2,781, F 2,740, HF 2,822건이지만, 이것은 호출량이나 인라이닝 성공률의 분모로 쓸 수 없어 변형 간 순위 비교에 사용하지 않는다. Comparator lambda의 `unstable_if` deoptimization은 각 실행 5–6건으로 비슷한 규모였다. 이 표본만으로 F의 회귀 원인을 확정할 수 없다.

추가 inlining 진단 실행의 측정 p50/p95는 A `429.691/533.008ms`, H `334.414/436.526ms`, F `503.273/634.509ms`, HF `475.082/573.623ms`였다. JFR profiling overhead와 run 중 host swap 활동이 있으므로 **이 값은 성능 비교에서 제외**한다. 모든 진단 run은 결과 정합성 확인 목적의 200/200 성공·mismatch 0만 유효하다.

## P3 직접 계측 결과 (완료)

P3 직접 카운터는 두 고정 OD에서 A/H/F/HF × 각 200개 요청을 수집했다. 모든 실행은 200/200 성공·fingerprint mismatch 0이다. `ThreadLocal` 누적값으로 graph 차원·accessor·Pareto qualifier/comparator 호출 수와 candidate-set histogram을 합산했으며, 자세한 결과는 [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md)에 있다.

P3 분석은 완료했다. H의 호출량 증가 및 F의 comparator 호출·candidate-set 증가 가설은 관측 OD에서 지지되지 않지만, H 접근당 비용과 F latency 회귀 원인은 여전히 미확정이다. 후속 작업은 P3가 아니라 P4 formal no-swap A/HF paired gate다. 진단/탐색 지연을 PR 성능 증거로 올리지 않는다.

## 원시 파일

- Standard JFR 8-run raw 및 hash: `/private/tmp/otp-ablation-p3-20260914/{cityhall-A,cityhall-H,cityhall-F,cityhall-HF,bupyeong-A,bupyeong-H,bupyeong-F,bupyeong-HF}/`; exact JFR hashes/markers는 manifest에 있다.
- Custom inlining JFR 4-run raw: `/private/tmp/otp-ablation-p3-20260914/{cityhall-A-inlining,cityhall-H-inlining,cityhall-F-inlining-valid,cityhall-HF-inlining}/`.
- Direct counter 8-run raw 및 JAR hashes: `/private/tmp/otp-ablation-instrumented-runs-20260914/{cityhall-A,cityhall-H,cityhall-F,cityhall-HF,bupyeong-A,bupyeong-H,bupyeong-F,bupyeong-HF}/`; 상세는 [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md) 및 manifest에 있다.
- Custom JFR settings SHA-256: `91973183dd1db58821e248f538445bca224482b2f8784fc99637f878754a725c` (`inlining-profile.jfc`).
- JFR event analysis used the local Java 23 `jfr` CLI; recordings were produced by Java 25.0.4. The CLI parsed JFR 2.1 successfully.
