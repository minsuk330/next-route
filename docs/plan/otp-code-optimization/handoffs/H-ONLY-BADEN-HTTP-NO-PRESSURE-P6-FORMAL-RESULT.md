# Baden-Württemberg A/H HTTP P6 결과 (소스 역할 기준)

생성 시각: 2026-09-17T04:19:58+00:00

상태: `USER_STOPPED_PARTIAL / ENVIRONMENT_CONTAMINATED / DIAGNOSTIC_ONLY`
P6 원래 계약(6 pair/12 fresh JVM)은 사용자의 중단 요청으로 완료되지 않았다. raw `pair06-H`(semantic A 원본)는 17,602/20,000건에서 중단됐고 raw `pair06-A` 및 pair01 교정 pair는 실행하지 않았다. 따라서 아래 bootstrap은 완료된 pair만 사용한 진단값이며 PR 성능 증거가 아니다.

## 결론

이 문서는 공식 Baden-Württemberg `travelSearch.csv`에서 파생한 동일 HTTP workload의 진단 결과다. 각 variant의 correctness와 지표를 보존하지만, host swap/page I/O가 한 번이라도 발생한 pair는 사전 규칙에 따라 PR 성능 증거에서 제외한다.

역할 정의: A=upstream 원본(`arrivalTimes[]`/`departureTimes[]` 평탄 배열 복사), H=최적화(`TripTimes[]`/`offsetsByTrip` 참조). legacy JAR basename은 역할 근거가 아니다.

`ENVIRONMENT_CONTAMINATED / DIAGNOSTIC_ONLY`: no-pressure pair 0/4. swap/pageout이 있는 run의 latency ratio는 PR 근거로 사용하지 않는다.

- pair-level fingerprint mismatch 합계: **0**
- HTTP status error 합계: **0**, transport error 합계: **0**
- 공식 expected CSV와의 불일치는 SpeedTest provenance 문제로 별도 기록하며 HTTP A/H 정합성과 섞지 않는다.

## 실행 조건

- native Java 26.0.2.1, Apple M3 Pro 18 GiB, `-Xms512m -Xmx10g -XX:+UseG1GC`, NMT summary
- 동일 graph/corpus/schedule, open-loop 8 requests/s, 32 workers, 20,000 requests/run
- Docker 미사용, fresh JVM, 측정 중 명시적 GC 없음
- graph SHA-256 `64ee49f2d24243ab21f88225d47f44aeab17017bb6a9b28a2d8d6e476b412bfc`
- corpus SHA-256 `42c6f861a704b1c6a246b2a55506267be1d4c4c49cb6f11ec8e2a962d0bef65c`
- A upstream baseline JAR SHA-256 `2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6` (legacy `otp-h-only.jar`)
- H optimized JAR SHA-256 `3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d` (legacy `otp-baseline.jar`)

## Run별 결과

`transit p50/p95/p99`, RSS `median/p95/max`, heap `EUmax/OUmax`, GC `events/pause-ms/full`, host `Pageouts/Swapins/Swapouts`.

| run | role | requests | integrity | transit p50/p95/p99 (ms) | RSS median/p95/max (MiB) | heap EUmax/OUmax (MiB) | GC events/sum/full | host Pageouts/Swapins/Swapouts | gate |
|---|---|---:|---|---|---|---|---|---|---|
| pair02-H | P6-02-A-baseline | 20000 | OK | 147.6/332.5/715.7 | 9402.4/9660.8/9959.0 | 6096.0/3111.8 | 1111/12822.2/0 | 147921/5104/0 | ENVIRONMENT_INVALID |
| pair02-A | P6-02-H-only | 20000 | OK | 124.1/277.4/537.5 | 8447.3/9759.1/9762.6 | 5528.0/3069.1 | 784/5731.2/0 | 13669/14103/0 | ENVIRONMENT_INVALID |
| pair03-H | P6-03-A-baseline | 20000 | OK | 135.1/294.0/538.2 | 9512.7/9660.1/9822.8 | 6080.0/3025.1 | 1116/8165.1/0 | 36695/896/0 | ENVIRONMENT_INVALID |
| pair03-A | P6-03-H-only | 20000 | OK | 122.2/278.8/522.4 | 8466.8/9120.9/9399.2 | 5432.0/3097.1 | 767/6179.0/0 | 20405/1338/0 | ENVIRONMENT_INVALID |
| pair04-A | P6-04-H-only | 20000 | OK | 129.7/296.3/600.5 | 8339.1/9202.5/9422.8 | 5304.0/3175.6 | 789/6532.0/0 | 40972/13871/5314 | ENVIRONMENT_INVALID |
| pair04-H | P6-04-A-baseline | 20000 | OK | 182.1/476.6/1559.7 | 8733.9/9419.4/9530.6 | 6112.0/3857.1 | 1092/18177.3/0 | 58687/9616/0 | ENVIRONMENT_INVALID |
| pair05-A | P6-05-H-only | 20000 | OK | 117.6/266.8/484.3 | 8555.4/9120.0/9624.8 | 5360.0/3137.1 | 782/5814.2/0 | 11001/1069/0 | ENVIRONMENT_INVALID |
| pair05-H | P6-05-A-baseline | 20000 | OK | 223.6/536.5/1248.4 | 6993.3/9143.6/9378.9 | 6088.0/3021.5 | 1089/24138.5/0 | 20154/933/0 | ENVIRONMENT_INVALID |
| pair06-H | P6-06-A-baseline | 17602 | FAIL | 201.8/542.9/1421.2 | 7947.0/9106.9/9502.1 | 6096.0/3675.8 | 963/26593.3/0 | None/None/None | ENVIRONMENT_INVALID |
| pair01-A | P6-01-H-only | 20000 | OK | 124.7/278.3/501.1 | 8257.7/8688.1/9084.1 | 5496.0/3097.1 | 754/6966.4/0 | 15433/309/0 | ENVIRONMENT_INVALID |
| pair01-H | P6-01-A-baseline | 20000 | FAIL | 133.6/368.7/2728.1 | 9516.4/10698.8/10703.2 | 6104.0/10233.1 | 65/11236.9/0 | 28301/500689/627711 | ENVIRONMENT_INVALID |

## NMT 메모리 분해

NMT snapshot의 top-level committed 값(MB)이다. `before/after`는 예열 직전과 측정 직후이며, RSS와 동일한 값이 아니고 Java heap의 live set을 뜻하지 않는다.

| run | role | Total | Java Heap | Class | Thread | Code | GC | Other |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| pair02-H | P6-02-A-baseline | 8603/10668 | 8272/10224 | 5/9 | 4/3 | 18/50 | 246/289 | —/10 |
| pair02-A | P6-02-H-only | 8678/8927 | 8320/8520 | 5/9 | 4/3 | 19/51 | 245/250 | —/10 |
| pair03-H | P6-03-A-baseline | 8650/10683 | 8320/10240 | 5/9 | 4/3 | 18/50 | 245/287 | —/10 |
| pair03-A | P6-03-H-only | 9445/9401 | 9080/8984 | 5/9 | 4/3 | 18/50 | 262/260 | —/10 |
| pair04-A | P6-04-H-only | 8651/8846 | 8320/8440 | 5/9 | 4/3 | 19/52 | 246/249 | —/10 |
| pair04-H | P6-04-A-baseline | 8620/10682 | 8272/10240 | 5/9 | 4/3 | 19/49 | 244/287 | —/10 |
| pair05-A | P6-05-H-only | 8650/8674 | 8296/8272 | 5/9 | 4/3 | 18/51 | 245/245 | —/10 |
| pair05-H | P6-05-A-baseline | 8626/10684 | 8296/10240 | 5/9 | 4/3 | 19/51 | 245/287 | —/10 |
| pair06-H | P6-06-A-baseline | 9436/— | 9080/— | 5/— | 4/— | 18/— | 262/— | — |
| pair01-A | P6-01-H-only | — | — | — | — | — | — | — |
| pair01-H | P6-01-A-baseline | — | — | — | — | — | — | — |

## Pair bootstrap (transit service, H/A)

| 지표 | H/A geometric mean | 95% CI |
|---|---:|---:|
| p50Ms (H/A; +26.9%) | 0.7307 | 0.5914–0.8723 |
| p95Ms (H/A; +29.7%) | 0.7032 | 0.5560–0.8893 |
| p99Ms (H/A; +42.6%) | 0.5744 | 0.3865–0.8538 |
| maxMs (H/A; +38.6%) | 0.6143 | 0.4557–0.8766 |

## 역할 교정에 따른 해석

위 H/A는 JAR SHA로 재분류한 semantic role 기준이다. ratio가 1보다 작으면 H 최적화(`TripTimes[]`/`offsetsByTrip`)가 A 원본(평탄 배열)보다 빠르다는 뜻이다. `pair*-A`/`pair*-H` raw 접미사와 legacy basename은 이 방향을 결정하지 않는다.

이 결과는 완료 4 pair만의 진단 bootstrap이며 모든 run에서 host pageout/swap이 발생했다. 따라서 방향성이 H 우세로 보이더라도 PR 성능 증거로 승격하지 않는다.

## 해석 제한

- bootstrap의 독립 단위는 request가 아니라 fresh-JVM pair다. 단, host gate 실패 pair는 진단값으로만 표시한다.
- `Swapins`/`Pageouts`는 macOS 호스트 전체 counter이며 OTP 전용이 아니다. non-zero는 압박의 존재를 보수적으로 표시할 뿐 원인을 단정하지 않는다.
- RSS/heap은 graph residency와 JVM 관리 영역을 포함하며, 라우팅 객체의 live set으로 과장하지 않는다.
- 한 Baden graph와 한 Apple host의 결과를 모든 OTP graph/OS/CPU에 일반화하지 않는다.

## 원자료

- analyzer root: `/private/tmp/otp-baden-http-native-20260917-p6`
- run별 `server.log`, `requests.jsonl`, `gc.log`, `process.csv`, `pressure-events.log`, NMT/vm_stat snapshot과 analyzer JSON을 root에 보존한다.
- `/private/tmp`는 비영속 경로이며, archival script가 완료한 뒤 `.artifacts/otp-baden-p6/`의 압축본과 `SHA256SUMS.txt`를 함께 사용한다.
- 사용자 중단 시점 raw는 `.artifacts/otp-baden-p6/`에 `archiveMode=partial-stop`으로 보존되었고, 압축본 SHA-256 검증을 완료했다.
