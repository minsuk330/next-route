# Baden HTTP P3 정합성 결과 (A/H 소스 역할 기준)

실행일: 2026-09-17  
상태: `P3_CORRECTNESS_PASSED / P4_PENDING`

## 실행 정체성

P0에서 임시 JAR basename이 의미론적 역할과 섞여 있음을 확인했다. 소스/바이트코드
기준으로 A는 upstream 원본, H는 이번 최적화 코드로 고정한다.

| 실험 역할 | 사용 JAR (legacy basename) | `TripPatternForDates` 클래스 |
| --- | --- | --- |
| A upstream 원본 | `/private/tmp/otp-baden-build-LgbuLS/otp-h-only.jar` | `arrivalTimes[]`, `departureTimes[]` 평탄 배열 |
| H 최적화 | `/private/tmp/otp-baden-build-LgbuLS/otp-baseline.jar` | `TripTimes[]`, `offsetsByTrip` 참조 |

두 JAR는 파일 개수 91,401개가 같고, 컨텐츠 차이는
`TripPatternForDates.class`와 build metadata(`otp-project-info.properties`) 2건뿐이다.

## P3 계약

- graph: `/private/tmp/otp-baden-memory-FRsabG/graph/graph.obj`
- graph SHA-256: `64ee49f2d24243ab21f88225d47f44aeab17017bb6a9b28a2d8d6e476b412bfc`
- corpus: `/private/tmp/otp-baden-memory-FRsabG/official/A-rerun/travelSearch.csv`
- corpus SHA-256: `42c6f861a704b1c6a246b2a55506267be1d4c4c49cb6f11ec8e2a962d0bef65c`
- date: `2022-07-25`, departure `08:00`, `numItineraries=3`
- endpoint: `http://127.0.0.1:18080/otp/gtfs/v1`
- JVM: Java 26.0.2.1, `-Xms512m -Xmx10g -XX:+UseG1GC`, NMT summary
- GC log: `-Xlog:gc*,safepoint` with no explicit GC
- raw directory execution order: `A1 -> H1 -> H2 -> A2`; semantic roles are shown in
  the tables and are determined from source content, not the raw directory suffix.

raw root: `/private/tmp/otp-baden-http-native-20260917-p3/`

주의: raw의 Java 경고에는 이전 물리 파일명이 남아 있을 수 있다. 아래 표의 `role`은
소스/바이트코드 내용으로 판정한 의미론적 역할이며, 파일명으로 다시 판정하지 않는다.

## 정합성 결과

| run | role | HTTP 200 | transport error | GraphQL error | routing error | 응답 fingerprint |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| A1 (raw dir) | H 최적화 | 256/256 | 0 | 0 | 1 | 252 unique |
| H1 (raw dir) | A upstream 원본 | 256/256 | 0 | 0 | 1 | 252 unique |
| H2 (raw dir) | A upstream 원본 | 256/256 | 0 | 0 | 1 | 252 unique |
| A2 (raw dir) | H 최적화 | 256/256 | 0 | 0 | 1 | 252 unique |

case별 비교:

- H 최적화(raw A1) vs A upstream(raw H1): fingerprint mismatch 0, routing error mismatch 0
- H 최적화(raw A1) vs A upstream(raw H2): fingerprint mismatch 0, routing error mismatch 0
- H 최적화(raw A1) vs H 최적화(raw A2): fingerprint mismatch 0
- 네 run 전체 fingerprint map 동일
- case 133의 `NO_TRANSIT_CONNECTION_IN_SEARCH_WINDOW`는 네 run 모두에 동일함
- 모든 run에서 무경로 case도 itinerary 0개가 아니라 동일한 결과를 반환함

공식 expected CSV의 기존 mismatch는 이 gate와 분리한다. 이 gate는
A/H 사이의 응답 동등성만 판정한다.

## GC 관찰

| raw run (semantic role) | natural Full GC | Young pause summary | Remark | Cleanup | pause max |
| --- | ---: | ---: | ---: | ---: | ---: |
| A1 (H 최적화) | 0 | 30 | 5 | 5 | 156.3 ms |
| H1 (A 원본) | 0 | 33 | 6 | 6 | 153.6 ms |
| H2 (A 원본) | 0 | 33 | 7 | 7 | 155.1 ms |
| A2 (H 최적화) | 0 | 30 | 5 | 5 | 151.0 ms |

P3는 정합성 실행이므로 RSS/heap sampler와 swap delta를 동시 측정하지 않았다.
GC log상 allocation/evacuation failure, to-space exhausted, OutOfMemory 표시는 0이다.
순차 256건 정합성이 확인된 것이지, 이 수치를 비포화 성능 결론으로 쓰지 않는다.

## 판정

`P3_CORRECTNESS_PASSED`: 공식 Baden corpus 256건을 HTTP로 재생했을 때 A 원본과
H 최적화의 관찰 결과가 완전히 일치했다. P4에서는 위의 같은 request
mapping/schedule/hash를 사용하고, 새 JAR 역할 확인 없이 실행하지 않는다.
