# Reduced 25% 실행 원장

실험 ID: `reduced-25-20260915`

| 단계 | run | 상태 | 결과 | raw |
| --- | --- | --- | --- | --- |
| R0 | `prepare-reduced25` | PASS | trips 154,887 → 40,955 (26.44%), validate `error_count=0` | `/private/tmp/nextroute-otp-reduced25/prepare-report.txt`, `validate.json` |
| R0 | `otp-reduced25-build` | PASS | graph 132MiB, `V=705,692`, `E=1,861,908`, stops 8,989, patterns 540 | `/private/tmp/nextroute-otp-reduced25/otp-build/`, `graph.obj` |
| R1 | `reduced25-correctness-A2` | PASS | 4/4 success, 축소 corpus 기준 생성 | `/private/tmp/nextroute-otp-reduced25/correctness-A2/` |
| R1 | `reduced25-correctness-HF` | PASS | 4/4 success, full-detail mismatch 0 | `/private/tmp/nextroute-otp-reduced25/correctness-HF/` |
| R1 | `reduced25-feasibility-A` | REDUCED-PRESSURE PASS / FORMAL FAIL | 400/400, stable, peak container 1.93GB, swap-in +38 pages | `/private/tmp/nextroute-otp-reduced25/feasibility-A/` |
| R1 | `reduced25-feasibility-HF` | REDUCED-PRESSURE PASS / FORMAL FAIL | 400/400, stable, peak container 1.92GB, swap-in +82 pages, mismatch 0 | `/private/tmp/nextroute-otp-reduced25/feasibility-HF/` |
| R2 | c1 pair-01 (AB) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 139.162/170.003ms, p95 245.148/275.705ms; swap-in A +8/HF +19 pages | `/private/tmp/nextroute-otp-reduced25/performance/c1/pair-01/` |
| R2 | c1 pair-02 (BA) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 168.653/162.374ms, p95 259.891/274.965ms; swap-in A +239/HF +17 pages | `/private/tmp/nextroute-otp-reduced25/performance/c1/pair-02/` |
| R2 | c1 pair-03 (AB) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 181.370/193.572ms, p95 270.984/282.258ms; swap-in A +12/HF +20 pages | `/private/tmp/nextroute-otp-reduced25/performance/c1/pair-03/` |
| R2 | c1 pair-04 (BA) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 195.921/181.566ms, p95 340.150/279.370ms; swap-in A +1,365/HF +307 pages | `/private/tmp/nextroute-otp-reduced25/performance/c1/pair-04/` |
| R2 | c1 pair-05 (AB) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 194.391/189.959ms, p95 333.364/306.271ms; swap-in A +451/HF +4 pages | `/private/tmp/nextroute-otp-reduced25/performance/c1/pair-05/` |
| R2 | c1 bootstrap | EXPLORATORY INCONCLUSIVE | p50 median +2.28% (95% CI −22.16~+7.33%), p95 −4.16% (−12.46~+17.87%), RSS +1.69% (−6.59~+6.54%); formal gate 미평가 | `/private/tmp/nextroute-otp-reduced25/performance/c1/` |
| R1 | `reduced25-feasibility-c2-A` | REDUCED-PRESSURE REFERENCE | c2 A 400/400, stable, peak container 3.60GB, swap-in +20 pages; formal gate 미충족 | `/private/tmp/nextroute-otp-reduced25/feasibility-c2-A/` |
| R2 | c2 pair-01 initial (BA) | INVALID ATTEMPT | A/HF 400/400이었으나 c1 reference를 사용해 concurrency manifest mismatch로 runner exit 1; pair 산입 안 함 | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-01/` |
| R2 | c2 pair-01 retry-01 (BA) | EXCLUDED — HF JAR SHA CHANGED | A/HF 400/400, mismatch 0; c2 reference는 맞지만 HF SHA `54bb…`라 현재 `dcd08…` cohort와 합치지 않음 | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-01-retry-01/` |
| R2 | c2 pair-01 retry-02 (BA) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 212.969/219.635ms, p95 349.538/391.306ms; swap-in A +138,352/HF +348 pages, swap-out A +277,020/HF +556 | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-01-retry-02/` |
| R2 | c2 pair-02 (AB) | EXCLUDED — HF JAR SHA CHANGED | A/HF 400/400, mismatch 0; HF SHA `54bb…`라 현재 `dcd08…` cohort와 합치지 않음 | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-02/` |
| R2 | c2 pair-03 (BA) | EXCLUDED — HF JAR SHA CHANGED | A/HF 400/400, mismatch 0; HF SHA `54bb…`라 현재 `dcd08…` cohort와 합치지 않음 | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-03/` |
| R2 | c2 pair-04 (AB) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 207.040/207.689ms, p95 347.277/343.503ms; swap-in A +50/HF +571 pages | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-04/` |
| R2 | c2 pair-05 | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 214.455/203.934ms, p95 368.272/342.551ms; swap 활동 있음 | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-05/` |
| R2 | c2 pair-01 retry-02 (현재 SHA) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 212.969/219.635ms, p95 349.538/391.306ms; swap-in/out 증가 | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-01-retry-02/` |
| R2 | c2 pair-02 retry-02 (현재 SHA) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 248.222/226.820ms, p95 609.940/395.654ms; host swap 사용량 증가 | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-02-retry-02/` |
| R2 | c2 pair-03 retry-01 (현재 SHA) | REDUCED_PRESSURE_EXPLORATORY / FORMAL INELIGIBLE | A/HF 400/400, mismatch 0; p50 186.049/212.515ms, p95 305.307/353.538ms; host swap counter 변동 | `/private/tmp/nextroute-otp-reduced25/performance/c2/pair-03-retry-01/` |
| R2 | c2 bootstrap | EXPLORATORY INCONCLUSIVE | p50 median −0.31% (95% CI −14.23~+8.62%), p95 +1.09% (−15.80~+35.13%), peak RSS +18.24% (+7.99~+29.97%); formal gate 미평가 | `/private/tmp/nextroute-otp-reduced25/performance/c2/`, `REDUCED-25-C2-BOOTSTRAP.json` |
| R2 | c4 | PENDING | concurrency=4 A reference부터 별도 생성 필요 | `/private/tmp/nextroute-otp-reduced25/performance/c4/` |

## R0 선택 규칙

`prepare_gtfs.py`에 수도권 원본의 `--bbox 126.80,37.42,127.12,37.62`를 전달했다.
정류장 하나라도 범위 밖인 운행편은 운행편 전체를 버렸고 `stop_times` 중간 행을
잘라내지 않았다. 따라서 남은 노선은 451개, 운행편은 40,955개다. 원본 대비
26.44%로 계획의 22.5–27.5% 허용 범위 안이다.

OSM은 기존 수도권 extract를 그대로 사용했다. 이 실험의 25% 기준은 GTFS 운행편이며,
OSM 파일 크기까지 25%라는 뜻은 아니다.

## R1 판정

검색 시각은 graph의 서비스 기간 안에 있도록 `2026-09-15T08:00:00+09:00`으로
고정했다. A와 HF 모두 같은 축소 graph, JAR 계열, JVM 옵션, 4개 OD를 사용했다.
각 OD 1회 full-detail correctness 요청이 성공했고 응답 비교에서 mismatch 0,
missing case 0, manifest mismatch 0이었다. 이는 정합성 기준이며 성능 결과가 아니다.

## 다음 실행 규칙

R2는 [LARGE-HEAP-AGENT-PLAN.md §P4-R](LARGE-HEAP-AGENT-PLAN.md#p4-r--데이터-25에서-메모리-압박-없는-성능-회귀-검증-다음-작업)의
새 graph·새 corpus·`-Xmx10g`·동시성별 AB/BA 5 pair 계약으로 시작했다. c1의
pair-01~05(AB/BA/AB/BA/AB)까지는 각 A/HF 400/400 성공, fingerprint mismatch 0,
warmup 안정화, RSS/GC `OBSERVED`, OOM·cleanup 오류 없음이었다. 다만 현재 Mac의
host-wide swap-in delta가 모든 run에서 0이 아니므로 세 pair 모두
`REDUCED_PRESSURE_EXPLORATORY`로만 기록하며 formal no-swap 근거로 승격하지 않는다.
pair-04의 A/HF도 각각 swap-in +1,365/+307 pages가 발생했고 pair-05도
+451/+4 pages가 발생했다. c1 bootstrap은 계획대로 10,000회 계산했지만 CI가 넓어
`INCONCLUSIVE`였으며, 모든 pair가 formal no-swap gate를 충족하지 못했다. c2/c4 및
전체 비열등성 판정은 아직 미실행이다. strict formal 결과가 필요하면
zero-swap host에서 같은 R2 matrix를 다시 실행한다.

동시성별 response reference는 manifest의 `concurrency`가 같은 A reference를
사용한다. c1 reference를 c2/c4에 재사용하면 응답 fingerprint가 같아도 runner가
manifest mismatch로 실패하므로, c2·c4 각각 첫 A feasibility reference를 별도로
생성한다.
