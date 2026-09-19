# Baden HTTP P5 screening 결과 (A/H 소스 역할 기준)

실행일: 2026-09-17  
상태: `SCREENING_COMPLETE / ENVIRONMENT_CONTAMINATED`

## 고정 조건

- A upstream 원본 (legacy basename): `/private/tmp/otp-baden-build-LgbuLS/otp-h-only.jar`
- H 최적화 (legacy basename): `/private/tmp/otp-baden-build-LgbuLS/otp-baseline.jar`
- A SHA-256: `2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6`
- H SHA-256: `3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d`
- graph SHA-256: `64ee49f2d24243ab21f88225d47f44aeab17017bb6a9b28a2d8d6e476b412bfc`
- corpus SHA-256: `42c6f861a704b1c6a246b2a55506267be1d4c4c49cb6f11ec8e2a962d0bef65c`
- native Java 26.0.2.1, `-Xms512m -Xmx10g -XX:+UseG1GC`, NMT summary
- 공식 Baden corpus-derived open-loop HTTP, 8 rps, 10,000 requests/run, 32 workers
- raw directory 순서: A1 → H1 → H2 → A2; semantic role은 JAR SHA로 재분류했다.
  매 run fresh JVM, Docker 미사용

P4 ramp에서 A/H 모두 2·4·8 rps를 120초씩 실행했고, 8 rps에서 요청 손실과 지속
backlog가 없었으므로 P5 rate로 고정했다. `ps %CPU`는 멀티코어 합산값이며 8 rps 평균은
A 180.1%, H 185.5%(11코어 기준 약 16–17%)였다.

## 결과

| raw run directory | 의미론적 역할 | HTTP 200 | service p50 | p95 | p99 | max | RSS max | YGC | Full GC |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| H1 | A upstream 원본 | 10,000/10,000 | 182.97 ms | 444.02 ms | 1,447.44 ms | 5,219.73 ms | 8,850 MiB | 313 | 0 |
| A1 | H 최적화 | 10,000/10,000 | 124.95 ms | 297.56 ms | 1,138.19 ms | 3,244.49 ms | 8,672 MiB | 297 | 0 |
| H2 | A upstream 원본 | 10,000/10,000 | 127.88 ms | 284.51 ms | 1,085.83 ms | 2,898.03 ms | 8,891 MiB | 361 | 0 |
| A2 | H 최적화 | 10,000/10,000 | 125.43 ms | 293.76 ms | 1,117.82 ms | 3,059.57 ms | 8,907 MiB | 294 | 0 |

A2 행의 RSS max는 8,907MiB이며 표의 마지막 열은 YGC 294회다. 모든 run에서
transport error와 timeout은 0이고 동일한 39건의 정상 routing-error 응답이 반복됐다.

## A/H 정합성

- A1 vs H1: 10,000/10,000 key 일치, response fingerprint mismatch 0
- H2 vs A2: 10,000/10,000 key 일치, response fingerprint mismatch 0
- 각 run의 모든 응답은 HTTP 200이며, canonical itinerary·routing error 결과가 pair 간
  동일했다.

따라서 P5는 부하 중 기능 동등성을 재확인했지만, H의 latency 우위나 열위를 확정하는
통계 표본은 아니다.

## Pair 관찰

| semantic order | H/A service p50 | H/A p95 | H/A RSS max | 해석 |
| --- | ---: | ---: | ---: | --- |
| A(원본: raw H1) → H(최적화: raw A1) | -31.7% | -33.0% | -2.0% | H 최적화가 첫 pair에서 빠름; 순서·host 상태 혼입 |
| A(원본: raw H2) → H(최적화: raw A2) | -1.9% | +3.3% | -0.2% | 사실상 동률 범위 |

첫 pair에서 raw H1(A 원본) 대비 raw A1(H 최적화)의 차이가 나타났지만, 이것이 H
코드의 일관된 개선인지 host 상태/실행 순서 영향인지는 두 pair만으로 분리할 수 없다.
P6에서는 `AB, BA, BA, AB, AB, BA` 순서를 사용한다.

## GC·heap·host 관찰

- 모든 run에서 자연 Full GC 0, allocation/evacuation failure 0, OOM 0
- A1/H1/H2/A2의 jstat `GCT` 마지막 값은 각각 4.30/10.39/4.38/4.49초였다.
- heap EU max는 각각 약 5.22/5.93/5.30/5.26GiB, OU는 약 3.07/2.98/3.06/3.05GiB였다.
- RSS는 graph residency를 포함한 whole-process sampled max이며 routing-only heap이 아니다.

host counter는 모든 run에서 `Swapouts=0`이었지만 `Swapins`가 각각 +126/+24/+84/+122,
`Pageouts`가 +7,740/+10,237/+7,198/+7,041 증가했다. `vm.swapusage used`는
965.31MiB로 고정됐다. 따라서 새 swap 축적은 보이지 않았으나 host swap/page I/O가
완전히 0인 formal no-pressure 조건은 아니며, 이 P5 수치는 `ENVIRONMENT_CONTAMINATED`
탐색 결과로만 사용한다.

## 원시 자료

- A1: `/private/tmp/otp-baden-http-native-20260917-p5/A1/`
- H1: `/private/tmp/otp-baden-http-native-20260917-p5/H1/`
- H2: `/private/tmp/otp-baden-http-native-20260917-p5/H2/`
- A2: `/private/tmp/otp-baden-http-native-20260917-p5/A2/`
- 실행기: `http-experiment/run_baden_native_http.zsh`

요약은 PR 성능 근거가 아니다. P6 formal 반복은 동일 renamed JAR와 동일 graph/corpus를
사용하되, P5 환경 오염과 pair별 host counter를 함께 보고한다.
