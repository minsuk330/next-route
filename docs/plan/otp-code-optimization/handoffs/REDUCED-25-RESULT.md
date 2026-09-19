# Reduced 25% 결과

상태: **R1 정합성 통과 / R2 c1 완료·c2 동일 SHA cohort 재구성 중**

현재 확정된 사실은 다음과 같다.

- GTFS 운행편 154,887개 중 40,955개를 보존했다(26.44%).
- 보존 범위는 `126.80,37.42–127.12,37.62` 안의 운행편 전체이며, 정류장 중간 절단은 없다.
- 축소 feed는 `validate_gtfs.py`에서 참조 무결성 오류 0건이다.
- 축소 graph는 `|V|=705,692`, `|E|=1,861,908`, 정류장 8,989개, 패턴 540개다.
- A/HF 각각 4개 축소 OD에서 full-detail 요청 4건이 성공했고, 응답 fingerprint mismatch는 0건이다.
- A/HF feasibility는 각각 400/400 성공, 안정화 gate 통과, OOM 없음, RSS/GC 수집 `OBSERVED`였다. peak container RSS는 A 1.93GB, HF 1.92GB였다.
- OTP/container의 메모리 압박은 기존 전체 graph보다 크게 낮아졌지만, macOS host-wide swap-in은 A +38페이지, HF +82페이지였다. 따라서 `swap delta=0` formal gate는 실패했고, 다음 성능 run은 formal이 아닌 reduced-pressure exploratory 등급으로만 해석할 수 있다.

## R2 c1 부분 실행

축소 graph·고정 4-OD·`-Xmx10g`·container 12g·동시성 1에서 AB/BA/AB/BA 네 pair를
실행했다. 모든 run은 warmup 안정화, 400/400 성공, correctness fingerprint
mismatch 0, RSS/GC `OBSERVED`, OOM 없음, cleanup 오류 없음이었다. 단위는
milliseconds이며 A/HF 순서로 표기한다.

| pair | 순서 | p50 A/HF | p95 A/HF | peak container A/HF | GC total A/HF | host swap-in A/HF |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 01 | AB | 139.162 / 170.003 | 245.148 / 275.705 | 1.95 / 1.87 GB | 332.180 / 195.473 ms | +8 / +19 pages |
| 02 | BA | 168.653 / 162.374 | 259.891 / 274.965 | 1.87 / 1.98 GB | 401.079 / 211.835 ms | +239 / +17 pages |
| 03 | AB | 181.370 / 193.572 | 270.984 / 282.258 | 1.92 / 1.90 GB | 404.038 / 190.552 ms | +12 / +20 pages |
| 04 | BA | 195.921 / 181.566 | 340.150 / 279.370 | 2.04 / 2.01 GB | 417.381 / 203.884 ms | +1,365 / +307 pages |
| 05 | AB | 194.391 / 189.959 | 333.364 / 306.271 | 2.12 / 1.99 GB | 370.399 / 211.676 ms | +451 / +4 pages |

다섯 pair 모두 host swap-in delta가 0이 아니어서 formal no-swap 표본이 아니다.

### c1 bootstrap 결과

`compare_runs.py --seed 20260908`로 run-pair 10,000회 bootstrap을 계산했고, 원본
출력은 [REDUCED-25-C1-BOOTSTRAP.json](REDUCED-25-C1-BOOTSTRAP.json)에 보존했다.
이는 c1 탐색 요약이며 correctness·soak·swap gate를 대체하지 않는다.

| 지표 | median improvement (A→HF) | bootstrap 95% CI | 상태 |
| --- | ---: | ---: | --- |
| p50 | +2.28% | −22.16% ~ +7.33% | INCONCLUSIVE |
| p95 | −4.16% | −12.46% ~ +17.87% | INCONCLUSIVE |
| peak RSS | +1.69% | −6.59% ~ +6.54% | INCONCLUSIVE |

CI가 넓고 모든 pair가 swap-in delta 0 조건을 충족하지 못했으므로, c1에서도
5% 비열등성이나 성능 우위를 주장하지 않는다. c2/c4는 아직 실행하지 않았다.

## c2 동시성 2 cohort

c2에서는 manifest의 `concurrency=2`를 맞추기 위해 별도 A reference를 생성했다.
reference 자체는 400/400·stable·RSS/GC `OBSERVED`였지만 swap-in +20 pages로
formal gate는 미충족이다. 최초 pair-01은 c1 reference를 잘못 사용해
`manifestMismatch(concurrency)`로 종료됐고, raw는 보존하되 유효 pair에서 제외했다.
이후 HF JAR SHA가 `54bb…`에서 `dcd08…`로 바뀐 것을 확인했다. 따라서 SHA `54bb…`를
사용한 c2 pair-01 retry-01·pair-02·pair-03도 현재 cohort에서 제외했다. 현재
`dcd08…` JAR로 pair-01 retry-02, pair-02 retry-02, pair-03 retry-01, pair-04,
pair-05의 5쌍을 완성했다.

| pair | 순서 | p50 A/HF | p95 A/HF | peak container A/HF | GC total A/HF | host swap-in A/HF |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| c2-01 retry-01 (SHA 제외) | BA | 207.900 / 194.708 | 338.235 / 313.312 | 2.28 / 1.94 GB | 533.587 / 319.237 ms | +44 / +40 pages |
| c2-01 retry-02 (현재 SHA) | BA | 212.969 / 219.635 | 349.538 / 391.306 | 2.78 / 2.00 GB | 416.169 / 330.610 ms | +138,352 / +348 pages |
| c2-02 (SHA 제외) | AB | 203.105 / 218.856 | 340.926 / 364.012 | 2.96 / 2.25 GB | 363.486 / 334.426 ms | +4 / +233 pages |
| c2-03 (SHA 제외) | BA | 199.681 / 195.654 | 347.945 / 351.396 | 4.01 / 2.02 GB | 324.241 / 280.845 ms | +3,852 / +1,508 pages |
| c2-04 | AB | 207.040 / 207.689 | 347.277 / 343.503 | 2.48 / 1.98 GB | 469.121 / 301.593 ms | swap 활동 있음 |
| c2-05 | BA | 214.455 / 203.934 | 368.272 / 342.551 | 2.42 / 2.01 GB | 567.831 / 352.720 ms | swap 활동 있음 |
| c2-01 retry-02 | BA | 212.969 / 219.635 | 349.538 / 391.306 | 2.78 / 2.00 GB | 416.169 / 330.610 ms | swap 활동 있음 |
| c2-02 retry-02 | AB | 248.222 / 226.820 | 609.940 / 395.654 | 2.41 / 2.01 GB | 858.028 / 347.462 ms | swap 사용량 증가 |
| c2-03 retry-01 | BA | 186.049 / 212.515 | 305.307 / 353.538 | 2.18 / 2.02 GB | 601.059 / 303.526 ms | swap 사용량 변동 |

현재 SHA cohort 5쌍은 모두 변형별 400/400 성공, reference mismatch 0, OOM 없음,
cleanup 오류 없음이었다. 다만 각 실행에서 호스트 swap 사용량/페이지 counter가
변동했고, 특히 pair-01 retry-02 A는 swap-out +277,020 pages였다. 따라서 이
cohort는 정식 no-swap 성능 gate가 아니라 reduced-pressure exploratory 표본이다.

### c2 bootstrap 결과

`compare_runs.py --seed 20260908` 10,000회 paired bootstrap 결과는
[REDUCED-25-C2-BOOTSTRAP.json](REDUCED-25-C2-BOOTSTRAP.json)에 보존했다.

| 지표 | median improvement (A→HF) | bootstrap 95% CI | 상태 |
| --- | ---: | ---: | --- |
| p50 | −0.31% | −14.23% ~ +8.62% | INCONCLUSIVE |
| p95 | +1.09% | −15.80% ~ +35.13% | INCONCLUSIVE |
| peak RSS | +18.24% | +7.99% ~ +29.97% | TARGET_MET (탐색) |

RSS 감소 신호는 c2에서 반복됐지만, p50·p95 구간은 0을 포함하고 host swap이
있으므로 “메모리 압박이 없는 환경에서 latency 회귀 없음” 또는 “전체 성능 우위”로
해석할 수 없다. c2 결과가 현재까지 가장 강한 메모리 방향성 증거이나, 사용자의
핵심 질문에 대한 최종 답은 zero-swap host에서 동일 matrix를 재실행해야 한다.

아직 다음 항목은 결론을 내릴 수 없다.

- 메모리 압박이 없는 조건의 A/HF p50·p95 회귀 여부
- 동시성 1·2·4의 반복 신뢰구간
- host swap delta 0을 만족하는지
- 전체 수도권 graph로 결과를 일반화할 수 있는지

현재 feasibility 단일 run의 p50은 A 182.879ms, HF 169.612ms였고, p95는 A
259.448ms, HF 284.850ms였다. 이는 반복 표본이 아니므로 성능 결론으로 사용하지 않는다.

남은 R2 matrix가 재개되면 c1 5 pair와 c2/c4 각각 5 pair를 채운 뒤 계획의
10,000회 bootstrap과 5% 비열등성 기준을 적용한다. `CI가 0을 포함한다`는
사실만으로 회귀 없음으로 기록하지 않으며, 계획의 환경 gate를 함께 적용한다.
