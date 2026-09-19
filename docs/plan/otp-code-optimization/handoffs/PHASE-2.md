# Phase 2 handoff

- Phase 상태: PARTIAL
- 전체 목표 상태: INCONCLUSIVE
- 작업 위치·branch: `/Users/wa/golbob/개인/OpenTripPlanner`, `codex/otp-phase0-3`

## 현재 후보

- B: `TripPatternForDate.newWithFilteredTripTimes`에서 실제 제외가 발생하기 전에는 리스트를 만들지 않는다. 변경이 없으면 동일 인스턴스를 반환한다.
- C: `RaptorRoutingRequestTransitDataCreator.createTripPatternsPerStop`에서 정류장별 Trove 임시 리스트와 후속 복사를 제거하고, count → exact primitive array 할당 → write의 2-pass로 작성한다.
- D: B+C 조합.

## 테스트 결과

- `TripPatternForDateTest`와 `RaptorRoutingRequestTransitDataCreatorTest`를 포함한 정상 Maven 실행: 6 tests, failures 0, errors 0.
- B 경계: 전부 수락 시 동일 인스턴스, 전부 제외 시 null, 부분 제외 시 순서와 남은 항목 보존.
- C 경계: 다수 pattern, pattern 없는 정류장, 정류장별 결과 배열의 순서·값 보존.
- 추가 경계 테스트를 포함한 Maven 검증: `TripPatternForDateTest` 7개와
  `RaptorRoutingRequestTransitDataCreatorTest` 4개, 총 11개 통과. 명령은
  `mvn -pl application -am -PprettierSkip -Dtest=TripPatternForDateTest,RaptorRoutingRequestTransitDataCreatorTest -Dsurefire.failIfNoSpecifiedTests=false test`.

## 성능 증거의 한계

기존 c4 탐색 raw에서는 A/B/C/D가 모두 성공하고 fingerprint가 일치했지만, 단일 JVM/비완전한 workload라 최종 증거가 아니다. 현재 기록값은 다음과 같다.

| variant | p50 | p95 | peak RSS |
| --- | ---: | ---: | ---: |
| A | 1026.01 ms | 1634.91 ms | 3,918,820 kB |
| B | 1101.14 ms | 3295.16 ms | 3,934,508 kB |
| C | 1064.73 ms | 1793.21 ms | 3,931,188 kB |
| D | 967.34 ms | 2184.35 ms | 3,927,460 kB |

D는 위 탐색에서 p50만 약간 좋아졌으나 측정 통제가 불충분하므로 채택·기각을 보류한다. 원본 대비 concurrency 1/2/4, 5개 독립 JVM, A/A, soak 검증 전에는 B/C/D를 최종 후보로 선언하지 않는다.

정식으로 package한 baseline A와 candidate D를 고정 graph에 기동해 현재 측정기로 짧은 smoke를
추가했다. 동시성별 warmup 2회/OD, measured 4회/OD이며 모든 실행이 16/16 성공하고 fingerprint
mismatch 0건이었다.

| concurrency | A p50/p95 | D p50/p95 | 관측 개선율(p50/p95) |
| ---: | ---: | ---: | ---: |
| 1 | 682.44 / 1973.53 ms | 516.92 / 790.77 ms | 24.25% / 59.93% |
| 2 | 867.14 / 1295.12 ms | 651.88 / 800.74 ms | 24.82% / 38.17% |
| 4 | 1036.58 / 2733.81 ms | 916.87 / 1420.74 ms | 11.55% / 48.03% |

이 표는 OD당 4개 measured 요청의 단일 JVM smoke 결과라 최종 증거가 아니다. raw JSONL은
`/private/tmp/otp-{baseline,candidate}-smoke-c{1,2,4}.jsonl`에 보존했다.

추가로 계획 기본값(OD당 warmup 25회, measured 100회)으로 baseline/candidate를 각각 한 번씩
실행했다. 각 동시성은 400/400 성공했고, 세 비교 모두 fingerprint mismatch 0건이었다.

| concurrency | A p50/p95 | D p50/p95 | 관측 개선율(p50/p95) |
| ---: | ---: | ---: | ---: |
| 1 | 604.747 / 1005.621 ms | 525.900 / 868.335 ms | 13.04% / 13.65% |
| 2 | 662.578 / 1000.092 ms | 698.687 / 1029.865 ms | -5.45% / -2.98% |
| 4 | 779.933 / 1170.343 ms | 952.985 / 1622.922 ms | -22.19% / -38.67% |

raw JSONL은 `/private/tmp/otp-{baseline,candidate}-full-c{1,2,4}.jsonl`에 보존했다. 별도
`docker stats` 샘플에서 관측된 peak memory는 A 3.767 GiB, D 3.777 GiB로 D가 약 0.27%
높았다. 이는 프로세스 RSS가 아니며 동시성별 측정 구간도 완전히 대응하지 않는다.
동일 JVM에서 c1→c2→c4를 실행한 단일 pass로 최종 효과를 판정하지 않는다. 현재 D를
최종 후보로 채택할 근거는 없다. Phase 3에서 프로파일 근거 없이 추가 컬렉션 최적화를
늘리지 않는다.

## 다음 단계

측정기·A/A 보완 기록은 `MEASUREMENT-REPAIR.md`를 먼저 확인한다. Phase 3는 비교 가능한
프로파일로 실제 hotspot을 확인한 뒤 한 가지 가설씩 진행한다. 측정 부족은 INCONCLUSIVE,
유효한 최종 측정으로 목표에 미달한 경우만 NOT_MET으로 판정한다.
