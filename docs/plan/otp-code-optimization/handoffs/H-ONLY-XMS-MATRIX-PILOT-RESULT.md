# H-only Xms matrix pilot result

작성: 2026-09-16. 상태: **PILOT_COMPLETE / 정식 PR 표본 아님**.

목적은 로컬 HTTP에서 `Xms=Xmx`가 A/H RSS 차이를 가리는지 확인하는 것이었다.
Helsinki shared graph와 공식 408-case corpus를 A/H-only fresh JVM에 공통으로 사용했다.
각 셀은 5분 warmup + 8 RPS 3분 measured(1,440 requests)였고, 명시적 GC는 호출하지
않았다. 모든 요청은 HTTP 200, 408개 fingerprint가 관측됐고 swap delta는 0이었다.

## Conditions

- Graph: `/private/tmp/otp-a-only-helsinki-4wN2sL/graph.obj`
- A JAR: `/private/tmp/otp-a-only-helsinki-4wN2sL/otp-a-only.jar`
- H JAR: `/private/tmp/otp-h-only-run-v83UVX/otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar`
- Java 26, G1, `Xmx8g`; fixed=`Xms8g`, elastic=`Xms512m`
- Host: macOS ARM64; Java PID RSS sampled by the existing process monitor
- Raw root: `/private/tmp/otp-http-xms-matrix-20260916/runs/`

## Results

| cell | latency mean / p50 / p95 (ms) | measured RSS p95 / peak (MiB) | max / last committed (MiB) | Young GC | Full GC |
| --- | ---: | ---: | ---: | ---: | ---: |
| A-fixed | 133.60 / 104.11 / 279.10 | 5,420.94 / 5,517.30 | 8,192 / 8,192 | 182 | 0 |
| H-fixed | 122.18 / 97.86 / 250.98 | 5,517.58 / 5,563.00 | 8,192 / 8,192 | 168 | 0 |
| A-elastic | 114.38 / 92.95 / 215.27 | 2,984.25 / 2,991.52 | 4,208 / 2,724 | 460 | 0 |
| H-elastic | 107.03 / 88.36 / 199.44 | 2,978.23 / 3,017.09 | 4,208 / 2,748 | 404 | 0 |

## Interpretation

- Fixed heap committed 8,192MiB for both variants, so RSS did not expose an expansion
  difference. H RSS p95 was 1.8% higher in this single fixed pair.
- Elastic A/H reached the same maximum committed heap (4,208MiB) and nearly the same
  measured RSS (2,984 vs 2,978MiB). H reduced Young GC by 12.2%, but did not demonstrate
  a process-RSS reduction on this Helsinki workload.
- H latency was lower in this pilot (elastic p50 −4.9%, p95 −7.4%), but one AB-order run
  per cell is not statistical performance evidence. H fixed p99/max had an outlier.
- Therefore `Xms=Xmx` masking is real, but **Xms alone does not explain the VPS Seoul
  RSS gap**. Graph/workload shape and the smaller VPS heap regime remain necessary factors.

The next isolating step is the same 2×2 contract with the Seoul id276 graph and the VPS
request mix, using A/H-only. Formal conclusions require balanced AB/BA repetitions and
paired confidence intervals.
