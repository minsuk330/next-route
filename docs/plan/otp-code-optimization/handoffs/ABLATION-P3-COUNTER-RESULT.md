# A/H/F/HF P3 직접 계측 결과

작성: 2026-09-15. 상태: **고정한 두 OD에서 직접 계측 완료. 원인 분석 자료이며 지연 성능 gate는 아니다.** JFR 표본 분석은 [ABLATION-P3-JFR-RESULT.md](ABLATION-P3-JFR-RESULT.md)를 참조한다.

## 실행 범위

- A/H/F/HF × `cityhall-gangnam`, `bupyeong-seoul` = 8개 fresh JVM 실행
- 각 실행 concurrency 1, measured 200회, warmup 0회. 고정 graph·seed·검색 시각·query를 사용하고 A의 P3 raw fingerprint와 비교했다.
- JAR는 임시 diagnostic source tree에서 빌드했으며, OTP 원본 checkout은 수정하지 않았다. `-Xmx10g`, Java 25.0.4, 고정 ARM64 image digest. 계측 실행 OTP container만 6GiB로 제한했다.
- 8개 모두 200/200 성공, fingerprint mismatch 0, metrics `OBSERVED`, cleanup 오류 0. 계측 JAR는 결과에 영향을 주는 추가 카운터가 있어 latency, allocation, RSS, GC 우위 판정에 사용하지 않는다.
- 카운터는 JVM의 thread-local 누적값을 종료 시 합산한다. 매 호출 전역 atomic/log는 없지만 hot path 계측 오버헤드는 존재한다. 따라서 호출 수와 크기 분포를 읽는 데만 쓴다.

## 1. H — 시간표 배열의 정확한 차원

`RaptorRoutingRequestTransitData`는 route 요청용 transit data를 만들 때 `TripPatternForDates`를 생성한다. 아래는 warmup 없이 보낸 **measured 200개 route 요청 배치 전체의 합계**이며, 두 OD와 네 변형에서 값이 일치했다. `/otp/routers/default/index/graphql` readiness 조회는 route 계산을 하지 않으므로 이 카운터에 유효 검색으로 포함되지 않는다.

| 값 | 200개 요청 배치 합계 | 요청당 평균 |
| --- | ---: | ---: |
| `TripPatternForDates` 생성 수 | 787,200 | 3,936 |
| `Σ stops` | 43,564,600 | 217,823 |
| `Σ trips` | 82,364,600 | 411,823 |
| `Σ(stops × trips)` | 5,259,269,400 | 26,296,347 |

A는 각 패턴마다 `arrivalTimes`와 `departureTimes` 두 `int[]`를 `stops × trips` 길이로 만든다. 따라서 이 200개 요청에서 두 배열의 원소 payload는 `8 × Σ(stops × trips)` = **42,074,155,200 bytes**, 요청당 평균 **210,370,776 bytes**다.

H는 이를 `TripTimes[]` 참조 배열과 `int[] offsetsByTrip`으로 바꿨다. 동일 실행 환경의 Java 25 JFR에서 `UseCompressedOops=true`를 확인했으므로 원소 payload 추정은 `(4-byte reference + 4-byte int) × Σtrips` = **658,916,800 bytes**, 요청당 평균 **3,294,584 bytes**다. 이 두 변경 배열의 배치 payload 차이는 **41,415,238,400 bytes**, 요청당 평균 **207,076,192 bytes**, 비율로 **약 98.43% 감소**다.

이 수치는 이 고정 200-request workload에서 산식으로 계산한 두 배열의 payload일 뿐, 전체 애플리케이션 할당량이나 retained heap/RSS 감소량이 아니다. 배열 헤더·alignment 및 객체 그래프의 다른 필드는 빼고, 변경되는 두 배열의 원소 payload만 비교했다. `wheelchairBoardings` 등 공통 구조는 양쪽에 모두 있으므로 비교에서 제외했다. OD별·요청별 dimensions가 달라질 수 있으므로 요청당 값은 배치 합계를 200으로 나눈 평균이다.

## 2. 검색 요청당 accessor·탐색 횟수

각 행은 200회 배치에서 센 합계를 200으로 나눈 정확한 요청당 평균이다. A/H/F/HF의 합계와 `candidateSetSizeHistogram`은 케이스마다 모두 일치했다.

| OD | 요청당 `getTripSchedule` wrapper | arrival accessor | departure accessor | Pareto qualify | 방향별 comparator 검사 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 시청→강남 | 674,940 | 4,283,857 | 11,065,176 | 3,154,236 | 20,071,584 |
| 부평→서울 | 269,178 | 1,783,394 | 5,632,888 | 1,024,335 | 2,597,485 |

H는 accessor 호출 *개수*를 늘리지 않았다. 다만 배열 직접 읽기와 `TripTimes` 참조를 거치는 읽기의 self-time/캐시 비용이 같은지는 이 카운터로 알 수 없으므로, “H의 accessor 비용은 없다”는 결론은 아니다.

F/HF는 A/H와 비교해 qualify 횟수, comparator 방향 검사 횟수, candidate set 크기 histogram이 바뀌지 않았다. 따라서 이 두 workload에서 F가 Pareto 비교 횟수나 후보 집합 크기를 키운다는 설명은 **지지되지 않는다**. 시청 workload의 qualify 중 기존 후보 집합 크기 `32+` bucket 비율은 80.79%였고, 부평 workload에는 `32+` 후보 집합이 없었다.

## 3. F — 비교 대상 타입

F/HF는 비교 방향별 타입이 `Path -> MutableDummyPath` / `MutableDummyPath -> Path`로 바뀌었고, A/H는 모두 `Path -> Path`였다. 각 OD에서 방향별 전체 비교 횟수는 그대로다.

| OD | 전체 방향별 비교 | F/HF `Path→MutableDummyPath` | F/HF `MutableDummyPath→Path` |
| --- | ---: | ---: | ---: |
| 시청→강남 | 4,014,316,800 | 2,007,995,000 | 2,006,321,800 |
| 부평→서울 | 519,497,000 | 259,883,800 | 259,613,200 |

함께 보면 JFR에서 `Path.dummyPath` allocation 표본이 F/HF에서 사라진 점과 부합한다. 즉 F는 비교 작업 *횟수*를 줄인 게 아니라 매 비교에서 임시 `Path`를 만드는 대신 mutable dummy 타입을 넘기는 구조로 바꿨다. P2에서 F 지연이 불리했던 원인은 아직 확정되지 않았다. 호출량 증가·후보 집합 증가 및 관측 call-site의 getter 인라이닝 실패는 이번 결과로 지지되지 않지만, comparator 1회 비용이나 다른 CPU/cache 상호작용까지 배제하지는 못한다.

## 판정과 한계

- **H 배열 payload 모델:** SUPPORTED. 실제 graph 차원에 대입한 원소 payload 산식은 약 98.43% 작다. 이는 전체 heap/RSS 절감률이 아니다.
- **H가 탐색 access 빈도를 늘림:** NOT SUPPORTED. 두 workload에서 wrapper/arrival/departure 호출 수가 A와 동일하다. 접근당 CPU 비용은 미측정이다.
- **F가 비교 수 또는 후보 set 크기를 늘림:** NOT SUPPORTED. 두 workload의 정확한 합계와 후보 histogram이 A/H와 동일하다.
- **F의 임시 `Path` 대신 mutable receiver 사용:** SUPPORTED. receiver pair 계측과 JFR allocation 표본이 같은 방향이다.
- **F의 p50 회귀 원인:** INCONCLUSIVE. 비교 수 증가·후보 폭증·관측 getter 인라이닝 실패로는 설명되지 않는다. comparator 비용, 주변 메모리 접근 등은 별도 실험 없이는 결론낼 수 없다.
- **P4:** 여전히 미완료. P4 no-swap A/HF paired latency gate를 통과한 것이 아니다.

## 재현 산출물

- Raw 실행 8개: `/private/tmp/otp-ablation-instrumented-runs-20260914/{cityhall-A,cityhall-H,cityhall-F,cityhall-HF,bupyeong-A,bupyeong-H,bupyeong-F,bupyeong-HF}/`
- 요약 JSON: `/private/tmp/otp-ablation-instrumented-runs-20260914/counter-summary.json`
- 임시 diagnostic counter SHA-256: `fb98825ad0ee71d239e1867af57ecca47a9f332500eae1ed2f60b806d8d34656`
- Diagnostic JAR SHA-256: A `41fd2029ca65bb18f61dbc486754fb06b347f8a7a64c1bfd62e895d2b41b7b08`, H `bb9054caab5e9ad810377e5f12f0b934eae6c31ed4bad04a68523f25b52e4d3e`, F `0f947df3e6ed3f6a541533c6609535dc23a8940e4f4994cd1d68166e2755546a`, HF `92f1956fe7b6e9b3796240ce6a7e181c6c6cd6e8b32aea71112306a5d6f62232`.
- 임시 runner는 shutdown hook 카운터를 회수하도록 종료 후 로그를 읽게만 수정했다. hash `3fab81033116bb2b91e0b47e2702a5d192386b1eadf9107a376e6f93fe4a3ae3`.

`/private/tmp`는 임시 공간이므로 원시 결과의 장기 보관을 보장하지 않는다. 핵심 수치는 이 문서와 [ABLATION-MANIFEST.json](ABLATION-MANIFEST.json)에 복사했다.
